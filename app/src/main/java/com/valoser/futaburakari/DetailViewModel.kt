package com.valoser.futaburakari

import android.content.Context
import android.util.Log
import androidx.core.text.HtmlCompat
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.net.MalformedURLException
import java.io.IOException
import java.net.URL
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.valoser.futaburakari.worker.ThreadMonitorWorker
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import com.valoser.futaburakari.ui.detail.SearchState
import androidx.collection.LruCache
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ダウンロード進捗を表すデータクラス
 */
data class DownloadProgress(
    val current: Int,
    val total: Int,
    val currentFileName: String? = null,
    val isActive: Boolean = true
) {
    val percentage: Int get() = if (total > 0) (current * 100 / total) else 0
}

data class DownloadConflictFile(
    val url: String,
    val fileName: String
)

data class DownloadConflictRequest(
    val requestId: Long,
    val totalCount: Int,
    val newCount: Int,
    val existingFiles: List<DownloadConflictFile>
)

/**
 * スレ詳細表示に関する状態と非同期処理を束ねる ViewModel。
 *
 * 機能概要:
 * - HTML を `DetailContent` 列（Text / Image / Video / ThreadEndTime）へパースし、NG 適用後のリストを `detailContent` で公開。
 * - キャッシュ戦略: `DetailCacheManager` と連携して生データをディスクへ保存しつつ、表示用は NG 適用済みを保持。スナップショットやアーカイブ再構成も活用。
 * - プロンプト永続化: 画像メタデータを段階的に抽出してキャッシュ/スナップショットへ反映し、EventStore 経由で UI へ伝搬する。
 * - 差分マージ: 再取得やバックグラウンド更新時に既存のプロンプトやレス状態をマージし、空値での上書きを避ける。
 * - 履歴・フォールバック: キャッシュ→スナップショット→アーカイブ復元の順でフォールバック読込を行い、404 検出時は履歴をアーカイブ扱いにして監視を停止する。
 * - レス番号抽出と「そうだね」: OP/返信双方のレス番号を正規化し、送信・取得結果をフローで UI に反映。
 * - 画像一括ダウンロード: 既存ファイル検出、競合解決ダイアログ、進捗共有を備えたダウンロードロジックを提供。
 * - メモリ保護: 利用状況に応じて NG フィルタキャッシュの縮小や Coil キャッシュのクリーンアップを行い、検索状態も Compose へ共有する。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val networkClient: NetworkClient,
    private val metadataCache: MetadataCache,
    private val cacheManager: DetailCacheManager,
) : ViewModel() {

    // Event-Sourcing アーキテクチャの中央イベントストア
    private val eventStore = DetailEventStore()
    val detailScreenState: StateFlow<DetailScreenState> = eventStore.currentState

    // 後方互換性のための表示用フロー（新アーキテクチャから算出）
    val detailContent: StateFlow<List<DetailContent>> = detailScreenState
        .map { state -> state.computeDisplayContent() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _displayContent = MutableStateFlow<List<DetailContent>>(emptyList())
    val displayContent: StateFlow<List<DetailContent>> = _displayContent.asStateFlow()

    // TTS音声読み上げマネージャー
    private val ttsManager = TtsManager(appContext)
    val ttsState: StateFlow<TtsManager.TtsState> = ttsManager.state
    val ttsCurrentResNum: StateFlow<String?> = ttsManager.currentResNum

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    val isLoading: StateFlow<Boolean> = detailScreenState
        .map { state -> state.uiState.isLoading }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** ダウンロード進捗を表すフロー。 */
    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _downloadConflictRequests = MutableSharedFlow<DownloadConflictRequest>(extraBufferCapacity = 1)
    val downloadConflictRequests = _downloadConflictRequests.asSharedFlow()

    private val _promptLoadingIds = MutableStateFlow<Set<String>>(emptySet())
    val promptLoadingIds: StateFlow<Set<String>> = _promptLoadingIds.asStateFlow()

    /** スレッドアーカイブ進捗を表すフロー */
    private val _archiveProgress = MutableStateFlow<ThreadArchiveProgress?>(null)
    val archiveProgress: StateFlow<ThreadArchiveProgress?> = _archiveProgress.asStateFlow()

    /** スレッドアーカイバー */
    private val threadArchiver by lazy { ThreadArchiver(appContext, networkClient) }

    /** ダウンロード/アーカイブのキャンセル用Job */
    private var downloadJob: kotlinx.coroutines.Job? = null
    private var archiveJob: kotlinx.coroutines.Job? = null

    private val downloadRequestIdGenerator = AtomicLong(0)
    private val pendingDownloadMutex = Mutex()
    private val pendingDownloadRequests = mutableMapOf<Long, PendingDownloadRequest>()

    // ダウンロード進捗の更新専用ロック（ViewModelインスタンス全体のロックを避ける）
    private val downloadProgressMutex = Mutex()

    // そうだねの更新通知用（resNum -> サーバ応答カウント）。UI側ではこれを受け取り表示を楽観上書き。
    private val _sodaneUpdate = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 1)
    val sodaneUpdate = _sodaneUpdate.asSharedFlow()

    // 「そうだね」送信（UIからはレス番号のみ渡す）。
    // 参照（Referer）は現在のスレURL（currentUrl）を使用し、成功時は更新通知でUIを反映。
    // resNum は parse 時に DetailContent.Text.resNum として保持しており、UI 側での行内パースが難しい場合のフォールバックに利用可能。

    private var currentUrl: String? = null
    // NG フィルタ適用前の生コンテンツを保持
    private var rawContent: List<DetailContent> = emptyList()
    private val rawContentMutex = Mutex()
    private suspend fun setRawContent(list: List<DetailContent>) {
        rawContentMutex.withLock {
            rawContent = list
        }
    }

    private suspend inline fun updateRawContent(transform: (List<DetailContent>) -> List<DetailContent>): List<DetailContent> {
        rawContentMutex.withLock {
            val updated = transform(rawContent)
            rawContent = updated
            return updated
        }
    }
    private val ngStore by lazy { NgStore(appContext) }

    // NGフィルタ結果のキャッシュ（動的サイズ調整）
    private val ngFilterCache = LruCache<Pair<List<DetailContent>, List<NgRule>>, List<DetailContent>>(
        calculateOptimalCacheSize()
    )

    // 適応的メモリ監視
    private var lastMemoryCheck = 0L
    private var memoryCheckIntervalMs = 30000L // 初期値30秒、使用率に応じて調整
    private var consecutiveHighMemoryCount = 0

    // データ整合性管理用のアトミックカウンタ
    private val contentUpdateCounter = AtomicLong(0)
    private val metadataUpdateCounter = AtomicInteger(0)

    init {
        viewModelScope.launch {
            detailScreenState.collect { state ->
                val errorMessage = state.uiState.error
                if (_error.value != errorMessage) {
                    _error.value = errorMessage
                }
            }
        }
    }

    /** デバイスメモリに基づいた最適なキャッシュサイズを計算 */
    private fun calculateOptimalCacheSize(): Int {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        // 最大メモリの1%をキャッシュに割り当て、最小20、最大200
        return ((maxMemory / 1024 / 1024 / 100).toInt()).coerceIn(20, 200)
    }

    /** NGフィルタ結果キャッシュをクリアする（NGルール変更やメモリ圧などのトリガーで使用）。 */
    private fun clearNgFilterCache() {
        ngFilterCache.evictAll()
    }

    /**
     * 適応的メモリ使用量監視の改善
     * - メモリ使用率に応じて監視間隔を動的調整
     * - 高負荷時はキャッシュサイズも縮小
     * - 段階的なクリーンアップ処理
     */
    private fun checkMemoryUsage() {
        val now = System.currentTimeMillis()
        if (now - lastMemoryCheck < memoryCheckIntervalMs) return
        lastMemoryCheck = now

        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsageRatio = usedMemory.toFloat() / maxMemory.toFloat()

        val memoryUsagePercent = (memoryUsageRatio * 100).toInt()
        Log.d("DetailViewModel", "Memory usage: $memoryUsagePercent% (${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB)")

        // 段階的メモリ管理（統合版）
        when {
            memoryUsageRatio > 0.90f -> {
                // 極度の高負荷：即座にアグレッシブクリーンアップ
                Log.w("DetailViewModel", "Extreme memory usage ($memoryUsagePercent%), performing aggressive cleanup")
                consecutiveHighMemoryCount++

                clearNgFilterCache()
                _plainTextCache.value = emptyMap()
                MyApplication.clearCoilImageCache(appContext)
                memoryCheckIntervalMs = 5000L

                // カウントが際限なく増加しないよう上限でリセット
                if (consecutiveHighMemoryCount >= 3) {
                    Log.d("DetailViewModel", "Resetting high memory counter after aggressive cleanup")
                    consecutiveHighMemoryCount = 0
                }
            }
            memoryUsageRatio > 0.85f -> {
                // 高負荷：アグレッシブクリーンアップ（GCは実行しない）
                Log.w("DetailViewModel", "Critical memory usage ($memoryUsagePercent%), performing aggressive cleanup")
                consecutiveHighMemoryCount++

                clearNgFilterCache()
                _plainTextCache.value = emptyMap()
                MyApplication.clearCoilImageCache(appContext)
                memoryCheckIntervalMs = 10000L

                // カウントが際限なく増加しないよう上限でリセット
                if (consecutiveHighMemoryCount >= 5) {
                    Log.d("DetailViewModel", "Resetting high memory counter to prevent overflow")
                    consecutiveHighMemoryCount = 0
                }
            }
            memoryUsageRatio > 0.75f -> {
                // 中高負荷：選択的クリーンアップ
                Log.w("DetailViewModel", "High memory usage ($memoryUsagePercent%), performing selective cleanup")
                consecutiveHighMemoryCount++

                clearNgFilterCache()
                val currentPlainCache = _plainTextCache.value
                if (currentPlainCache.size > 20) {
                    val reducedCache = currentPlainCache.toList().takeLast(10).toMap()
                    _plainTextCache.value = reducedCache
                }
                MyApplication.clearCoilImageCache(appContext)
                memoryCheckIntervalMs = 15000L

                // カウントが際限なく増加しないよう上限でリセット
                if (consecutiveHighMemoryCount >= 5) {
                    consecutiveHighMemoryCount = 0
                }
            }
            memoryUsageRatio > 0.70f -> {
                // 中程度の負荷：軽度のクリーンアップ
                consecutiveHighMemoryCount++
                memoryCheckIntervalMs = 20000L
                if (consecutiveHighMemoryCount >= 3) {
                    Log.w("DetailViewModel", "Sustained memory pressure, clearing image cache")
                    MyApplication.clearCoilImageCache(appContext)
                    clearNgFilterCache()
                    consecutiveHighMemoryCount = 0
                }
            }
            memoryUsageRatio > 0.60f -> {
                memoryCheckIntervalMs = 25000L
                consecutiveHighMemoryCount = 0
            }
            else -> {
                memoryCheckIntervalMs = 30000L
                consecutiveHighMemoryCount = 0
            }
        }
    }

    /**
     * メモリ使用量を即時に測定し、デバッグ表示用の概要文字列を返す。
     */
    fun forceMemoryCheck(): String {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryUsageRatio = usedMemory.toFloat() / maxMemory.toFloat()

        val coilInfo = MyApplication.getCoilCacheInfo(appContext)

        return "Memory: ${(memoryUsageRatio * 100).toInt()}% (${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB)\nCoil: $coilInfo"
    }

    // ---- Search state (single source of truth) ----
    private var currentSearchQuery: String? = null
    private val _currentQueryFlow = MutableStateFlow<String?>(null)
    val currentQuery: StateFlow<String?> = _currentQueryFlow.asStateFlow()
    private val searchResultPositions = mutableListOf<Int>()
    private var currentSearchHitIndex = -1
    private val _searchState = MutableStateFlow(SearchState(active = false, currentIndexDisplay = 0, total = 0))
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    // そうだねの状態を保持するマップ (resNum -> そうだねが押されたかどうか)
    // メモリリーク防止のため、最大サイズを制限
    // synchronized ブロックでスレッドセーフを保証
    private val sodaNeStates = object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > 300 // 最大300件まで保持（メモリ使用量削減）
        }
    }

    // メタデータ抽出の並列数は実行時にユーザー設定を参照

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
        contentUpdateCounter.incrementAndGet()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                fetchDetailsWithEventStore(url, forceRefresh)
            } catch (e: Exception) {
                Log.e("DetailViewModel", "Error in fetchDetails: ${e.message}", e)
                eventStore.setError("詳細の取得に失敗しました: ${e.message}")
            }
        }
    }

    private suspend fun fetchDetailsWithEventStore(url: String, forceRefresh: Boolean) {
        eventStore.applyEvent(DetailEvent.LoadingStateChanged(true))
        currentUrl = url

        try {
            if (!forceRefresh) {
                val cachedDetails = withContext(Dispatchers.IO) { cacheManager.loadDetails(url) }
                if (cachedDetails != null) {
                    Log.d("DetailViewModel", "Loading from cache with new architecture: ${cachedDetails.size} items")
                    val sanitized = setRawContentSanitized(cachedDetails)
                    val merged = mergeWithExistingPrompts(sanitized)
                    setRawContent(merged)
                    eventStore.loadFromOldContent(merged, url)
                    applyNgAndPostAsync()
                    updateMetadataWithEventStore(merged, url)
                    return
                }

                val archived = runCatching {
                    HistoryManager.getAll(appContext).any { it.url == url && it.isArchived }
                }.getOrDefault(false)

                if (archived) {
                    val snapshot = withContext(Dispatchers.IO) { cacheManager.loadArchiveSnapshot(url) }
                    if (!snapshot.isNullOrEmpty()) {
                        Log.d("DetailViewModel", "Loading from archive snapshot with new architecture: ${snapshot.size} items")
                        val sanitized = setRawContentSanitized(snapshot)
                        val merged = mergeWithExistingPrompts(sanitized)
                        setRawContent(merged)
                        eventStore.loadFromOldContent(merged, url)
                        applyNgAndPostAsync()
                        updateMetadataWithEventStore(merged, url)
                        return
                    }
                }
            }

            val document = withContext(Dispatchers.IO) {
                networkClient.fetchDocument(url).apply {
                    outputSettings().prettyPrint(false)
                }
            }

            val parsedContent = parseContentFromDocument(document, url)
            val sanitized = setRawContentSanitized(parsedContent)
            val merged = mergeWithExistingPrompts(sanitized)
            setRawContent(merged)

            eventStore.loadFromOldContent(merged, url)

            withContext(Dispatchers.IO) { cacheManager.saveDetails(url, merged) }

            applyNgAndPostAsync()
            updateMetadataWithEventStore(merged, url)

        } catch (e: Exception) {
            Log.e("DetailViewModel", "Error fetching details for $url", e)
            eventStore.setError("詳細の取得に失敗しました: ${e.message}")

            val cached = withContext(Dispatchers.IO) { cacheManager.loadDetails(url) }
            if (cached != null) {
                if (e is IOException && (e.message?.contains("404") == true)) {
                    try {
                        HistoryManager.markArchived(appContext, url, autoExpireIfStale = true)
                        ThreadMonitorWorker.cancelByUrl(appContext, url)
                    } catch (markError: Exception) {
                        Log.w("DetailViewModel", "Failed to mark archived for $url", markError)
                    }
                }
                val sanitized = setRawContentSanitized(cached)
                val merged = mergeWithExistingPrompts(sanitized)
                setRawContent(merged)
                eventStore.loadFromOldContent(merged, url)
                applyNgAndPostAsync()
                updateMetadataWithEventStore(merged, url)
                eventStore.applyEvent(DetailEvent.ErrorSet(null))
            } else {
                val reconstructed = withContext(Dispatchers.IO) {
                    cacheManager.reconstructFromArchive(url)
                }
                if (!reconstructed.isNullOrEmpty()) {
                    val sanitized = setRawContentSanitized(reconstructed)
                    val merged = mergeWithExistingPrompts(sanitized)
                    setRawContent(merged)
                    eventStore.loadFromOldContent(merged, url)
                    applyNgAndPostAsync()
                    updateMetadataWithEventStore(merged, url)
                    eventStore.applyEvent(DetailEvent.ErrorSet(null))
                }
            }
        } finally {
            eventStore.applyEvent(DetailEvent.LoadingStateChanged(false))
        }
    }

    /** スレッドの差分更新をチェックし、新規アイテムがあれば追加して反映する。*/
    fun checkForUpdates(url: String, currentItemCount: Int, callback: (Boolean) -> Unit) {
        viewModelScope.launch {
            try {
                // 現在のHTMLを取得
                val document = withContext(Dispatchers.IO) {
                    networkClient.fetchDocument(url).apply {
                        outputSettings().prettyPrint(false)
                    }
                }

                // 新しいコンテンツをパース
                val newContentList = parseContentFromDocument(document, url)

                // 現在の表示（生データ）のID集合と内容ハッシュ集合を作成し、重複チェックを強化
                val currentIds = rawContent.map { it.id }.toSet()
                val currentContentHashes = rawContent.map { contentHash(it) }.toSet()

                val newItems = newContentList.filter { item ->
                    val itemId = item.id
                    val itemContentHash = contentHash(item)

                    // ID一致もしくは内容ハッシュ一致で既存判定（重複を防ぐ）
                    itemId !in currentIds && itemContentHash !in currentContentHashes
                }

                // デバッグログ：IDの重複状況と内容重複を確認
                val duplicateIds = newContentList.map { it.id }.groupBy { it }.filter { it.value.size > 1 }
                val duplicateContent = newContentList.map { contentHash(it) }.groupBy { it }.filter { it.value.size > 1 }

                if (duplicateIds.isNotEmpty()) {
                    Log.w("DetailViewModel", "checkForUpdates: Duplicate IDs found: ${duplicateIds.keys}")
                }
                if (duplicateContent.isNotEmpty()) {
                    Log.w("DetailViewModel", "checkForUpdates: Duplicate content found: ${duplicateContent.size} groups")
                }

                Log.d("DetailViewModel", "checkForUpdates: Current items=${rawContent.size}, New parsed=${newContentList.size}, New items=${newItems.size}")

                if (newItems.isNotEmpty()) {
                    // 生データを更新してキャッシュ保存、表示はNG適用後
                    val sanitizedNewItems = sanitizePrompts(newItems)
                    val updatedRaw = updateRawContent { it + sanitizedNewItems }
                    withContext(Dispatchers.IO) { cacheManager.saveDetails(url, updatedRaw) }

                    // 新しいアーキテクチャへ反映
                    eventStore.loadFromOldContent(updatedRaw, url)
                    applyNgAndPostAsync()
                    updateMetadataWithEventStore(sanitizedNewItems, url)
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

    private suspend fun parseContentFromDocument(document: Document, url: String): List<DetailContent> =
        withContext(Dispatchers.Default) {
        val progressivelyLoadedContent = mutableListOf<DetailContent>()
        val threadId = url.substringAfterLast('/').substringBefore(
            ".htm",
            missingDelimiterValue = url.substringAfterLast('/')
        ).ifBlank {
            url.hashCode().toUInt().toString(16)
        }
        // URL末尾を基準にスレIDを決定し、欠損時はURLハッシュで安定化

        val threadContainer = document.selectFirst("div.thre")

        if (threadContainer == null) {
            _error.postValue("スレッドのコンテナが見つかりませんでした。")
            Log.e("DetailViewModel", "div.thre container not found in document for URL: $url")
            return@withContext emptyList<DetailContent>()
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
                // OPの場合、子要素の返信テーブルを除外して処理
                val originalHtml = block.html()
                // テーブルタグを除去（正規表現で効率的に処理）
                val withoutTables = originalHtml.replace(TABLE_REMOVAL_PATTERN, "")
                // 画像タグをaltテキストに置換
                html = withoutTables.replace(IMG_WITH_ALT_PATTERN) { match ->
                    val alt = match.groupValues[1].ifBlank { "img" }
                    "[$alt]"
                }.replace(IMG_PATTERN, "[img]")
            } else {
                // 返信の場合、.rtdセルからHTMLを取得
                val rtd = block.selectFirst(".rtd")
                if (rtd != null) {
                    val rawHtml = rtd.html()
                    // 画像タグをaltテキストに置換（正規表現で効率的に処理）
                    html = rawHtml.replace(IMG_WITH_ALT_PATTERN) { match ->
                        val alt = match.groupValues[1].ifBlank { "img" }
                        "[$alt]"
                    }.replace(IMG_PATTERN, "[img]")
                } else {
                    html = ""
                }
            }

            val resNum = if (isOp) {
                threadId
            } else {
                NO_PATTERN.find(html)?.groupValues?.getOrNull(2)
                    ?: NO_PATTERN_FALLBACK.find(html)?.groupValues?.getOrNull(1)
            }
            // OPはスレID、返信は本文内の No. からレス番号を抽出

            if (html.isNotBlank()) {
                // レス番号ベースの安定ID（機能互換性を保持）
                val stableId = if (isOp) {
                    "text_op_$threadId"
                } else {
                    "text_${resNum ?: "reply_${threadId}_${index}"}"
                }
                progressivelyLoadedContent.add(
                    DetailContent.Text(
                        id = stableId,
                        htmlContent = html,
                        resNum = resNum
                    )
                )
            }

            // --- 2. メディアコンテンツの解析 ---
            val mediaLinkNode = if (isOp) {
                // OPの場合、返信テーブル内の画像を除外してメディアリンクを検索
                // より厳密に：OPのコンテンツ内に直接含まれる画像のみを取得
                val cloned = block.clone()
                // 返信テーブル（td.rtd を含むtable）を完全に除去
                cloned.select("table:has(td.rtd)").remove()
                cloned.select("table").remove()  // 念のため他のテーブルも除去

                // OPのテキスト部分内で画像リンクを検索（より限定的に）
                val opMediaLinks = cloned.select("a[target=_blank][href]").filter { a ->
                    MEDIA_URL_PATTERN.containsMatchIn(a.attr("href"))
                }

                // OPに画像がない場合はnullを返す（次の画像を取得しない）
                if (opMediaLinks.isEmpty()) {
                    Log.d("DetailViewModel", "OP has no images - returning null")
                    null
                } else {
                    Log.d("DetailViewModel", "OP has ${opMediaLinks.size} image(s) - using first one")
                    opMediaLinks.firstOrNull()
                }
            } else {
                // 返信の場合、通常通り検索
                block.select("a[target=_blank][href]").firstOrNull { a ->
                    MEDIA_URL_PATTERN.containsMatchIn(a.attr("href"))
                }
            }

            // メディアコンテンツのID生成ではblockResNumを使わない（URL固定のため）

            if (mediaLinkNode != null) {
                val link = mediaLinkNode
                val hrefAttr = link.attr("href")
                try {
                    val absoluteUrl = URL(URL(url), hrefAttr).toString()
                    val fileName = absoluteUrl.substringAfterLast('/')
                    val thumbnailUrl = resolveThumbnailUrl(link, url, absoluteUrl)

                    // 効率的な拡張子チェック
                    val extension = hrefAttr.substringAfterLast('.', "").lowercase()
                    val mediaContent = when {
                        extension in IMAGE_EXTENSIONS -> {
                            // URLのみでID生成し、HTML解析の影響を排除
                            DetailContent.Image(
                                id = "image_${absoluteUrl.hashCode().toUInt().toString(16)}",
                                imageUrl = absoluteUrl,
                                prompt = null,
                                fileName = fileName,
                                thumbnailUrl = thumbnailUrl
                            )
                        }
                        extension in VIDEO_EXTENSIONS -> {
                            // URLのみでID生成し、HTML解析の影響を排除
                            DetailContent.Video(
                                id = "video_${absoluteUrl.hashCode().toUInt().toString(16)}",
                                videoUrl = absoluteUrl,
                                prompt = null,
                                fileName = fileName,
                                thumbnailUrl = null
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
            } else if (isOp) {
                // OPに画像がない場合は「画像なし」プレースホルダーを追加
                progressivelyLoadedContent.add(
                    DetailContent.Image(
                        // スレURLに基づく安定したプレースホルダーID
                        id = "no_image_op_${url.hashCode().toUInt().toString(16)}",
                        imageUrl = "", // 空のURLで「画像なし」を表現
                        prompt = null,
                        fileName = null
                    )
                )
                Log.d("DetailViewModel", "OP has no images - added placeholder")
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
                DetailContent.ThreadEndTime(
                    // 時刻文字列のハッシュでThreadEndTimeのIDを固定
                    id = "thread_end_time_${it.hashCode().toUInt().toString(16)}",
                    endTime = it
                )
            )
        }

        return@withContext progressivelyLoadedContent.toList()
    }

    private fun deriveResFromFileName(fileName: String): String? {
        val candidate = fileName.substringBeforeLast('.', "").takeIf { it.isNotBlank() }
        return candidate?.takeIf { it.any(Char::isDigit) }
    }

    /** アンカータグ内の <img> からサムネイルURLを解決し、なければフルURLから推測する。 */
    private fun resolveThumbnailUrl(
        linkNode: Element,
        documentUrl: String,
        fullImageUrl: String
    ): String? {
        val base = runCatching { URL(documentUrl) }.getOrNull()
        fun Element?.resolveAttr(vararg names: String): String? {
            if (this == null) return null
            for (name in names) {
                if (!hasAttr(name)) continue
                val raw = attr(name).trim()
                if (raw.isEmpty()) continue
                val normalized = raw.substringBefore(' ').substringBefore(',')
                if (normalized.isEmpty() || normalized.startsWith("data:", ignoreCase = true)) continue
                val absolute = if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                    normalized
                } else {
                    base?.let { runCatching { URL(it, normalized).toString() }.getOrNull() }
                }
                if (!absolute.isNullOrBlank()) return absolute
            }
            return null
        }

        // 1. <img> や <source> の各属性(src/data-src/srcset等)を優先的に解決
        val imgNodes = linkNode.select("img,source")
        for (node in imgNodes) {
            node.resolveAttr(
                "src",
                "data-src",
                "data-original",
                "data-thumb",
                "data-lazy-src",
                "data-lazy",
                "data-llsrc",
                "data-placeholder",
                "data-url"
            )?.let { return it }
            node.resolveAttr("srcset", "data-srcset")?.let { return it }
        }

        // 2. 属性で取得できない場合は HTML の構造上に存在する子孫の <img> を走査
        linkNode.select("*[src],*[data-src],*[srcset],*[data-srcset]").forEach { element ->
            element.resolveAttr(
                "src",
                "data-src",
                "data-original",
                "data-thumb",
                "data-lazy-src",
                "data-lazy",
                "data-llsrc",
                "data-placeholder",
                "data-url",
                "srcset",
                "data-srcset"
            )?.let { return it }
        }

        return guessThumbnailFromFull(fullImageUrl)
    }

    /** `/src/12345.jpg` -> `/thumb/12345s.jpg` 形式でサムネイルURLを推測する。 */
    private fun guessThumbnailFromFull(fullImageUrl: String): String? {
        val sanitized = fullImageUrl
            .substringBefore('#')
            .substringBefore('?')
        val marker = "/src/"
        val markerIndex = sanitized.indexOf(marker)
        if (markerIndex == -1) return null
        val dot = sanitized.lastIndexOf('.')
        val hasExtension = dot > markerIndex && dot < sanitized.length - 1
        val baseWithoutExt = if (hasExtension) sanitized.substring(0, dot) else sanitized
        val originalExtension = if (hasExtension) sanitized.substring(dot + 1).lowercase() else ""
        val extensionCandidates = buildList {
            if (originalExtension.isNotBlank()) {
                if (originalExtension != "jpg" && originalExtension != "jpeg") add("jpg")
                add(originalExtension)
            } else {
                add("jpg")
            }
        }
        val replacements = listOf("/thumb/", "/cat/")
        for (replacement in replacements) {
            val replaced = baseWithoutExt.replaceFirst(marker, replacement)
            if (replaced == baseWithoutExt) continue
            val baseCandidates = buildList {
                val withSuffix = if (replaced.endsWith("s")) replaced else replaced + "s"
                add(withSuffix)
                if (withSuffix != replaced) add(replaced)
            }
            for (baseCandidate in baseCandidates) {
                for (ext in extensionCandidates) {
                    val candidate = if (ext.isNotBlank()) "$baseCandidate.$ext" else baseCandidate
                    if (!candidate.equals(sanitized, ignoreCase = true)) return candidate
                }
            }
        }
        return null
    }

    /**
     * DetailContentの内容ハッシュを生成し、重複判定に使用する。
     * IDとは独立して、実際のコンテンツの同一性を判定する。
     */
    private fun contentHash(content: DetailContent): String {
        return when (content) {
            is DetailContent.Text -> {
                // 本文内容から「そうだね数」等の可変要素を除外してハッシュ化
                val plainText = android.text.Html.fromHtml(content.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
                extractPlainBodyFromPlain(plainText).hashCode().toString()
            }
            is DetailContent.Image -> {
                // 画像URLでハッシュ化（プロンプトは変動するため除外）
                content.imageUrl.hashCode().toString()
            }
            is DetailContent.Video -> {
                // 動画URLでハッシュ化
                content.videoUrl.hashCode().toString()
            }
            is DetailContent.ThreadEndTime -> {
                // 終了時刻でハッシュ化
                content.endTime.hashCode().toString()
            }
        }
    }

    /**
     * 画像メタデータ（主にプロンプト/説明）をバックグラウンドで抽出し、EventStore に反映する。
     *
     * 挙動:
     * - 画像ごとにキャッシュを確認し、未取得の場合のみ `MetadataExtractor.extract` を実行。
     * - 結果は EventStore を通じて InProgress → Completed/Failed へ遷移させる。
     * - 取得できたプロンプトはメタデータキャッシュと詳細キャッシュへ書き戻す。
     * - 動画は現在対象外（ログ出力のみ）。
     */
    private suspend fun updateMetadataInBackground(contentList: List<DetailContent>, url: String) {
        updateMetadataWithEventStore(contentList, url)
    }

    /**
     * 指定レス番号に「そうだね」を送信し、成功時は (resNum -> count) をUIへ通知。
     * UI 側ではこの通知を受けて楽観表示（＋/そうだね → そうだねxN）を行い、
     * 行内に No が見つからない場合も自投稿番号(selfResNum)でフォールバックして置換する。
     */
    fun postSodaNe(resNum: String) {
        val url = currentUrl
        if (url == null) {
            val message = "「そうだね」の投稿に失敗しました: 対象のURLが不明です。"
            _error.value = message
            viewModelScope.launch { eventStore.setError(message) }
            return
        }
        viewModelScope.launch {
            try {
                val count = networkClient.postSodaNe(resNum, url)
                if (count != null) {
                    // 成功: 次回以降押下を抑止
                    synchronized(sodaNeStates) {
                        sodaNeStates[resNum] = true
                    }
                    _sodaneUpdate.tryEmit(resNum to count)
                    eventStore.applyEvent(DetailEvent.ErrorSet(null))
                } else {
                    val message = "「そうだね」の投稿に失敗しました。"
                    _error.value = message
                    eventStore.setError(message)
                }
            } catch (e: Exception) {
                Log.e("DetailViewModel", "Error in postSodaNe: ${e.message}", e)
                val message = "「そうだね」の投稿中にエラーが発生しました: ${e.message}"
                _error.value = message
                eventStore.setError(message)
            }
        }
    }

    /**
     * 指定レス番号の「そうだね」押下状態を返す。
     * 重複送信の抑止など、UI 側の制御に用いるフラグ。
     */
    fun getSodaNeState(resNum: String): Boolean {
        return synchronized(sodaNeStates) {
            sodaNeStates[resNum] ?: false
        }
    }

    /**
     * 現在保持している「そうだね」押下状態を全てクリアする。
     * ページ遷移や強制更新時に呼び出し、状態の持ち越しを防ぐ。
     */
    fun resetSodaNeStates() {
        synchronized(sodaNeStates) {
            sodaNeStates.clear()
        }
    }

    /**
     * 通常の削除（画像のみ/本文含む）を実行する。
     * 成功時はスレッドを強制再取得して表示を最新化する。
     */
    fun deletePost(postUrl: String, referer: String, resNum: String, pwd: String, onlyImage: Boolean) {
        viewModelScope.launch {
            try {
                // 新アーキテクチャでローディング状態を設定
                eventStore.applyEvent(DetailEvent.LoadingStateChanged(true))

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
                    val errorMsg = "削除に失敗しました。削除キーが違う可能性があります。"
                    eventStore.setError(errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = "削除中にエラーが発生しました: ${e.message}"
                eventStore.setError(errorMsg)
            } finally {
                eventStore.applyEvent(DetailEvent.LoadingStateChanged(false))
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
                eventStore.applyEvent(DetailEvent.LoadingStateChanged(true))

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
                    val errorMsg = "del の実行に失敗しました。権限やCookieを確認してください。"
                    eventStore.setError(errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = "del 実行中にエラーが発生しました: ${e.message}"
                eventStore.setError(errorMsg)
            } finally {
                eventStore.applyEvent(DetailEvent.LoadingStateChanged(false))
            }
        }
    }

    // ===== 補助関数と正規表現 =====

    /**
     * メディア（画像/動画）ファイル拡張子を持つかを簡易判定する。
     * 解析対象の `<a href>` の抽出フィルタとして使用。
     */
    private fun isMediaUrl(rawHref: String): Boolean {
        return MEDIA_URL_PATTERN.containsMatchIn(rawHref)
    }
    companion object {
        // プリコンパイル済み正規表現
        private val DOC_WRITE = Regex("""document\.write\s*\(\s*'(.*?)'\s*\)""")
        private val TIME = Regex("""<span id="contdisp">([^<]+)</span>""")
        private val NO_PATTERN = Regex("""No\.?\s*(\n?\s*)?(\d+)""")
        private val NO_PATTERN_FALLBACK = Regex("""No\.?\s*(\d+)""")
        private val MEDIA_URL_PATTERN = Regex("""\.(jpg|jpeg|png|gif|webp|webm|mp4)$""", RegexOption.IGNORE_CASE)
        private val TABLE_REMOVAL_PATTERN = Regex("<table[^>]*>.*?</table>", RegexOption.DOT_MATCHES_ALL)
        private val IMG_WITH_ALT_PATTERN = Regex("<img[^>]*alt=[\"']([^\"']*)[\"'][^>]*>")
        private val IMG_PATTERN = Regex("<img[^>]*>")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")
        private val VIDEO_EXTENSIONS = setOf("webm", "mp4")
        private val DUPLICATE_WHITESPACE_REGEX = Regex("\\s+")
    }

    // ===== NG フィルタリング =====

    /** 現在のNGルールでフィルタを再適用し、表示と検索状態を更新する。 */
    fun reapplyNgFilter() {
        clearNgFilterCache() // NGルール変更時はキャッシュをクリア
        viewModelScope.launch {
            applyNgAndPostAsync()
        }
    }

    /**
     * NGルールを適用した結果を `detailContent` に反映し、検索状態も更新する。
     * 併せて生データのキャッシュ保存と、表示状態のアーカイブスナップショット保存を行う。
     */
    private suspend fun applyNgAndPostAsync() {
        val rules = ngStore.cleanupAndGetRules()
        val source = rawContent

        val filtered = if (rules.isEmpty()) {
            source
        } else {
            withContext(Dispatchers.Default) { filterByNgRulesOptimized(source, rules) }
        }

        val hiddenIds = if (rules.isEmpty()) {
            emptySet()
        } else {
            val sourceIds = source.asSequence().map { it.id }.toSet()
            val filteredIds = filtered.asSequence().map { it.id }.toSet()
            sourceIds - filteredIds
        }

        val plainTextMap = computePlainTextMap(filtered)
        val displayPreferences = withContext(Dispatchers.Default) { loadDisplayFilterConfig() }
        val displayFiltered = withContext(Dispatchers.Default) {
            applyDisplayFilters(filtered, plainTextMap, displayPreferences)
        }
        val displayPlainText = withContext(Dispatchers.Default) {
            displayFiltered.asSequence()
                .filterIsInstance<DetailContent.Text>()
                .associate { text -> text.id to (plainTextMap[text.id] ?: toPlainText(text)) }
        }

        withContext(Dispatchers.Main) {
            _plainTextCache.value = displayPlainText
            _displayContent.value = displayFiltered
        }

        eventStore.applyEvent(DetailEvent.NgFilterApplied(rules, hiddenIds))

        recomputeSearchState(displayFiltered)

        currentUrl?.let { url ->
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { cacheManager.saveDetails(url, source) }
            }
        }
    }

    /**
     * 既存表示（prior）に含まれるプロンプト等を新規取得（base）へ引き継ぐ。
     * - Image/Video のプロンプト情報を積極的にマージ（既存のプロンプトも保持）。
     * - 照合キーは `fileName` 優先、無い場合は URL 末尾（ファイル名相当）、最後に完全URL。
     */
    private suspend fun mergeWithExistingPrompts(base: List<DetailContent>): List<DetailContent> {
        val current = detailContent.value
        if (current.isEmpty()) return base
        return mergePrompts(base, current)
    }

    private suspend fun mergePrompts(base: List<DetailContent>, prior: List<DetailContent>): List<DetailContent> {
        if (base.isEmpty() || prior.isEmpty()) return base

        return withContext(Dispatchers.Default) {
            fun keyForImage(url: String?, fileName: String?): List<String> {
                val keys = mutableListOf<String>()
                // 1. ファイル名での照合
                fileName?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
                // 2. URL末尾のファイル名での照合
                url?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
                // 3. 完全URLでの照合
                url?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
                return keys
            }

            val promptByKey: Map<String, String> = buildMap {
                prior.forEach { dc ->
                    when (dc) {
                        is DetailContent.Image -> {
                            val keys = keyForImage(dc.imageUrl, dc.fileName)
                            val p = dc.prompt
                            if (!p.isNullOrBlank()) {
                                keys.forEach { k -> put(k, p) }
                            }
                        }
                        is DetailContent.Video -> {
                            val keys = keyForImage(dc.videoUrl, dc.fileName)
                            val p = dc.prompt
                            if (!p.isNullOrBlank()) {
                                keys.forEach { k -> put(k, p) }
                            }
                        }
                        else -> {}
                    }
                }
            }

            if (promptByKey.isEmpty()) {
                base
            } else {
                base.map { dc ->
                    when (dc) {
                        is DetailContent.Image -> {
                            val currentPrompt = dc.prompt
                            if (!currentPrompt.isNullOrBlank()) {
                                dc // 既にプロンプトがある場合はそのまま
                            } else {
                                val keys = keyForImage(dc.imageUrl, dc.fileName)
                                val p = keys.firstNotNullOfOrNull { k -> promptByKey[k] }
                                if (!p.isNullOrBlank()) dc.copy(prompt = p) else dc
                            }
                        }
                        is DetailContent.Video -> {
                            val currentPrompt = dc.prompt
                            if (!currentPrompt.isNullOrBlank()) {
                                dc // 既にプロンプトがある場合はそのまま
                            } else {
                                val keys = keyForImage(dc.videoUrl, dc.fileName)
                                val p = keys.firstNotNullOfOrNull { k -> promptByKey[k] }
                                if (!p.isNullOrBlank()) dc.copy(prompt = p) else dc
                            }
                        }
                        else -> dc
                    }
                }
            }
        }
    }

    /** NGルールに基づきテキストと直後のメディア列を順次評価して返す（skipping 状態を全体で共有するため並列化しない）。 */
    private suspend fun filterByNgRulesOptimized(src: List<DetailContent>, rules: List<NgRule>): List<DetailContent> {
        val cacheKey = src to rules
        ngFilterCache.get(cacheKey)?.let { return it }

        val result = filterChunk(src, rules)

        ngFilterCache.put(cacheKey, result)
        return result
    }

    private fun filterChunk(src: List<DetailContent>, rules: List<NgRule>): List<DetailContent> {
        if (src.isEmpty()) return src
        val out = ArrayList<DetailContent>(src.size)
        var skipping = false
        for (item in src) {
            when (item) {
                is DetailContent.Text -> {
                    if (isNgItem(item, rules)) {
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

    private fun isNgItem(item: DetailContent.Text, rules: List<NgRule>): Boolean {
        val id = extractIdFromHtml(item.htmlContent)
        val body = extractPlainBodyFromPlain(plainTextOf(item))
        return rules.any { r ->
            when (r.type) {
                RuleType.ID -> {
                    if (id.isNullOrBlank()) false else match(id, r.pattern, r.match ?: MatchType.EXACT, ignoreCase = true)
                }
                RuleType.BODY -> match(body, r.pattern, r.match ?: MatchType.SUBSTRING, ignoreCase = true)
                RuleType.TITLE -> false // タイトルNGはMainActivity側で適用
            }
        }
    }

    /** NGルールに基づきテキストと直後のメディア列を間引いた一覧を返す（従来版）。 */
    private fun filterByNgRules(src: List<DetailContent>, rules: List<NgRule>): List<DetailContent> {
        if (src.isEmpty()) return src
        val out = ArrayList<DetailContent>(src.size)
        var skipping = false
        for (item in src) {
            when (item) {
                is DetailContent.Text -> {
                    val id = extractIdFromHtml(item.htmlContent)
                    // プレーンテキストはキャッシュを活用
                    val body = extractPlainBodyFromPlain(plainTextOf(item))
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
        return extractPlainBodyFromPlain(plain)
    }

    private fun extractPlainBodyFromPlain(plain: String): String {
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

    private fun markPromptLoading(ids: Collection<String>, loading: Boolean) {
        if (ids.isEmpty()) return
        _promptLoadingIds.update { current ->
            if (loading) current + ids else current - ids
        }
    }

    private fun markPromptLoading(id: String, loading: Boolean) {
        markPromptLoading(listOf(id), loading)
    }

    private suspend fun sanitizePrompts(list: List<DetailContent>): List<DetailContent> {
        if (list.isEmpty()) return list
        val needsSanitize = list.any { it.hasPromptNeedingSanitize() }
        if (!needsSanitize) return list

        return withContext(Dispatchers.Default) {
            var changed = false
            val sanitized = list.map { content ->
                when (content) {
                    is DetailContent.Image -> {
                        val normalized = normalizePrompt(content.prompt)
                        if (normalized != content.prompt) {
                            changed = true
                            content.copy(prompt = normalized)
                        } else content
                    }
                    is DetailContent.Video -> {
                        val normalized = normalizePrompt(content.prompt)
                        if (normalized != content.prompt) {
                            changed = true
                            content.copy(prompt = normalized)
                        } else content
                    }
                    else -> content
                }
            }
            if (changed) sanitized else list
        }
    }

    private suspend fun setRawContentSanitized(list: List<DetailContent>): List<DetailContent> {
        val sanitized = sanitizePrompts(list)
        setRawContent(sanitized)
        return sanitized
    }

    private fun DetailContent.hasPromptNeedingSanitize(): Boolean = when (this) {
        is DetailContent.Image -> this.prompt.needsHtmlNormalization()
        is DetailContent.Video -> this.prompt.needsHtmlNormalization()
        else -> false
    }

    private fun String?.needsHtmlNormalization(): Boolean {
        val value = this?.trim() ?: return false
        if (value.isEmpty()) return false
        val hasAngleBrackets = value.indexOf('<') >= 0 && value.indexOf('>') > value.indexOf('<')
        if (hasAngleBrackets) return true
        val lower = value.lowercase()
        return lower.contains("&lt;") || lower.contains("&gt;") || lower.contains("&amp;") || lower.contains("&#")
    }

    private fun normalizePrompt(raw: String?): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.needsHtmlNormalization()) return trimmed
        val plain = HtmlCompat.fromHtml(trimmed, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
        return plain.ifBlank { null }
    }

    // ===== 検索: 公開APIと内部実装 =====
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
    private fun recomputeSearchState(contentOverride: List<DetailContent>? = null) {
        val q = currentSearchQuery?.trim().orEmpty()
        searchResultPositions.clear()
        if (q.isBlank()) {
            publishSearchState()
            return
        }
        val contentList = contentOverride ?: displayContent.value.ifEmpty { detailContent.value }
        viewModelScope.launch(Dispatchers.Default) {
            val hits = mutableListOf<Int>()
            contentList.forEachIndexed { index, content ->
                val textToSearch: String? = when (content) {
                    is DetailContent.Text -> plainTextOf(content)
                    is DetailContent.Image -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.imageUrl.substringAfterLast('/')}"
                    is DetailContent.Video -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.videoUrl.substringAfterLast('/')}"
                    is DetailContent.ThreadEndTime -> null
                }
                if (textToSearch?.contains(q, ignoreCase = true) == true) {
                    hits.add(index)
                }
            }
            withContext(Dispatchers.Main) {
                searchResultPositions.clear()
                searchResultPositions.addAll(hits)
                if (hits.isNotEmpty() && currentSearchHitIndex !in hits.indices) {
                    currentSearchHitIndex = 0
                }
                publishSearchState()
            }
        }
    }

    // ===== プレーンテキストキャッシュ =====
    private val _plainTextCache = MutableStateFlow<Map<String, String>>(emptyMap())
    val plainTextCache: StateFlow<Map<String, String>> = _plainTextCache.asStateFlow()

    private fun toPlainText(t: DetailContent.Text): String {
        return android.text.Html.fromHtml(t.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString()
    }

    private suspend fun computePlainTextMap(list: List<DetailContent>): Map<String, String> {
        return withContext(Dispatchers.Default) {
            list.asSequence()
                .filterIsInstance<DetailContent.Text>()
                .associate { t -> t.id to toPlainText(t) }
        }
    }

    fun ensurePlainTextCachedFor(contents: List<DetailContent>) {
        if (contents.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) {
            val current = _plainTextCache.value
            val missing = contents.asSequence()
                .filterIsInstance<DetailContent.Text>()
                .filter { !current.containsKey(it.id) }
                .toList()
            if (missing.isEmpty()) return@launch
            val updated = HashMap(current)
            var changed = false
            for (text in missing) {
                if (!updated.containsKey(text.id)) {
                    updated[text.id] = toPlainText(text)
                    changed = true
                }
            }
            if (changed) {
                withContext(Dispatchers.Main) { _plainTextCache.value = updated }
            }
        }
    }

    fun plainTextOf(t: DetailContent.Text): String {
        val cached = _plainTextCache.value[t.id]
        if (cached != null) return cached
        val now = toPlainText(t)
        viewModelScope.launch(Dispatchers.Default) {
            val updated = HashMap(_plainTextCache.value)
            if (!updated.containsKey(t.id)) {
                updated[t.id] = now
                // メモリリーク防止：キャッシュサイズを制限
                if (updated.size > 500) {
                    // 古いエントリを削除（最新の300件のみ保持・メモリ効率改善）
                    val sorted = updated.entries.toList()
                    val toKeep = sorted.takeLast(300).associate { it.key to it.value }
                    withContext(Dispatchers.Main) { _plainTextCache.value = toKeep }
                } else {
                    withContext(Dispatchers.Main) { _plainTextCache.value = updated }
                }
            }
        }
        return now
    }

    private data class DisplayFilterConfig(
        val hideDeletedRes: Boolean,
        val hideDuplicateRes: Boolean,
        val duplicateResThreshold: Int
    )

    private fun loadDisplayFilterConfig(): DisplayFilterConfig {
        val hideDeleted = AppPreferences.getHideDeletedRes(appContext)
        val hideDuplicate = AppPreferences.getHideDuplicateRes(appContext)
        val threshold = AppPreferences.getDuplicateResThreshold(appContext)
        return DisplayFilterConfig(hideDeleted, hideDuplicate, threshold)
    }

    private fun applyDisplayFilters(
        items: List<DetailContent>,
        plainTextCache: Map<String, String>,
        config: DisplayFilterConfig
    ): List<DetailContent> {
        if (items.isEmpty()) return items
        val fallbackPlainText = { text: DetailContent.Text -> plainTextCache[text.id] ?: toPlainText(text) }

        val withoutDeleted = if (config.hideDeletedRes) {
            items.filter { item ->
                when (item) {
                    is DetailContent.Text -> !isDeletedRes(item)
                    else -> true
                }
            }
        } else {
            items
        }

        val withoutPhantom = filterPhantomQuoteResponses(withoutDeleted, plainTextCache, fallbackPlainText)

        return if (config.hideDuplicateRes) {
            filterDuplicateResponses(withoutPhantom, config.duplicateResThreshold, plainTextCache, fallbackPlainText)
        } else {
            withoutPhantom
        }
    }

    private fun filterPhantomQuoteResponses(
        items: List<DetailContent>,
        plainTextCache: Map<String, String>,
        plainTextOf: (DetailContent.Text) -> String
    ): List<DetailContent> {
        if (items.isEmpty()) return items
        val seenBodyLines = mutableSetOf<String>()
        val result = mutableListOf<DetailContent>()
        var index = 0
        var anyFiltered = false

        while (index < items.size) {
            val item = items[index]
            if (item is DetailContent.Text) {
                val plain = plainTextCache[item.id] ?: plainTextOf(item)
                val lines = plain.lines()

                var hide = false
                lines@ for (line in lines) {
                    val normalizedSource = line
                        .replace("\u200B", "")
                        .replace('　', ' ')
                        .replace('＞', '>')
                    val trimmedStart = normalizedSource.trimStart()
                    if (!trimmedStart.startsWith(">")) continue
                    val leadingGtCount = trimmedStart.takeWhile { it == '>' }.length
                    if (leadingGtCount != 1) continue
                    val quoteBody = trimmedStart.drop(leadingGtCount).trimStart()
                    val normalizedQuote = normalizeBodyLineForQuoteDetection(quoteBody) ?: continue
                    if (normalizedQuote.length < 2) continue
                    if (normalizedQuote.all { it.isDigit() }) continue
                    if (normalizedQuote !in seenBodyLines) {
                        hide = true
                        break@lines
                    }
                }

                if (hide) {
                    anyFiltered = true
                    index++
                    while (index < items.size) {
                        val attachment = items[index]
                        if (attachment is DetailContent.Image || attachment is DetailContent.Video) {
                            anyFiltered = true
                            index++
                        } else {
                            break
                        }
                    }
                    continue
                } else {
                    result += item
                    for (line in lines) {
                        val normalized = normalizeBodyLineForQuoteDetection(line) ?: continue
                        val trimmedStart = line
                            .replace("\u200B", "")
                            .replace('　', ' ')
                            .replace('＞', '>')
                            .trimStart()
                        val leadingGt = trimmedStart.takeWhile { it == '>' }.length
                        if (leadingGt == 0) {
                            seenBodyLines += normalized
                        }
                    }
                    index++
                }
            } else {
                result += item
                index++
            }
        }

        return if (anyFiltered) result else items
    }

    private fun filterDuplicateResponses(
        items: List<DetailContent>,
        threshold: Int,
        plainTextCache: Map<String, String>,
        plainTextOf: (DetailContent.Text) -> String
    ): List<DetailContent> {
        if (items.isEmpty()) return items
        val limit = threshold.coerceAtLeast(1)
        val result = mutableListOf<DetailContent>()
        val counters = mutableMapOf<String, Int>()
        var index = 0
        var anyFiltered = false

        while (index < items.size) {
            val item = items[index]
            if (item is DetailContent.Text) {
                val plain = plainTextCache[item.id] ?: plainTextOf(item)
                val key = buildDuplicateContentKey(plain)
                if (key != null) {
                    val newCount = (counters[key] ?: 0) + 1
                    counters[key] = newCount
                    if (newCount <= limit) {
                        result += item
                        index++
                    } else {
                        anyFiltered = true
                        index++
                        while (index < items.size) {
                            val attachment = items[index]
                            if (attachment is DetailContent.Image || attachment is DetailContent.Video) {
                                anyFiltered = true
                                index++
                            } else {
                                break
                            }
                        }
                    }
                    continue
                }
            }
            result += item
            index++
        }

        return if (anyFiltered) result else items
    }

    private fun normalizeBodyLineForQuoteDetection(raw: String): String? {
        var normalized = raw
            .replace("\u200B", "")
            .replace('　', ' ')
            .replace('＞', '>')
            .trim()
        if (normalized.isEmpty()) return null
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC)
        normalized = DUPLICATE_WHITESPACE_REGEX.replace(normalized, " ").trim()
        if (normalized.isEmpty()) return null
        if (normalized.startsWith("No.", ignoreCase = true)) return null
        if (normalized.startsWith("ID:", ignoreCase = true)) return null
        return normalized
    }

    private fun isDeletedRes(text: DetailContent.Text): Boolean {
        return text.htmlContent.contains("スレッドを立てた人によって削除されました") ||
            text.htmlContent.contains("書き込みをした人によって削除されました")
    }

    private fun buildDuplicateContentKey(plainText: String): String? {
        val bodyLines = mutableListOf<String>()
        for (line in plainText.lines()) {
            var normalized = line.replace("\u200B", "")
                .replace('　', ' ')
                .replace('＞', '>')
                .replace('≫', '>')
                .trim()
            if (normalized.isEmpty()) continue
            if (normalized.startsWith(">")) continue
            normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC)
            val collapsed = DUPLICATE_WHITESPACE_REGEX.replace(normalized, " ").trim()
            if (collapsed.isEmpty()) continue
            if (collapsed.startsWith("No.", ignoreCase = true)) continue
            if (collapsed.startsWith("ID:", ignoreCase = true)) continue
            bodyLines += collapsed
        }

        if (bodyLines.isEmpty()) return null
        return bodyLines.joinToString("\n")
    }


    // ===== 新アーキテクチャ対応のメタデータ更新処理 =====

    /**
     * EventStore 連携でメタデータを更新する内部処理。
     * 画像は既存プロンプトを尊重しつつ、キャッシュ→抽出→Completed/Failed のイベントを発行し、
     * 成功時にはメタデータキャッシュと保存済み詳細も更新する。動画は現状ログのみ。
     */
    private suspend fun updateMetadataWithEventStore(contentList: List<DetailContent>, url: String) {
        contentList.forEach { content ->
            when (content) {
                is DetailContent.Image -> {
                    if (!content.prompt.isNullOrBlank()) {
                        // 既にプロンプトがある場合はキャッシュにも反映しておく
                        viewModelScope.launch(Dispatchers.IO) {
                            runCatching { metadataCache.put(content.imageUrl, content.prompt!!) }
                        }
                        return@forEach
                    }

                    if (!PromptSettings.isPromptFetchEnabled(appContext)) {
                        return@forEach
                    }

                    markPromptLoading(content.id, true)
                    eventStore.applyEvent(DetailEvent.MetadataExtractionStarted(content.id))

                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val cachedPrompt = runCatching { metadataCache.get(content.imageUrl) }.getOrNull()
                            val prompt = if (!cachedPrompt.isNullOrBlank()) {
                                cachedPrompt
                            } else {
                                withTimeoutOrNull(15000L) {
                                    MetadataExtractor.extract(appContext, content.imageUrl, networkClient)
                                }
                            }

                            val normalized = normalizePrompt(prompt)

                            eventStore.updateMetadataProgressively(content.id, normalized)

                            if (!normalized.isNullOrBlank()) {
                                val listForPersistence = updateRawContent { current ->
                                    var changed = false
                                    val mapped = current.map { existing ->
                                        when (existing) {
                                            is DetailContent.Image ->
                                                if (existing.id == content.id && existing.prompt != normalized) {
                                                    changed = true
                                                    existing.copy(prompt = normalized)
                                                } else {
                                                    existing
                                                }
                                            else -> existing
                                        }
                                    }
                                    if (changed) mapped else current
                                }
                                runCatching { metadataCache.put(content.imageUrl, normalized) }
                                runCatching { cacheManager.saveDetails(url, listForPersistence) }
                            }
                        } catch (e: Exception) {
                            Log.e("DetailViewModel", "Metadata extraction error for ${content.imageUrl}", e)
                            eventStore.updateMetadataProgressively(content.id, null)
                        } finally {
                            markPromptLoading(content.id, false)
                        }
                    }
                }
                is DetailContent.Video -> {
                    if (content.prompt.isNullOrBlank()) {
                        Log.d("DetailViewModel", "Video metadata extraction not yet implemented for ${content.videoUrl}")
                    }
                }
                else -> {}
            }
        }
    }

    /** 検索UI表示用の集計（アクティブ/現在位置/総数）をフローに反映。 */
    private fun publishSearchState() {
        val active = (currentSearchQuery != null) && searchResultPositions.isNotEmpty()
        val currentDisp = if (active && currentSearchHitIndex in searchResultPositions.indices) currentSearchHitIndex + 1 else 0
        val total = searchResultPositions.size
        val newState = SearchState(active = active, currentIndexDisplay = currentDisp, total = total)
        _searchState.value = newState
        viewModelScope.launch {
            eventStore.applyEvent(DetailEvent.SearchStateUpdated(if (active) newState else null))
        }
    }

    private data class PendingDownloadRequest(
        val id: Long,
        val urls: List<String>,
        val newUrls: List<String>,
        val existingByUrl: Map<String, List<MediaSaver.ExistingMedia>>
    ) {
        val existingCount: Int get() = existingByUrl.values.sumOf { it.size }
    }

    private enum class DownloadConflictResolution {
        SkipExisting,
        OverwriteExisting
    }

    fun downloadImages(urls: List<String>) {
        if (urls.isEmpty()) return

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _downloadProgress.value = DownloadProgress(0, urls.size, isActive = true)
            var completed = 0

            try {
                val semaphore = kotlinx.coroutines.sync.Semaphore(4)
                coroutineScope {
                    val jobs = urls.map { url ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                val fileName = url.substringAfterLast('/')
                                _downloadProgress.value = DownloadProgress(completed, urls.size, fileName, true)

                                MediaSaver.saveImage(appContext, url, networkClient, referer = currentUrl)

                                downloadProgressMutex.withLock {
                                    completed++
                                    _downloadProgress.value = DownloadProgress(completed, urls.size, fileName, true)
                                }
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // キャンセルされた場合
                throw e
            } finally {
                delay(500) // 完了表示を少し見せる
                _downloadProgress.value = null
                downloadJob = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadProgress.value = null
    }

    fun downloadImagesSkipExisting(urls: List<String>) {
        if (urls.isEmpty()) return

        viewModelScope.launch {
            val requestId = downloadRequestIdGenerator.incrementAndGet()
            val existingByUrl = MediaSaver.findExistingImages(appContext, urls)
            val newUrls = urls.filterNot { existingByUrl.containsKey(it) }
            val pending = PendingDownloadRequest(
                id = requestId,
                urls = urls,
                newUrls = newUrls,
                existingByUrl = existingByUrl
            )

            if (existingByUrl.isEmpty()) {
                performBulkDownload(pending, DownloadConflictResolution.SkipExisting)
                return@launch
            }

            pendingDownloadMutex.withLock {
                pendingDownloadRequests[requestId] = pending
            }

            val conflictFiles = existingByUrl
                .flatMap { (url, entries) -> entries.map { DownloadConflictFile(url = url, fileName = it.fileName) } }
                .sortedBy { it.fileName }

            _downloadConflictRequests.emit(
                DownloadConflictRequest(
                    requestId = requestId,
                    totalCount = urls.size,
                    newCount = newUrls.size,
                    existingFiles = conflictFiles
                )
            )
        }
    }

    fun confirmDownloadSkip(requestId: Long) {
        viewModelScope.launch {
            val pending = removePendingRequest(requestId) ?: return@launch
            if (pending.newUrls.isEmpty()) {
                withContext(Dispatchers.Main) {
                    val message = if (pending.existingCount > 0) {
                        "${pending.existingCount}件の画像は既にダウンロード済みでした"
                    } else {
                        "ダウンロード対象の画像がありません"
                    }
                    android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            performBulkDownload(pending, DownloadConflictResolution.SkipExisting)
        }
    }

    fun confirmDownloadOverwrite(requestId: Long) {
        viewModelScope.launch {
            val pending = removePendingRequest(requestId) ?: return@launch
            performBulkDownload(pending, DownloadConflictResolution.OverwriteExisting)
        }
    }

    fun cancelDownloadRequest(requestId: Long) {
        viewModelScope.launch {
            pendingDownloadMutex.withLock {
                pendingDownloadRequests.remove(requestId)
            }
            // 古いリクエストのクリーンアップ（メモリリーク防止）
            cleanupOldDownloadRequests()
        }
    }

    /**
     * 古いダウンロードリクエストをクリーンアップする（メモリリーク防止）
     */
    private suspend fun cleanupOldDownloadRequests() {
        pendingDownloadMutex.withLock {
            if (pendingDownloadRequests.size > 5) {
                // 最新の3件のみ保持（メモリ効率改善）
                val sorted = pendingDownloadRequests.entries.sortedByDescending { it.key }
                val toRemove = sorted.drop(3).map { it.key }
                toRemove.forEach { pendingDownloadRequests.remove(it) }
                Log.d("DetailViewModel", "Cleaned up ${toRemove.size} old download requests")
            }
        }
    }

    private suspend fun removePendingRequest(requestId: Long): PendingDownloadRequest? {
        return pendingDownloadMutex.withLock { pendingDownloadRequests.remove(requestId) }
    }

    private suspend fun performBulkDownload(
        pending: PendingDownloadRequest,
        resolution: DownloadConflictResolution
    ) {
        val urlsToDownload = when (resolution) {
            DownloadConflictResolution.SkipExisting -> pending.newUrls
            DownloadConflictResolution.OverwriteExisting -> pending.urls
        }

        val total = urlsToDownload.size

        if (resolution == DownloadConflictResolution.SkipExisting && total == 0) {
            withContext(Dispatchers.Main) {
                val message = if (pending.existingCount > 0) {
                    "${pending.existingCount}件の画像は既にダウンロード済みでした"
                } else {
                    "ダウンロード対象の画像がありません"
                }
                android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (total == 0) return

        _downloadProgress.value = DownloadProgress(0, total, isActive = true)
        var completed = 0
        var skippedCount = if (resolution == DownloadConflictResolution.SkipExisting) pending.existingCount else 0
        var newSuccess = 0
        var overwriteSuccess = 0
        var failureCount = 0

        val semaphore = Semaphore(4)

        try {
            coroutineScope {
                val jobs = urlsToDownload.map { url ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val fileName = url.substringAfterLast('/')
                            _downloadProgress.value = DownloadProgress(completed, total, fileName, true)
                            val hasExisting = !pending.existingByUrl[url].isNullOrEmpty()
                            val success = when (resolution) {
                                DownloadConflictResolution.SkipExisting ->
                                    MediaSaver.saveImageIfNotExists(appContext, url, networkClient, referer = currentUrl)
                                DownloadConflictResolution.OverwriteExisting -> {
                                    pending.existingByUrl[url]?.let { entries ->
                                        MediaSaver.deleteMedia(appContext, entries)
                                    }
                                    MediaSaver.saveImageIfNotExists(appContext, url, networkClient, referer = currentUrl)
                                }
                            }

                            downloadProgressMutex.withLock {
                                if (success) {
                                    if (hasExisting && resolution == DownloadConflictResolution.OverwriteExisting) {
                                        overwriteSuccess++
                                    } else {
                                        newSuccess++
                                    }
                                } else {
                                    if (resolution == DownloadConflictResolution.SkipExisting) {
                                        skippedCount++
                                    } else {
                                        failureCount++
                                    }
                                }
                                completed++
                                _downloadProgress.value = DownloadProgress(completed, total, fileName, true)
                            }
                        }
                    }
                }

                jobs.awaitAll()
            }

            withContext(Dispatchers.Main) {
                val message = when (resolution) {
                    DownloadConflictResolution.SkipExisting -> buildSkipMessage(newSuccess, skippedCount)
                    DownloadConflictResolution.OverwriteExisting -> buildOverwriteMessage(newSuccess, overwriteSuccess, failureCount)
                }
                android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        } finally {
            delay(500)
            _downloadProgress.value = null
        }
    }

    private fun buildSkipMessage(downloadedCount: Int, skippedCount: Int): String {
        return when {
            downloadedCount > 0 && skippedCount > 0 -> "新規ダウンロード: ${downloadedCount}件、スキップ: ${skippedCount}件"
            downloadedCount > 0 -> "${downloadedCount}件の画像をダウンロードしました"
            skippedCount > 0 -> "${skippedCount}件の画像は既にダウンロード済みでした"
            else -> "ダウンロード対象の画像がありません"
        }
    }

    private fun buildOverwriteMessage(newSuccess: Int, overwriteSuccess: Int, failureCount: Int): String {
        val totalSuccess = newSuccess + overwriteSuccess
        return when {
            totalSuccess > 0 && failureCount > 0 -> "保存完了: ${totalSuccess}件（新規: ${newSuccess}件、上書き: ${overwriteSuccess}件、失敗: ${failureCount}件）"
            totalSuccess > 0 && overwriteSuccess > 0 && newSuccess > 0 -> "保存完了: ${totalSuccess}件（新規: ${newSuccess}件、上書き: ${overwriteSuccess}件）"
            totalSuccess > 0 && overwriteSuccess > 0 -> "既存ファイルを${overwriteSuccess}件上書き保存しました"
            totalSuccess > 0 -> "${totalSuccess}件の画像をダウンロードしました"
            failureCount > 0 -> "画像の保存に失敗しました"
            else -> "ダウンロード対象の画像がありません"
        }
    }

    // ========== スレッド保存機能 ==========

    /**
     * スレッド全体をアーカイブする
     * 画像・動画・HTMLを一括でダウンロードし、同じディレクトリに保存する
     * @param threadTitle スレッドのタイトル
     * @return 成功メッセージまたはエラー
     */
    fun archiveThread(threadTitle: String): String? {
        if (_archiveProgress.value?.isActive == true) {
            return "既にスレッド保存が実行中です"
        }

        archiveJob?.cancel()
        archiveJob = viewModelScope.launch {
            try {
                _archiveProgress.value = ThreadArchiveProgress(
                    current = 0,
                    total = 1,
                    currentFileName = "準備中...",
                    isActive = true
                )

                val threadUrl = currentUrl ?: ""
                val contents = detailContent.value

                val result = threadArchiver.archiveThread(
                    threadTitle = threadTitle,
                    threadUrl = threadUrl,
                    contents = contents
                ) { progress ->
                    _archiveProgress.value = progress
                }

                // 完了メッセージを表示
                result.onSuccess { message ->
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            appContext,
                            message,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }.onFailure { error ->
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            appContext,
                            "スレッド保存に失敗しました: ${error.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }

                // 進捗表示をクリア
                delay(500)
                _archiveProgress.value = null
                archiveJob = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                // キャンセルされた場合
                _archiveProgress.value = null
                archiveJob = null
                throw e
            } catch (e: Exception) {
                Log.e("DetailViewModel", "Archive failed", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        appContext,
                        "スレッド保存に失敗しました: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                _archiveProgress.value = null
                archiveJob = null
            }
        }
        return null
    }

    fun cancelArchive() {
        archiveJob?.cancel()
        archiveJob = null
        _archiveProgress.value = null
    }

    // ========== TTS音声読み上げ制御 ==========

    /**
     * 全レスを順次読み上げ開始
     */
    fun startTtsReading() {
        val items = detailContent.value.filterIsInstance<DetailContent.Text>()
        ttsManager.playTexts(items) { text ->
            plainTextCache.value[text.id] ?: android.text.Html.fromHtml(
                text.htmlContent,
                android.text.Html.FROM_HTML_MODE_COMPACT
            ).toString()
        }
    }

    /**
     * 指定レス番号から読み上げ開始
     */
    fun startTtsFromResNum(resNum: String) {
        val items = detailContent.value.filterIsInstance<DetailContent.Text>()
        ttsManager.playFromResNum(resNum, items) { text ->
            plainTextCache.value[text.id] ?: android.text.Html.fromHtml(
                text.htmlContent,
                android.text.Html.FROM_HTML_MODE_COMPACT
            ).toString()
        }
    }

    /**
     * TTS一時停止
     */
    fun pauseTts() {
        ttsManager.pause()
    }

    /**
     * TTS再開
     */
    fun resumeTts() {
        ttsManager.resume()
    }

    /**
     * TTS停止
     */
    fun stopTts() {
        ttsManager.stop()
    }

    /**
     * 次のレスへスキップ
     */
    fun skipNextTts() {
        ttsManager.skipNext()
    }

    /**
     * 前のレスへ戻る
     */
    fun skipPreviousTts() {
        ttsManager.skipPrevious()
    }

    /**
     * 読み上げ速度設定
     */
    fun setTtsSpeechRate(rate: Float) {
        ttsManager.setSpeechRate(rate)
    }

    override fun onCleared() {
        super.onCleared()
        ttsManager.release()
    }
}
