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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import com.valoser.futaburakari.ui.detail.SearchState

/**
 * スレ詳細用の ViewModel。
 *
 * 機能概要:
 * - HTML を `DetailContent` 列（Text / Image / Video / ThreadEndTime）へパースし、NG適用後を `detailContent` に公開。
 * - キャッシュ戦略: 生データはディスクへ保存、表示はNG適用後。スナップショット（アーカイブ用）も併用。
 * - プロンプト永続化: 画像メタデータの段階抽出→反映ごとにキャッシュ/スナップショットを更新。
 *   - 表示状態（NG適用＋取得済みプロンプト）でスナップショットを保存し、dat落ち/オフライン復元性を向上。
 *   - `file://` のローカル画像には EXIF(UserComment) にも書き戻し（上書き）して再抽出の成功率を高める。
 * - 再取得時の揺れ対策: 既存表示のプロンプトを新リストへマージしてから表示更新（空で潰さない）。
 * - フォールバック: キャッシュ/スナップショット/アーカイブ再構成の各経路でも再抽出を走らせ、段階反映。
 * - 履歴更新: サムネイル/既読序数の更新、そうだね投稿、削除、検索状態の管理。
 * - レス番号抽出: OP はURL末尾、返信は本文HTML内の「No」表記から抽出（ドット有無/全角・空白/改行の差異に頑健）。
 *   これにより ID がない投稿でも No が安定して解決され、UI 側の「そうだね」判定・送信に利用できる。
 *   なお UI 側（DetailList）では表示テキストの正規化により、`ID:` と `No` の隣接や日付直後の `No` 隣接へ空白補正を行い、
 *   可読性とクリック検出の安定化を図っている（ViewModel は生HTMLを保持し、検出はHTML/プレーン双方から頑健に行う）。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val networkClient: NetworkClient,
) : ViewModel() {

    /** NG適用後の表示用リストを流すフロー（UI購読用）。 */
    private val _detailContent = MutableStateFlow<List<DetailContent>>(emptyList())
    val detailContent: StateFlow<List<DetailContent>> = _detailContent.asStateFlow()

    /** 画面に表示するエラーメッセージ。null はエラーなし。 */
    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    /** 通信・更新の進行中を表すフロー。 */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // そうだねの更新通知用（resNum -> サーバ応答カウント）。UI側ではこれを受け取り表示を楽観上書き。
    private val _sodaneUpdate = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 1)
    val sodaneUpdate = _sodaneUpdate.asSharedFlow()

    // 「そうだね」送信（UIからはレス番号のみ渡す）。
    // 参照（Referer）は現在のスレURL（currentUrl）を使用し、成功時は更新通知でUIを反映。
    // resNum は parse 時に DetailContent.Text.resNum として保持しており、UI 側での行内パースが難しい場合のフォールバックに利用可能。

    private val cacheManager = DetailCacheManager(appContext)
    private var currentUrl: String? = null
    // NG フィルタ適用前の生コンテンツを保持
    private var rawContent: List<DetailContent> = emptyList()
    private val ngStore by lazy { NgStore(appContext) }

    // ---- Search state (single source of truth) ----
    private var currentSearchQuery: String? = null
    private val _currentQueryFlow = MutableStateFlow<String?>(null)
    val currentQuery: StateFlow<String?> = _currentQueryFlow.asStateFlow()
    private val searchResultPositions = mutableListOf<Int>()
    private var currentSearchHitIndex = -1
    private val _searchState = MutableStateFlow(SearchState(active = false, currentIndexDisplay = 0, total = 0))
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    // そうだねの状態を保持するマップ (resNum -> そうだねが押されたかどうか)
    private val sodaNeStates = mutableMapOf<String, Boolean>()

    // メタデータ抽出の並列数を制限
    private val limitedIO = Dispatchers.IO.limitedParallelism(3)

    /**
     * 詳細を取得して表示を更新。
     *
     * ポリシー:
     * - まずキャッシュ/スナップショットがあれば即時表示（NG適用後）。その後、画像プロンプトを再抽出して段階反映。
     * - ネット再取得時は既存表示のプロンプトを新規リストへマージし、空で潰さないようにしてから表示更新。
     * - 例外時はキャッシュ → スナップショット → アーカイブ再構成の順で復元し、いずれの経路でも再抽出を走らせる。
     * - `forceRefresh=true` の場合は常に再取得。
     */
    fun fetchDetails(url: String, forceRefresh: Boolean = false) {
        Log.d("DetailViewModel", "fetchDetails: Called with forceRefresh: $forceRefresh for URL: $url")
        viewModelScope.launch {
            // スレ移動時に「そうだね」状態を正しくリセットするため、
            // 代入前のURLと比較してページ遷移を判定する。
            val isNewPage = this@DetailViewModel.currentUrl != url
            if (forceRefresh || isNewPage) {
                resetSodaNeStates()
            }
            this@DetailViewModel.currentUrl = url
            _isLoading.value = true
            _error.value = null
            var itemIdCounter = 0L


            try {
                if (!forceRefresh) {
                    val cachedDetails = withContext(Dispatchers.IO) { cacheManager.loadDetails(url) }
                    if (cachedDetails != null) {
                        rawContent = cachedDetails
                        applyNgAndPost()
                        // 即時表示後に、キャッシュ由来でも不足メタデータ（画像プロンプト等）を並行取得して段階反映
                        updateMetadataInBackground(cachedDetails, url)
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

                // 既存表示のプロンプト等を引き継ぐ（ネット更新で一時的に消えないように）
                val prior = _detailContent.value
                val merged = if (prior.isEmpty()) progressivelyLoadedContent else mergePrompts(progressivelyLoadedContent, prior)

                // キャッシュは生データを保存し、表示はNG適用後
                rawContent = merged
                applyNgAndPost()
                _isLoading.value = false

                // バックグラウンドでメタデータを取得し、完了後に段階反映
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
                    // キャッシュ由来でも画像プロンプト抽出を再試行（オフライン時の復元用）
                    updateMetadataInBackground(cached, url)
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
                    // キャッシュも無い → アーカイブスナップショット or 媒体のみで再構成
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
                        // スナップショット/再構成からも画像プロンプト抽出を実施（file:// を対象）
                        updateMetadataInBackground(reconstructed, url)
                        _error.value = null
                    } else {
                        _error.value = "詳細の取得に失敗しました: ${e.message}"
                    }
                }
                _isLoading.value = false
            }
        }
    }

    /** スレッドの差分更新をチェックし、新規アイテムがあれば追加して反映する。*/
    fun checkForUpdates(url: String, currentItemCount: Int, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                // 現在のHTMLを取得
                val document = withContext(Dispatchers.IO) {
                    networkClient.fetchDocument(url)
                }

                // 新しいコンテンツをパース
                val newContentList = parseContentFromDocument(document, url)

                // 現在の表示（生データ）のID集合を作成し、差分のみ抽出
                val currentIds = rawContent.map { it.id }.toSet()
                val newItems = newContentList.filter { it.id !in currentIds }

                if (newItems.isNotEmpty()) {
                    // 生データを更新してキャッシュ保存、表示はNG適用後
                    rawContent = rawContent + newItems
                    withContext(Dispatchers.IO) { cacheManager.saveDetails(url, rawContent) }
                    applyNgAndPost()
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

    /** HTMLドキュメントから `DetailContent` の一覧を構築する。OPと返信を順に処理。 */
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
                // レス番号の抽出: OP はURL末尾、返信は HTML 内の "No."（改行/空白やドットの有無に頑健）から取得
                val resNum = if (isOp) {
                    url.substringAfterLast('/').substringBefore(".htm")
                } else {
                    // Futaba系の "No."（または一部で「No」）に続く数値を安定抽出（改行や余分な空白を許容）
                    Regex("""No\.?\s*(\n?\s*)?(\d+)""").find(html)?.groupValues?.getOrNull(2)
                        ?: Regex("""No\.?\s*(\d+)""").find(html)?.groupValues?.getOrNull(1)
                }
                progressivelyLoadedContent.add(
                    DetailContent.Text(id = "text_${itemIdCounter++}", htmlContent = html, resNum = resNum)
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
     * 画像メタデータ（主にプロンプト/説明）をバックグラウンドで抽出し、250ms間隔でバッチ適用する。
     *
     * 挙動:
     * - 画像ごとに `MetadataExtractor.extract` を実行（HTTP/ローカル file:// 対応）。
     * - 反映時にキャッシュ/スナップショットへ都度保存。
     * - `file://` の場合、EXIF(UserComment) にも書き戻し（上書き）し、後続の再抽出を安定化。
     * - 動画は対象外。
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
                            /*  画像プロンプトの取得タイムアウト時間はここを変更  */
                            withTimeoutOrNull(60000L) { MetadataExtractor.extract(appContext, content.imageUrl, networkClient) }
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
                    // 動画のプロンプト取得は行わない
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

        // 受信・段階反映（250ms間隔でバッチ適用）
        viewModelScope.launch(Dispatchers.Default) {
            val batch = mutableMapOf<String, String?>()
            var lastFlush = System.currentTimeMillis()
            val flushIntervalMs = 250L

            suspend fun flush(force: Boolean = false) {
                val now = System.currentTimeMillis()
                val due = (now - lastFlush) >= flushIntervalMs
                if (batch.isNotEmpty() && (force || due)) {
                    val current = _detailContent.value.toMutableList()
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
                        val snapshot = current.toList()
                        _detailContent.value = snapshot
                        withContext(Dispatchers.IO) {
                            cacheManager.saveDetails(url, snapshot)
                            // 抽出完了ごとにアーカイブスナップショットも更新（オフライン時の即時反映用）
                            cacheManager.saveArchiveSnapshot(url, snapshot)
                            // 取得済みプロンプトをローカルのアーカイブ画像へも書き戻し（上書き許容）
                            runCatching {
                                batch.forEach { (id, p) ->
                                    val prompt = p ?: return@forEach
                                    val idx = snapshot.indexOfFirst { it.id == id }
                                    if (idx < 0) return@forEach
                                    when (val it = snapshot[idx]) {
                                        is DetailContent.Image -> {
                                            val u = it.imageUrl
                                            if (u.startsWith("file:")) {
                                                val path = android.net.Uri.parse(u).path
                                                if (!path.isNullOrBlank()) {
                                                    try {
                                                        val exif = androidx.exifinterface.media.ExifInterface(path)
                                                        exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_USER_COMMENT, prompt)
                                                        exif.saveAttributes()
                                                    } catch (_: Exception) {
                                                        // ignore write failure per-file
                                                    }
                                                }
                                            }
                                        }
                                        is DetailContent.Video -> { /* not supported */ }
                                        else -> {}
                                    }
                                }
                            }
                        }
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

    /**
     * 指定レス番号に「そうだね」を送信し、成功時は (resNum -> count) をUIへ通知。
     * UI 側ではこの通知を受けて楽観表示（＋/そうだね → そうだねxN）を行い、
     * 行内に No が見つからない場合も自投稿番号(selfResNum)でフォールバックして置換する。
     */
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

    /**
     * 指定レス番号の「そうだね」押下状態を返す。
     * 重複送信の抑止など、UI 側の制御に用いるフラグ。
     */
    fun getSodaNeState(resNum: String): Boolean {
        return sodaNeStates[resNum] ?: false
    }

    /**
     * 現在保持している「そうだね」押下状態を全てクリアする。
     * ページ遷移や強制更新時に呼び出し、状態の持ち越しを防ぐ。
     */
    fun resetSodaNeStates() {
        sodaNeStates.clear()
    }

    /**
     * 通常の削除（画像のみ/本文含む）を実行する。
     * 成功時はスレッドを強制再取得して表示を最新化する。
     */
    fun deletePost(postUrl: String, referer: String, resNum: String, pwd: String, onlyImage: Boolean) {
        viewModelScope.launch {
            try {
                _isLoading.value = true

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
                _isLoading.value = false
            }
        }
    }

    /**
     * del.php 経由の削除を実行する。
     * 成功時はスレッドを強制再取得して表示を最新化する。
     */
    fun deleteViaDelPhp(resNum: String, reason: String = "110") {
        viewModelScope.launch {
            try {
                val url = currentUrl ?: return@launch
                _isLoading.value = true

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
                _isLoading.value = false
            }
        }
    }

    // ===== Helpers & Regex =====

    /**
     * メディア（画像/動画）ファイル拡張子を持つかを簡易判定する。
     * 解析対象の `<a href>` の抽出フィルタとして使用。
     */
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

    /** 現在のNGルールでフィルタを再適用し、表示と検索状態を更新する。 */
    fun reapplyNgFilter() {
        applyNgAndPost()
    }

    /**
     * NGルールを適用した結果を `detailContent` に反映し、検索状態も更新する。
     * 併せて生データのキャッシュ保存と、表示状態のアーカイブスナップショット保存を行う。
     */
    private fun applyNgAndPost() {
        ngStore.cleanup()
        val rules = ngStore.getRules()
        if (rules.isEmpty()) {
            _detailContent.value = rawContent
            recomputeSearchState()
            return
        }
        val filtered = filterByNgRules(rawContent, rules)
        _detailContent.value = filtered
        recomputeSearchState()
        // 生データはキャッシュへ保存 + アーカイブスナップショットも保存（オフライン復元用）
        currentUrl?.let { url ->
            viewModelScope.launch(Dispatchers.IO) {
                cacheManager.saveDetails(url, rawContent)
                // スナップショットは表示状態（フィルタ適用済み・既存プロンプトを含む）で保存
                cacheManager.saveArchiveSnapshot(url, _detailContent.value)
            }
        }
    }

    /**
     * 既存表示（prior）に含まれるプロンプト等を新規取得（base）へ引き継ぐ。
     * - Image/Video で prompt が空の要素のみ対象。
     * - 照合キーは `fileName` 優先、無い場合は URL 末尾（ファイル名相当）。
     */
    private fun mergePrompts(base: List<DetailContent>, prior: List<DetailContent>): List<DetailContent> {
        if (base.isEmpty() || prior.isEmpty()) return base
        fun keyForImage(url: String?, fileName: String?): String? =
            fileName?.takeIf { it.isNotBlank() } ?: url?.substringAfterLast('/')

        val promptByKey: Map<String, String> = buildMap {
            prior.forEach { dc ->
                when (dc) {
                    is DetailContent.Image -> {
                        val k = keyForImage(dc.imageUrl, dc.fileName)
                        val p = dc.prompt
                        if (!k.isNullOrBlank() && !p.isNullOrBlank()) put(k, p)
                    }
                    is DetailContent.Video -> {
                        val k = keyForImage(dc.videoUrl, dc.fileName)
                        val p = dc.prompt
                        if (!k.isNullOrBlank() && !p.isNullOrBlank()) put(k, p)
                    }
                    else -> {}
                }
            }
        }

        if (promptByKey.isEmpty()) return base

        return base.map { dc ->
            when (dc) {
                is DetailContent.Image -> {
                    if (!dc.prompt.isNullOrBlank()) dc else {
                        val k = keyForImage(dc.imageUrl, dc.fileName)
                        val p = if (!k.isNullOrBlank()) promptByKey[k] else null
                        if (!p.isNullOrBlank()) dc.copy(prompt = p) else dc
                    }
                }
                is DetailContent.Video -> {
                    if (!dc.prompt.isNullOrBlank()) dc else {
                        val k = keyForImage(dc.videoUrl, dc.fileName)
                        val p = if (!k.isNullOrBlank()) promptByKey[k] else null
                        if (!p.isNullOrBlank()) dc.copy(prompt = p) else dc
                    }
                }
                else -> dc
            }
        }
    }

    /** NGルールに基づきテキストと直後のメディア列を間引いた一覧を返す。 */
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

    /** 指定のマッチ種別で文字列照合するユーティリティ。 */
    private fun match(target: String, pattern: String, type: MatchType, ignoreCase: Boolean): Boolean {
        return when (type) {
            MatchType.EXACT -> target.equals(pattern, ignoreCase)
            MatchType.PREFIX -> target.startsWith(pattern, ignoreCase)
            MatchType.SUBSTRING -> target.contains(pattern, ignoreCase)
            MatchType.REGEX -> runCatching { Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(target) }.getOrElse { false }
        }
    }

    /** HTMLから ID: xxx を抽出。タグ境界とテキスト両方を考慮して安定化。 */
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

    /** 検索用のプレーン本文を生成（付帯情報やファイル行を除去）。 */
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

    // ===== Search: public APIs and internals =====
    /** 検索を開始し、最初のヒット位置に移動できるよう状態を更新。 */
    fun performSearch(query: String) {
        currentSearchQuery = query
        _currentQueryFlow.value = query
        searchResultPositions.clear()
        currentSearchHitIndex = -1
        recomputeSearchState()
        if (searchResultPositions.isNotEmpty()) {
            currentSearchHitIndex = 0
            publishSearchState()
        }
    }

    /** 検索状態をクリア。 */
    fun clearSearch() {
        val wasActive = currentSearchQuery != null
        currentSearchQuery = null
        _currentQueryFlow.value = null
        searchResultPositions.clear()
        currentSearchHitIndex = -1
        publishSearchState()
        if (wasActive) {
            // no-op placeholder for legacy callbacks
        }
    }

    /** 検索ヒットの前の項目へ循環移動。 */
    fun navigateToPrevHit() {
        if (searchResultPositions.isEmpty()) return
        currentSearchHitIndex--
        if (currentSearchHitIndex < 0) currentSearchHitIndex = searchResultPositions.size - 1
        publishSearchState()
    }

    /** 検索ヒットの次の項目へ循環移動。 */
    fun navigateToNextHit() {
        if (searchResultPositions.isEmpty()) return
        currentSearchHitIndex++
        if (currentSearchHitIndex >= searchResultPositions.size) currentSearchHitIndex = 0
        publishSearchState()
    }

    /** 現在の表示リストから検索ヒット位置を再計算して公開。 */
    private fun recomputeSearchState() {
        searchResultPositions.clear()
        val q = currentSearchQuery?.trim().orEmpty()
        if (q.isBlank()) {
            publishSearchState()
            return
        }
        val contentList = _detailContent.value
        contentList.forEachIndexed { index, content ->
            val textToSearch: String? = when (content) {
                is DetailContent.Text -> android.text.Html.fromHtml(content.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
                is DetailContent.Image -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.imageUrl.substringAfterLast('/')}"
                is DetailContent.Video -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.videoUrl.substringAfterLast('/')}"
                is DetailContent.ThreadEndTime -> null
            }
            if (textToSearch?.contains(q, ignoreCase = true) == true) {
                searchResultPositions.add(index)
            }
        }
        publishSearchState()
    }

    /** 検索UI表示用の集計（アクティブ/現在位置/総数）をフローに反映。 */
    private fun publishSearchState() {
        val active = (currentSearchQuery != null) && searchResultPositions.isNotEmpty()
        val currentDisp = if (active && currentSearchHitIndex in searchResultPositions.indices) currentSearchHitIndex + 1 else 0
        val total = searchResultPositions.size
        _searchState.value = SearchState(active = active, currentIndexDisplay = currentDisp, total = total)
    }
}
