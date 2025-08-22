package com.example.hutaburakari

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

    /**
     * サムネURLからフル画像URLを推測する
     * 例:
     *   .../thumb/123456s.jpg → .../src/123456.jpg
     *   .../cat/123456s.png   → .../src/123456.png
     */
    private fun guessFullFromPreview(previewUrl: String): String? {
        return try {
            var s = previewUrl
                .replace("/thumb/", "/src/")
                .replace("/cat/", "/src/")

            // 末尾の 's' を落として拡張子は維持
            s = s.replace(Regex("s\\.(jpg|jpeg|png|gif|webp)$", RegexOption.IGNORE_CASE), ".$1")

            // 念のため絶対URLに正規化
            URL(s).toString()
        } catch (_: Exception) {
            null
        }
    }

    /** HEAD リクエストで存在確認（HTMLダウンロード不要で軽い） */
    private suspend fun headExists(url: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).head().build()
        runCatching {
            NetworkModule.okHttpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        }.getOrDefault(false)
    }

    /**
     * ★高速版：
     *  1) サムネからフルURLを推測 → HEAD で存在確認
     *  2) 外れた分だけ detail HTML を取りに行き、/src/ の最初の画像を取得
     *  3) 結果をマージして返す
     */
    private suspend fun enrichWithFullImages(items: List<ImageItem>): List<ImageItem> {
        if (items.isEmpty()) return items

        // まず推測
        val guessedPairs = items.map { it to guessFullFromPreview(it.previewUrl) }

        // HEAD で一気に確認（並列は控えめに）
        val limitedIO = Dispatchers.IO.limitedParallelism(2)
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

        // まだ fullImageUrl が無いものだけ HTML を解析
        val needHtml = headChecked.filter { it.fullImageUrl.isNullOrBlank() }
        if (needHtml.isEmpty()) return headChecked

        val htmlFilled = withContext(limitedIO) {
            needHtml.map { item ->
                async {
                    val full = runCatching {
                        val detailDoc = NetworkClient.fetchDocument(item.detailUrl)
                        // 最初の1件だけを素早く取る
                        detailDoc.selectFirst("""div.thre a[href*="/src/"]""")
                            ?.absUrl("href")
                    }.getOrNull()
                    item.copy(fullImageUrl = full ?: item.fullImageUrl)
                }
            }.awaitAll()
        }

        // マージ（順序は元のまま維持）
        val filledMap = htmlFilled.associateBy { it.detailUrl }
        return headChecked.map { filledMap[it.detailUrl] ?: it }
    }

    fun fetchImagesFromUrl(url: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val document = NetworkClient.fetchDocument(url)
                val baseItems = parseItemsFromDocument(document) // previewUrl, detailUrl までは従来通り
                val enriched = enrichWithFullImages(baseItems)    // ★改良版で高速に fullImageUrl 付与
                _images.value = enriched
            } catch (e: Exception) {
                _error.value = "データの取得に失敗しました: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * カタログに新しい更新があるかチェックし、あれば追加する
     * 既存の fullImageUrl はできるだけ引き継ぎ、足りない分だけ解決
     */
    fun checkForUpdates(url: String, currentItemCount: Int, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                val document = NetworkClient.fetchDocument(url)
                val newItemList = parseItemsFromDocument(document)

                // 既存データの fullImageUrl を引き継ぐ
                val currentItems = _images.value ?: emptyList()
                val currentMapByDetail = currentItems.associateBy { it.detailUrl }

                val carried = newItemList.map { ni ->
                    val old = currentMapByDetail[ni.detailUrl]
                    ni.copy(fullImageUrl = old?.fullImageUrl)
                }

                // まだ fullImageUrl が無いものを高速ロジックで解決
                val need = carried.filter { it.fullImageUrl.isNullOrBlank() }
                val filled = if (need.isNotEmpty()) enrichWithFullImages(need) else emptyList()
                val filledMap = filled.associateBy { it.detailUrl }

                // マージ
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
     * ドキュメントから ImageItem のリストを解析する共通メソッド
     * （catalogのセル #cattable td を走査）
     */
    private fun parseItemsFromDocument(document: Document): List<ImageItem> {
        val parsedItems = mutableListOf<ImageItem>()
        val cells = document.select("#cattable td")

        for (cell in cells) {
            val linkTag = cell.select("a").first()
            val imgTag = cell.select("img").first()
            val smallTag = cell.select("small").first()
            val fontTag = cell.select("font").first()

            if (linkTag != null && imgTag != null && smallTag != null && fontTag != null) {
                val imageUrl = imgTag.absUrl("src")
                val detailUrl = linkTag.absUrl("href")
                val title = smallTag.text()
                val replies = fontTag.text()

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
                } else {
                    Log.w(
                        "MainViewModel",
                        "Skipping item due to empty imageUrl or detailUrl. " +
                                "Image src: ${imgTag.attr("src")}, Link href: ${linkTag.attr("href")}"
                    )
                }
            }
        }
        return parsedItems
    }
}