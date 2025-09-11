/*
 * カタログ取得・解析・整形を担う ViewModel。
 *
 * 役割
 * - カタログHTMLの取得・解析（#cattable 優先 → 準備ページは空 → cgi 風フォールバック）
 * - プレビュー画像URLの検証/補正と、フル画像URLの推測・補完（HEAD 検証つき）
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import org.jsoup.nodes.Document
import org.jsoup.Jsoup
import java.net.URL
import javax.inject.Inject

@HiltViewModel
/**
 * カタログの取得・解析・整形、および表示用状態の公開を担当。
 * - 解析手順: `#cattable` 優先 → 準備ページ（/junbi/）は空 → cgi 風フォールバック
 * - 画像URL処理: プレビューURLの検証/補正と、フル画像URLの推測（/src/ 置換 + 末尾 "s." 除去）→ HEAD で存在確認
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
     * 画像ロードがHTTP 404で失敗した個別アイテムに対して、代替URLを探索して差し替える。
     * - `/src/` を含むURLの404は「詳細HTMLからの /src/ 抽出」を優先（拡張子総当たりは最後の手段）。
     * - サムネイル（/cat/ や /thumb/）の404は候補（拡張子違い等）を逐次 HEAD で検証して置換。
     */
    fun fixImageIf404(detailUrl: String, failedUrl: String) {
        // detailUrl|failedUrl 単位で多重実行を抑制（プレビューとフルの並行修正を許可）
        val guardKey = "$detailUrl|$failedUrl"
        synchronized(fixing404) {
            if (!fixing404.add(guardKey)) return
        }
        viewModelScope.launch {
            try {
                val current = _images.value ?: return@launch
                val target = current.find { it.detailUrl == detailUrl } ?: return@launch

                val isFull = "/src/" in failedUrl
                // 回数制限: 一定回数を超えたら以降は自動修正を停止
                val count = inc404(detailUrl, failedUrl)
                if (count > MAX_404_RETRY) {
                    val limited = current.map {
                        if (it.detailUrl == detailUrl) {
                            if (isFull) {
                                it.copy(preferPreviewOnly = true, fullImageUrl = null)
                            } else {
                                it.copy(previewUnavailable = true)
                            }
                        } else it
                    }
                    _images.postValue(limited)
                    return@launch
                }
                if (isFull) {
                    // UIの再試行ループを止めるため、即座にプレビュー固定へ切替
                    runCatching {
                        val primed = current.map {
                            if (it.detailUrl == detailUrl) it.copy(
                                preferPreviewOnly = true
                            ) else it
                        }
                        _images.postValue(primed)
                    }
                    // 1) 詳細HTMLから /src/ を抽出（最優先）
                    val resolved: String? = runCatching {
                        val doc = networkClient.fetchDocument(detailUrl)
                        doc.selectFirst("""div.thre a[href*="/src/"]""")?.absUrl("href")
                            ?: doc.selectFirst("a[href*='/src/']")?.absUrl("href")
                    }.getOrNull()

                    // HTMLから得た /src/ があれば無検証で採用（HEADが恒常的に403になる環境のための緩和）
                    val usedHtmlDirect = !resolved.isNullOrBlank()
                    val next: String? = if (usedHtmlDirect) {
                        resolved
                    } else {
                        // 最後の手段として拡張子バリエーションを検証しながら試す
                        val base = failedUrl.substringBeforeLast('.')
                        val extCandidates = listOf("jpg", "jpeg", "png", "webp", "gif", "webm", "mp4")
                            .map { "$base.$it" }
                        extCandidates.firstOrNull { runCatching { urlExistsTwoStage(it, referer = detailUrl) }.getOrDefault(false) }
                    }
                    if (!next.isNullOrBlank() && next != target.fullImageUrl) {
                        val updated = current.map {
                            if (it.detailUrl == detailUrl) it.copy(
                                fullImageUrl = next,
                                urlFixNote = if (usedHtmlDirect) {
                                    "URL修正: /src/をHTMLから採用（無検証）"
                                } else {
                                    "URL修正: /src/候補を検証して確定"
                                },
                                preferPreviewOnly = false
                            ) else it
                        }
                        _images.postValue(updated)
                        clear404ForDetail(detailUrl)
                    } else if (!next.isNullOrBlank() && next == target.fullImageUrl) {
                        // 同一URLでも存在確認が取れた場合はプレビュー固定を解除して復帰させる
                        // UI 側の 404 ガード解除のため、注記を更新して再合成のきっかけにする
                        val updated = current.map {
                            if (it.detailUrl == detailUrl) it.copy(
                                preferPreviewOnly = false,
                                urlFixNote = "URL修正: /src/存在確認OK"
                            ) else it
                        }
                        _images.postValue(updated)
                        clear404ForDetail(detailUrl)
                    } else if (target.fullImageUrl != null && next.isNullOrBlank()) {
                        // 候補が見つからない場合はフル画像URLを一旦クリアしてプレビュー表示にフォールバック
                        val updated = current.map {
                            if (it.detailUrl == detailUrl) it.copy(
                                fullImageUrl = null,
                                preferPreviewOnly = true
                            ) else it
                        }
                        _images.postValue(updated)
                    }
                } else {
                    // サムネイルの404: 候補からHEADで置換。見つからなければ previewUnavailable=true で停止。
                    val candidates = buildCatalogThumbCandidates(detailUrl)
                        .filter { it != target.previewUrl }
                    val next = candidates.firstOrNull { runCatching { urlExistsTwoStage(it, referer = detailUrl) }.getOrDefault(false) }
                    val updated = current.map {
                        if (it.detailUrl == detailUrl) {
                            if (!next.isNullOrBlank() && next != it.previewUrl) {
                                it.copy(
                                    previewUrl = next,
                                    urlFixNote = "URL修正: サムネイル候補に置換",
                                    previewUnavailable = false
                                )
                            } else {
                                it.copy(
                                    previewUnavailable = true
                                )
                            }
                        } else it
                    }
                    _images.postValue(updated)
                    if (!next.isNullOrBlank()) clear404ForDetail(detailUrl)
                }
            } catch (_: Exception) {
                // 失敗時は無視（UIは既存のエラープレースホルダを表示）
            } finally {
                synchronized(fixing404) { fixing404.remove(guardKey) }
            }
        }
    }

    /**
     * プレビューURLからフル画像URLを推測する。
     * - `/thumb/` または `/cat/` を `/src/` に置換
     * - 末尾の `s.` を通常拡張子（.jpg/.png 等）に置換
     * 失敗時は `null` を返す。
     */
    private fun guessFullFromPreview(previewUrl: String): String? {
        return try {
            var s = previewUrl
                .replace("/thumb/", "/src/")
                .replace("/cat/", "/src/")
            s = s.replace(Regex("s\\.(jpg|jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE), ".$1")
            URL(s).toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * URL の存在確認を行う。
     * - まず UA 付き HEAD で確認し、失敗した場合は GET Range(0-0) でフォールバック。
     */
    private suspend fun urlExists(url: String, referer: String? = null): Boolean {
        // 1) HEAD with UA
        val okHead = withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", Ua.STRING)
                .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                .build()
            runCatching {
                okHttpClient.newCall(req).executeAsync().use { resp ->
                    resp.isSuccessful
                }
            }.getOrDefault(false)
        }
        if (okHead) return true
        // 2) Fallback: GET Range 0-0（NetworkClientはUA等を付与）
        val bytes = runCatching { networkClient.fetchRange(url, 0, 1, referer = referer) }.getOrNull()
        return bytes != null
    }

    /**
     * フル画像URLを段階的に補完する。
     * - 推測規則で候補を生成 → HEAD 検証 → 不足分は詳細HTMLから `/src/` を抽出
     */
    private suspend fun enrichWithFullImages(items: List<ImageItem>): List<ImageItem> {
        if (items.isEmpty()) return items
        val guessedPairs = items.map { it to guessFullFromPreview(it.previewUrl) }
        val concurrency = AppPreferences.getConcurrencyLevel(appContext)
        val limitedIO = Dispatchers.IO.limitedParallelism(concurrency)
        val headChecked = withContext(limitedIO) {
            guessedPairs.map { (item, guessedUrl) ->
                async {
                    if (guessedUrl != null && urlExists(guessedUrl, referer = item.detailUrl)) {
                        item.copy(fullImageUrl = guessedUrl)
                    } else {
                        item
                    }
                }
            }.awaitAll()
        }
        val needHtml = headChecked.filter { it.fullImageUrl.isNullOrBlank() }
        if (needHtml.isEmpty()) return headChecked
        val htmlFilled = withContext(limitedIO) {
            needHtml.map { item ->
                async {
                    val full = runCatching {
                        val detailDoc = networkClient.fetchDocument(item.detailUrl)
                        detailDoc.selectFirst("""div.thre a[href*="/src/"]""")
                            ?.absUrl("href")
                    }.getOrNull()
                    item.copy(fullImageUrl = full ?: item.fullImageUrl)
                }
            }.awaitAll()
        }
        val filledMap = htmlFilled.associateBy { it.detailUrl }
        return headChecked.map { filledMap[it.detailUrl] ?: it }
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
                    val preferPreview = old?.preferPreviewOnly ?: false
                    val carriedFull = old?.fullImageUrl
                    val guessed = if (preferPreview) null else fresh.fullImageUrl ?: guessFullFromPreview(fresh.previewUrl)
                    fresh.copy(
                        fullImageUrl = carriedFull ?: guessed,
                        preferPreviewOnly = preferPreview,
                        urlFixNote = old?.urlFixNote,
                        previewUnavailable = old?.previewUnavailable ?: false
                    )
                }
                _images.value = merged
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
     * 指定URLのカタログ更新を軽量に確認し、更新があればコールバックする。
     * 既存の fullImageUrl を引き継ぎ、不足分のみを補完する。
     */
    fun checkForUpdates(url: String, currentItemCount: Int, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val document = networkClient.fetchDocument(url)
                val newItemList = parseItemsFromDocument(document, url)

                val currentItems = _images.value ?: emptyList()
                val currentMapByDetail = currentItems.associateBy { it.detailUrl }
                // 既存の fullImageUrl を引き継ぎつつ、新規は推測規則で即時付与
                val carried = newItemList.map { ni ->
                    val old = currentMapByDetail[ni.detailUrl]
                    val preferPreview = old?.preferPreviewOnly ?: false
                    val guessed = if (preferPreview) null else ni.fullImageUrl ?: guessFullFromPreview(ni.previewUrl)
                    ni.copy(
                        fullImageUrl = old?.fullImageUrl ?: guessed,
                        preferPreviewOnly = preferPreview,
                        previewUnavailable = old?.previewUnavailable ?: false
                    )
                }
                val hasNewContent = carried.size != currentItems.size || !carried.containsAll(currentItems)

                if (hasNewContent) {
                    _images.postValue(carried)
                    Log.d("MainViewModel", "Updated catalog (light): ${currentItems.size} -> ${carried.size} items")
                    callback(true)
                } else {
                    callback(false)
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error checking for catalog updates", e)
                callback(false)
            }
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

    // 短い遅延を挟んで再確認（伝播遅延などの瞬間的不一致に対応）
    private suspend fun urlExistsWithRetry(url: String, retryDelayMs: Long = 50L, referer: String? = null): Boolean {
        if (urlExists(url, referer)) return true
        delay(retryDelayMs)
        return urlExists(url, referer)
    }

    // 瞬間的な未反映（画像転送遅延等）に備え、短い遅延をはさむ確認
    // 実質チェックタイミング: 約 50ms → +100ms → +150ms（合計 ~300ms）
    private suspend fun urlExistsTwoStage(url: String, referer: String? = null, delaysMs: List<Long> = listOf(100L, 150L)): Boolean {
        // まず50ms待ってから1回目の確認
        delay(50L)
        if (urlExists(url, referer)) return true
        for (d in delaysMs) {
            delay(d)
            if (urlExists(url, referer)) return true
        }
        return false
    }

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

    // プレビューURLをHEADで検証し、無効なら候補から有効なものに置換
    private suspend fun validatePreviewUrls(items: List<ImageItem>): List<ImageItem> {
        if (items.isEmpty()) return items
        val limited = Dispatchers.IO.limitedParallelism(AppPreferences.getConcurrencyLevel(appContext))
        return withContext(limited) {
            items.map { item ->
                async {
                    val current = item.previewUrl
                    // まず現行URLが有効ならそのまま
                    if (current.isNotBlank() && urlExists(current, referer = item.detailUrl)) return@async item
                    // 候補を順番にHEAD確認
                    val candidates = buildCatalogThumbCandidates(item.detailUrl)
                    val chosen = candidates.firstOrNull { runCatching { urlExists(it, referer = item.detailUrl) }.getOrDefault(false) }
                    if (chosen != null && chosen != current) item.copy(previewUrl = chosen) else item
                }
            }.awaitAll()
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
     * [Legacy] 旧・標準サーバー用パーサ（未使用）。互換のため残置。
     */
    private fun parseForStandardServer(document: Document): List<ImageItem> {
        val parsedItems = mutableListOf<ImageItem>()
        val cells = document.select("#cattable td")

        for (cell in cells) {
            val linkTag = cell.selectFirst("a")
            val imgTag = linkTag?.selectFirst("img")

            // 最低限、リンクと画像があれば処理を続行
            if (linkTag != null && imgTag != null) {
                val imageUrl = imgTag.absUrl("src")
                val detailUrl = linkTag.absUrl("href")

                // タイトルとレス数は存在すれば取得し、なければ空文字にする
                val title = firstLineFromSmall(cell.selectFirst("small"))
                val replies = cell.selectFirst("font")?.text() ?: ""

                if (imageUrl.isNotEmpty() && detailUrl.isNotEmpty()) {
                    parsedItems.add(
                        ImageItem(
                            previewUrl = imageUrl,
                            title = title,
                            replyCount = replies,
                            detailUrl = detailUrl,
                            fullImageUrl = null
                        )
                    )
                }
            }
        }
        return parsedItems
    }

    /**
     * [Legacy] 旧・cgi サーバー用パーサ（未使用）。互換のため残置。
     */
    private fun parseForCgiServer(document: Document): List<ImageItem> {
        val parsedItems = mutableListOf<ImageItem>()
        // cgiサーバーは 'div' で囲まれた 'a' タグのリストで構成されていることが多い
        val links = document.select("div > a[href*='/res/']")

        for (linkTag in links) {
            val imgTag = linkTag.selectFirst("img")
            if (imgTag != null) {
                val imageUrl = imgTag.absUrl("src")
                val detailUrl = linkTag.absUrl("href")

                // cgiサーバーでは、関連情報が <small> タグに入っていることが多い
                val infoText = firstLineFromSmall(linkTag.parent()?.selectFirst("small"))

                if (imageUrl.isNotEmpty() && detailUrl.isNotEmpty()) {
                    parsedItems.add(
                        ImageItem(
                            previewUrl = imageUrl,
                            title = infoText, // cgiでは詳細な分離が難しいため、取得したテキストをそのまま入れる
                            replyCount = "",   // レス数は別途取得が困難なため空にする
                            detailUrl = detailUrl,
                            fullImageUrl = null
                        )
                    )
                }
            }
        }
        return parsedItems
    }
}
