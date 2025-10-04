/*
 * カタログ取得・解析・整形を担う ViewModel。
 *
 * 役割
 * - カタログHTMLの取得・解析（#cattable 優先 → 準備ページは空 → cgi 風フォールバック）
 * - プレビュー画像URLの検証/補正と、フル画像URLの推測・補完（/src/ 置換 + 末尾 "s." 除去 → HEAD/Range 検証＋必要時のみスレ先頭を軽量スニッフ）
 * - 表示用データ/状態: 画像は内部で `StateFlow<Map<detailUrl, ImageItem>>` で差分更新し、UI には `StateFlow<List<ImageItem>>` を提供。読込中/エラーは LiveData で公開
 * - 新着取得時は既存アイテムを差分マージし、検証済みフルURLを優先的に保持しつつ不足分のみ推測
 *
 * 実装メモ
 * - フル画像推測は /thumb/ や /cat/ を /src/ に置換し、末尾 "s." を通常拡張子へ置換
 * - HEAD/Range 検証や補完は IO ディスパッチャで実行
 * - サムネイルプリフェッチの並列度は AppPreferences の設定値で制御
 * - タイトルは <small> の先頭行（<br> より前）を取得して 1 行化
 */
package com.valoser.futaburakari

import android.util.Log
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import coil3.imageLoader
import coil3.network.HttpException
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Precision
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.util.concurrent.TimeUnit
import org.jsoup.nodes.Document
import org.jsoup.Jsoup
import java.net.URL
import javax.inject.Inject
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext

@HiltViewModel
/**
 * カタログの取得・解析・整形、および表示用状態の公開を担当。
 * - 解析手順: `#cattable` 優先 → 準備ページ（/junbi/）は空 → cgi 風フォールバック
 * - 画像URL処理: プレビューURLの検証/補正と、フル画像URLの推測（/src/ 置換 + 末尾 "s." 除去、拡張子差替）→ HEAD/Range 検証＋必要時のみ HTML スニッフ
 * - 状態公開: 画像リストは StateFlow、読込中/エラーは LiveData で公開
 * - 新着取得時は既存の検証済みフルURLを優先保持し、不足分のみ推測して差分更新
 * - タイトル整形: `<small>` の 1 行目のみを採用して 1 行化
 *
 * 実装詳細:
 * - HEAD/GET Range 検証は IO 上で行い、プリフェッチ時の並列度は `AppPreferences` の設定値で制御。
 */
class MainViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val networkClient: NetworkClient,
) : ViewModel() {

    /** デバイスメモリに基づいた最適な画像キャッシュサイズを計算 */
    private fun calculateMaxImageCacheSize(): Int {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        // 利用可能メモリに基づいて動的調整（最小1000、最大10000）
        val memoryMB = maxMemory / 1024 / 1024
        return when {
            memoryMB < 512 -> 1000
            memoryMB < 1024 -> 2000
            memoryMB < 2048 -> 5000
            else -> 10000
        }
    }

    companion object {
        // 正規表現をプリコンパイル
        private val THUMB_PATTERN = Regex("(/thumb/|/cat/|/jun/)")
        private val EXTENSION_PATTERN = Regex("s\\.(jpg|jpeg|png|gif|webp|webm|mp4)$", RegexOption.IGNORE_CASE)
        private val VALID_EXTENSION_PATTERN = Regex("\\.(jpg|jpeg|png|gif|webp|webm|mp4)$", RegexOption.IGNORE_CASE)

        private const val PREVIEW_BATCH_SIZE = 4
        private const val PREVIEW_BATCH_DELAY_MS = 5L
        private const val FULL_BATCH_DELAY_MS = 50L
    }

    // URL推測結果をキャッシュ（サイズを拡大）
    private val urlGuessCache = LruCache<String, String?>(500)
    private val failedUrlCache = LruCache<String, Boolean>(200)

    // 画像ロード/解析などの IO をまとめる専用 Dispatcher（並列度を制限）
    private val ImageLoadingDispatcher = Dispatchers.IO.limitedParallelism(16)

    // カタログ画面からのプリフェッチ要求を受けるチャネル
    private val catalogPrefetchHints = MutableSharedFlow<CatalogPrefetchHint>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // 差分更新向け: detailUrl をキーにした順序付きマップで保持
    // 動的サイズ制限付きLinkedHashMapでメモリ使用量を制御
    private val maxImageCacheSize = calculateMaxImageCacheSize()
    private val _imageMap = MutableStateFlow<LinkedHashMap<String, ImageItem>>(
        object : LinkedHashMap<String, ImageItem>(maxImageCacheSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageItem>?): Boolean {
                return size > maxImageCacheSize
            }
        }
    )
    private val _imageList = MutableStateFlow<List<ImageItem>>(emptyList())
    @Deprecated("UI 側では imageList を利用する")
    val imageMap: StateFlow<Map<String, ImageItem>> = _imageMap.asStateFlow()

    private fun updateImageState(
        newMap: LinkedHashMap<String, ImageItem>,
        newList: List<ImageItem>? = null,
    ) {
        _imageMap.value = newMap
        _imageList.value = newList ?: newMap.values.toList()
    }

    @Volatile
    private var fetchJob: Job? = null

    @Volatile
    private var fetchJobUrl: String? = null

    val imageList: StateFlow<List<ImageItem>> = _imageList.asStateFlow()

    private val _error = MutableLiveData<String>()
    // エラー時のメッセージ
    val error: LiveData<String> = _error

    /**
     * カタログ画面から渡されたプリフェッチヒントを登録する。
     *
     * ビューポートが高速で変化した場合でも最新の要求だけを処理するため、SharedFlow に積んで
     * `collectLatest` で前回処理をキャンセルする。
     */
    fun submitCatalogPrefetchHint(hint: CatalogPrefetchHint) {
        if (hint.items.isEmpty() || hint.cellWidthPx <= 0 || hint.cellHeightPx <= 0) return
        if (!catalogPrefetchHints.tryEmit(hint)) {
            viewModelScope.launch { catalogPrefetchHints.emit(hint) }
        }
    }

    private suspend fun handleCatalogPrefetch(hint: CatalogPrefetchHint) {
        val width = hint.cellWidthPx.coerceAtLeast(1)
        val height = hint.cellHeightPx.coerceAtLeast(1)
        val imageLoader = appContext.imageLoader

        val previewTargets = hint.items.mapNotNull { item ->
            val preview = item.previewUrl
            if (preview.isBlank()) null else item.detailUrl to preview
        }

        previewTargets.chunked(PREVIEW_BATCH_SIZE).forEach { batch ->
            batch.forEach { (referer, url) ->
                val request = ImageRequest.Builder(appContext)
                    .data(url)
                    .size(Dimension.Pixels(width), Dimension.Pixels(height))
                    .precision(Precision.INEXACT)
                    .httpHeaders(
                        NetworkHeaders.Builder()
                            .add("Referer", referer)
                            .add("Accept", "*/*")
                            .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                            .add("User-Agent", Ua.STRING)
                            .build()
                    )
                    .build()
                imageLoader.enqueue(request)
            }
            if (PREVIEW_BATCH_DELAY_MS > 0) delay(PREVIEW_BATCH_DELAY_MS)
        }

        val prefetchTargets = hint.items.mapNotNull { item ->
            val full = item.fullImageUrl
            val preferPreview = item.preferPreviewOnly
            val hadFull = item.hadFullSuccess
            val isVideo = full?.lowercase()?.let { url ->
                url.endsWith(".webm") || url.endsWith(".mp4") || url.endsWith(".mkv")
            } ?: false
            when {
                !full.isNullOrBlank() && !preferPreview && !hadFull && !isVideo -> Triple(item, item.detailUrl, full)
                else -> null
            }
        }

        if (prefetchTargets.isEmpty()) return

        val concurrency = AppPreferences.getConcurrencyLevel(appContext).coerceIn(1, 4)
        prefetchTargets.chunked(concurrency).forEach { batch ->
            batch.forEach { (item, referer, url) ->
                val request = ImageRequest.Builder(appContext)
                    .data(url)
                    .size(Dimension.Pixels(width), Dimension.Pixels(height))
                    .precision(Precision.INEXACT)
                    .httpHeaders(
                        NetworkHeaders.Builder()
                            .add("Referer", referer)
                            .add("Accept", "*/*")
                            .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                            .add("User-Agent", Ua.STRING)
                            .build()
                    )
                    .listener(
                        onSuccess = { request, _ ->
                            val loaded = request.data?.toString()
                            if (loaded?.contains("/src/") == true && !item.hadFullSuccess) {
                                notifyFullImageSuccess(item.detailUrl, loaded)
                            }
                        },
                        onError = { request, result ->
                            val throwable = result.throwable
                            if (throwable is HttpException && throwable.response.code == 404) {
                                request.data?.toString()?.takeIf { it.isNotEmpty() }?.let { failed ->
                                    fixImageIf404NoHtml(item.detailUrl, failed)
                                }
                            }
                        }
                    )
                    .build()
                imageLoader.enqueue(request)
            }
            if (FULL_BATCH_DELAY_MS > 0) delay(FULL_BATCH_DELAY_MS)
        }
    }

    init {
        // 最新のプリフェッチ要求のみを処理し、過去の要求はキャンセル
        viewModelScope.launch(ImageLoadingDispatcher) {
            catalogPrefetchHints.collectLatest { hint ->
                handleCatalogPrefetch(hint)
            }
        }
    }

    private val _isLoading = MutableLiveData<Boolean>()
    // 通信/解析中フラグ
    val isLoading: LiveData<Boolean> = _isLoading

    // 個別404に対する同時多発を抑制するためのガード
    // 404修正の同時多発を抑制するためのガード
    // detailUrl 単位だとプレビュー404対応中にフル画像404の修正が潰れることがあるため、
    // 失敗URL単位（detailUrl|failedUrl）で抑制する。
    private val fixing404 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // 404回数制限（detailUrl + failedUrl 単位でカウント）
    private val http404Counts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val MAX_404_RETRY = 3
    // 再確認ディレイ（ミリ秒）のジッター範囲（スパイク緩和用）
    // プレビューの瞬間未反映対策の再確認待ちを短縮（体感のキビキビ感を優先）
    private val RECHECK_DELAY_RANGE_MS: LongRange = 200L..500L

    // 直近でスニッフに失敗したスレを一定時間スキップするための簡易メモ（過剰な再スニッフ抑止）
    private val sniffNegativeUntil = mutableMapOf<String, Long>()
    private val SNIFF_NEG_TTL_MS = 90_000L

    // UI のローカル404ガード解除用に、同一メッセージでも値変化を起こせる短いノンス付き注記を生成
    private fun okNote(): String = "URL修正: /src/存在確認OK#" + (System.nanoTime() % 100000).toString().padStart(5, '0')

    private fun inc404(detailUrl: String, failedUrl: String): Int {
        val key = "$detailUrl|$failedUrl"
        return http404Counts.compute(key) { _, current -> (current ?: 0) + 1 } ?: 1
    }

    private fun clear404ForDetail(detailUrl: String) {
        val prefix = "$detailUrl|"
        val keysToRemove = http404Counts.keys.filter { it.startsWith(prefix) }
        keysToRemove.forEach { http404Counts.remove(it) }
    }

    /**
     * プレビューURLからフル画像URLを推測する。
     * - `/thumb/` または `/cat/` を `/src/` に置換
     * - 末尾の `s.` を通常拡張子（.jpg/.png 等）に置換（webm/mp4 も対象）
     * 失敗時は `null` を返す。
     */
    private fun guessFullFromPreview(previewUrl: String): String? {
        return urlGuessCache.get(previewUrl) ?: run {
            // 既に失敗記録があるなら即座にnullを返す
            if (failedUrlCache.get(previewUrl) == true) return null

            val result = guessFullFromPreviewInternal(previewUrl)
            if (result != null) {
                urlGuessCache.put(previewUrl, result)
            } else {
                failedUrlCache.put(previewUrl, true)
            }
            result
        }
    }

    private fun guessFullFromPreviewInternal(previewUrl: String): String? {
        return try {
            var s = previewUrl
                .replace("/thumb/", "/src/")
                .replace("/cat/", "/src/")
                .replace("/jun/", "/src/")

            // 末尾の "s.ext" を通常の拡張子へ（例: 12345s.jpg -> 12345.jpg）
            s = s.replace(EXTENSION_PATTERN, ".$1")

            // 既に正しい拡張子形式ならそのまま、拡張子が無ければ .jpg を仮置き
            s = when {
                s.contains(VALID_EXTENSION_PATTERN) -> s
                else -> "$s.jpg"
            }
            URL(s).toString()
        } catch (_: Exception) {
            null
        }
    }

    // 拡張子バリアントの列挙・検証は廃止

    /**
     * HTML を取得せずに、個別 404 発生時の代替探索を行う。
     *
     * - `failedUrl` がフル画像系(`/src/`)の場合は失敗URLを記録し、スレ先頭の軽量スニッフで代替URLを試す
     * - プレビュー系の場合は候補サムネイルの存在確認を軽量並列で行い、見つかれば置換
     * - 規定回数を超える 404 は停止し、`preferPreviewOnly` または `previewUnavailable` を立てる
     */
    fun fixImageIf404NoHtml(detailUrl: String, failedUrl: String) {
        val guardKey = "$detailUrl|$failedUrl"
        if (!fixing404.add(guardKey)) return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val currentMap = _imageMap.value
                val target = currentMap[detailUrl] ?: return@launch
                val isFull = "/src/" in failedUrl
                val count = inc404(detailUrl, failedUrl)
                Log.d("VM404", "notify 404(nohtml ${if (isFull) "full" else "thumb"}) #$count: detail=$detailUrl url=$failedUrl")
                if (isFull) {
                    // 失敗URLを記録
                    run {
                        val updated = target.copy(failedUrls = target.failedUrls + failedUrl)
                        val newMap = LinkedHashMap(currentMap)
                        newMap[detailUrl] = updated
                        updateImageState(newMap)
                    }

                    // 軽量スニッフのみで確認（拡張子バリアントのレースは廃止）
                    run {
                        val now = System.currentTimeMillis()
                        val negUntil = sniffNegativeUntil[detailUrl]
                        val canSniff = negUntil == null || now >= negUntil
                        if (canSniff) {
                            val sniffed = runCatching { sniffFullUrlFromThreadHead(detailUrl) }.getOrNull()
                            if (!sniffed.isNullOrBlank()) {
                                val itemNow = _imageMap.value[detailUrl] ?: target
                                val updated = itemNow.copy(
                                    fullImageUrl = sniffed,
                                    urlFixNote = "URL修正: スレ先頭から抽出",
                                    preferPreviewOnly = false
                                )
                                val newMap = LinkedHashMap(_imageMap.value)
                                newMap[detailUrl] = updated
                                updateImageState(newMap)
                                clear404ForDetail(detailUrl)
                                sniffNegativeUntil.remove(detailUrl)
                                return@launch
                            } else {
                                sniffNegativeUntil[detailUrl] = now + SNIFF_NEG_TTL_MS
                            }
                        }
                    }

                    // フル画像側は「閾値を超えたら停止」（>）とする。
                    // inc404 は 1 始まりのため、MAX_404_RETRY=2 なら 3 回目で停止。
                    if (count > MAX_404_RETRY) {
                        val itemNow = _imageMap.value[detailUrl] ?: target
                        val limited = if (itemNow.hadFullSuccess) itemNow else itemNow.copy(
                            fullImageUrl = null,
                            preferPreviewOnly = true,
                            urlFixNote = "URL停止: フル画像の404が規定回数を超過"
                        )
                        val newMap = LinkedHashMap(_imageMap.value)
                        newMap[detailUrl] = limited
                        updateImageState(newMap)
                        return@launch
                    }
                    if (target.fullImageUrl != null) {
                        // 候補が見つからない場合、未成功なら一時的に解除して再試行の余地を残す
                        if (!target.hadFullSuccess) {
                            val itemNow = _imageMap.value[detailUrl] ?: target
                            val updated = itemNow.copy(
                                fullImageUrl = null,
                                preferPreviewOnly = false,
                                urlFixNote = "URL再試行: フル画像候補なし#" + (System.nanoTime() % 100000).toString().padStart(5, '0')
                            )
                            val newMap = LinkedHashMap(_imageMap.value)
                            newMap[detailUrl] = updated
                            updateImageState(newMap)
                        }
                    }
                } else {
                    // プレビュー側は閾値超過で停止。候補が尽きた場合は即停止する。
                    if (count > MAX_404_RETRY) {
                        val itemNow = _imageMap.value[detailUrl] ?: target
                        val limited = itemNow.copy(previewUnavailable = true, urlFixNote = "URL停止: プレビュー候補の全滅")
                        val newMap = LinkedHashMap(_imageMap.value)
                        newMap[detailUrl] = limited
                        updateImageState(newMap)
                        return@launch
                    }
                    val candidates = buildCatalogThumbCandidates(detailUrl).filter { it != target.previewUrl }
                    // 404検証の高速化: 直列→限定並列（呼び出し側指定）で探索
                    val next = findFirstExistingUrlLimitedParallel(candidates, referer = detailUrl, maxParallel = 1)
                    if (!next.isNullOrBlank() && next != target.previewUrl) {
                        val itemNow = _imageMap.value[detailUrl] ?: target
                        val updated = itemNow.copy(previewUrl = next, urlFixNote = "URL修正: サムネイル候補に置換", previewUnavailable = false)
                        val newMap = LinkedHashMap(_imageMap.value)
                        newMap[detailUrl] = updated
                        updateImageState(newMap)
                        clear404ForDetail(detailUrl)
                    } else if (!target.previewUnavailable) {
                        val itemNow = _imageMap.value[detailUrl] ?: target
                        val limited = itemNow.copy(
                            previewUnavailable = true,
                            urlFixNote = "URL停止: サムネイル候補が存在せず"
                        )
                        val newMap = LinkedHashMap(_imageMap.value)
                        newMap[detailUrl] = limited
                        updateImageState(newMap)
                        clear404ForDetail(detailUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e("VM404", "fixImageIf404NoHtml failed detail=$detailUrl url=$failedUrl", e)
            } finally {
                fixing404.remove(guardKey)
            }
        }
    }

    /**
     * スレッドHTMLの先頭付近から軽量に実画像URLをスニッフする。
     *
     * - 先頭12KBのみ取得し、1-based 50〜60行目に含まれる `href` を走査
     * - `/src/` を含むリンク、または画像/動画拡張子を持つリンクを優先採用
     * - 見つかった場合は `detailUrl` を基準に絶対URLへ解決して返す
     */
    private suspend fun sniffFullUrlFromThreadHead(detailUrl: String): String? {
        val bytes = networkClient.fetchRange(detailUrl, 0, 12_288, referer = detailUrl, callTimeoutMs = 800)
            ?: return null
        val text = EncodingUtils.decode(bytes, null)
        val lines = text.lineSequence().toList()
        // 1-based 50..60 行のみを対象
        val slice = lines.drop(49).take(11).joinToString("\n")

        val mSrc = Regex("href\\s*=\\s*(['\"])((?:(?!\\1).)*/src/(?:(?!\\1).)*)\\1", RegexOption.IGNORE_CASE).find(slice)
        val candidate = mSrc?.groupValues?.getOrNull(2) ?: run {
            val mImg = Regex("href\\s*=\\s*(['\"])((?:(?!\\1).)*\\.(?:jpg|jpeg|png|gif|webp|webm|mp4))\\1", RegexOption.IGNORE_CASE).find(slice)
            mImg?.groupValues?.getOrNull(2)
        }
        if (!candidate.isNullOrBlank() && isMediaHref(candidate)) {
            return try { URL(URL(detailUrl), candidate).toString() } catch (_: Exception) { null }
        }
        return null
    }

    private fun isMediaHref(raw: String): Boolean {
        val h = raw.lowercase()
        return h.contains("/src/") ||
                h.endsWith(".png") || h.endsWith(".jpg") || h.endsWith(".jpeg") ||
                h.endsWith(".gif") || h.endsWith(".webp") ||
                h.endsWith(".webm") || h.endsWith(".mp4")
    }

    /**
     * URL の存在確認を行う。
     * - まず UA 付き HEAD で確認し、失敗時は 1 バイトの GET Range(0-1) にフォールバック
     * - 一時的エラーを考慮し、呼び出し元が指定した回数だけ短い遅延を挟みつつ試行する（既定は1回）
     */
    private suspend fun urlExists(
        url: String,
        referer: String? = null,
        attempts: Int = 1,
        callTimeoutMs: Long? = null,
    ): Boolean {
        Log.d("UrlExists", "ENTER urlExists for: $url")
        repeat(attempts) { attempt ->
            Log.d("UrlExists", "BEGIN Attempt #${attempt + 1} for: $url")
            // 1) HEAD with UA
            val okHead = withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", Ua.STRING)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                    .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                    .build()
                runCatching {
                    val call = okHttpClient.newCall(req).apply {
                        if (callTimeoutMs != null) {
                            try { timeout().timeout(callTimeoutMs, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
                        }
                    }
                    call.executeAsync().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.w("UrlExists", "Attempt #${attempt}: HEAD failed: ${resp.code} for $url")
                        }
                        resp.isSuccessful
                    }
                }.onFailure { e ->
                    Log.e("UrlExists", "Attempt #${attempt}: HEAD threw exception for $url", e)
                }.getOrDefault(false)
            }
            if (okHead) {
                Log.d("UrlExists", "SUCCESS (HEAD) for: $url")
                return true
            }

            // 2) Fallback: 1byte の GET Range (0-1)
            Log.d("UrlExists", "HEAD failed, trying GET Range for: $url")
            val bytes = runCatching {
                networkClient.fetchRange(url, 0, 1, referer = referer, callTimeoutMs = callTimeoutMs)
            }.onFailure { e ->
                Log.e("UrlExists", "Attempt #${attempt}: fetchRange threw exception for $url", e)
            }.getOrNull()
            if (bytes != null) {
                Log.d("UrlExists", "SUCCESS (GET Range) for: $url")
                return true
            }
            Log.w("UrlExists", "Attempt #${attempt}: Both HEAD and GET Range failed for $url.")
            // 次の試行まで短い遅延を挟む（スパイク回避）。
            if (attempt < attempts) {
                val backoff = 50L + kotlin.random.Random.nextLong(0, 50)
                delay(backoff)
            }
        }
        Log.w("UrlExists", "URL check ultimately FAILED for $url")
        return false
    }

    /**
     * 指定URLからカタログを取得して即時表示する。
     * - HTML解析は専用Dispatcherで行い、完了後にメインスレッドへ反映
     * - 既存の検証済み状態（フルURLや404ガードなど）を detailUrl 単位でマージ
     * - ここでは追加のHEAD検証は行わず、後段のプリフェッチ/404補正フローに委ねる
     */
    fun fetchImagesFromUrl(url: String) {
        val ongoingJob = fetchJob
        if (ongoingJob != null && fetchJobUrl != url) {
            ongoingJob.cancel()
        }

        val job = viewModelScope.launch(ImageLoadingDispatcher) {
            val currentJob = coroutineContext[Job]
            withContext(Dispatchers.Main) { _isLoading.value = true }
            try {
                val document = networkClient.fetchDocument(url)
                // まずは解析（HEADせず）
                val baseItems = parseItemsFromDocument(document, url)
                // 既存一覧から detailUrl 単位で状態を引き継ぐ
                val oldMap = _imageMap.value
                val merged = baseItems.chunked(100).flatMap { chunk ->
                    chunk.map { fresh ->
                        val old = oldMap[fresh.detailUrl]

                        // 重要: 成功済みのフルURLがある場合は必ず保持
                        val existingVerifiedFull = old?.lastVerifiedFullUrl
                        val existingFull = old?.fullImageUrl
                        val preferPreview = old?.preferPreviewOnly ?: false
                        val hadFull = old?.hadFullSuccess ?: false

                        val determinedFullUrl = when {
                            !existingVerifiedFull.isNullOrBlank() -> existingVerifiedFull
                            !existingFull.isNullOrBlank() -> existingFull
                            !preferPreview -> guessFullFromPreview(fresh.previewUrl)
                            else -> null
                        }

                        val mergedPreviewUnavailable = if (fresh.previewUnavailable) {
                            true
                        } else {
                            old?.previewUnavailable ?: false
                        }

                        fresh.copy(
                            fullImageUrl = determinedFullUrl,
                            preferPreviewOnly = preferPreview && existingVerifiedFull.isNullOrBlank(),
                            hadFullSuccess = hadFull || !existingVerifiedFull.isNullOrBlank(),
                            urlFixNote = old?.urlFixNote,
                            previewUnavailable = mergedPreviewUnavailable,
                            lastVerifiedFullUrl = existingVerifiedFull,
                            failedUrls = old?.failedUrls ?: emptySet()
                        )
                    }
                }

                val shouldApplyResult = currentJob != null && (fetchJob === currentJob || fetchJobUrl == url)
                if (shouldApplyResult) {
                    val newMap = LinkedHashMap<String, ImageItem>(merged.size)
                    merged.forEach { item -> newMap[item.detailUrl] = item }
                    updateImageState(newMap, merged)
                    Log.d(
                        "VM_FETCH",
                        "Merged ${merged.size} items, ${merged.count { it.fullImageUrl != null }} have full URLs"
                    )
                } else {
                    Log.d("VM_FETCH", "Drop merged result for stale fetch: $url")
                }
            } catch (ce: CancellationException) {
                Log.d("VM_FETCH", "Fetch cancelled for url: $url")
                throw ce
            } catch (e: Exception) {
                if (fetchJob === currentJob) {
                    withContext(Dispatchers.Main) {
                        _error.value = "データの取得に失敗しました: ${e.message}"
                    }
                } else {
                    Log.w("VM_FETCH", "Ignoring error from stale fetch for $url: ${e.message}")
                }
            } finally {
                if (fetchJob === currentJob) {
                    withContext(Dispatchers.Main) { _isLoading.value = false }
                    fetchJob = null
                    fetchJobUrl = null
                }
            }

            // 以降はバックグラウンドで必要最小限の改善のみ（404等の個別対応を優先するため全件HEADは行わない）
            // 必要であればプレビューURLのみ軽量検証を段階的に適用可能だが、既定ではスキップ
        }

        fetchJob = job
        fetchJobUrl = url
    }

    /**
     * ドキュメントから ImageItem のリストを解析する。
     * 構造に応じて処理を振り分け（#cattable 優先、準備ページは空、なければ cgi 風フォールバック）。
     */
    private fun parseItemsFromDocument(document: Document, url: String): List<ImageItem> {
        // 1) まず #cattable を最優先（cgi でも普通に存在する）
        val hasCatalogTable = document.select("#cattable td").isNotEmpty()
        if (hasCatalogTable) return parseFromCattable(document)

        // 2) 一部の準備ページは空
        if (url.contains("/junbi/")) return emptyList()

        // 3) 最後の手段として旧 cgi 風のフォールバック
        return parseCgiFallback(document)
    }

    // #cattable 用パーサ（旧実装の整理版）。
    // <img> が無い行は res/{id}.htm からIDを抜き、候補URLを構築すると同時に previewUnavailable を立てる。
    private fun parseFromCattable(document: Document): List<ImageItem> {
        val parsedItems = mutableListOf<ImageItem>()
        val cells = document.select("#cattable td")

        for (cell in cells) {
            val linkTag = cell.selectFirst("a") ?: continue
            val detailUrl = linkTag.absUrl("href")

            // 1) まず通常通り <img> があればそれを使う
            val imgTag = linkTag.selectFirst("img")
            var imageUrl: String? = imgTag?.absUrl("src")
            var missingPreview = false

            // 2) <img> が無い（今回のHTMLのような）場合、res/{id}.htm から id を抜いて推測構築（候補列挙は行うが選定は後段の検証に委譲）
            if (imageUrl.isNullOrEmpty()) {
                val href = linkTag.attr("href") // 例: "res/178828.htm"
                val m = Regex("""res/(\d+)\.htm""").find(href)
                if (m != null) {
                    val id = m.groupValues[1]
                    // 例: https://zip.2chan.net/32/res/... -> https://zip.2chan.net/32
                    val boardBase = detailUrl.substringBeforeLast("/res/")
                    // 2chan のカタログは "cat/{id}s.{ext}" 形式が基本（小サムネ）。
                    // まずもっとも一般的な jpg を既定にし、後段の HEAD 検証と 404 修正で適正化する。
                    imageUrl = "$boardBase/cat/${id}s.jpg"
                    missingPreview = true
                }
            }

            // サムネイルURLが最終的に得られない場合はスキップ
            if (imageUrl.isNullOrEmpty()) continue

            // タイトル・レス数（無ければ空でOK）
            val title = firstLineFromSmall(cell.selectFirst("small"))
            val replies = cell.selectFirst("font")?.text() ?: ""

            parsedItems.add(
                ImageItem(
                    previewUrl = imageUrl!!,
                    title = title,
                    replyCount = replies,
                    detailUrl = detailUrl,
                    fullImageUrl = null,
                    previewUnavailable = missingPreview
                )
            )
        }
        return parsedItems
    }

    /**
     * サムネイル候補URL（cat/thumb の拡張子違い）を列挙する。
     */
    private fun buildCatalogThumbCandidates(detailUrl: String): List<String> {
        val m = Regex("""/res/(\d+)\.htm""").find(detailUrl) ?: return emptyList()
        val id = m.groupValues[1]
        val boardBase = detailUrl.substringBeforeLast("/res/")
        // 優先度順: cat/{id}s.* → cat/{id}.* → thumb/{id}s.*
        return listOf(
            "$boardBase/cat/${id}s.jpg",
            "$boardBase/cat/${id}s.png",
            "$boardBase/cat/${id}s.webp",
            "$boardBase/cat/$id.jpg",
            "$boardBase/cat/$id.png",
            "$boardBase/cat/$id.webp",
            "$boardBase/thumb/${id}s.jpg",
            "$boardBase/thumb/${id}s.png",
            "$boardBase/thumb/${id}s.webp"
        )
    }

    // 瞬間的な未反映（画像転送遅延等）に備え、短い遅延＋微小ジッターをはさみ1回だけ確認する
    private suspend fun urlExistsTwoStage(url: String, referer: String? = null): Boolean {
        // 短い猶予後に軽量確認（HEAD 1 回、短い callTimeout）
        val waitMs = kotlin.random.Random.nextLong(RECHECK_DELAY_RANGE_MS.first, RECHECK_DELAY_RANGE_MS.last + 1)
        delay(waitMs)
        return urlExists(url, referer, attempts = 1, callTimeoutMs = 1500)
    }

    /**
     * 候補URL群の中から、存在確認が取れた最初の1件を返す。
     * - バッチ単位で最大 `maxParallel` 並列（既定: 1）で検証し、各バッチ内で最初に成功したものを採用。
     * - バッチに成功なしの場合は次バッチへ進む。
     */
    private suspend fun findFirstExistingUrlLimitedParallel(
        candidates: List<String>,
        referer: String,
        maxParallel: Int = 1,
    ): String? = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope null
        val parallel = maxParallel.coerceAtLeast(1)
        var idx = 0
        while (idx < candidates.size) {
            val end = (idx + parallel).coerceAtMost(candidates.size)
            val batch = candidates.subList(idx, end)
            val results = batch.mapIndexed { batchIndex, url ->
                async {
                    val ok = runCatching { urlExistsTwoStage(url, referer) }.getOrDefault(false)
                    Triple(ok, url, batchIndex)
                }
            }.awaitAll()
            // バッチ内の元順で最初の成功を採用
            val hit = results
                .filter { it.first }
                .minByOrNull { it.third }
                ?.second
            if (!hit.isNullOrBlank()) return@coroutineScope hit
            idx = end
            // CPU負荷軽減のため短時間スリープ
            if (idx < candidates.size) {
                kotlinx.coroutines.delay(10L)
            }
        }
        null
    }

    // 先着レースは廃止

    // small 要素から <br> より前の一行目を抽出してプレーンテキスト化
    // - 例: "タイトル<br>サブタイトル" → "タイトル"
    // - `<br>` が無い場合は全体をプレーン化して trim のみ
    private fun firstLineFromSmall(small: org.jsoup.nodes.Element?): String {
        val html = small?.html() ?: return ""
        val idx = html.indexOf("<br", ignoreCase = true)
        val head = if (idx >= 0) html.substring(0, idx) else html
        return try {
            Jsoup.parse(head).text().trim()
        } catch (_: Exception) {
            head.replace(Regex("<[^>]+>"), "").trim()
        }
    }

    // 置き換え：cgi フォールバック（旧 parseForCgiServer を安全側に縮約）
    private fun parseCgiFallback(document: Document): List<ImageItem> {
        val parsedItems = mutableListOf<ImageItem>()
        val links = document.select("a[href*='/res/']")

        for (linkTag in links) {
            val imgTag = linkTag.selectFirst("img") ?: continue
            val imageUrl = imgTag.absUrl("src")
            val detailUrl = linkTag.absUrl("href")
            val infoText = firstLineFromSmall(linkTag.parent()?.selectFirst("small"))
            if (imageUrl.isNotEmpty() && detailUrl.isNotEmpty()) {
                parsedItems.add(
                    ImageItem(
                        previewUrl = imageUrl,
                        title = infoText,
                        replyCount = "",
                        detailUrl = detailUrl,
                        fullImageUrl = null
                    )
                )
            }
        }
        return parsedItems
    }

    /**
     * 実画像の描画成功をUIから通知する。
     * 成功時のみ `preferPreviewOnly` を解除し、404 カウンタをクリアする。
     */
    fun notifyFullImageSuccess(detailUrl: String, loadedUrl: String) {
        viewModelScope.launch {
            val item = _imageMap.value[detailUrl] ?: return@launch
            val updated = item.copy(
                fullImageUrl = loadedUrl,
                lastVerifiedFullUrl = loadedUrl,
                preferPreviewOnly = false,
                hadFullSuccess = true,
                failedUrls = emptySet(),
                urlFixNote = okNote()
            )
            val newMap = LinkedHashMap(_imageMap.value)
            newMap[detailUrl] = updated
            updateImageState(newMap)
            clear404ForDetail(detailUrl)
        }
    }

    /**
     * 内部用: フル画像URLの更新を差分反映する。
     */
    private fun updateFullImageUrl(detailUrl: String, url: String) {
        val item = _imageMap.value[detailUrl] ?: return
        val updated = item.copy(
            fullImageUrl = url,
            urlFixNote = "URL修正: 推測候補の検証で確定"
        )
        val newMap = LinkedHashMap(_imageMap.value)
        newMap[detailUrl] = updated
        updateImageState(newMap)
    }
}
