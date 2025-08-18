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
import org.jsoup.nodes.Element // Added import

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _detailContent = MutableLiveData<List<DetailContent>>()
    val detailContent: LiveData<List<DetailContent>> = _detailContent

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val cacheManager = DetailCacheManager(application)
    private var currentUrl: String? = null

    fun fetchDetails(url: String, forceRefresh: Boolean = false) {
        Log.d("DetailViewModel", "fetchDetails: Called with forceRefresh: $forceRefresh for URL: $url") // Added log
        viewModelScope.launch {
            this@DetailViewModel.currentUrl = url
            _isLoading.value = true
            _error.value = null
            var itemIdCounter = 0L // ID生成用のカウンターを追加

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

                // Network fetch starts
                val document = withContext(Dispatchers.IO) {
                    NetworkClient.fetchDocument(getApplication(), url)
                }
                
                // ▼▼▼▼▼ ここから下のブロックを全て置き換える ▼▼▼▼▼

                val progressivelyLoadedContent = mutableListOf<DetailContent>()
                val promptJobs = mutableListOf<Deferred<Pair<Int, String?>>>()

                _detailContent.postValue(emptyList()) // UIを一旦クリア

                // スレッド全体のコンテナを取得
                val threadContainer = document.selectFirst("div.thre")

                if (threadContainer == null) {
                    _error.value = "スレッドが見つかりませんでした。"
                    _isLoading.value = false
                    _detailContent.postValue(emptyList())
                    return@launch
                }
                
                // 処理対象となる全ての投稿（OP + 返信）をリストアップする
                val postBlocks = mutableListOf<Element>()
                postBlocks.add(threadContainer) // 最初の投稿(OP)としてコンテナ自体を追加
                postBlocks.addAll(threadContainer.select("table:has(td.rtd)")) // コンテナ内の返信(table)を全て追加

                // 全ての投稿をループ処理
                postBlocks.forEachIndexed { index, block ->
                    val isOp = (index == 0) // 最初の要素がOP

                    // --- 1. テキストコンテンツの解析 ---
                    val textSourceElement = if (isOp) {
                        // OPの場合、子要素の返信テーブルを除外したクローンを作成する
                        block.clone().apply { select("table").remove() }
                    } else {
                        // 返信の場合、ブロック（table）そのものを使用する
                        block
                    }
                    
                    val textBlock = textSourceElement.clone()
                    // メディアファイルへのリンクはテキストコンテンツから除外する
                    val mediaLinkForTextExclusion = block.select("a[target=_blank]").firstOrNull { a ->
                        val href = a.attr("href").lowercase()
                        href.endsWith(".png") || href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".gif") || href.endsWith(".webp") || href.endsWith(".webm") || href.endsWith(".mp4")
                    }
                    mediaLinkForTextExclusion?.let { link -> textBlock.select("a[href='''${link.attr("href")}''']").remove() }

                    // HTMLコンテンツを抽出
                    val html = if (isOp) {
                        textBlock.html() // OPの場合、クローンのinnerHTMLを取得
                    } else {
                        textBlock.selectFirst(".rtd")?.html() ?: "" // 返信の場合、.rtdセルのinnerHTMLを取得
                    }

                    if (html.isNotBlank()) {
                        progressivelyLoadedContent.add(DetailContent.Text(id = "text_${itemIdCounter++}", htmlContent = html))
                    }

                    // --- 2. メディアコンテンツの解析 ---
                    val mediaLinkNode = block.select("a[target=_blank]").firstOrNull { a ->
                        val href = a.attr("href").lowercase()
                        href.endsWith(".png") || href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".gif") || href.endsWith(".webp") || href.endsWith(".webm") || href.endsWith(".mp4")
                    }

                    mediaLinkNode?.let { link ->
                        val hrefAttr = link.attr("href")
                        val absoluteUrl = try {
                            URL(URL(url), hrefAttr).toString()
                        } catch (e: MalformedURLException) {
                            Log.e("DetailViewModel", "Failed to form absolute URL from base: $url and href: $hrefAttr", e)
                            hrefAttr
                        }
                        val fileName = absoluteUrl.substringAfterLast('/')
                        val itemIndexInList = progressivelyLoadedContent.size

                        val mediaContent = when {
                            hrefAttr.lowercase().endsWith(".jpg") || hrefAttr.lowercase().endsWith(".png") || hrefAttr.lowercase().endsWith(".jpeg") || hrefAttr.lowercase().endsWith(".gif") || hrefAttr.lowercase().endsWith(".webp") -> {
                                DetailContent.Image(id = absoluteUrl, imageUrl = absoluteUrl, prompt = null, fileName = fileName)
                            }
                            hrefAttr.lowercase().endsWith(".webm") || hrefAttr.lowercase().endsWith(".mp4") -> {
                                DetailContent.Video(id = absoluteUrl, videoUrl = absoluteUrl, prompt = null, fileName = fileName)
                            }
                            else -> null
                        }

                        if (mediaContent != null) {
                            progressivelyLoadedContent.add(mediaContent)
                            val deferredPrompt = async(Dispatchers.IO) {
                                try {
                                    val prompt = withTimeoutOrNull(30000L) {
                                        MetadataExtractor.extract(getApplication(), absoluteUrl)
                                    }
                                    if (prompt == null) {
                                        Log.w("DetailViewModel", "Metadata for $absoluteUrl was null (possibly due to timeout or explicit null return)")
                                    }
                                    Pair(itemIndexInList, prompt)
                                } catch (e: Exception) {
                                    Log.e("DetailViewModel", "Exception during metadata extraction task for $absoluteUrl", e)
                                    Pair(itemIndexInList, null as String?)
                                }
                            }
                            promptJobs.add(deferredPrompt)
                        }
                    }
                }

                // ▲▲▲▲▲ ここまでのブロックを全て置き換える ▲▲▲▲▲

                // Extract thread end time
                val scriptElements = document.select("script")
                var threadEndTime: String? = null
                val docWriteRegex = Regex("""document\.write\s*\(\s*'(.*?)'\s*\)"""")
                val timeRegex = Regex("""<span id="contdisp">([^<]+)<\/span>"""")

                for (scriptElement in scriptElements) {
                    val scriptData = scriptElement.data()
                    if (scriptData.contains("document.write") && scriptData.contains("contdisp")) {
                        val docWriteMatch = docWriteRegex.find(scriptData)
                        val writtenHtmlFromDocWrite = docWriteMatch?.groupValues?.getOrNull(1)
                        val writtenHtml = writtenHtmlFromDocWrite?.replace("\\'", "'")?.replace("\\/", "/")
                        if (writtenHtml != null) {
                            val timeMatch = timeRegex.find(writtenHtml)
                            threadEndTime = timeMatch?.groupValues?.getOrNull(1)
                            if (threadEndTime != null) break
                        }
                    }
                }

                threadEndTime?.let {
                    progressivelyLoadedContent.add(DetailContent.ThreadEndTime(id = "thread_end_time_${itemIdCounter++}", endTime = it))
                }

                _detailContent.postValue(progressivelyLoadedContent.toList())
                _isLoading.value = false

                // Launch a new coroutine for background processing of prompts, final UI update, and caching
                viewModelScope.launch(Dispatchers.Default) { // Start on Default for awaitAll and list processing
                    try {
                        val allPromptResults = promptJobs.awaitAll()

                        // Create the final list with prompts, based on the initially displayed content
                        val finalListWithPrompts = progressivelyLoadedContent.toMutableList()
                        allPromptResults.forEach { (index, prompt) ->
                            if (index < finalListWithPrompts.size) { // Check bounds
                                val itemToUpdate = finalListWithPrompts[index]
                                finalListWithPrompts[index] = when (itemToUpdate) {
                                    is DetailContent.Image -> itemToUpdate.copy(prompt = prompt)
                                    is DetailContent.Video -> itemToUpdate.copy(prompt = prompt)
                                    else -> {
                                         if (itemToUpdate !is DetailContent.Text && itemToUpdate !is DetailContent.ThreadEndTime) {
                                            Log.w("DetailViewModel", "BG Update: Attempted to update non-media item at index $index with prompt.")
                                         }
                                         itemToUpdate
                                    }
                                }
                            } else {
                                Log.w("DetailViewModel", "BG Update: Index $index out of bounds for finalListWithPrompts. Size: ${finalListWithPrompts.size}")
                            }
                        }

                        // Update UI on the main thread with the content including all fetched prompts
                        withContext(Dispatchers.Main) {
                            _detailContent.postValue(finalListWithPrompts.toList())
                        }

                        // Then, cache this same list on IO thread
                        withContext(Dispatchers.IO) {
                            cacheManager.saveDetails(url, finalListWithPrompts.toList())
                        }
                        Log.d("DetailViewModel", "Background prompt processing, final UI update, and caching completed for $url.")
                    } catch (e: Exception) {
                        Log.e("DetailViewModel", "Error in background prompt processing, UI update, or caching for $url", e)
                    }
                }

            } catch (e: Exception) {
                _error.value = "詳細の取得に失敗しました: ${e.message}"
                Log.e("DetailViewModel", "Error fetching details for $url", e)
                _isLoading.value = false // Ensure loading is stopped on error
            }
        }
    }

    fun invalidateCache(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            cacheManager.invalidateCache(url)
        }
    }

    fun postSodaNe(resNum: String) {
        val url = currentUrl
        if (url == null) {
            _error.value = "「そうだね」の投稿に失敗しました: 対象のURLが不明です。"
            return
        }

        Log.d("DetailViewModel", "postSodaNe called. resNum: $resNum, currentUrl: $url")

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                Log.d("DetailViewModel", "Calling NetworkClient.postSodaNe with resNum: $resNum, referer: $url")
                val success = NetworkClient.postSodaNe(getApplication(), resNum, url)
                Log.d("DetailViewModel", "NetworkClient.postSodaNe result: $success")
                if (success) {
                    Log.d("DetailViewModel", "postSodaNe: \'success\' is true. Calling fetchDetails for URL: $url") // Added log
                    fetchDetails(url, forceRefresh = true)
                } else {
                    _error.value = "「そうだね」の投稿に失敗しました。"
                    _isLoading.value = false
                }
            } catch (e: Exception) {
                Log.e("DetailViewModel", "Error in postSodaNe: ${e.message}", e)
                _error.value = "「そうだね」の投稿中にエラーが発生しました: ${e.message}"
                _isLoading.value = false
            }
        }
    }
}
