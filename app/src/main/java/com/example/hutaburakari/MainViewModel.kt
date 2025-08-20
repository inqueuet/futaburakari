package com.example.hutaburakari

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.jsoup.nodes.Document

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _images = MutableLiveData<List<ImageItem>>()
    val images: LiveData<List<ImageItem>> = _images

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // ★共通化：detail を叩いて fullImageUrl を埋める
    private suspend fun enrichWithFullImages(items: List<ImageItem>): List<ImageItem> {
        if (items.isEmpty()) return items
        val limitedIO = Dispatchers.IO.limitedParallelism(8)
        return withContext(limitedIO) {
            items.map { item ->
                async {
                    val full = runCatching {
                        val detailDoc = NetworkClient.fetchDocument(item.detailUrl)
                        // thre 内の a[href] から /src/ の jpg/png/webp を優先取得
                        val link = detailDoc.select("div.thre a[href]").firstOrNull { a ->
                            val href = a.attr("href").lowercase()
                            href.contains("/src/") && (
                                    href.endsWith(".jpg") || href.endsWith(".jpeg") ||
                                            href.endsWith(".png") || href.endsWith(".gif") ||
                                            href.endsWith(".webp")
                                    )
                        }
                        link?.absUrl("href")
                    }.getOrNull()
                    item.copy(fullImageUrl = full ?: item.fullImageUrl)
                }
            }.awaitAll()
        }
    }

    fun fetchImagesFromUrl(url: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val document = NetworkClient.fetchDocument(url)
                val baseItems = parseItemsFromDocument(document) // previewUrl, detailUrl までは従来通り
                val enriched = enrichWithFullImages(baseItems)   // ★差し替え：共通関数でフル画像付与
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
     */
    fun checkForUpdates(url: String, currentItemCount: Int, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                // 現在のHTMLを取得
                val document = NetworkClient.fetchDocument(url)

                // 新しいコンテンツをパース
                val newItemList = parseItemsFromDocument(document)

                // 既存データの fullImageUrl を引き継ぐ
                val currentItems = _images.value ?: emptyList()
                val currentMapByDetail = currentItems.associateBy { it.detailUrl }

                val carried = newItemList.map { ni ->
                    val old = currentMapByDetail[ni.detailUrl]
                    ni.copy(fullImageUrl = old?.fullImageUrl)
                }

                // fullImageUrl がまだ無いものだけ detail を取りに行く
                val needFetch = carried.filter { it.fullImageUrl.isNullOrBlank() }
                val fetched = enrichWithFullImages(needFetch)
                val fetchedMap = fetched.associateBy { it.detailUrl }

                // 取得結果をマージ
                val merged = carried.map { ci -> fetchedMap[ci.detailUrl] ?: ci }

                val hasNewContent = merged.size != currentItems.size || !merged.containsAll(currentItems)

                if (hasNewContent) {
                    _images.postValue(merged) // ★更新後も fullImageUrl を保持
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
     * ドキュメントからImageItemのリストを解析する共通メソッド
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
                // imgTagのsrc属性も同様にabsUrlで絶対URLを取得
                val imageUrl = imgTag.absUrl("src")
                val detailUrl = linkTag.absUrl("href")
                val title = smallTag.text()
                val replies = fontTag.text()

                // imageUrlが空でないことを確認
                if (imageUrl.isNotEmpty() && detailUrl.isNotEmpty()) {
                    // ImageItem の第1引数は previewUrl（サムネ）
                    parsedItems.add(ImageItem(imageUrl, title, replies, detailUrl, fullImageUrl = null))
                } else {
                    Log.w(
                        "MainViewModel",
                        "Skipping item due to empty imageUrl or detailUrl. Image src: ${imgTag.attr("src")}, Link href: ${linkTag.attr("href")}"
                    )
                }
            }
        }

        return parsedItems
    }
}