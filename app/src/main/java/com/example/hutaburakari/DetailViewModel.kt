package com.example.hutaburakari

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.hutaburakari.cache.DetailCacheManager
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.MalformedURLException
import java.net.URL
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _detailContent = MutableLiveData<List<DetailContent>>()
    val detailContent: LiveData<List<DetailContent>> = _detailContent

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // そうだねの更新通知用
    private val _sodaneUpdate = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 1)
    val sodaneUpdate = _sodaneUpdate.asSharedFlow()

    // 代わりに、レス番号で受ける関数を用意（UIから resNo だけ渡す）
    fun onClickSodane(resNo: String, referer: String) {
        viewModelScope.launch {
            val count = NetworkClient.postSodaNe(resNo, referer) // Int? を返す想定
            if (count != null) {
                _sodaneUpdate.tryEmit(resNo to count)
            } else {
                _error.postValue("「そうだね」の送信に失敗しました。")
            }
        }
    }

    private val cacheManager = DetailCacheManager(application)
    private var currentUrl: String? = null

    // そうだねの状態を保持するマップ (resNum -> そうだねが押されたかどうか)
    private val sodaNeStates = mutableMapOf<String, Boolean>()

    // (F) メタデータ抽出の並列数を制限
    private val limitedIO = Dispatchers.IO.limitedParallelism(4)

    fun fetchDetails(url: String, forceRefresh: Boolean = false) {
        Log.d("DetailViewModel", "fetchDetails: Called with forceRefresh: $forceRefresh for URL: $url")
        viewModelScope.launch {
            this@DetailViewModel.currentUrl = url
            _isLoading.value = true
            _error.value = null
            var itemIdCounter = 0L

            // 新しいページを読み込む場合はそうだねの状態をリセット
            if (forceRefresh || currentUrl != url) {
                resetSodaNeStates()
            }

            try {
                if (!forceRefresh) {
                    val cachedDetails = withContext(Dispatchers.IO) {
                        cacheManager.loadDetails(url)
                    }
                    if (cachedDetails != null) {
                        _detailContent.postValue(cachedDetails)
                        _isLoading.value = false
                        return@launch
                    }
                }

                val document = withContext(Dispatchers.IO) {
                    NetworkClient.fetchDocument(url).apply {
                        // (D) HTMLシリアライズのオーバーヘッドを抑制
                        outputSettings().prettyPrint(false)
                    }
                }

                val progressivelyLoadedContent = parseContentFromDocument(document, url)

                // 全ての解析が完了してから一度だけ通知
                _detailContent.postValue(progressivelyLoadedContent)
                _isLoading.value = false

                // バックグラウンドでメタデータを取得し、完了後に再度更新
                updateMetadataInBackground(progressivelyLoadedContent, url)

            } catch (e: Exception) {
                _error.value = "詳細の取得に失敗しました: ${e.message}"
                Log.e("DetailViewModel", "Error fetching details for $url", e)
                _isLoading.value = false
            }
        }
    }

    /**
     * スレッドに新しい更新があるかチェックし、あれば追加する
     */
    fun checkForUpdates(url: String, currentItemCount: Int, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                // 現在のHTMLを取得
                val document = withContext(Dispatchers.IO) {
                    NetworkClient.fetchDocument(url)
                }

                // 新しいコンテンツをパース
                val newContentList = parseContentFromDocument(document, url)

                // 現在のコンテンツと比較
                val currentContent = _detailContent.value ?: emptyList()
                val hasNewContent = newContentList.size > currentContent.size

                if (hasNewContent) {
                    // 新しいコンテンツのみを抽出
                    val newItems = newContentList.drop(currentContent.size)

                    // 既存のコンテンツに新しいアイテムを追加
                    val updatedContent = currentContent + newItems

                    // UIを更新
                    _detailContent.postValue(updatedContent)

                    // キャッシュも更新
                    withContext(Dispatchers.IO) {
                        cacheManager.saveDetails(url, updatedContent)
                    }

                    // 新しいアイテムのメタデータも取得
                    updateMetadataInBackground(newItems, url)

                    callback(true)
                } else {
                    callback(false)
                }

            } catch (e: Exception) {
                Log.e("DetailViewModel", "Error checking for updates", e)
                callback(false)
            }
        }
    }

    /**
     * ドキュメントからコンテンツを解析する共通メソッド（修正版）
     */
    private suspend fun parseContentFromDocument(document: Document, url: String): List<DetailContent> {
        val progressivelyLoadedContent = mutableListOf<DetailContent>()
        var itemIdCounter = 0L

        val threadContainer = document.selectFirst("div.thre")

        if (threadContainer == null) {
            _error.postValue("スレッドのコンテナが見つかりませんでした。")
            Log.e("DetailViewModel", "div.thre container not found in document for URL: $url")
            return emptyList()
        }

        // 処理対象となる全ての投稿（OP + 返信）をリストアップ
        val postBlocks = mutableListOf<Element>()
        postBlocks.add(threadContainer) // 最初の投稿(OP)としてコンテナ自体を追加

        // OPコンテナ内の返信テーブルを全て追加
        threadContainer.select("td.rtd")
            .mapNotNull { it.closest("table") }
            .distinct()
            .let { postBlocks.addAll(it) }

        // 全ての投稿をループ処理
        postBlocks.forEachIndexed { index, block ->
            val isOp = (index == 0) // 最初の要素がOP

            // --- 1. テキストコンテンツの解析 ---
            val html: String
            if (isOp) {
                // OPの場合、子要素の返信テーブルを除外したクローンを作成
                val textSourceElement = block.clone().apply { select("table").remove() }

                // メディアファイルへのリンクをテキストコンテンツから除外
                //textSourceElement.select("a[target=_blank][href]")
                //    .filter { a -> isMediaUrl(a.attr("href")) }
                //    .forEach { it.remove() }

                html = textSourceElement.html()
            } else {
                // 返信の場合、.rtdセルからHTMLを取得
                val rtd = block.selectFirst(".rtd")
                if (rtd != null) {
                    val textBlock = rtd.clone()
                    // メディアファイルへのリンクをテキストコンテンツから除外
                    //textBlock.select("a[target=_blank][href]")
                    //    .filter { a -> isMediaUrl(a.attr("href")) }
                    //    .forEach { it.remove() }
                    html = textBlock.html()
                } else {
                    html = ""
                }
            }

            if (html.isNotBlank()) {
                progressivelyLoadedContent.add(
                    DetailContent.Text(id = "text_${itemIdCounter++}", htmlContent = html)
                )
            }

            // --- 2. メディアコンテンツの解析 ---
            val mediaLinkNode = block.select("a[target=_blank][href]").firstOrNull { a ->
                isMediaUrl(a.attr("href"))
            }

            mediaLinkNode?.let { link ->
                val hrefAttr = link.attr("href")
                try {
                    val absoluteUrl = URL(URL(url), hrefAttr).toString()
                    val fileName = absoluteUrl.substringAfterLast('/')

                    val lower = hrefAttr.lowercase()
                    val mediaContent = when {
                        lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".jpeg")
                                || lower.endsWith(".gif") || lower.endsWith(".webp") -> {
                            DetailContent.Image(
                                id = absoluteUrl,
                                imageUrl = absoluteUrl,
                                prompt = null,
                                fileName = fileName
                            )
                        }
                        lower.endsWith(".webm") || lower.endsWith(".mp4") -> {
                            DetailContent.Video(
                                id = absoluteUrl,
                                videoUrl = absoluteUrl,
                                prompt = null,
                                fileName = fileName
                            )
                        }
                        else -> null
                    }

                    if (mediaContent != null) {
                        progressivelyLoadedContent.add(mediaContent)
                    }
                } catch (e: MalformedURLException) {
                    Log.e(
                        "DetailViewModel",
                        "Skipping malformed media URL. Base: '$url', Href: '$hrefAttr'",
                        e
                    )
                }
            }
        }

        // スレッド終了時刻の解析
        val scriptElements = document.select("script")
        var threadEndTime: String? = null

        for (scriptElement in scriptElements) {
            val scriptData = scriptElement.data()
            if (scriptData.contains("document.write") && scriptData.contains("contdisp")) {
                val docWriteMatch = DOC_WRITE.find(scriptData)
                val writtenHtmlFromDocWrite = docWriteMatch?.groupValues?.getOrNull(1)
                val writtenHtml = writtenHtmlFromDocWrite
                    ?.replace("\\'", "'")
                    ?.replace("\\/", "/")
                if (writtenHtml != null) {
                    val timeMatch = TIME.find(writtenHtml)
                    threadEndTime = timeMatch?.groupValues?.getOrNull(1)
                    if (threadEndTime != null) break
                }
            }
        }

        threadEndTime?.let {
            progressivelyLoadedContent.add(
                DetailContent.ThreadEndTime(id = "thread_end_time_${itemIdCounter++}", endTime = it)
            )
        }

        return progressivelyLoadedContent.toList()
    }

    /**
     * バックグラウンドでメタデータを更新
     */
    private fun updateMetadataInBackground(contentList: List<DetailContent>, url: String) {
        val promptJobs = mutableListOf<Deferred<Pair<Int, String?>>>()

        contentList.forEachIndexed { index, content ->
            when (content) {
                is DetailContent.Image -> {
                    val deferredPrompt = viewModelScope.async(limitedIO) {
                        try {
                            val prompt = withTimeoutOrNull(5000L) {
                                MetadataExtractor.extract(getApplication(), content.imageUrl)
                            }
                            if (prompt == null) {
                                Log.w(
                                    "DetailViewModel",
                                    "Metadata for ${content.imageUrl} was null (timeout or null)"
                                )
                            }
                            Pair(index, prompt)
                        } catch (e: Exception) {
                            Log.e(
                                "DetailViewModel",
                                "Exception during metadata extraction task for ${content.imageUrl}",
                                e
                            )
                            Pair(index, null as String?)
                        }
                    }
                    promptJobs.add(deferredPrompt)
                }
                is DetailContent.Video -> {
                    val deferredPrompt = viewModelScope.async(limitedIO) {
                        try {
                            val prompt = withTimeoutOrNull(5000L) {
                                MetadataExtractor.extract(getApplication(), content.videoUrl)
                            }
                            if (prompt == null) {
                                Log.w(
                                    "DetailViewModel",
                                    "Metadata for ${content.videoUrl} was null (timeout or null)"
                                )
                            }
                            Pair(index, prompt)
                        } catch (e: Exception) {
                            Log.e(
                                "DetailViewModel",
                                "Exception during metadata extraction task for ${content.videoUrl}",
                                e
                            )
                            Pair(index, null as String?)
                        }
                    }
                    promptJobs.add(deferredPrompt)
                }
                else -> {}
            }
        }

        // メタデータ取得完了後に更新
        if (promptJobs.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val allPromptResults = promptJobs.awaitAll()

                    val currentContentList = _detailContent.value?.toMutableList() ?: return@launch
                    var hasUpdates = false

                    allPromptResults.forEach { (relativeIndex, prompt) ->
                        // contentList内の相対インデックスから全体のインデックスを計算
                        val absoluteIndex = currentContentList.size - contentList.size + relativeIndex

                        if (absoluteIndex >= 0 && absoluteIndex < currentContentList.size) {
                            val itemToUpdate = currentContentList[absoluteIndex]
                            val updatedItem = when (itemToUpdate) {
                                is DetailContent.Image -> itemToUpdate.copy(prompt = prompt)
                                is DetailContent.Video -> itemToUpdate.copy(prompt = prompt)
                                else -> itemToUpdate
                            }
                            if (updatedItem != itemToUpdate) {
                                currentContentList[absoluteIndex] = updatedItem
                                hasUpdates = true
                            }
                        }
                    }

                    if (hasUpdates) {
                        withContext(Dispatchers.Main) {
                            _detailContent.postValue(currentContentList.toList())
                        }

                        withContext(Dispatchers.IO) {
                            cacheManager.saveDetails(url, currentContentList.toList())
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DetailViewModel", "Error in background prompt processing for $url", e)
                }
            }
        }
    }

    // ファイル下部の既存 postSodaNe(resNum: String) を「Int? 前提」に書き換え
    fun postSodaNe(resNum: String) {
        val url = currentUrl
        if (url == null) {
            _error.value = "「そうだね」の投稿に失敗しました: 対象のURLが不明です。"
            return
        }
        viewModelScope.launch {
            try {
                val count = NetworkClient.postSodaNe(resNum, url) // ← Int? を受ける
                if (count != null) {
                    _sodaneUpdate.tryEmit(resNum to count)        // ← _sodaneUpdate に統一
                } else {
                    _error.value = "「そうだね」の投稿に失敗しました。"
                }
            } catch (e: Exception) {
                Log.e("DetailViewModel", "Error in postSodaNe: ${e.message}", e)
                _error.value = "「そうだね」の投稿中にエラーが発生しました: ${e.message}"
            }
        }
    }

    // そうだねの状態を取得
    fun getSodaNeState(resNum: String): Boolean {
        return sodaNeStates[resNum] ?: false
    }

    // そうだねの状態をリセット（新しいページを読み込む時など）
    fun resetSodaNeStates() {
        sodaNeStates.clear()
    }

    // 削除
    fun deletePost(postUrl: String, referer: String, resNum: String, pwd: String) {
        viewModelScope.launch {
            try {
                _isLoading.postValue(true)

                // 念のため直前にスレGETしてCookieを埋める（posttime等）
                withContext(Dispatchers.IO) { NetworkClient.fetchDocument(referer) }

                val ok = withContext(Dispatchers.IO) {
                    NetworkClient.deletePost(
                        postUrl = postUrl,
                        referer = referer,
                        resNum = resNum,
                        pwd = pwd
                    )
                }

                if (ok) {
                    // 成功したらスレ再取得（forceRefresh）
                    currentUrl?.let { fetchDetails(it, forceRefresh = true) }
                } else {
                    _error.postValue("削除に失敗しました。削除キーが違う可能性があります。")
                }
            } catch (e: Exception) {
                _error.postValue("削除中にエラーが発生しました: ${e.message}")
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    // ===== Helpers & Regex =====

    private fun isMediaUrl(rawHref: String): Boolean {
        val h = rawHref.lowercase()
        return h.endsWith(".png") || h.endsWith(".jpg") || h.endsWith(".jpeg") ||
                h.endsWith(".gif") || h.endsWith(".webp") ||
                h.endsWith(".webm") || h.endsWith(".mp4")
    }
    companion object {
        // (E) プリコンパイル済み正規表現
        private val DOC_WRITE = Regex("""document\.write\s*\(\s*'(.*?)'\s*\)""")
        private val TIME = Regex("""<span id="contdisp">([^<]+)</span>""")
    }
}