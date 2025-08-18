package com.example.hutaburakari

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _images = MutableLiveData<List<ImageItem>>()
    val images: LiveData<List<ImageItem>> = _images

    private val _error = MutableLiveData<String>()
    val error: LiveData<String> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // ★★★ このinitブロックをコメントアウトまたは削除します ★★★
    /*
    init {
        fetchImagesFromUrl("https://may.2chan.net/b/futaba.php?mode=cat&sort=3")
    }
    */

    fun fetchImagesFromUrl(url: String) { // url はカタログページの完全なURL (例: https://may.2chan.net/27/futaba.php?mode=cat&sort=3)
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val document = NetworkClient.fetchDocument(url)

                val parsedItems = mutableListOf<ImageItem>()
                // val baseUrl = "https://may.2chan.net" // ← この固定のbaseUrlは使わない

                val cells = document.select("#cattable td")

                for (cell in cells) {
                    val linkTag = cell.select("a").first()
                    val imgTag = cell.select("img").first()
                    val smallTag = cell.select("small").first()
                    val fontTag = cell.select("font").first()

                    if (linkTag != null && imgTag != null && smallTag != null && fontTag != null) {
                        // imgTagのsrc属性も同様にabsUrlで絶対URLを取得
                        val imageUrl = imgTag.absUrl("src")
                        // val relativeLink = linkTag?.attr("href") // ← attr("href")で相対パスを取得する代わりに
                        val detailUrl = linkTag.absUrl("href") // ← absUrl("href")で絶対URLを取得する
                        val title = smallTag.text()
                        val replies = fontTag.text()

                        // imageUrlが空でないことを確認（万が一src属性が空だったり、不正な場合）
                        if (imageUrl.isNotEmpty() && detailUrl.isNotEmpty()) {
                             parsedItems.add(ImageItem(imageUrl, title, replies, detailUrl))
                        } else {
                            Log.w("MainViewModel", "Skipping item due to empty imageUrl or detailUrl. Image src: ${imgTag.attr("src")}, Link href: ${linkTag.attr("href")}")
                        }
                    }
                }
                _images.value = parsedItems

            } catch (e: Exception) {
                _error.value = "データの取得に失敗しました: ${e.message}"
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }
}