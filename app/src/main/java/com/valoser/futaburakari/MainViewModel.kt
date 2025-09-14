/*
 * カタログ取得・解析・整形を担う ViewModel。
 *
 * 役割
 * - カタログHTMLの取得・解析（#cattable 優先 → 準備ページは空 → cgi 風フォールバック）
 * - プレビュー画像URLの検証/補正と、フル画像URLの推測・補完（HEAD 検証つき、HTML 解析なし）
 * - 表示用データ/状態（読込中/エラー）を LiveData で公開
 * - 既存リストの更新確認（checkForUpdates）では既知の fullImageUrl を引き継ぎ、不足分のみ補完
 *
 * 実装メモ
 * - フル画像推測は /thumb/ や /cat/ を /src/ に置換し、末尾 "s." を通常拡張子へ置換
 * - HEAD 検証と補完は IO ディスパッチャで実行し、並列度は AppPreferences の設定値で制御
 * - タイトルは <small> の先頭行（<br> より前）を取得して 1 行化
 */
package com.valoser.futaburakari

import android.util.Log
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.util.concurrent.TimeUnit
import org.jsoup.nodes.Document
import org.jsoup.Jsoup
import java.net.URL
import javax.inject.Inject

@HiltViewModel
/**
 * カタログの取得・解析・整形、および表示用状態の公開を担当。
 * - 解析手順: `#cattable` 優先 → 準備ページ（/junbi/）は空 → cgi 風フォールバック
 * - 画像URL処理: プレビューURLの検証/補正と、フル画像URLの推測（/src/ 置換 + 末尾 "s." 除去、拡張子差替）→ HEAD で存在確認
 * - 状態公開: 読込中/エラー/画像リストを LiveData で公開
 * - 更新確認: 既存の fullImageUrl を活かしつつ不足分のみ補完し、差分があれば通知
 * - タイトル整形: `<small>` の 1 行目のみを採用して 1 行化
 *
 * 実装詳細:
 * - HEAD/GET Range 検証や並列度は `AppPreferences` の設定値に基づいて IO で制御。
 */
class MainViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val okHttpClient: OkHttpClient,
    private val networkClient: NetworkClient,
) : ViewModel() {

    private val _images = MutableLiveData<List<ImageItem>>()
    // 画面表示用アイテム一覧
    val images: LiveData<List<ImageItem>> = _images

    private val _error = MutableLiveData<String>()
    // エラー時のメッセージ
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    // 通信/解析中フラグ
    val isLoading: LiveData<Boolean> = _isLoading

    // 個別404に対する同時多発を抑制するためのガード
    // 404修正の同時多発を抑制するためのガード
    // detailUrl 単位だとプレビュー404対応中にフル画像404の修正が潰れることがあるため、
    // 失敗URL単位（detailUrl|failedUrl）で抑制する。
    private val fixing404 = mutableSetOf<String>()
    // 404回数制限（detailUrl + failedUrl 単位でカウント）
    private val http404Counts = mutableMapOf<String, Int>()
    private val MAX_404_RETRY = 3
    // 再確認ディレイ（ミリ秒）のジッター範囲（スパイク緩和用）
    // プレビューの瞬間未反映対策の再確認待ちを短縮（体感のキビキビ感を優先）
    private val RECHECK_DELAY_RANGE_MS: LongRange = 200L..500L

    // 直近でスニッフに失敗したスレを一定時間スキップするための簡易メモ（過剰な再スニッフ抑止）
    private val sniffNegativeUntil = mutableMapOf<String, Long>()
    private val SNIFF_NEG_TTL_MS = 90_000L

    // UI のローカル404ガード解除用に、同一メッセージでも値変化を起こせる短いノンス付き注記を生成
    private fun okNote(): String = "URL修正: /src/存在確認OK#" + (System.nanoTime() % 100000).toString().padStart(5, '0')

    private fun inc404(detailUrl: String, failedUrl: String): Int = synchronized(http404Counts) {
        val key = "$detailUrl|$failedUrl"
        val next = (http404Counts[key] ?: 0) + 1
        http404Counts[key] = next
        next
    }

    private fun clear404ForDetail(detailUrl: String) = synchronized(http404Counts) {
        val prefix = "$detailUrl|"
        val it = http404Counts.keys.iterator()
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) it.remove()
        }
    }

    /**
     * プレビューURLからフル画像URLを推測する。
     * - `/thumb/` または `/cat/` を `/src/` に置換
     * - 末尾の `s.` を通常拡張子（.jpg/.png 等）に置換（webm/mp4 も対象）
     * 失敗時は `null` を返す。
     */
    private fun guessFullFromPreview(previewUrl: String): String? {
        return try {
            var s = previewUrl
                .replace("/thumb/", "/src/")
                .replace("/cat/", "/src/")
                .replace("/jun/", "/src/")

            // 末尾の "s.ext" を通常の拡張子へ（例: 12345s.jpg -> 12345.jpg）
            s = s.replace(
                Regex("s\\.(jpg|jpeg|png|gif|webp|webm|mp4)$", RegexOption.IGNORE_CASE),
                ".$1"
            )

            // 既に正しい拡張子形式ならそのまま、拡張子が無ければ .jpg を仮置き
            s = when {
                s.contains(Regex("\\.(jpg|jpeg|png|gif|webp|webm|mp4)$", RegexOption.IGNORE_CASE)) -> s
                else -> "$s.jpg"
            }
            URL(s).toString()
        } catch (_: Exception) {
            null
        }
    }

    // 拡張子バリアントの列挙・検証は廃止

    /**
     * HTMLを用いずに404代替探索を行う版（新規）。
     */
    fun fixImageIf404NoHtml(detailUrl: String, failedUrl: String) {
        val guardKey = "$detailUrl|$failedUrl"
        synchronized(fixing404) { if (!fixing404.add(guardKey)) return }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val current = _images.value ?: return@launch
                val target = current.find { it.detailUrl == detailUrl } ?: return@launch
                val isFull = "/src/" in failedUrl
                val count = inc404(detailUrl, failedUrl)
                Log.d("VM404", "notify 404(nohtml ${if (isFull) "full" else "thumb"}) #$count: detail=$detailUrl url=$failedUrl")
                if (isFull) {
                    // 失敗URLを記録
                    run {
                        val updated = current.map { itm ->
                            if (itm.detailUrl == detailUrl) itm.copy(
                                failedUrls = itm.failedUrls + failedUrl
                            ) else itm
                        }
                        _images.postValue(updated)
                    }

                    // 軽量スニッフのみで確認（拡張子バリアントのレースは廃止）
                    run {
                        val now = System.currentTimeMillis()
                        val negUntil = sniffNegativeUntil[detailUrl]
                        val canSniff = negUntil == null || now >= negUntil
                        if (canSniff) {
                            val sniffed = runCatching { sniffFullUrlFromThreadHead(detailUrl) }.getOrNull()
                            if (!sniffed.isNullOrBlank()) {
                                val setBySniff = (_images.value ?: current).map { itm ->
                                    if (itm.detailUrl == detailUrl) itm.copy(
                                        fullImageUrl = sniffed,
                                        urlFixNote = "URL修正: スレ先頭から抽出",
                                        preferPreviewOnly = false
                                    ) else itm
                                }
                                _images.postValue(setBySniff)
                                clear404ForDetail(detailUrl)
                                sniffNegativeUntil.remove(detailUrl)
                                return@launch
                            } else {
                                sniffNegativeUntil[detailUrl] = now + SNIFF_NEG_TTL_MS
                            }
                        }
                    }

                    // 停止条件はプレビューと同じく「閾値を超えたら停止」（>）。
                    // inc404 は 1 始まりのため、MAX_404_RETRY=2 なら 3 回目で停止。
                    if (count > MAX_404_RETRY) {
                        val limited = current.map { itm ->
                            if (itm.detailUrl == detailUrl) {
                                if (itm.hadFullSuccess) itm else itm.copy(
                                    fullImageUrl = null,
                                    preferPreviewOnly = true,
                                    urlFixNote = "URL停止: フル画像の404が規定回数を超過"
                                )
                            } else itm
                        }
                        _images.postValue(limited)
                        return@launch
                    }
                    if (target.fullImageUrl != null) {
                        // 候補が見つからない場合、未成功なら一時的に解除して再試行の余地を残す
                        if (!target.hadFullSuccess) {
                            val updated = current.map {
                                if (it.detailUrl == detailUrl) it.copy(
                                    fullImageUrl = null,
                                    preferPreviewOnly = false,
                                    urlFixNote = "URL再試行: フル画像候補なし#" + (System.nanoTime() % 100000).toString().padStart(5, '0')
                                ) else it
                            }
                            _images.postValue(updated)
                        }
                    }
                } else {
                    // プレビューも同一条件（>）で統一
                    if (count > MAX_404_RETRY) {
                        val limited = current.map { if (it.detailUrl == detailUrl) it.copy(previewUnavailable = true, urlFixNote = "URL停止: プレビュー候補の全滅") else it }
                        _images.postValue(limited)
                        return@launch
                    }
                    val candidates = buildCatalogThumbCandidates(detailUrl).filter { it != target.previewUrl }
                    val next = candidates.firstOrNull { runCatching { urlExistsTwoStage(it, referer = detailUrl) }.getOrDefault(false) }
                    if (!next.isNullOrBlank() && next != target.previewUrl) {
                        val updated = current.map {
                            if (it.detailUrl == detailUrl) {
                                it.copy(previewUrl = next, urlFixNote = "URL修正: サムネイル候補に置換", previewUnavailable = false)
                            } else it
                        }
                        _images.postValue(updated)
                        clear404ForDetail(detailUrl)
                    }
                }
            } catch (e: Exception) {
                Log.e("VM404", "fixImageIf404NoHtml failed detail=$detailUrl url=$failedUrl", e)
            } finally {
                synchronized(fixing404) { fixing404.remove(guardKey) }
            }
        }
    }

    // スレ htm からの軽量スニッフ（12KB固定）。50〜60行のみを対象に href を抽出して絶対URLを返す。
    private suspend fun sniffFullUrlFromThreadHead(detailUrl: String): String? {
        val bytes = networkClient.fetchRange(detailUrl, 0, 12_288, referer = detailUrl, callTimeoutMs = 1200)
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
     * - まず UA 付き HEAD で確認し、失敗した場合は GET Range(0-0) でフォールバック。
     * - 一時的なネットワークエラーを考慮し、短い遅延を挟んで2回まで試行する。
     */
    private suspend fun urlExists(
        url: String,
        referer: String? = null,
        attempts: Int = 2,
        callTimeoutMs: Long? = null,
    ): Boolean {
        Log.d("UrlExists", "ENTER urlExists for: $url")
        for (attempt in 1..attempts) {
            Log.d("UrlExists", "BEGIN Attempt #$attempt for: $url")
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

            // 2) Fallback: GET Range 0-0
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
                val backoff = 150L + kotlin.random.Random.nextLong(0, 100)
                delay(backoff)
            }
        }
        Log.w("UrlExists", "URL check ultimately FAILED for $url")
        return false
    }

    /**
     * 指定URLからカタログを取得してすぐに表示し、その後に段階的な補正/補完をバックグラウンドで行う。
     * 段階化:
     *  1) まずプレビューのみで一覧を表示（HEADブロックなし）
     *  2) バックグラウンドでプレビューURLのHEAD検証・補正（必要時のみ差分更新）
     *  3) さらにバックグラウンドでフル画像URLの推測/HEAD検証（差分更新）
     */
    fun fetchImagesFromUrl(url: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val document = networkClient.fetchDocument(url)
                // まずは解析（HEADせず）
                val baseItems = parseItemsFromDocument(document, url)
                // 既存一覧から detailUrl 単位で状態を引き継ぐ
                val oldMap = (_images.value ?: emptyList()).associateBy { it.detailUrl }
                val merged = baseItems.map { fresh ->
                    val old = oldMap[fresh.detailUrl]

                    // 重要: 成功済みのフルURLがある場合は必ず保持
                    val existingVerifiedFull = old?.lastVerifiedFullUrl
                    val existingFull = old?.fullImageUrl
                    val preferPreview = old?.preferPreviewOnly ?: false
                    val hadFull = old?.hadFullSuccess ?: false

                    // フルURL決定ロジックの修正
                    val determinedFullUrl = when {
                        // 検証済みURLがあれば最優先
                        !existingVerifiedFull.isNullOrBlank() -> existingVerifiedFull
                        // 既存のフルURLがあれば引き継ぐ
                        !existingFull.isNullOrBlank() -> existingFull
                        // preferPreviewOnlyでなければ推測を試みる
                        !preferPreview -> guessFullFromPreview(fresh.previewUrl)
                        else -> null
                    }

                    fresh.copy(
                        fullImageUrl = determinedFullUrl,
                        preferPreviewOnly = preferPreview && existingVerifiedFull.isNullOrBlank(), // 検証済みURLがあればpreferPreviewを解除
                        hadFullSuccess = hadFull || !existingVerifiedFull.isNullOrBlank(),
                        urlFixNote = old?.urlFixNote,
                        previewUnavailable = old?.previewUnavailable ?: false,
                        lastVerifiedFullUrl = existingVerifiedFull,
                        failedUrls = old?.failedUrls ?: emptySet()
                    )
                }
                _images.value = merged

                // デバッグログ
                Log.d("VM_FETCH", "Merged ${merged.size} items, ${merged.count { it.fullImageUrl != null }} have full URLs")
            } catch (e: Exception) {
                _error.value = "データの取得に失敗しました: ${e.message}"
            } finally {
                _isLoading.value = false
            }

            // 以降はバックグラウンドで必要最小限の改善のみ（404等の個別対応を優先するため全件HEADは行わない）
            // 必要であればプレビューURLのみ軽量検証を段階的に適用可能だが、既定ではスキップ
        }
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
    // <img> が無い行は res/{id}.htm からIDを抜き、候補URLを1つ構築（後段で検証）。
    private fun parseFromCattable(document: Document): List<ImageItem> {
        val parsedItems = mutableListOf<ImageItem>()
        val cells = document.select("#cattable td")

        for (cell in cells) {
            val linkTag = cell.selectFirst("a") ?: continue
            val detailUrl = linkTag.absUrl("href")

            // 1) まず通常通り <img> があればそれを使う
            val imgTag = linkTag.selectFirst("img")
            var imageUrl: String? = imgTag?.absUrl("src")

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
                    fullImageUrl = null
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
     * 実画像の描画成功をUIから通知する。成功時のみ previewOnly を解除し、404カウンタをクリア。
     */
    fun notifyFullImageSuccess(detailUrl: String, loadedUrl: String) {
        viewModelScope.launch {
            val current = _images.value ?: return@launch
            val updated = current.map { item ->
                if (item.detailUrl == detailUrl) {
                    item.copy(
                        fullImageUrl = loadedUrl,
                        lastVerifiedFullUrl = loadedUrl,
                        preferPreviewOnly = false,
                        hadFullSuccess = true,
                        failedUrls = emptySet(),
                        urlFixNote = okNote()
                    )
                } else item
            }
            _images.postValue(updated)
            clear404ForDetail(detailUrl)
        }
    }

    private fun updateFullImageUrl(detailUrl: String, url: String) {
        val current = _images.value ?: return
        val updated = current.map { item ->
            if (item.detailUrl == detailUrl) item.copy(
                fullImageUrl = url,
                urlFixNote = "URL修正: 推測候補の検証で確定"
            ) else item
        }
        _images.postValue(updated)
    }
}
