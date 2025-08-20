package com.example.hutaburakari

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.jsoup.nodes.Document

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _images = MutableLiveData<List<ImageItem>>()
    val images: LiveData<List<ImageItem>> = _images

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    fun fetchImagesFromUrl(url: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val document = NetworkClient.fetchDocument(url)
                val parsedItems = parseItemsFromDocument(document)
                _images.value = parsedItems
            } catch (e: Exception) {
                _error.value = "データの取得に失敗しました: ${e.message}"
                e.printStackTrace()
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

                // 現在のコンテンツと比較
                val currentItems = _images.value ?: emptyList()
                val hasNewContent = newItemList.size != currentItems.size ||
                        !newItemList.containsAll(currentItems)

                if (hasNewContent) {
                    // UIを更新（完全置換 - カタログは順序が変わることがあるため）
                    _images.postValue(newItemList)

                    Log.d("MainViewModel", "Updated catalog: ${currentItems.size} -> ${newItemList.size} items")
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
                    parsedItems.add(ImageItem(imageUrl, title, replies, detailUrl))
                } else {
                    Log.w("MainViewModel", "Skipping item due to empty imageUrl or detailUrl. Image src: ${imgTag.attr("src")}, Link href: ${linkTag.attr("href")}")
                }
            }
        }

        return parsedItems
    }
}