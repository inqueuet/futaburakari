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
import org.jsoup.nodes.Element

class DetailViewModel(application: Application) : AndroidViewModel(application) {

    private val _detailContent = MutableLiveData<List<DetailContent>>()
    val detailContent: LiveData<List<DetailContent>> = _detailContent

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    private val cacheManager = DetailCacheManager(application)
    private var currentUrl: String? = null

    // (F) メタデータ抽出の並列数を制限
    private val limitedIO = Dispatchers.IO.limitedParallelism(4)

    fun fetchDetails(url: String, forceRefresh: Boolean = false) {
        Log.d("DetailViewModel", "fetchDetails: Called with forceRefresh: $forceRefresh for URL: $url")
        viewModelScope.launch {
            this@DetailViewModel.currentUrl = url
            _isLoading.value = true
            _error.value = null
            var itemIdCounter = 0L

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

                val progressivelyLoadedContent = mutableListOf<DetailContent>()
                val promptJobs = mutableListOf<Deferred<Pair<Int, String?>>>()

                _detailContent.postValue(emptyList())

                val threadContainer = document.selectFirst("div.thre")

                if (threadContainer == null) {
                    _error.value = "スレッドが見つかりませんでした。"
                    _isLoading.value = false
                    _detailContent.postValue(emptyList())
                    return@launch
                }

                val postBlocks = mutableListOf<Element>()
                postBlocks.add(threadContainer)

                // (B) :has() を避け、td.rtd から親 table を辿る
                threadContainer.select("td.rtd")
                    .mapNotNull { it.closest("table") }
                    .distinct()
                    .let { postBlocks.addAll(it) }

                postBlocks.forEachIndexed { index, block ->
                    val isOp = (index == 0)

                    // (A) OP の巨大 clone を避け、基本は .rtd を直接解析
                    val html: String = run {
                        val rtd = block.selectFirst(".rtd")
                        if (rtd != null) {
                            val textBlock = rtd.clone()
                            // メディアリンクをテキストから除外
                            textBlock.select("a[target=_blank][href]")
                                .filter { a -> isMediaUrl(a.attr("href")) }
                                .forEach { it.remove() }
                            textBlock.html()
                        } else {
                            // フォールバック：OP で .rtd が無いケースのみ最小限の clone
                            if (isOp) {
                                val textSourceElement = block.clone().apply { select("table").remove() }
                                textSourceElement.select("a[target=_blank][href]")
                                    .filter { a -> isMediaUrl(a.attr("href")) }
                                    .forEach { it.remove() }
                                textSourceElement.html()
                            } else {
                                ""
                            }
                        }
                    }

                    if (html.isNotBlank()) {
                        progressivelyLoadedContent.add(
                            DetailContent.Text(id = "text_${itemIdCounter++}", htmlContent = html)
                        )
                    }

                    // (C) アンカー走査を1回に統合
                    val mediaLinkNode = block.select("a[target=_blank][href]").firstOrNull { a ->
                        isMediaUrl(a.attr("href"))
                    }

                    mediaLinkNode?.let { link ->
                        val hrefAttr = link.attr("href")
                        try {
                            val absoluteUrl = URL(URL(url), hrefAttr).toString()
                            val fileName = absoluteUrl.substringAfterLast('/')
                            val itemIndexInList = progressivelyLoadedContent.size

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

                                // (F) 並列を制限しつつ、タイムアウト短縮
                                val deferredPrompt = async(limitedIO) {
                                    try {
                                        val prompt = withTimeoutOrNull(5000L) {
                                            MetadataExtractor.extract(getApplication(), absoluteUrl)
                                        }
                                        if (prompt == null) {
                                            Log.w(
                                                "DetailViewModel",
                                                "Metadata for $absoluteUrl was null (timeout or null)"
                                            )
                                        }
                                        Pair(itemIndexInList, prompt)
                                    } catch (e: Exception) {
                                        Log.e(
                                            "DetailViewModel",
                                            "Exception during metadata extraction task for $absoluteUrl",
                                            e
                                        )
                                        Pair(itemIndexInList, null as String?)
                                    }
                                }
                                promptJobs.add(deferredPrompt)
                            }
                        } catch (e: MalformedURLException) {
                            Log.e(
                                "DetailViewModel",
                                "Skipping malformed media URL. Base: '$url', Href: '$hrefAttr'",
                                e
                            )
                        }
                    }

                    // ★ 2件解析するごとに、UIに途中経過を通知する（元仕様のまま）
                    if (index > 0 && index % 2 == 0) {
                        _detailContent.postValue(progressivelyLoadedContent.toList())
                    }
                }

                val scriptElements = document.select("script")
                var threadEndTime: String? = null

                // (E) 正規表現はプリコンパイルしたものを使用
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

                // 最終結果を通知
                _detailContent.postValue(progressivelyLoadedContent.toList())
                _isLoading.value = false

                viewModelScope.launch(Dispatchers.Default) {
                    try {
                        val allPromptResults = promptJobs.awaitAll()

                        val finalListWithPrompts = progressivelyLoadedContent.toMutableList()
                        allPromptResults.forEach { (indexInList, prompt) ->
                            if (indexInList < finalListWithPrompts.size) {
                                val itemToUpdate = finalListWithPrompts[indexInList]
                                finalListWithPrompts[indexInList] = when (itemToUpdate) {
                                    is DetailContent.Image -> itemToUpdate.copy(prompt = prompt)
                                    is DetailContent.Video -> itemToUpdate.copy(prompt = prompt)
                                    else -> itemToUpdate
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            _detailContent.postValue(finalListWithPrompts.toList())
                        }

                        withContext(Dispatchers.IO) {
                            cacheManager.saveDetails(url, finalListWithPrompts.toList())
                        }
                    } catch (e: Exception) {
                        Log.e("DetailViewModel", "Error in background prompt processing for $url", e)
                    }
                }

            } catch (e: Exception) {
                _error.value = "詳細の取得に失敗しました: ${e.message}"
                Log.e("DetailViewModel", "Error fetching details for $url", e)
                _isLoading.value = false
            }
        }
    }

    fun postSodaNe(resNum: String) {
        val url = currentUrl
        if (url == null) {
            _error.value = "「そうだね」の投稿に失敗しました: 対象のURLが不明です。"
            return
        }

        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val success = NetworkClient.postSodaNe(resNum, url)

                if (success) {
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