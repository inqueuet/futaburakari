package com.valoser.futaburakari

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.nodes.Document
import java.net.URL

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _images = MutableLiveData<List<ImageItem>>()
    val images: LiveData<List<ImageItem>> = _images

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

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

    private suspend fun headExists(url: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).head().build()
        runCatching {
            NetworkModule.okHttpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }

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
                        val detailDoc = NetworkClient.fetchDocument(item.detailUrl)
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
                val document = NetworkClient.fetchDocument(url)
                // ★修正点: urlを渡して解析方法を切り替え
                val baseItems = parseItemsFromDocument(document, url)
                val enriched = enrichWithFullImages(baseItems)
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
                val document = NetworkClient.fetchDocument(url)
                // ★修正点: urlを渡して解析方法を切り替え
                val newItemList = parseItemsFromDocument(document, url)
                val currentItems = _images.value ?: emptyList()
                val currentMapByDetail = currentItems.associateBy { it.detailUrl }
                val carried = newItemList.map { ni ->
                    val old = currentMapByDetail[ni.detailUrl]
                    ni.copy(fullImageUrl = old?.fullImageUrl)
                }
                val need = carried.filter { it.fullImageUrl.isNullOrBlank() }
                val filled = if (need.isNotEmpty()) enrichWithFullImages(need) else emptyList()
                val filledMap = filled.associateBy { it.detailUrl }
                val merged = carried.map { filledMap[it.detailUrl] ?: it }
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
     * ドキュメントからImageItemのリストを解析する
     * URLに応じて適切なパーサーに処理を振り分ける
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

    // 追加：#cattable 用パーサ（旧 parseForStandardServer の実質改名）
    private fun parseFromCattable(document: Document): List<ImageItem> {
        val parsedItems = mutableListOf<ImageItem>()
        val cells = document.select("#cattable td")

        for (cell in cells) {
            val linkTag = cell.selectFirst("a") ?: continue
            val detailUrl = linkTag.absUrl("href")

            // 1) まず通常通り <img> があればそれを使う
            val imgTag = linkTag.selectFirst("img")
            var imageUrl: String? = imgTag?.absUrl("src")

            // 2) <img> が無い（今回のHTMLのような）場合、res/{id}.htm から id を抜いて推測構築
            if (imageUrl.isNullOrEmpty()) {
                val href = linkTag.attr("href") // 例: "res/178828.htm"
                val m = Regex("""res/(\d+)\.htm""").find(href)
                if (m != null) {
                    val id = m.groupValues[1]
                    // 例: https://zip.2chan.net/32/res/... -> https://zip.2chan.net/32
                    val boardBase = detailUrl.substringBeforeLast("/res/")
                    // Futaba の実運用でよく見かける配置を優先順で列挙（存在チェックはせず最初を使う）
                    val candidates = listOf(
                        "$boardBase/cat/$id.jpg",
                        "$boardBase/cat/$id.png",
                        "$boardBase/cat/$id.webp",
                        "$boardBase/thumb/${id}s.jpg",
                        "$boardBase/thumb/${id}s.png",
                        "$boardBase/thumb/${id}s.webp"
                    )
                    imageUrl = candidates.firstOrNull()
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
     * 標準的なサーバーおよびお絵かき系サーバー用のパーサー
     * #cattable を探し、タグの欠損に寛容なロジックで解析する
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
     * cgi.2chan.net サーバー用のパーサー
     * こちらは #cattable を持たないため、異なるセレクタで解析する
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