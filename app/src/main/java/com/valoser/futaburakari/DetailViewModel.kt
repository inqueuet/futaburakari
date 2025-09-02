package com.valoser.futaburakari

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.valoser.futaburakari.cache.DetailCacheManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.MalformedURLException
import java.io.IOException
import java.net.URL
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val networkClient: NetworkClient,
) : ViewModel() {

    private val _detailContent = MutableLiveData<List<DetailContent>>()
    val detailContent: LiveData<List<DetailContent>> = _detailContent

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // そうだねの更新通知用
    private val _sodaneUpdate = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 1)
    val sodaneUpdate = _sodaneUpdate.asSharedFlow()

    // 「そうだね」送信（UIからはレス番号のみ渡す）
    // 参照（Referer）は現在のスレURL（currentUrl）を使用して NetworkClient に委譲
    // 成功時はサーバ返り値のカウントを通知して UI に反映

    private val cacheManager = DetailCacheManager(appContext)
    private var currentUrl: String? = null
    // NG フィルタ適用前の生コンテンツを保持
    private var rawContent: List<DetailContent> = emptyList()
    private val ngStore by lazy { NgStore(appContext) }

    // そうだねの状態を保持するマップ (resNum -> そうだねが押されたかどうか)
    private val sodaNeStates = mutableMapOf<String, Boolean>()

    // (F) メタデータ抽出の並列数を制限
    private val limitedIO = Dispatchers.IO.limitedParallelism(2)

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
                    val cachedDetails = withContext(Dispatchers.IO) { cacheManager.loadDetails(url) }
                    if (cachedDetails != null) {
                        rawContent = cachedDetails
                        applyNgAndPost()
                        _isLoading.value = false
                        return@launch
                    }
                    // 履歴がアーカイブ済みで、アーカイブスナップショットがあれば即時表示してネットワークを避ける
                    val archived = runCatching {
                        HistoryManager.getAll(appContext).any { it.url == url && it.isArchived }
                    }.getOrDefault(false)
                    if (archived) {
                        val snap = withContext(Dispatchers.IO) { cacheManager.loadArchiveSnapshot(url) }
                        if (!snap.isNullOrEmpty()) {
                            rawContent = snap
                            applyNgAndPost()
                            _isLoading.value = false
                            return@launch
                        }
                    }
                }

                val document = withContext(Dispatchers.IO) {
                    networkClient.fetchDocument(url).apply {
                        // (D) HTMLシリアライズのオーバーヘッドを抑制
                        outputSettings().prettyPrint(false)
                    }
                }

                val progressivelyLoadedContent = parseContentFromDocument(document, url)

                // キャッシュは生データを保存し、表示はNG適用後
                rawContent = progressivelyLoadedContent
                applyNgAndPost()
                _isLoading.value = false

                // バックグラウンドでメタデータを取得し、完了後に再度更新
                updateMetadataInBackground(progressivelyLoadedContent, url)

            } catch (e: Exception) {
                // ネットワーク失敗時はキャッシュへフォールバック（アーカイブ閲覧時など）
                Log.e("DetailViewModel", "Error fetching details for $url", e)
                val cached = withContext(Dispatchers.IO) { cacheManager.loadDetails(url) }
                if (cached != null) {
                    // 404（dat落ち）相当なら履歴をアーカイブ扱いにする（BG監視OFF時の救済）
                    if (e is IOException && (e.message?.contains("404") == true)) {
                        runCatching { HistoryManager.markArchived(appContext, url) }
                    }
                    rawContent = cached
                    applyNgAndPost()
                    // キャッシュ（ローカル保存済み）からサムネイルを拾って履歴に反映
                    runCatching {
                        val media = cached.firstOrNull { it is DetailContent.Image || it is DetailContent.Video }
                        val thumb = when (media) {
                            is DetailContent.Image -> media.imageUrl
                            is DetailContent.Video -> media.videoUrl
                            else -> null
                        }
                        if (!thumb.isNullOrBlank()) {
                            HistoryManager.updateThumbnail(appContext, url, thumb)
                        }
                    }
                    _error.value = null
                } else {
                    // キャッシュも無い → アーカイブスナップショット or 媒体だけでも再構成
                    val reconstructed = withContext(Dispatchers.IO) {
                        cacheManager.loadArchiveSnapshot(url) ?: cacheManager.reconstructFromArchive(url)
                    }
                    if (!reconstructed.isNullOrEmpty()) {
                        // アーカイブ扱いにしてサムネも反映
                        runCatching { HistoryManager.markArchived(appContext, url) }
                        runCatching {
                            val first = reconstructed.first()
                            val thumb = when (first) {
                                is DetailContent.Image -> first.imageUrl
                                is DetailContent.Video -> first.videoUrl
                                else -> null
                            }
                            if (!thumb.isNullOrBlank()) HistoryManager.updateThumbnail(appContext, url, thumb)
                        }
                        rawContent = reconstructed
                        applyNgAndPost()
                        _error.value = null
                    } else {
                        _error.value = "詳細の取得に失敗しました: ${e.message}"
                    }
                }
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
                    networkClient.fetchDocument(url)
                }

                // 新しいコンテンツをパース
                val newContentList = parseContentFromDocument(document, url)

                // --- ▼▼▼ ここから修正 ▼▼▼ ---

                val currentIds = rawContent.map { it.id }.toSet()

                // 2. 新しく取得したリストの中から、まだ表示されていないIDを持つアイテムだけを抽出する
                val newItems = newContentList.filter { it.id !in currentIds }

                if (newItems.isNotEmpty()) {
                    // 3. 生データを更新してキャッシュ保存、表示はNG適用後
                    rawContent = rawContent + newItems
                    withContext(Dispatchers.IO) { cacheManager.saveDetails(url, rawContent) }
                    applyNgAndPost()
                    updateMetadataInBackground(newItems, url)
                    callback(true)
                } else {
                    callback(false)
                }

                // --- ▲▲▲ ここまで修正 ▲▲▲ ---

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
                val textSourceElement = block.clone().apply {
                    select("table").remove()       // 返信テーブルを除去
                    // <img> は一律削除せず、ALT等のテキストに置換して意味を残す
                    select("img").forEach { img ->
                        val alt = img.attr("alt").ifBlank { "img" }
                        img.replaceWith(org.jsoup.nodes.TextNode("[$alt]"))
                    }
                    // <a href="..."> は残すのでそのまま
                }

                // メディアファイルへのリンクをテキストコンテンツから除外
                //textSourceElement.select("a[target=_blank][href]")
                //    .filter { a -> isMediaUrl(a.attr("href")) }
                //    .forEach { it.remove() }

                html = textSourceElement.html()
            } else {
                // 返信の場合、.rtdセルからHTMLを取得
                val rtd = block.selectFirst(".rtd")
                if (rtd != null) {
                    val textBlock = rtd.clone().apply {
                        // <img> は一律削除せず、ALT等のテキストに置換して意味を残す
                        select("img").forEach { img ->
                            val alt = img.attr("alt").ifBlank { "img" }
                            img.replaceWith(org.jsoup.nodes.TextNode("[$alt]"))
                        }
                    }
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
        // 段階反映: 各ジョブ完了ごとにチャンネルへ送り、一定間隔でまとめて適用
        val updates = Channel<Pair<String, String?>>(Channel.UNLIMITED)
        val sendJobs = mutableListOf<Deferred<Unit>>()

        contentList.forEach { content ->
            when (content) {
                is DetailContent.Image -> {
                    val job = viewModelScope.async(limitedIO) {
                        val prompt = try {
                            withTimeoutOrNull(5000L) { MetadataExtractor.extract(appContext, content.imageUrl, networkClient) }
                        } catch (e: Exception) {
                            Log.e("DetailViewModel", "Metadata task error for ${content.imageUrl}", e)
                            null
                        }
                        if (prompt == null) {
                            Log.w("DetailViewModel", "Metadata for ${content.imageUrl} was null (timeout or null)")
                        }
                        updates.send(content.id to prompt)
                    }
                    sendJobs.add(job)
                }
                is DetailContent.Video -> {
                    val job = viewModelScope.async(limitedIO) {
                        val prompt = try {
                            withTimeoutOrNull(5000L) { MetadataExtractor.extract(appContext, content.videoUrl, networkClient) }
                        } catch (e: Exception) {
                            Log.e("DetailViewModel", "Metadata task error for ${content.videoUrl}", e)
                            null
                        }
                        if (prompt == null) {
                            Log.w("DetailViewModel", "Metadata for ${content.videoUrl} was null (timeout or null)")
                        }
                        updates.send(content.id to prompt)
                    }
                    sendJobs.add(job)
                }
                else -> {}
            }
        }

        if (sendJobs.isEmpty()) return

        // クローズ処理: 全送信ジョブ完了後にチャネルを閉じる
        viewModelScope.launch {
            try {
                sendJobs.joinAll()
            } finally {
                updates.close()
            }
        }

        // 受信・段階反映（200–300ms間隔でバッチ適用）
        viewModelScope.launch(Dispatchers.Default) {
            val batch = mutableMapOf<String, String?>()
            var lastFlush = System.currentTimeMillis()
            val flushIntervalMs = 250L

            suspend fun flush(force: Boolean = false) {
                val now = System.currentTimeMillis()
                val due = (now - lastFlush) >= flushIntervalMs
                if (batch.isNotEmpty() && (force || due)) {
                    val current = _detailContent.value?.toMutableList() ?: return
                    var changed = false
                    batch.forEach { (id, prompt) ->
                        val idx = current.indexOfFirst { it.id == id }
                        if (idx >= 0) {
                            val it = current[idx]
                            val upd = when (it) {
                                is DetailContent.Image -> it.copy(prompt = prompt)
                                is DetailContent.Video -> it.copy(prompt = prompt)
                                else -> it
                            }
                            if (upd != it) { current[idx] = upd; changed = true }
                        }
                    }
                    if (changed) {
                        _detailContent.postValue(current.toList())
                        withContext(Dispatchers.IO) { cacheManager.saveDetails(url, current.toList()) }
                    }
                    batch.clear()
                    lastFlush = now
                }
            }

            // 受信ループ
            for (pair in updates) {
                batch[pair.first] = pair.second
                // 必要なら定期的に反映
                flush(force = false)
            }
            // 終了時の最終反映
            flush(force = true)
        }
    }

    fun postSodaNe(resNum: String) {
        val url = currentUrl
        if (url == null) {
            _error.value = "「そうだね」の投稿に失敗しました: 対象のURLが不明です。"
            return
        }
        viewModelScope.launch {
            try {
                val count = networkClient.postSodaNe(resNum, url)
                if (count != null) {
                    // 成功: 次回以降押下を抑止
                    sodaNeStates[resNum] = true
                    _sodaneUpdate.tryEmit(resNum to count)
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
    fun deletePost(postUrl: String, referer: String, resNum: String, pwd: String, onlyImage: Boolean) {
        viewModelScope.launch {
            try {
                _isLoading.postValue(true)

                // 念のため直前にスレGETしてCookieを埋める（posttime等）
                withContext(Dispatchers.IO) { networkClient.fetchDocument(referer) }

                val ok = withContext(Dispatchers.IO) {
                    networkClient.deletePost(
                        postUrl = postUrl,
                        referer = referer,
                        resNum = resNum,
                        pwd = pwd,
                        onlyImage = onlyImage,
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

    // del.php 経由の削除
    fun deleteViaDelPhp(resNum: String, reason: String = "110") {
        viewModelScope.launch {
            try {
                val url = currentUrl ?: return@launch
                _isLoading.postValue(true)

                // 事前に参照スレをGETしてCookie類を確実に用意
                withContext(Dispatchers.IO) { networkClient.fetchDocument(url) }

                val ok = withContext(Dispatchers.IO) {
                    networkClient.deleteViaDelPhp(
                        threadUrl = url,
                        targetResNum = resNum,
                        reason = reason,
                    )
                }

                if (ok) {
                    // 成功したら最新状態を取得
                    fetchDetails(url, forceRefresh = true)
                } else {
                    _error.postValue("del の実行に失敗しました。権限やCookieを確認してください。")
                }
            } catch (e: Exception) {
                _error.postValue("del 実行中にエラーが発生しました: ${e.message}")
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

    // ===== NG filtering =====

    fun reapplyNgFilter() {
        applyNgAndPost()
    }

    private fun applyNgAndPost() {
        ngStore.cleanup()
        val rules = ngStore.getRules()
        if (rules.isEmpty()) {
            _detailContent.postValue(rawContent)
            return
        }
        val filtered = filterByNgRules(rawContent, rules)
        _detailContent.postValue(filtered)
        // 生データはキャッシュへ保存 + アーカイブスナップショットも保存（オフライン復元用）
        currentUrl?.let { url ->
            viewModelScope.launch(Dispatchers.IO) {
                cacheManager.saveDetails(url, rawContent)
                cacheManager.saveArchiveSnapshot(url, rawContent)
            }
        }
    }

    private fun filterByNgRules(src: List<DetailContent>, rules: List<NgRule>): List<DetailContent> {
        if (src.isEmpty()) return src
        val out = ArrayList<DetailContent>(src.size)
        var skipping = false
        for (item in src) {
            when (item) {
                is DetailContent.Text -> {
                    val id = extractIdFromHtml(item.htmlContent)
                    val body = extractPlainBody(item.htmlContent)
                    val isNg = rules.any { r ->
                        when (r.type) {
                            RuleType.ID -> {
                                if (id.isNullOrBlank()) false else match(id, r.pattern, r.match ?: MatchType.EXACT, ignoreCase = true)
                            }
                            RuleType.BODY -> match(body, r.pattern, r.match ?: MatchType.SUBSTRING, ignoreCase = true)
                            RuleType.TITLE -> false // タイトルNGはMainActivity側で適用
                        }
                    }
                    if (isNg) {
                        skipping = true
                        continue
                    } else {
                        skipping = false
                        out += item
                    }
                }
                is DetailContent.Image, is DetailContent.Video -> {
                    if (!skipping) out += item
                }
                is DetailContent.ThreadEndTime -> out += item
            }
        }
        return out
    }

    private fun match(target: String, pattern: String, type: MatchType, ignoreCase: Boolean): Boolean {
        return when (type) {
            MatchType.EXACT -> target.equals(pattern, ignoreCase)
            MatchType.PREFIX -> target.startsWith(pattern, ignoreCase)
            MatchType.SUBSTRING -> target.contains(pattern, ignoreCase)
            MatchType.REGEX -> runCatching { Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(target) }.getOrElse { false }
        }
    }

    private fun extractIdFromHtml(html: String): String? {
        // 0) まず HTML 上で抽出（タグ境界で確実に切れる）
        run {
            val htmlNorm = java.text.Normalizer.normalize(
                html
                    .replace("\u200B", "")
                    .replace('　', ' ')
                    .replace('：', ':')
                , java.text.Normalizer.Form.NFKC
            )
            val htmlRegex = Regex("""(?i)\bID\s*:\s*([^\s<)]+)""")
            val hm = htmlRegex.find(htmlNorm)
            hm?.groupValues?.getOrNull(1)?.trim()?.let { return it }
        }

        // 1) HTMLから生成したプレーンテキスト側（タグが落ちることで No. が隣接するケースに対処）
        val plain = android.text.Html
            .fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT)
            .toString()
        val normalized = java.text.Normalizer.normalize(
            plain
                .replace("\u200B", "")
                .replace('　', ' ')
                .replace('：', ':')
            , java.text.Normalizer.Form.NFKC
        )
        // No. が直後に続く場合に備えて、No. 直前で打ち切る先読み
        val plainRegex = Regex("""\b[Ii][Dd]\s*:\s*([A-Za-z0-9+/_\.-]+)(?=\s|\(|$|No\.)""")
        val pm = plainRegex.find(normalized)
        return pm?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractPlainBody(html: String): String {
        val plain = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
        val dateRegex = Regex("""\d{2}/\d{2}/\d{2}\([^)]+\)\d{2}:\d{2}:\d{2}""")
        val fileExtRegex = Regex("""\.(?:jpg|jpeg|png|gif|webp|bmp|svg|webm|mp4|mov|mkv|avi|wmv|flv)\b""", RegexOption.IGNORE_CASE)
        val sizeSuffixRegex = Regex("""[ \t]*[\\-ー−―–—]?\s*\(\s*\d+(?:\.\d+)?\s*(?:[kKmMgGtT]?[bB])\s*\)""")
        val headLabelRegex = Regex("""^(?:画像|動画|ファイル名|ファイル|添付|サムネ|サムネイル)(?:\s*ファイル名)?\s*[:：]""", RegexOption.IGNORE_CASE)

        fun isLabeledSizeOnlyLine(t: String): Boolean {
            return headLabelRegex.containsMatchIn(t) && sizeSuffixRegex.containsMatchIn(t)
        }

        return plain
            .lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("ID:") || t.startsWith("No.") || dateRegex.containsMatchIn(t) || t.contains("Name")
            }
            .filterNot { line ->
                val t = line.trim()
                headLabelRegex.containsMatchIn(t) ||
                        (fileExtRegex.containsMatchIn(t) && sizeSuffixRegex.containsMatchIn(t)) ||
                        isLabeledSizeOnlyLine(t) ||
                        (fileExtRegex.containsMatchIn(t) && t.contains("サムネ"))
            }
            .joinToString("\n")
            .trimEnd()
    }
}
