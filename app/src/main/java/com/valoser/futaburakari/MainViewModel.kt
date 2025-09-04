package com.valoser.futaburakari

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import java.net.URL
import javax.inject.Inject

@HiltViewModel
/**
 * カタログ取得・解析・整形を担う ViewModel。
 *
 * - HTMLを取得し、`#cattable` 優先で解析（なければ cgi 風フォールバック）
 * - プレビュー画像URLの検証/補正、フルサイズ画像URLの推測・補完を行う
 * - 進行状態とエラーを LiveData で公開
 */
class MainViewModel @Inject constructor(
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

    // プレビューURLからフル画像URLを推測（thumb/cat -> src、末尾の s. を通常拡張に）
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

    // HEADでURLの存在確認（IOディスパッチャ実行）
    private suspend fun headExists(url: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).head().build()
        runCatching {
            okHttpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }

    // フル画像URLを補完：推測→HEAD検証→不足分は詳細HTMLから /src/ を抽出
    private suspend fun enrichWithFullImages(items: List<ImageItem>): List<ImageItem> {
        if (items.isEmpty()) return items
        val guessedPairs = items.map { it to guessFullFromPreview(it.previewUrl) }
        val limitedIO = Dispatchers.IO.limitedParallelism(4)
        val headChecked = withContext(limitedIO) {
            guessedPairs.map { (item, guessedUrl) ->
                async {
                    if (guessedUrl != null && headExists(guessedUrl)) {
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

    fun fetchImagesFromUrl(url: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val document = networkClient.fetchDocument(url)
                // URLに応じて解析手段を切り替え（#cattable 優先 → 準備ページは空 → cgi フォールバック）
                val baseItems = parseItemsFromDocument(document, url)
                val previewSafe = validatePreviewUrls(baseItems)
                val enriched = enrichWithFullImages(previewSafe)
                _images.value = enriched
            } catch (e: Exception) {
                _error.value = "データの取得に失敗しました: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun checkForUpdates(url: String, currentItemCount: Int, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val document = networkClient.fetchDocument(url)
                // 解析手段の切り替えは同様。既存のfullImageUrlを引き継ぎ、不足分のみ補完する。
                val newItemList = parseItemsFromDocument(document, url)
                val currentItems = _images.value ?: emptyList()
                val currentMapByDetail = currentItems.associateBy { it.detailUrl }
                val carried = newItemList.map { ni ->
                    val old = currentMapByDetail[ni.detailUrl]
                    ni.copy(fullImageUrl = old?.fullImageUrl)
                }
                val need = carried.filter { it.fullImageUrl.isNullOrBlank() }
                val previewSafe = validatePreviewUrls(carried)
                val needFull = previewSafe.filter { it.fullImageUrl.isNullOrBlank() }
                val filled = if (needFull.isNotEmpty()) enrichWithFullImages(needFull) else emptyList()
                val filledMap = filled.associateBy { it.detailUrl }
                val merged = previewSafe.map { filledMap[it.detailUrl] ?: it }
                val hasNewContent =
                    merged.size != currentItems.size || !merged.containsAll(currentItems)

                if (hasNewContent) {
                    _images.postValue(merged)
                    Log.d("MainViewModel", "Updated catalog: ${currentItems.size} -> ${merged.size} items")
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
                    // 一旦 jpg を既定とする（後段で HEAD により検証して適正化）
                    imageUrl = "$boardBase/cat/$id.jpg"
                }
            }

            // サムネイルURLが最終的に得られない場合はスキップ
            if (imageUrl.isNullOrEmpty()) continue

            // タイトル・レス数（無ければ空でOK）
            val title = cell.selectFirst("small")?.text() ?: ""
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

    // サムネイル候補（cat/thumb の拡張子違い）を列挙
    private fun buildCatalogThumbCandidates(detailUrl: String): List<String> {
        val m = Regex("""/res/(\d+)\.htm""").find(detailUrl) ?: return emptyList()
        val id = m.groupValues[1]
        val boardBase = detailUrl.substringBeforeLast("/res/")
        return listOf(
            "$boardBase/cat/$id.jpg",
            "$boardBase/cat/$id.png",
            "$boardBase/cat/$id.webp",
            "$boardBase/thumb/${id}s.jpg",
            "$boardBase/thumb/${id}s.png",
            "$boardBase/thumb/${id}s.webp"
        )
    }

    // プレビューURLをHEADで検証し、無効なら候補から有効なものに置換
    private suspend fun validatePreviewUrls(items: List<ImageItem>): List<ImageItem> {
        if (items.isEmpty()) return items
        val limited = Dispatchers.IO.limitedParallelism(4)
        return withContext(limited) {
            items.map { item ->
                async {
                    val current = item.previewUrl
                    // まず現行URLが有効ならそのまま
                    if (current.isNotBlank() && headExists(current)) return@async item
                    // 候補を順番にHEAD確認
                    val candidates = buildCatalogThumbCandidates(item.detailUrl)
                    val chosen = candidates.firstOrNull { runCatching { headExists(it) }.getOrDefault(false) }
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
            val infoText = linkTag.parent()?.selectFirst("small")?.text() ?: ""
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
                val title = cell.selectFirst("small")?.text() ?: ""
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
                val infoText = linkTag.parent()?.selectFirst("small")?.text() ?: ""

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
