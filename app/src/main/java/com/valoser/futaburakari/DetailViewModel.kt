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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import com.valoser.futaburakari.ui.detail.SearchState
import androidx.collection.LruCache
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

    /** ダウンロード進捗を表すフロー。 */
    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _downloadConflictRequests = MutableSharedFlow<DownloadConflictRequest>(extraBufferCapacity = 1)
    val downloadConflictRequests = _downloadConflictRequests.asSharedFlow()

    private val _promptLoadingIds = MutableStateFlow<Set<String>>(emptySet())
    val promptLoadingIds: StateFlow<Set<String>> = _promptLoadingIds.asStateFlow()

    private val downloadRequestIdGenerator = AtomicLong(0)
    private val pendingDownloadMutex = Mutex()
    private val pendingDownloadRequests = mutableMapOf<Long, PendingDownloadRequest>()

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

    /** デバイスメモリに基づいた最適なキャッシュサイズを計算 */
    private fun calculateOptimalCacheSize(): Int {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        // 最大メモリの1%をキャッシュに割り当て、最小20、最大200
        return ((maxMemory / 1024 / 1024 / 100).toInt()).coerceIn(20, 200)
    }

    /** NGルールが変更された時にキャッシュをクリアする */
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

        // 段階的メモリ管理
        when {
            memoryUsagePercent > 90 -> {
                // 極度の高負荷：即座にアクション
                Log.w("DetailViewModel", "Critical memory usage detected, immediate cleanup")
                clearNgFilterCache()
                // Coilメモリキャッシュも即座にクリア
                MyApplication.clearCoilImageCache(appContext)
                memoryCheckIntervalMs = 5000L
                consecutiveHighMemoryCount++
            }
            memoryUsagePercent > 80 -> {
                // 高負荷：Coilメモリキャッシュをクリア
                Log.w("DetailViewModel", "High memory usage detected, clearing Coil memory cache")
                MyApplication.clearCoilImageCache(appContext)
                consecutiveHighMemoryCount++
                memoryCheckIntervalMs = 10000L
                if (consecutiveHighMemoryCount >= 2) {
                    clearNgFilterCache()
                    consecutiveHighMemoryCount = 0
                }
            }
            memoryUsagePercent > 70 -> {
                // 中程度の負荷：一部のキャッシュをクリア
                consecutiveHighMemoryCount++
                memoryCheckIntervalMs = 15000L
                if (consecutiveHighMemoryCount >= 3) {
                    Log.w("DetailViewModel", "Sustained memory pressure, clearing image cache")
                    MyApplication.clearCoilImageCache(appContext)
                    clearNgFilterCache()
                    consecutiveHighMemoryCount = 0
                }
            }
            memoryUsagePercent > 60 -> {
                memoryCheckIntervalMs = 20000L
                consecutiveHighMemoryCount = 0
            }
            else -> {
                memoryCheckIntervalMs = 30000L
                consecutiveHighMemoryCount = 0
            }
        }

        // 使用率に応じて監視間隔を調整
        memoryCheckIntervalMs = when {
            memoryUsageRatio > 0.85f -> 10000L  // 85%超: 10秒間隔
            memoryUsageRatio > 0.70f -> 20000L  // 70%超: 20秒間隔
            else -> 30000L                      // 通常: 30秒間隔
        }

        when {
            memoryUsageRatio > 0.85f -> {
                Log.w("DetailViewModel", "Critical memory usage (${(memoryUsageRatio * 100).toInt()}%), performing aggressive cleanup")
                consecutiveHighMemoryCount++

                // アグレッシブクリーンアップ
                clearNgFilterCache()
                _plainTextCache.value = emptyMap()
                MyApplication.clearCoilImageCache(appContext)

                // 連続して高メモリ状態が続く場合はさらに強力な対策
                if (consecutiveHighMemoryCount >= 3) {
                    Log.w("DetailViewModel", "Persistent high memory usage, forcing garbage collection")
                    System.gc()
                    consecutiveHighMemoryCount = 0
                }
            }
            memoryUsageRatio > 0.75f -> {
                Log.w("DetailViewModel", "High memory usage (${(memoryUsageRatio * 100).toInt()}%), performing selective cleanup")
                consecutiveHighMemoryCount++

                // 選択的クリーンアップ：半分のキャッシュをクリア
                clearNgFilterCache()
                val currentPlainCache = _plainTextCache.value
                if (currentPlainCache.size > 20) {
                    val reducedCache = currentPlainCache.toList().takeLast(10).toMap()
                    _plainTextCache.value = reducedCache
                }
            }
            else -> {
                // メモリ使用量が正常範囲に戻った
                consecutiveHighMemoryCount = 0
            }
        }
    }

    /**
     * メモリ使用量を強制的にチェックして警告を表示する
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
    private val sodaNeStates = mutableMapOf<String, Boolean>()

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
        val updateId = contentUpdateCounter.incrementAndGet()
        viewModelScope.launch {
            // データ整合性チェック：更新中の競合状態を防ぐ
            if (contentUpdateCounter.get() != updateId) {
                Log.d("DetailViewModel", "Newer update detected, canceling this fetch")
                return@launch
            }

            // スレ移動時に「そうだね」状態を正しくリセットするため、
            // 代入前のURLと比較してページ遷移を判定する。
            val isNewPage = this@DetailViewModel.currentUrl != url
            if (forceRefresh || isNewPage) {
                resetSodaNeStates()
            }
            this@DetailViewModel.currentUrl = url
            _isLoading.value = true
            _error.value = null
            _promptLoadingIds.value = emptySet()
            var itemIdCounter = 0L


            try {
                // メモリ使用量をチェック
                checkMemoryUsage()

                if (!forceRefresh) {
                    val cachedDetails = withContext(Dispatchers.IO) { cacheManager.loadDetails(url) }
                    if (cachedDetails != null) {
                        val sanitized = setRawContentSanitized(cachedDetails)
                        applyNgAndPostAsync()
                        // 即時表示後に、キャッシュ由来でも不足メタデータ（画像プロンプト等）を並行取得して段階反映
                        updateMetadataInBackground(sanitized, url)
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
                            setRawContentSanitized(snap)
                            applyNgAndPostAsync()
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
                val sanitizedMerged = setRawContentSanitized(merged)
                val sanitizedProgressive = sanitizePrompts(progressivelyLoadedContent)
                applyNgAndPostAsync()
                _isLoading.value = false

                // バックグラウンドでメタデータを取得し、完了後に段階反映
                updateMetadataInBackground(sanitizedProgressive, url)

            } catch (e: Exception) {
                // ネットワーク失敗時はキャッシュへフォールバック（アーカイブ閲覧時など）
                Log.e("DetailViewModel", "Error fetching details for $url", e)
                val cached = withContext(Dispatchers.IO) { cacheManager.loadDetails(url) }
                if (cached != null) {
                    // 404（dat落ち）相当なら履歴をアーカイブ扱いにする（BG監視OFF時の救済）
                    if (e is IOException && (e.message?.contains("404") == true)) {
                        runCatching { HistoryManager.markArchived(appContext, url) }
                    }
                    val sanitizedCached = setRawContentSanitized(cached)
                    applyNgAndPostAsync()
                    // キャッシュ由来でも画像プロンプト抽出を再試行（オフライン時の復元用）
                    updateMetadataInBackground(sanitizedCached, url)
                    // キャッシュ（ローカル保存済み）からサムネイルを拾って履歴に反映（OPの画像のみ）
                    try {
                        val firstTextIndex = cached.indexOfFirst { it is DetailContent.Text }
                        val media = if (firstTextIndex >= 0) {
                            // OPレスに直接関連付けられた画像/動画のみを取得
                            // parseContentFromDocument と同じロジック：OPの直後で次のTextレスより前のメディアを探す
                            val afterOP = cached.drop(firstTextIndex + 1)
                            val nextTextIndex = afterOP.indexOfFirst { it is DetailContent.Text }
                            val opMediaRange = if (nextTextIndex >= 0) {
                                afterOP.take(nextTextIndex)
                            } else {
                                afterOP
                            }

                            // OPに属する最初のメディアのみを取得（空のURLを持つプレースホルダーは除外）
                            val opMedia = opMediaRange.firstOrNull {
                                when (it) {
                                    is DetailContent.Image -> it.imageUrl.isNotBlank()
                                    is DetailContent.Video -> it.videoUrl.isNotBlank()
                                    else -> false
                                }
                            }

                            // OPレスの番号を確認してより厳密にチェック
                            val opText = cached[firstTextIndex] as DetailContent.Text
                            val opResNum = opText.resNum

                            // メディアのIDがOPレス番号と関連しているかチェック
                            if (opMedia != null && opResNum != null) {
                                val mediaId = when (opMedia) {
                                    is DetailContent.Image -> opMedia.id
                                    is DetailContent.Video -> opMedia.id
                                    else -> null
                                }
                                // IDの末尾がOPレス番号と一致するかチェック
                                if (mediaId != null && mediaId.endsWith("#$opResNum")) {
                                    opMedia
                                } else {
                                    Log.d("DetailViewModel", "Skipping thumbnail - media ID '$mediaId' doesn't match OP resNum '$opResNum'")
                                    null
                                }
                            } else {
                                opMedia
                            }
                        } else null
                        val thumb = when (media) {
                            is DetailContent.Image -> media.imageUrl
                            is DetailContent.Video -> media.videoUrl
                            else -> null
                        }
                        if (!thumb.isNullOrBlank()) {
                            Log.d("DetailViewModel", "Updating thumbnail for OP: $thumb")
                            HistoryManager.updateThumbnail(appContext, url, thumb)
                        } else {
                            Log.d("DetailViewModel", "No OP thumbnail found - clearing history thumbnail")
                            HistoryManager.clearThumbnail(appContext, url)
                        }
                    } catch (_: Exception) {
                        Log.w("DetailViewModel", "Failed to update cached thumbnail for $url")
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
                        try {
                            val firstTextIndex = reconstructed.indexOfFirst { it is DetailContent.Text }
                            val media = if (firstTextIndex >= 0) {
                                // OPレスに直接関連付けられた画像/動画のみを取得
                                val afterOP = reconstructed.drop(firstTextIndex + 1)
                                val nextTextIndex = afterOP.indexOfFirst { it is DetailContent.Text }
                                val opMediaRange = if (nextTextIndex >= 0) {
                                    afterOP.take(nextTextIndex)
                                } else {
                                    afterOP
                                }

                                // OPに属する最初のメディアのみを取得（空のURLを持つプレースホルダーは除外）
                                val opMedia = opMediaRange.firstOrNull {
                                    when (it) {
                                        is DetailContent.Image -> it.imageUrl.isNotBlank()
                                        is DetailContent.Video -> it.videoUrl.isNotBlank()
                                        else -> false
                                    }
                                }

                                // OPレスの番号を確認してより厳密にチェック
                                val opText = reconstructed[firstTextIndex] as DetailContent.Text
                                val opResNum = opText.resNum

                                // メディアのIDがOPレス番号と関連しているかチェック
                                if (opMedia != null && opResNum != null) {
                                    val mediaId = when (opMedia) {
                                        is DetailContent.Image -> opMedia.id
                                        is DetailContent.Video -> opMedia.id
                                        else -> null
                                    }
                                    // IDの末尾がOPレス番号と一致するかチェック
                                    if (mediaId != null && mediaId.endsWith("#$opResNum")) {
                                        opMedia
                                    } else {
                                        Log.d("DetailViewModel", "Skipping archive thumbnail - media ID '$mediaId' doesn't match OP resNum '$opResNum'")
                                        null
                                    }
                                } else {
                                    opMedia
                                }
                            } else null
                            val thumb = when (media) {
                                is DetailContent.Image -> media.imageUrl
                                is DetailContent.Video -> media.videoUrl
                                else -> null
                            }
                            if (!thumb.isNullOrBlank()) {
                                Log.d("DetailViewModel", "Updating archive thumbnail for OP: $thumb")
                                HistoryManager.updateThumbnail(appContext, url, thumb)
                            } else {
                                Log.d("DetailViewModel", "No OP archive thumbnail found - clearing history thumbnail")
                                HistoryManager.clearThumbnail(appContext, url)
                            }
                        } catch (_: Exception) {
                            Log.w("DetailViewModel", "Failed to update archive thumbnail for $url")
                        }
                        val sanitizedReconstructed = setRawContentSanitized(reconstructed)
                        applyNgAndPostAsync()
                        // スナップショット/再構成からも画像プロンプト抽出を実施（file:// を対象）
                        updateMetadataInBackground(sanitizedReconstructed, url)
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
                    val sanitizedNewItems = sanitizePrompts(newItems)
                    val updatedRaw = rawContent + sanitizedNewItems
                    rawContent = updatedRaw
                    withContext(Dispatchers.IO) { cacheManager.saveDetails(url, updatedRaw) }
                    applyNgAndPostAsync()
                    updateMetadataInBackground(sanitizedNewItems, url)
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
    private suspend fun parseContentFromDocument(document: Document, url: String): List<DetailContent> =
        withContext(Dispatchers.Default) {
        val progressivelyLoadedContent = mutableListOf<DetailContent>()
        var itemIdCounter = 0L

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

            if (html.isNotBlank()) {
                // レス番号の抽出: OP はURL末尾、返信は HTML 内の "No."（改行/空白やドットの有無に頑健）から取得
                val resNum = if (isOp) {
                    url.substringAfterLast('/').substringBefore(".htm")
                } else {
                    // Futaba系の "No."（または一部で「No」）に続く数値を安定抽出（改行や余分な空白を許容）
                    NO_PATTERN.find(html)?.groupValues?.getOrNull(2)
                        ?: NO_PATTERN_FALLBACK.find(html)?.groupValues?.getOrNull(1)
                }
                progressivelyLoadedContent.add(
                    DetailContent.Text(id = "text_${itemIdCounter++}", htmlContent = html, resNum = resNum)
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

            // ループ先頭で isOp を見た後に、そのブロックの resNum を必ず計算しておく
            val blockResNum: String? = if (isOp) {
                url.substringAfterLast('/').substringBefore(".htm")
            } else {
                val rtd = block.selectFirst(".rtd")
                val htmlForRes = rtd?.html().orEmpty()
                NO_PATTERN.find(htmlForRes)?.groupValues?.getOrNull(2)
                    ?: NO_PATTERN_FALLBACK.find(htmlForRes)?.groupValues?.getOrNull(1)
            }

            if (mediaLinkNode != null) {
                val link = mediaLinkNode
                val hrefAttr = link.attr("href")
                try {
                    val absoluteUrl = URL(URL(url), hrefAttr).toString()
                    val fileName = absoluteUrl.substringAfterLast('/')

                    // 効率的な拡張子チェック
                    val extension = hrefAttr.substringAfterLast('.', "").lowercase()
                    val mediaContent = when {
                        extension in IMAGE_EXTENSIONS -> {
                            DetailContent.Image(
                                id = "$absoluteUrl#${blockResNum ?: index}",
                                imageUrl = absoluteUrl,
                                prompt = null,
                                fileName = fileName
                            )
                        }
                        extension in VIDEO_EXTENSIONS -> {
                            DetailContent.Video(
                                id = "$absoluteUrl#${blockResNum ?: index}",
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
            } else if (isOp) {
                // OPに画像がない場合は「画像なし」プレースホルダーを追加
                progressivelyLoadedContent.add(
                    DetailContent.Image(
                        id = "no_image_op_${itemIdCounter++}",
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
                DetailContent.ThreadEndTime(id = "thread_end_time_${itemIdCounter++}", endTime = it)
            )
        }

        return@withContext progressivelyLoadedContent.toList()
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
        // バッファサイズを制限してメモリ使用量を制御
        val maxBufferSize = maxOf(100, contentList.size / 10) // 最小100、最大でアイテム数の10%
        val updates = Channel<Pair<String, String?>>(maxBufferSize)
        val sendJobs = mutableListOf<Deferred<Unit>>()

        contentList.forEach { content ->
            when (content) {
                is DetailContent.Image -> {
                    // プロンプト情報が既に存在し、かつキャッシュにも存在する場合のみスキップ
                    if (!content.prompt.isNullOrBlank()) {
                        // キャッシュの存在確認
                        val cachedPrompt = runCatching {
                            MetadataCache(appContext).get(content.imageUrl)
                        }.getOrNull()

                        if (!cachedPrompt.isNullOrBlank()) {
                            Log.d("DetailViewModel", "Skipping metadata extraction for ${content.imageUrl} - has prompt and cache")
                            return@forEach
                        } else {
                            Log.d("DetailViewModel", "Re-extracting metadata for ${content.imageUrl} - prompt exists but no cache")
                        }
                    }

                    markPromptLoading(content.id, true)

                    val limitedIO = Dispatchers.IO.limitedParallelism(AppPreferences.getConcurrencyLevel(appContext))
                    val job = viewModelScope.async(limitedIO) {
                        val prompt = try {
                            /*  画像プロンプトの取得タイムアウト時間はここを変更  */
                            withTimeoutOrNull(15000L) { MetadataExtractor.extract(appContext, content.imageUrl, networkClient) }
                        } catch (e: Exception) {
                            Log.e("DetailViewModel", "Metadata task error for ${content.imageUrl}", e)
                            null
                        }
                        if (prompt == null) {
                            Log.w("DetailViewModel", "Metadata for ${content.imageUrl} was null (timeout or null)")
                        }
                        val normalized = normalizePrompt(prompt)
                        updates.send(content.id to normalized)
                    }
                    job.invokeOnCompletion { throwable ->
                        if (throwable != null) {
                            markPromptLoading(content.id, false)
                        }
                    }
                    sendJobs.add(job)
                }
                is DetailContent.Video -> {
                    // 動画のプロンプト取得は行わない（既にプロンプトがある場合もスキップログを出力）
                    if (!content.prompt.isNullOrBlank()) {
                        Log.d("DetailViewModel", "Skipping metadata extraction for ${content.videoUrl} - already has prompt")
                    }
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

        // 受信・段階反映（即座に反映 + バッチング最適化）
        viewModelScope.launch(Dispatchers.Default) {
            val batch = mutableMapOf<String, String?>()
            var lastFlush = System.currentTimeMillis()
            val flushIntervalMs = 250L // 250msに短縮（より即座な反映）

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
                        markPromptLoading(id, false)
                    }
                    if (changed) {
                        val snapshot = current.toList()
                        // UIは即座に更新
                        withContext(Dispatchers.Main) {
                            _detailContent.value = snapshot
                        }
                        // ディスク保存は非同期で実行
                        withContext(Dispatchers.IO) {
                            runCatching {
                                cacheManager.saveDetails(url, snapshot)
                                // 抽出完了ごとにアーカイブスナップショットも更新（オフライン時の即時反映用）
                                cacheManager.saveArchiveSnapshot(url, snapshot)
                                // 取得済みプロンプトをローカルのアーカイブ画像へも書き戻し（上書き許容）
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
                            }.onFailure { e ->
                                Log.w("DetailViewModel", "Failed to save metadata updates", e)
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
                // より積極的な反映: 3件溜まったら即座に反映
                if (batch.size >= 3) {
                    flush(force = true)
                } else {
                    // 定期的に反映
                    flush(force = false)
                }
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
        if (rules.isEmpty()) {
            postRawContent()
            return
        }

        val filtered = withContext(Dispatchers.Default) {
            filterByNgRulesOptimized(rawContent, rules)
        }

        postFilteredContent(filtered)
    }

    private suspend fun postRawContent() {
        val list = rawContent
        val cache = buildPlainTextCache(list)
        withContext(Dispatchers.Main) {
            _plainTextCache.value = cache
            _detailContent.value = list
            recomputeSearchState()
        }
    }

    private suspend fun postFilteredContent(filtered: List<DetailContent>) {
        val cache = buildPlainTextCache(filtered)
        withContext(Dispatchers.Main) {
            _plainTextCache.value = cache
            _detailContent.value = filtered
            recomputeSearchState()
            // 生データはキャッシュへ保存 + アーカイブスナップショットも保存（オフライン復元用）
            currentUrl?.let { url ->
                val snapshot = filtered
                viewModelScope.launch(Dispatchers.IO) {
                    cacheManager.saveDetails(url, rawContent)
                    cacheManager.saveArchiveSnapshot(url, snapshot)
                }
            }
        }
    }

    // 互換: 既存呼び出し箇所があるため、非suspend版はバックグラウンドで実行
    private fun applyNgAndPost() {
        viewModelScope.launch { applyNgAndPostAsync() }
    }

    /**
     * 既存表示（prior）に含まれるプロンプト等を新規取得（base）へ引き継ぐ。
     * - Image/Video のプロンプト情報を積極的にマージ（既存のプロンプトも保持）。
     * - 照合キーは `fileName` 優先、無い場合は URL 末尾（ファイル名相当）、最後に完全URL。
     */
    private fun mergePrompts(base: List<DetailContent>, prior: List<DetailContent>): List<DetailContent> {
        if (base.isEmpty() || prior.isEmpty()) return base

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

        if (promptByKey.isEmpty()) return base

        return base.map { dc ->
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

    /** NGルールに基づきテキストと直後のメディア列を間引いた一覧を返す（最適化版）。 */
    private suspend fun filterByNgRulesOptimized(src: List<DetailContent>, rules: List<NgRule>): List<DetailContent> {
        val cacheKey = src to rules
        ngFilterCache.get(cacheKey)?.let { return it }

        val result = if (src.size > 100) {
            // CPU性能とリストサイズに応じて動的に並列処理を最適化
            withContext(Dispatchers.Default) {
                val cpuCount = Runtime.getRuntime().availableProcessors()
                val optimalChunkSize = maxOf(50, src.size / (cpuCount * 2)) // CPU数の2倍のチャンクに分割
                val actualChunkSize = minOf(optimalChunkSize, 200) // 最大200件で制限

                src.chunked(actualChunkSize).map { chunk ->
                    async {
                        filterChunk(chunk, rules)
                    }
                }.awaitAll().flatten()
            }
        } else {
            // 小さなリストは単一スレッドで処理
            filterChunk(src, rules)
        }

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
        rawContent = sanitized
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
    private fun recomputeSearchState() {
        val q = currentSearchQuery?.trim().orEmpty()
        searchResultPositions.clear()
        if (q.isBlank()) {
            publishSearchState()
            return
        }
        val contentList = _detailContent.value
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

    private suspend fun buildPlainTextCache(list: List<DetailContent>): Map<String, String> {
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
                withContext(Dispatchers.Main) { _plainTextCache.value = updated }
            }
        }
        return now
    }

    /** 検索UI表示用の集計（アクティブ/現在位置/総数）をフローに反映。 */
    private fun publishSearchState() {
        val active = (currentSearchQuery != null) && searchResultPositions.isNotEmpty()
        val currentDisp = if (active && currentSearchHitIndex in searchResultPositions.indices) currentSearchHitIndex + 1 else 0
        val total = searchResultPositions.size
        _searchState.value = SearchState(active = active, currentIndexDisplay = currentDisp, total = total)
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

        viewModelScope.launch {
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

                                MediaSaver.saveImage(appContext, url, networkClient)

                                synchronized(this@DetailViewModel) {
                                    completed++
                                    _downloadProgress.value = DownloadProgress(completed, urls.size, fileName, true)
                                }
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            } finally {
                delay(500) // 完了表示を少し見せる
                _downloadProgress.value = null
            }
        }
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
                                    MediaSaver.saveImageIfNotExists(appContext, url, networkClient)
                                DownloadConflictResolution.OverwriteExisting -> {
                                    pending.existingByUrl[url]?.let { entries ->
                                        MediaSaver.deleteMedia(appContext, entries)
                                    }
                                    MediaSaver.saveImageIfNotExists(appContext, url, networkClient)
                                }
                            }

                            synchronized(this@DetailViewModel) {
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
}
