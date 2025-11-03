/**
 * スレ詳細画面（Scaffold構成）のCompose実装。
 * - リスト、検索、ダイアログ/シート、広告などの UI を統合します。
 * - 検索や NG フィルタ、メディア操作など多数のハンドラ/状態を受け取って詳細表示を構成します。
 */
package com.valoser.futaburakari.ui.detail

import com.valoser.futaburakari.TtsManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.valoser.futaburakari.DetailContent
import androidx.compose.ui.viewinterop.AndroidView
import com.valoser.futaburakari.ui.detail.FastScroller
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import coil3.imageLoader
import coil3.network.httpHeaders
import coil3.network.NetworkHeaders
import coil3.memory.MemoryCache
import com.valoser.futaburakari.DownloadConflictRequest
import com.valoser.futaburakari.image.ImageKeys
import com.valoser.futaburakari.ui.detail.buildIdPostsItems
import com.valoser.futaburakari.ui.detail.buildResReferencesItems
import com.valoser.futaburakari.ui.theme.LocalSpacing
import com.valoser.futaburakari.ui.common.AppBarPosition

/**
 * スレ詳細の Compose スクリーン（Scaffold）。
 *
 * 機能概要:
 * - リスト: 高速スクロール、プル更新、末尾近辺での追加読込トリガーに対応。
 * - 検索: ドック型の検索バー（遅延サジェスト）と下部の Prev/Next ナビを提供。
 * - ダイアログ/シート:
 *   - ID メニュー（同一IDの投稿 / NGに追加）— ボタン中央寄せ・キャンセルなし。
 *   - 本文/No/ファイル名メニューは DetailList 側で生成（返信/確認/NG）。
 *   - 参照系（No/引用/ファイル名）や同一IDの結果はボトムシートで表示（シート内では更なるクリック操作は無効化して二重遷移を防止）。
 *     リストは `wrapContentHeight()` を用い、外側で `heightIn(max=画面高の約90%)` を掛けることで、
 *     結果が少ない場合でもシートが不必要に伸びないようにする。
 * - 広告: バナーの実測高さを下部インセットとして反映（呼び出し側へ状態通知可能）。
 * - パフォーマンス: ID/No./引用/ファイル名/被引用の集計は `Dispatchers.Default` で実行し、結果のみを状態反映。
 * - メディア: メディア一覧は内部シートで扱い、`onOpenMedia` は互換維持のためのダミーとして引数に残す。
 * - プロンプト: `promptFeaturesEnabled` が false の場合はプロンプト関連のダウンロードメニューを非表示にする。
 * - 低帯域モード: `lowBandwidthMode` が true の場合、表示に使う画像はサムネイルを優先し、タップ時のみフルサイズを開く。
 *   一覧グリッドは可視範囲の前後にあるサムネイルを Coil でプリフェッチし、スクロール直後の表示遅延を低減。
 * - AppBar: 戻る/更新/検索/メディア一覧のアイコンに加え、
 *            右上メニュー（More）から「返信 / NG 管理 / 音声読み上げ / 画像一括DL（通常・プロンプト） / 画像編集（任意）」を提供。
 * - TTS: 音声読み上げの状態共有や制御パネル（再生/一時停止・停止・前後スキップ）を備え、対象レスへの自動スクロールも行う。
 *
 * パラメータ要約:
 * - `title`: AppBar に表示するタイトル。
 * - `onBack`/`onReply`/`onReload`/`onOpenNg`: ナビゲーションと主要アクションのハンドラ（返信/NG はメニューから）。
 * - `onOpenMedia`: 互換維持用（内部でメディアシートを表示するため実体は未使用）。
 * - `onImageEdit`: 画像編集画面への遷移ハンドラ（null の場合は非表示）。
 * - `onSodaneClick`: 「そうだね」押下時のハンドラ（null で非表示）。
 * - `onDeletePost`: 削除要求のハンドラ（レス番/画像のみ指定）。
 * - `onSubmitSearch`/`onDebouncedSearch`/`onClearSearch`: 検索の確定/遅延/クリア時ハンドラ。
 * - `onSearchPrev`/`onSearchNext`: 検索ヒットの前/次へ移動するためのハンドラ。
 * - `onReapplyNgFilter`: NG ルール変更後に再適用するためのフック。
 * - `searchStateFlow`/`searchActiveFlow`/`onSearchActiveChange`: 検索 UI の状態連携。
 * - `bottomOffsetPxFlow`: 既存実装との互換用の下部オフセット（広告等の高さを px で通知）。
 * - `recentSearchesFlow`: 検索サジェスト用の履歴。
 * - `showAds`/`adUnitId`/`onBottomPaddingChange`/`bottomOffsetPxFlow`: 広告や下部パディングの制御。
 * - `threadUrl`: NG ルールの sourceKey 生成向けのスレ URL。
 * - `initialScrollIndex`/`initialScrollOffset`/`onSaveScroll`: スクロール位置の入出力。
 * - `itemsFlow`/`currentQueryFlow`/`isRefreshingFlow`: 本文・検索・更新状態の Flow 連携。
 * - `getSodaneState`/`sodaneUpdates`: 「そうだね」の状態問い合わせとサーバ更新ストリーム。
 * - クリック系: `onQuoteClick`/`onResNumClick`/`onResNumConfirmClick`/`onResNumDelClick`/`onBodyClick`/`onAddNgFromBody`/`onThreadEndTimeClick` など。
 *   - No/ファイル名/本文タップ時は DetailList 側でメニューを表示（ボタン中央寄せ・キャンセルなし）。
 * - `onVisibleMaxOrdinal`: 画面内の最大 ordinal を通知（読み込みや既読管理用）。
 * - `onNearListEnd`: 末尾近辺到達時の通知（無限スクロール用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreenScaffold(
    title: String,
    appBarPosition: AppBarPosition = AppBarPosition.TOP,
    onBack: () -> Unit,
    onReply: () -> Unit,
    onReload: () -> Unit,
    onOpenNg: () -> Unit,
    onOpenMedia: () -> Unit,
    promptFeaturesEnabled: Boolean = true,
    lowBandwidthMode: Boolean = false,
    onImageEdit: (() -> Unit)? = null,
    onSodaneClick: ((String) -> Unit)? = null,
    onDeletePost: (resNum: String, onlyImage: Boolean) -> Unit,
    onSubmitSearch: (String) -> Unit,
    onDebouncedSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
    // NG再適用のためのフック（追加後呼ぶ）
    onReapplyNgFilter: (() -> Unit)? = null,
    searchStateFlow: StateFlow<com.valoser.futaburakari.ui.detail.SearchState>? = null,
    onSearchPrev: (() -> Unit)? = null,
    onSearchNext: (() -> Unit)? = null,
    bottomOffsetPxFlow: StateFlow<Int>? = null,
    searchActiveFlow: StateFlow<Boolean>? = null,
    onSearchActiveChange: ((Boolean) -> Unit)? = null,
    recentSearchesFlow: StateFlow<List<String>>? = null,
    // TTS音声読み上げ関連
    ttsStateFlow: StateFlow<TtsManager.TtsState>? = null,
    ttsCurrentResNumFlow: StateFlow<String?>? = null,
    onTtsStart: (() -> Unit)? = null,
    onTtsPause: (() -> Unit)? = null,
    onTtsResume: (() -> Unit)? = null,
    onTtsStop: (() -> Unit)? = null,
    onTtsSkipNext: (() -> Unit)? = null,
    onTtsSkipPrevious: (() -> Unit)? = null,
    onTtsSetSpeed: ((Float) -> Unit)? = null,
    // Compose専用: 広告バーの表示と高さ通知
    showAds: Boolean = false,
    adUnitId: String? = null,
    onBottomPaddingChange: ((Int) -> Unit)? = null,
    // スレURL（NGルールのsourceKey用）
    threadUrl: String? = null,
    // 自分が投稿したレス番号のセット
    myPostNumbers: Set<String> = emptySet(),
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    initialScrollAnchorId: String? = null,
    onSaveScroll: ((Int, Int, String?) -> Unit)? = null,
    itemsFlow: StateFlow<List<DetailContent>>? = null,
    plainTextCacheFlow: StateFlow<Map<String, String>>? = null,
    onEnsurePlainTextCache: ((List<DetailContent>) -> Unit)? = null,
    plainTextOf: ((DetailContent.Text) -> String)? = null,
    currentQueryFlow: StateFlow<String?>? = null,
    getSodaneState: ((String) -> Boolean)? = null,
    onQuoteClick: ((String) -> Unit)? = null,
    onResNumClick: ((String, String) -> Unit)? = null,
    onResNumConfirmClick: ((String) -> Unit)? = null,
    onResNumDelClick: ((String) -> Unit)? = null,
    onBodyClick: ((String) -> Unit)? = null,
    onAddNgFromBody: ((String) -> Unit)? = null,
    onThreadEndTimeClick: (() -> Unit)? = null,
    onImageLoaded: (() -> Unit)? = null,
    isRefreshingFlow: StateFlow<Boolean>? = null,
    onVisibleMaxOrdinal: ((Int) -> Unit)? = null,
    // 末尾近辺に到達したときに呼ばれる（無限スクロール用）
    onNearListEnd: (() -> Unit)? = null,
    onDownloadImages: ((List<String>) -> Unit)? = null,
    onDownloadImagesSkipExisting: ((List<String>) -> Unit)? = null,
    // ダウンロード進捗状態
    downloadProgressFlow: StateFlow<com.valoser.futaburakari.DownloadProgress?>? = null,
    onCancelDownload: (() -> Unit)? = null,
    downloadConflictFlow: Flow<DownloadConflictRequest>? = null,
    onDownloadConflictSkip: ((Long) -> Unit)? = null,
    onDownloadConflictOverwrite: ((Long) -> Unit)? = null,
    onDownloadConflictCancel: ((Long) -> Unit)? = null,
    // スレッド保存機能
    onArchiveThread: (() -> Unit)? = null,
    archiveProgressFlow: StateFlow<com.valoser.futaburakari.ThreadArchiveProgress?>? = null,
    onCancelArchive: (() -> Unit)? = null,
    // そうだねのサーバ応答（resNum -> count）
    sodaneUpdates: kotlinx.coroutines.flow.Flow<Pair<String, Int>>? = null,
    promptLoadingIdsFlow: StateFlow<Set<String>>? = null,
) {
    var query by remember { mutableStateOf("") }
    val localSearchActive = remember { mutableStateOf(false) }
    val searchActive: Boolean = searchActiveFlow?.collectAsState(initial = false)?.value ?: localSearchActive.value
    val setSearchActive = remember(onSearchActiveChange) {
        { active: Boolean -> onSearchActiveChange?.invoke(active) ?: run { localSearchActive.value = active } }
    }
    val activeQuery = currentQueryFlow?.collectAsStateWithLifecycle(null)?.value

    LaunchedEffect(activeQuery, searchActive) {
        if (!searchActive) {
            query = activeQuery.orEmpty()
        }
    }

    // ダウンロード進捗状態
    val downloadProgress by downloadProgressFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }

    // スレッドアーカイブ進捗状態
    val archiveProgress by archiveProgressFlow?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) }

    val promptLoadingIds: Set<String> = promptLoadingIdsFlow?.collectAsStateWithLifecycle(emptySet())?.value ?: emptySet()

    var downloadConflict by remember { mutableStateOf<DownloadConflictRequest?>(null) }
    LaunchedEffect(downloadConflictFlow) {
        if (downloadConflictFlow == null) {
            downloadConflict = null
            return@LaunchedEffect
        }
        downloadConflictFlow.collectLatest { request ->
            downloadConflict = request
        }
    }

    // 画像一括ダウンロード用のコールバック（重複チェック付き）
    var onBulkDownloadImagesSkipExisting by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onBulkDownloadPromptImagesSkipExisting by remember { mutableStateOf<(() -> Unit)?>(null) }

    // ダイアログ/シート用のUI状態を上位（topBar/本文の両方）で共有できるように保持
    var titleClickPending by remember { mutableStateOf(false) }
    var openMediaSheet by remember { mutableStateOf(false) }
            var idMenuTarget by remember { mutableStateOf<String?>(null) }
            var idSheetItems by remember { mutableStateOf<List<DetailContent>?>(null) }
            var resRefItems by remember { mutableStateOf<List<DetailContent>?>(null) }
            // NG追加（Compose）用の状態
            var pendingNgId by remember { mutableStateOf<String?>(null) }
            var pendingNgBody by remember { mutableStateOf<String?>(null) }

            var selectionMode by remember { mutableStateOf(false) }
            val selectedImages = remember { mutableStateListOf<String>() }
            var jumpToBottomRequest by remember { mutableIntStateOf(0) }

    val toolbarWindowInsets = if (appBarPosition == AppBarPosition.TOP) {
        TopAppBarDefaults.windowInsets
    } else {
        WindowInsets(0, 0, 0, 0)
    }

    // システムナビゲーションバーのインセット（Androidの3ボタンナビゲーション対応）
    val systemBarsInsets = WindowInsets.systemBars
    val navigationBarHeight = systemBarsInsets.getBottom(LocalDensity.current)

    // ボトムバーの高さを保持（検索ナビ・TTSパネルのオフセット計算用）
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }

    val toolbar: @Composable () -> Unit = {
        TopAppBar(
            title = {
                // タイトルクリックで「スレタイ（引用元）＋引用先」を表示
                Text(
                    text = title,
                    maxLines = 2,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { titleClickPending = true }
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { onReply() }) {
                    Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = "返信")
                }
                if (!searchActive && !activeQuery.isNullOrBlank()) {
                    SuggestionChip(
                        modifier = Modifier.padding(end = 8.dp),
                        onClick = {
                            query = activeQuery
                            setSearchActive(true)
                        },
                        icon = { Icon(Icons.Rounded.Search, contentDescription = "検索") },
                        label = { Text(text = activeQuery, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
                IconButton(onClick = { setSearchActive(!searchActive) }) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search")
                }
                IconButton(onClick = onReload) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "再読み込み")
                }
                IconButton(onClick = { openMediaSheet = true }) {
                    Icon(Icons.Rounded.Image, contentDescription = "メディア一覧")
                }
                // 返信/NG/音声読み上げ/一括ダウンロード/画像編集などをオーバーフローメニューに集約
                var moreExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { moreExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false }
                ) {
                    // 基本操作グループ
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("一番下まで飛ぶ") },
                        onClick = {
                            moreExpanded = false
                            jumpToBottomRequest = if (jumpToBottomRequest == Int.MAX_VALUE) 1 else jumpToBottomRequest + 1
                        }
                    )


                    // ダウンロード関連グループ
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("画像一括ダウンロード") },
                        leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = "ダウンロード") },
                        onClick = {
                            moreExpanded = false
                            onBulkDownloadImagesSkipExisting?.invoke()
                        }
                    )
                    if (promptFeaturesEnabled) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("プロンプト付き画像DL") },
                            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = "ダウンロード") },
                            onClick = {
                                moreExpanded = false
                                onBulkDownloadPromptImagesSkipExisting?.invoke()
                            }
                        )
                    }
                    if (onArchiveThread != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("スレッド保存") },
                            leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = "ダウンロード") },
                            onClick = {
                                moreExpanded = false
                                onArchiveThread()
                            }
                        )
                    }


                    // その他機能グループ
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("音声読み上げ") },
                        onClick = { moreExpanded = false; onTtsStart?.invoke() }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("NG 管理") },
                        leadingIcon = { Icon(Icons.Rounded.Block, contentDescription = "NG管理") },
                        onClick = { moreExpanded = false; onOpenNg() }
                    )
                    if (onImageEdit != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("画像編集") },
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = "編集") },
                            onClick = { moreExpanded = false; onImageEdit() }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
            windowInsets = toolbarWindowInsets
        )
    }

    Scaffold(
        topBar = {
            if (appBarPosition == AppBarPosition.TOP) {
                toolbar()
            }
        },
        bottomBar = {
            if (appBarPosition == AppBarPosition.BOTTOM) {
                Column(
                    modifier = Modifier
                        .onGloballyPositioned { coordinates ->
                            bottomBarHeightPx = coordinates.size.height
                        }
                        // システムナビゲーションバーと重ならないようにパディング追加
                        .padding(bottom = with(LocalDensity.current) { navigationBarHeight.toDp() })
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    toolbar()
                }
            }
        }
    ) { contentPadding: PaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val fallbackPlainProvider = remember(plainTextOf) {
                plainTextOf ?: { t: DetailContent.Text -> android.text.Html.fromHtml(t.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT).toString() }
            }
            // UI をブロックしないためのスコープ（重い集計はメインスレッド外で実行）
            val scope = rememberCoroutineScope()
            val ngStore = remember(ctx) { com.valoser.futaburakari.NgStore(ctx) }
            // 上位に持ち上げた（hoisted）「そうだね」表示カウント
            val sodaneCounts = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
            LaunchedEffect(sodaneUpdates) {
                sodaneUpdates?.let { flow ->
                    flow.collect { (rn, count) -> sodaneCounts[rn] = count }
                }
            }
            // 下のダイアログ/シートからも参照できるよう items / listState を上位に保持
            val raw = itemsFlow?.collectAsStateWithLifecycle(emptyList())?.value ?: emptyList()
            val items = remember(raw) { normalizeThreadEndTime(raw) }
            val itemsVersion = remember(items) {
                items.fold(0) { acc, item -> (acc * 31) xor item.id.hashCode() }
            }
            val plainTextCache = plainTextCacheFlow?.collectAsStateWithLifecycle(emptyMap())?.value ?: emptyMap()

            LaunchedEffect(items, plainTextCache) {
                if (onEnsurePlainTextCache != null) {
                    val missing = items.asSequence()
                        .filterIsInstance<DetailContent.Text>()
                        .any { !plainTextCache.containsKey(it.id) }
                    if (missing) {
                        onEnsurePlainTextCache(items)
                    }
                }
            }

            val plainOfProvider = remember(plainTextCache, fallbackPlainProvider) {
                { t: DetailContent.Text -> plainTextCache[t.id] ?: fallbackPlainProvider(t) }
            }

            // 画像一括ダウンロードのコールバックを設定（重複チェック付き）
            LaunchedEffect(items, promptFeaturesEnabled) {
                onBulkDownloadImagesSkipExisting = {
                    val imageUrls = items.filterIsInstance<DetailContent.Image>().map { it.imageUrl }
                    if (imageUrls.isNotEmpty()) {
                        onDownloadImagesSkipExisting?.invoke(imageUrls)
                    }
                }
                onBulkDownloadPromptImagesSkipExisting = if (promptFeaturesEnabled) {
                    {
                        val promptImageUrls = items.filterIsInstance<DetailContent.Image>()
                            .filter { !it.prompt.isNullOrBlank() }
                            .map { it.imageUrl }
                        if (promptImageUrls.isNotEmpty()) {
                            onDownloadImagesSkipExisting?.invoke(promptImageUrls)
                        }
                    }
                } else {
                    null
                }
            }
            // リストと高速スクロールで同じ listState を共有
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialScrollIndex.coerceAtLeast(0),
                initialFirstVisibleItemScrollOffset = initialScrollOffset.coerceAtLeast(0)
            )
            var lastHandledJumpRequest by remember { mutableIntStateOf(0) }
            LaunchedEffect(jumpToBottomRequest, itemsVersion) {
                if (jumpToBottomRequest == 0 || jumpToBottomRequest == lastHandledJumpRequest) return@LaunchedEffect
                if (items.isEmpty()) return@LaunchedEffect
                val targetIndex = items.lastIndex
                if (targetIndex >= 0) {
                    listState.animateScrollToItem(targetIndex)
                    lastHandledJumpRequest = jumpToBottomRequest
                }
            }
            // 検索ナビ（Compose 内でリストに吸着）を上位スコープで保持
            var navPrev by remember { mutableStateOf<(() -> Unit)?>(null) }
            var navNext by remember { mutableStateOf<(() -> Unit)?>(null) }
            var imagesLoadedVersion by remember { mutableIntStateOf(0) }
            val externalOnImageLoaded = rememberUpdatedState(onImageLoaded)
            val handleImageLoaded: () -> Unit = {
                imagesLoadedVersion = if (imagesLoadedVersion == Int.MAX_VALUE) 0 else imagesLoadedVersion + 1
                externalOnImageLoaded.value?.invoke()
            }
            if (itemsFlow != null) {
                val searchQuery = activeQuery
                val refreshing = isRefreshingFlow?.collectAsStateWithLifecycle(false)?.value ?: false
                val pullState = rememberPullToRefreshState()
                var fastScrollActive by remember { mutableStateOf(false) }
                var bottomPaddingVersion by remember { mutableIntStateOf(0) }
                var lastContentIndex by remember { mutableIntStateOf(-1) }
                LaunchedEffect(itemsVersion) {
                    imagesLoadedVersion = 0
                }
                LaunchedEffect(items) {
                    lastContentIndex = withContext(Dispatchers.Default) {
                        items.indexOfLast {
                            when (it) {
                                is com.valoser.futaburakari.DetailContent.Text,
                                is com.valoser.futaburakari.DetailContent.Image,
                                is com.valoser.futaburakari.DetailContent.Video -> true
                                else -> false
                            }
                        }
                    }
                }
                // 下部余白: 既存の Flow があればそれを優先。無ければ広告の実測高さから算出
                val legacyPx = bottomOffsetPxFlow?.collectAsState(initial = 0)?.value
                var adPx by remember { mutableStateOf(0) }
                val bottomPx = legacyPx ?: adPx
                val bottomDp = with(LocalDensity.current) { bottomPx.toDp() }
                LaunchedEffect(bottomPx) {
                    bottomPaddingVersion = if (bottomPaddingVersion == Int.MAX_VALUE) 0 else bottomPaddingVersion + 1
                }
                var deleteTarget by remember { mutableStateOf<String?>(null) }

                // 無限スクロール検知: 末尾のコンテンツ（Text/Image/Video）近辺に到達したら通知。
                // 同一サイズの items に対しては 1 回だけ発火（重複抑止）。
                var lastTriggeredSize by remember { mutableStateOf(-1) }
                LaunchedEffect(items, refreshing, fastScrollActive) {
                    if (refreshing || fastScrollActive) return@LaunchedEffect
                    val li = listState.layoutInfo
                    val lastVisible = li.visibleItemsInfo.lastOrNull()?.index ?: -1
                    if (lastVisible < 0) return@LaunchedEffect
                    val lastContentIndexSnapshot = lastContentIndex
                    if (lastContentIndexSnapshot < 0) return@LaunchedEffect
                    val threshold = 1
                    if (items.size != lastTriggeredSize && lastVisible >= lastContentIndexSnapshot - threshold) {
                        lastTriggeredSize = items.size
                        onNearListEnd?.invoke()
                    }
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    DetailQuickActions(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LocalSpacing.current.s, vertical = LocalSpacing.current.xs),
                        onReload = onReload,
                        onOpenMedia = { openMediaSheet = true },
                        onOpenNg = onOpenNg,
                        onImageEdit = onImageEdit,
                        onTtsStart = onTtsStart
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        PullToRefreshBox(
                            state = pullState,
                            isRefreshing = refreshing,
                            onRefresh = onReload,
                        ) {
                            val endPadding = DefaultFastScrollerWidth + 8.dp
                            DetailListCompose(
                        items = items,
                        searchQuery = searchQuery,
                        threadUrl = threadUrl,
                        useLowBandwidthThumbnails = lowBandwidthMode,
                        myPostNumbers = myPostNumbers,
                        modifier = Modifier
                            .fillMaxSize()
                            .bottomPullRefresh(
                                listState = listState,
                                isRefreshing = refreshing,
                                enabled = items.isNotEmpty(),
                                onRefresh = onReload
                            ),
                        threadTitle = title,
                        promptLoadingIds = promptLoadingIds,
                        plainTextCache = plainTextCache,
                        plainTextOf = plainOfProvider,
                        onQuoteClick = { token ->
                            // 引用トークンがファイル名（xxx.jpg 等）の場合はファイル名参照の集計を優先。
                            val snapshot = items
                            val core = token.trimStart().dropWhile { it == '>' || it == '＞' }.trim()
                            val isFilename = Regex("""(?i)^[A-Za-z0-9._-]+\.(jpg|jpeg|png|gif|webp|bmp|mp4|webm|avi|mov|mkv)$""").matches(core)
                            scope.launch {
                                val list = withContext(Dispatchers.Default) {
                                    if (isFilename) buildFilenameReferencesItems(snapshot, core, plainTextOf = plainOfProvider)
                                    else buildQuoteAndBackrefItems(snapshot, token, threadTitle = title, plainTextOf = plainOfProvider)
                                }
                                if (list.isNotEmpty()) {
                                    resRefItems = list
                                } else {
                                    onQuoteClick?.invoke(token)
                                }
                            }
                        },
                        onSodaneClick = onSodaneClick,
                        onThreadEndTimeClick = onThreadEndTimeClick,
                        onResNumClick = { resNum, resBody ->
                            onResNumClick?.invoke(resNum, resBody)
                        },
                        onResNumConfirmClick = { resNum ->
                            // No. 参照の集計は重いためバックグラウンドで実施し、完了後にシートへ反映
                            val snapshot = items
                            scope.launch {
                                val list = withContext(Dispatchers.Default) {
                                    buildResReferencesItems(snapshot, resNum, plainTextOf = plainOfProvider)
                                }
                                if (list.isNotEmpty()) {
                                    resRefItems = list
                                } else {
                                    // フォールバック（必要なら従来の処理へ委譲）
                                    onResNumConfirmClick?.invoke(resNum)
                                }
                            }
                        },
                        onResNumDelClick = { resNum ->
                            deleteTarget = resNum
                        },
                        onIdClick = { id -> idMenuTarget = id },
                        onBodyClick = onBodyClick,
                        onAddNgFromBody = { body -> pendingNgBody = body },
                        // ファイル名参照の集計もバックグラウンドで実施し、完了後にシートへ反映
                        onFileNameClick = { fn ->
                            val snapshot = items
                            scope.launch {
                                    val list = withContext(Dispatchers.Default) {
                                        buildFilenameReferencesItems(snapshot, fn, plainTextOf = plainOfProvider)
                                    }
                                if (list.isNotEmpty()) {
                                    resRefItems = list
                                }
                            }
                        },
                        onBodyShowBackRefs = { src ->
                            // 本文タップの「被引用」探索も重いためバックグラウンドで実行
                            val snapshot = items
                            scope.launch {
                                val list = withContext(Dispatchers.Default) {
                                    buildSelfAndBackrefItems(snapshot, src, plainTextOf = plainOfProvider)
                                }
                                if (list.isNotEmpty()) {
                                    resRefItems = list
                                } else {
                                    // フォールバック: 何もヒットしなければ何もしない（必要ならToastなど）
                                }
                            }
                        },
                        getSodaneState = getSodaneState,
                        sodaneCounts = sodaneCounts,
                        onSetSodaneCount = { rn, c -> sodaneCounts[rn] = c },
                        onImageLoaded = handleImageLoaded,
                        onVisibleMaxOrdinal = onVisibleMaxOrdinal,
                        listState = listState,
                        initialScrollIndex = initialScrollIndex,
                        initialScrollOffset = initialScrollOffset,
                        initialScrollAnchorId = initialScrollAnchorId,
                        itemsVersion = itemsVersion,
                        bottomPaddingVersion = bottomPaddingVersion,
                        imagesLoadedVersion = imagesLoadedVersion,
                        onSaveScroll = onSaveScroll,
                        // 左端に 8dp の余白を追加
                        contentPadding = PaddingValues(start = LocalSpacing.current.s, end = endPadding, bottom = bottomDp),
                        onProvideSearchNavigator = { p, n ->
                            navPrev = p
                            navNext = n
                        }
                    )
                        }
                    }
                }
                // 削除確認（Compose）
                val pendingDelete = deleteTarget
                if (pendingDelete != null) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { deleteTarget = null },
                        title = { Text(text = "No.$pendingDelete の削除") },
                        text = { Text(text = "削除方法を選択してください") },
                        confirmButton = {
                            Row {
                                androidx.compose.material3.TextButton(onClick = {
                                    onDeletePost(pendingDelete, true)
                                    deleteTarget = null
                                }) { Text("画像のみ削除") }
                                androidx.compose.material3.TextButton(onClick = {
                                    onDeletePost(pendingDelete, false)
                                    deleteTarget = null
                                }) { Text("レスごと削除") }
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") }
                        }
                    )
                }
                // 高速スクロール（右端オーバーレイ）
                FastScroller(
                    modifier = Modifier
                        .align(Alignment.CenterEnd),
                    listState = listState,
                    itemsCount = items.size,
                    bottomPadding = bottomDp,
                    onDragActiveChange = { active -> fastScrollActive = active }
                )
                // 広告バー（Compose）
                if (showAds && adUnitId != null) {
                    Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                        AdBanner(adUnitId = adUnitId) { h ->
                            adPx = h
                            onBottomPaddingChange?.invoke(h)
                        }
                    }
                } else {
                    // 広告非表示時は余白をクリア
                    LaunchedEffect(showAds) {
                        adPx = 0
                        onBottomPaddingChange?.invoke(0)
                    }
                }

                // ダウンロード進捗表示
                downloadProgress?.let { progress ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { progress.current.toFloat() / progress.total.toFloat() },
                                modifier = Modifier.fillMaxWidth(0.8f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${progress.percentage}% (${progress.current}/${progress.total})",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            progress.currentFileName?.let { fileName ->
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            androidx.compose.material3.TextButton(onClick = { onCancelDownload?.invoke() }) {
                                Text("キャンセル")
                            }
                        }
                    }
                }

                // スレッドアーカイブ進捗表示
                archiveProgress?.let { progress ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(16.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "スレッド保存中...",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { progress.current.toFloat() / progress.total.toFloat() },
                                modifier = Modifier.fillMaxWidth(0.8f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${progress.percentage}% (${progress.current}/${progress.total})",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            progress.currentFileName?.let { fileName ->
                                Text(
                                    text = fileName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            androidx.compose.material3.TextButton(onClick = { onCancelArchive?.invoke() }) {
                                Text("キャンセル")
                            }
                        }
                    }
                }

                // Material3 PullToRefreshBox によるインジケータ描画
            }

            // 既存ファイル確認ダイアログ
            downloadConflict?.let { conflict ->
                val existingCount = conflict.existingFiles.size
                val previewLimit = 5
                val previewItems = conflict.existingFiles.take(previewLimit)
                val remainingCount = existingCount - previewItems.size

                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {
                        onDownloadConflictCancel?.invoke(conflict.requestId)
                        downloadConflict = null
                    },
                    title = { Text("保存確認") },
                    text = {
                        Column {
                            Text("${existingCount}件の画像が既に保存されています。")
                            if (conflict.newCount > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("新規保存予定: ${conflict.newCount}件")
                            }
                            if (previewItems.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "対象ファイル",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                previewItems.forEach { file ->
                                    Text(
                                        text = "・${file.fileName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                                if (remainingCount > 0) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "ほか${remainingCount}件",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            downloadConflict = null
                            onDownloadConflictSkip?.invoke(conflict.requestId)
                        }) {
                            Text("新規のみ保存")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            downloadConflict = null
                            onDownloadConflictOverwrite?.invoke(conflict.requestId)
                        }) {
                            Text("すべて上書き保存")
                        }
                    }
                )
            }

            // ID メニュー（Composeダイアログ）
            val idTarget = idMenuTarget
            if (idTarget != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { idMenuTarget = null },
                    title = { Text("ID: $idTarget") },
                    text = { Text("操作を選択してください") },
                    confirmButton = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            androidx.compose.material3.TextButton(onClick = {
                                // 同一IDの投稿一覧をバックグラウンドで作成し、完了後にシートを開く（UIブロックを避ける）
                                val snapshot = items
                                val target = idTarget
                                scope.launch {
                                    val list = withContext(Dispatchers.Default) {
                                        buildIdPostsItems(snapshot, target, plainTextOf = plainOfProvider)
                                    }
                                    idMenuTarget = null
                                    idSheetItems = list
                                }
                            }) { Text("同一IDの投稿") }
                            androidx.compose.material3.TextButton(onClick = {
                                pendingNgId = idTarget
                                idMenuTarget = null
                            }) { Text("NGに追加") }
                        }
                    }
                )
            }

            // ID を NG に追加（確認ダイアログ）
            pendingNgId?.let { toAdd ->
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { pendingNgId = null },
                    title = { Text("IDをNGに追加") },
                    text = { Text("ID: $toAdd をNGにしますか？") },
                    confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                            val source = threadUrl?.let { com.valoser.futaburakari.UrlNormalizer.threadKey(it) }
                            scope.launch(Dispatchers.IO) {
                                val result = runCatching {
                                    ngStore.addRule(
                                        com.valoser.futaburakari.RuleType.ID,
                                        toAdd,
                                        com.valoser.futaburakari.MatchType.EXACT,
                                        sourceKey = source,
                                        ephemeral = true
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    if (result.isSuccess) {
                                        onReapplyNgFilter?.invoke()
                                        android.widget.Toast.makeText(ctx, "追加しました", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        val errorMsg = result.exceptionOrNull()?.message ?: "不明なエラー"
                                        android.widget.Toast.makeText(ctx, "追加に失敗しました: $errorMsg", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    pendingNgId = null
                                }
                            }
                        }) { Text("追加") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { pendingNgId = null }) { Text("キャンセル") }
                    }
                )
            }

            // 本文 NG 追加（入力+マッチ方法）
            pendingNgBody?.let { initial ->
                var text by remember(initial) { mutableStateOf(initial) }
                var match by remember { mutableStateOf(com.valoser.futaburakari.MatchType.SUBSTRING) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { pendingNgBody = null },
                    title = { Text("本文でNG追加") },
                    text = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                label = { Text("含めたくない語句（例: スパム語）") },
                                singleLine = false,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(LocalSpacing.current.s))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                val opt = listOf(
                                    com.valoser.futaburakari.MatchType.SUBSTRING to "部分一致",
                                    com.valoser.futaburakari.MatchType.PREFIX to "前方一致",
                                    com.valoser.futaburakari.MatchType.REGEX to "正規表現",
                                )
                                opt.forEach { (mt, label) ->
                                    androidx.compose.material3.FilterChip(
                                        selected = match == mt,
                                        onClick = { match = mt },
                                        label = { Text(label) },
                                        modifier = Modifier.padding(end = LocalSpacing.current.s)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            val pat = text.trim()
                            if (pat.isEmpty()) {
                                pendingNgBody = null
                                return@TextButton
                            }
                            val source = threadUrl?.let { com.valoser.futaburakari.UrlNormalizer.threadKey(it) }
                            scope.launch(Dispatchers.IO) {
                                val result = runCatching {
                                    ngStore.addRule(
                                        com.valoser.futaburakari.RuleType.BODY,
                                        pat,
                                        match,
                                        sourceKey = source,
                                        ephemeral = true
                                    )
                                }
                                withContext(Dispatchers.Main) {
                                    if (result.isSuccess) {
                                        onReapplyNgFilter?.invoke()
                                        android.widget.Toast.makeText(ctx, "追加しました", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        val errorMsg = result.exceptionOrNull()?.message ?: "不明なエラー"
                                        android.widget.Toast.makeText(ctx, "追加に失敗しました: $errorMsg", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                    pendingNgBody = null
                                }
                            }
                        }) { Text("追加") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { pendingNgBody = null }) { Text("キャンセル") }
                    }
                )
            }

            // 同一ID投稿一覧のシート（Compose）
            // シート内のリストは wrapContentHeight を用い、外側で heightIn(max=画面高の約90%) を掛ける。
            // これにより、内容が少ない場合は内容高さに収まり、不要にシートを引き伸ばせなくなる。
            val idItems = idSheetItems
            if (idItems != null) {
                val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { idSheetItems = null },
                    sheetState = sheetState
                ) {
                    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                    val maxHeight = with(LocalDensity.current) { (screenHeight * 0.9f).dp }
                    // 内容に応じて高さを決め、上限のみ設定
                    androidx.compose.foundation.layout.Box(modifier = Modifier.heightIn(max = maxHeight)) {
                        DetailListCompose(
                            items = idItems,
                            searchQuery = null,
                            threadUrl = threadUrl,
                            useLowBandwidthThumbnails = lowBandwidthMode,
                            modifier = Modifier.wrapContentHeight(),
                            promptLoadingIds = promptLoadingIds,
                            plainTextCache = plainTextCache,
                            plainTextOf = plainOfProvider,
                            onQuoteClick = onQuoteClick,
                            onSodaneClick = null,
                            onThreadEndTimeClick = null,
                            onResNumClick = null,
                            onResNumConfirmClick = null,
                            onResNumDelClick = null,
                            onIdClick = null,
                            onBodyClick = null,
                            onAddNgFromBody = null,
                            getSodaneState = { false },
                            sodaneCounts = emptyMap(),
                            onSetSodaneCount = null,
                            onImageLoaded = handleImageLoaded,
                            onVisibleMaxOrdinal = null,
                            contentPadding = PaddingValues(horizontal = LocalSpacing.current.s, vertical = LocalSpacing.current.s)
                        )
                    }
                }
            }

            // 引用/No./ファイル名参照の一覧シート（Compose）
            // 集計結果があればボトムシートで表示（描画時点では重い処理は完了済み）
            // こちらも wrapContentHeight + heightIn(max=...) で過伸長を抑止
            val refItems = resRefItems
            if (refItems != null) {
                val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { resRefItems = null },
                    sheetState = sheetState
                ) {
                    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                    val maxHeight = with(LocalDensity.current) { (screenHeight * 0.9f).dp }
                    androidx.compose.foundation.layout.Box(modifier = Modifier.heightIn(max = maxHeight)) {
                        DetailListCompose(
                            items = refItems,
                            searchQuery = null,
                            threadUrl = threadUrl,
                            useLowBandwidthThumbnails = lowBandwidthMode,
                            modifier = Modifier.wrapContentHeight(),
                            promptLoadingIds = promptLoadingIds,
                            plainTextCache = plainTextCache,
                            plainTextOf = plainOfProvider,
                            onQuoteClick = onQuoteClick,
                            onSodaneClick = null,
                            onThreadEndTimeClick = null,
                            onResNumClick = null,
                            onResNumConfirmClick = null,
                            onResNumDelClick = null,
                            onIdClick = null,
                            onBodyClick = null,
                            onAddNgFromBody = null,
                            getSodaneState = { false },
                            sodaneCounts = emptyMap(),
                            onSetSodaneCount = null,
                            onImageLoaded = handleImageLoaded,
                            onVisibleMaxOrdinal = null,
                            contentPadding = PaddingValues(horizontal = LocalSpacing.current.s, vertical = LocalSpacing.current.s)
                        )
                    }
            }
        }

        // 本ファイル末尾に補助的なトップレベル関数を定義

        // メディア一覧（Compose ModalBottomSheet）
            if (openMediaSheet) {
                val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
                // シート内の操作用のローカルスコープ（例: クリックで親リストへスクロール）
                val scope = rememberCoroutineScope()
                    androidx.compose.material3.ModalBottomSheet(
                        onDismissRequest = { openMediaSheet = false },
                        sheetState = sheetState
                    ) {
                        if (selectionMode) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = {
                                    selectionMode = false
                                    selectedImages.clear()
                                }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Cancel Selection")
                                }
                                Text(
                                    text = "${selectedImages.size}件選択中",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                IconButton(onClick = {
                                    onDownloadImages?.invoke(selectedImages.toList())
                                    selectionMode = false
                                    selectedImages.clear()
                                }) {
                                    Icon(Icons.Rounded.Download, contentDescription = "Download Selected")
                                }
                            }
                        }
                        // Compose 標準のグリッドで表示
                        val images = remember(items, lowBandwidthMode) {
                            data class Entry(
                                val imageIdx: Int,
                                val parentTextIdx: Int,
                                val fullUrl: String,
                                val previewUrl: String,
                                val prompt: String?
                            )
                            val out = ArrayList<Entry>()
                            // 各画像/動画に対して、直前のTextレスを探して関連付ける
                            for (i in items.indices) {
                                when (val c = items[i]) {
                                    is com.valoser.futaburakari.DetailContent.Image -> {
                                        // 直前のTextレスを探す
                                        val parentTextIdx = (i - 1 downTo 0).firstOrNull { idx ->
                                            items[idx] is com.valoser.futaburakari.DetailContent.Text
                                        } ?: i
                                        val preview = if (lowBandwidthMode) {
                                            c.thumbnailUrl?.takeIf { it.isNotBlank() } ?: c.imageUrl
                                        } else {
                                            c.imageUrl
                                        }
                                        out += Entry(i, parentTextIdx, c.imageUrl, preview, c.prompt)
                                    }
                                    is com.valoser.futaburakari.DetailContent.Video -> {
                                        // 直前のTextレスを探す
                                        val parentTextIdx = (i - 1 downTo 0).firstOrNull { idx ->
                                            items[idx] is com.valoser.futaburakari.DetailContent.Text
                                        } ?: i
                                        val preview = c.thumbnailUrl ?: c.videoUrl
                                        out += Entry(i, parentTextIdx, c.videoUrl, preview, c.prompt)
                                    }
                                    else -> {}
                                }
                            }
                            out
                        }
                        val gridColumns = if (lowBandwidthMode) 2 else 3
                        val cellHeightDp = if (lowBandwidthMode) 160.dp else 110.dp
                    
                    // グリッドの可視範囲を監視し、オフスクリーンを先読み
                    // - 前方12件・後方6件を目安にサムネイルを事前デコード
                    // - セルサイズに近い解像度（幅=画面幅/列数, 高さ=cellHeightDp, Precision.INEXACT）でキャッシュを温める
                    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                    run {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val imageLoader = ctx.imageLoader
                        val prefetched = remember(images) { mutableSetOf<String>() }
                        val config = androidx.compose.ui.platform.LocalConfiguration.current
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val screenWidthPx = remember(config.screenWidthDp, density) {
                            with(density) { config.screenWidthDp.dp.toPx().toInt().coerceAtLeast(1) }
                        }
                        val cellWidthPx = remember(screenWidthPx, gridColumns) { (screenWidthPx / gridColumns).coerceAtLeast(1) }
                        val cellHeightPx = with(density) { cellHeightDp.toPx().toInt().coerceAtLeast(1) }
                        val prefetchAhead = 12
                        val prefetchBack = 6

                        LaunchedEffect(images, gridState) {
                            snapshotFlow { gridState.layoutInfo.visibleItemsInfo }
                                .map { vis ->
                                    val first = vis.minOfOrNull { it.index } ?: 0
                                    val last = vis.maxOfOrNull { it.index } ?: -1
                                    first to last
                                }
                                .distinctUntilChanged()
                                .collectLatest { (first, last) ->
                                    if (images.isEmpty()) return@collectLatest
                                    val startAhead = (last + 1).coerceAtLeast(0)
                                    val endAhead = (last + prefetchAhead).coerceAtMost(images.lastIndex)
                                    val startBack = (first - prefetchBack).coerceAtLeast(0)
                                    val endBack = (first - 1).coerceAtLeast(-1)

                                    fun urlFor(i: Int): String? = images.getOrNull(i)?.previewUrl

                                    // 前方プリフェッチ
                                    for (i in startAhead..endAhead) {
                                        val url = urlFor(i) ?: continue
                                        if (prefetched.add(url)) {
                                            val req = coil3.request.ImageRequest.Builder(ctx)
                                                .data(url)
                                                .apply {
                                                    val ref = threadUrl
                                                    if (!ref.isNullOrBlank()) {
                                                        httpHeaders(
                                                            NetworkHeaders.Builder()
                                                                .add("Referer", ref)
                                                                .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                                                                .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                                                                .build()
                                                        )
                                                    }
                                                }
                                                .size(coil3.size.Size(coil3.size.Dimension.Pixels(cellWidthPx), coil3.size.Dimension.Pixels(cellHeightPx)))
                                                .scale(coil3.size.Scale.FILL)
                                                .precision(coil3.size.Precision.INEXACT)
                                                .memoryCacheKey(ImageKeys.full(url))
                                                .placeholderMemoryCacheKey(ImageKeys.full(url))
                                                .diskCachePolicy(coil3.request.CachePolicy.ENABLED)
                                                .memoryCachePolicy(coil3.request.CachePolicy.ENABLED)
                                                .networkCachePolicy(coil3.request.CachePolicy.ENABLED)
                                                .build()
                                            imageLoader.enqueue(req)
                                        }
                                    }

                                    // 後方（少しだけ戻り）のプリフェッチ
                                    if (endBack >= startBack) {
                                        for (i in startBack..endBack) {
                                            val url = urlFor(i) ?: continue
                                            if (prefetched.add(url)) {
                                                val req = coil3.request.ImageRequest.Builder(ctx)
                                                    .data(url)
                                                    .apply {
                                                        val ref = threadUrl
                                                        if (!ref.isNullOrBlank()) {
                                                            httpHeaders(
                                                                NetworkHeaders.Builder()
                                                                    .add("Referer", ref)
                                                                    .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                                                                    .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                                                                    .build()
                                                            )
                                                        }
                                                    }
                                                    .size(coil3.size.Size(coil3.size.Dimension.Pixels(cellWidthPx), coil3.size.Dimension.Pixels(cellHeightPx)))
                                                    .scale(coil3.size.Scale.FILL)
                                                    .precision(coil3.size.Precision.INEXACT)
                                                    .memoryCacheKey(ImageKeys.full(url))
                                                    .placeholderMemoryCacheKey(ImageKeys.full(url))
                                                    .diskCachePolicy(coil3.request.CachePolicy.ENABLED)
                                                    .memoryCachePolicy(coil3.request.CachePolicy.ENABLED)
                                                    .networkCachePolicy(coil3.request.CachePolicy.ENABLED)
                                                    .build()
                                                imageLoader.enqueue(req)
                                            }
                                        }
                                    }
                                }
                        }
                    }
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(gridColumns),
                        state = gridState,
                        contentPadding = PaddingValues(LocalSpacing.current.s)
                    ) {
                        items(images.size) { idx ->
                            val e = images[idx]
                            // Gridセルと同一のサイズ指定でリクエストし、プリフェッチとキャッシュキーを一致させる
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            val config = androidx.compose.ui.platform.LocalConfiguration.current
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx().toInt().coerceAtLeast(1) }
                            val cellWidthPx = (screenWidthPx / gridColumns).coerceAtLeast(1)
                            val cellHeightPx = with(density) { cellHeightDp.toPx().toInt().coerceAtLeast(1) }

                            val request = coil3.request.ImageRequest.Builder(ctx)
                                .data(e.previewUrl)
                                .apply {
                                    val ref = threadUrl
                                    if (!ref.isNullOrBlank()) {
                                        httpHeaders(
                                            NetworkHeaders.Builder()
                                                .add("Referer", ref)
                                                .build()
                                        )
                                    }
                                }
                                .size(coil3.size.Size(coil3.size.Dimension.Pixels(cellWidthPx), coil3.size.Dimension.Pixels(cellHeightPx)))
                                .scale(coil3.size.Scale.FILL)
                                .precision(coil3.size.Precision.INEXACT)
                                .memoryCacheKey(ImageKeys.full(e.previewUrl))
                                .placeholderMemoryCacheKey(ImageKeys.full(e.previewUrl))
                                .diskCachePolicy(coil3.request.CachePolicy.ENABLED)
                                .memoryCachePolicy(coil3.request.CachePolicy.ENABLED)
                                .build()

                            val isSelected = selectedImages.contains(e.fullUrl)

                            Box(modifier = Modifier.padding(LocalSpacing.current.xs)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onLongPress = {
                                            selectionMode = true
                                            if (!selectedImages.contains(e.fullUrl)) {
                                                selectedImages.add(e.fullUrl)
                                            }
                                        },
                                        onTap = {
                                            if (selectionMode) {
                                                val alreadySelected = selectedImages.contains(e.fullUrl)
                                                if (alreadySelected) {
                                                    selectedImages.remove(e.fullUrl)
                                                } else {
                                                    selectedImages.add(e.fullUrl)
                                                }
                                            } else {
                                                scope.launch { listState.scrollToItem(e.parentTextIdx) }
                                                openMediaSheet = false
                                            }
                                        }
                                    )
                                }
                            ) {
                                coil3.compose.SubcomposeAsyncImage(
                                    model = request,
                                    imageLoader = ctx.imageLoader,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(cellHeightDp),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    loading = {
                                        androidx.compose.foundation.layout.Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(cellHeightDp)
                                        ) {
                                            androidx.compose.material3.CircularProgressIndicator(
                                                modifier = Modifier
                                                    .align(androidx.compose.ui.Alignment.Center)
                                            )
                                        }
                                    }
                                )
                                if (!e.prompt.isNullOrBlank()) {
                                    Text(
                                        text = "AI",
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .background(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                                shape = RoundedCornerShape(bottomStart = 4.dp)
                                            )
                                            .padding(horizontal = 4.dp, vertical = 2.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                if (isSelected) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.align(Alignment.Center)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 検索バー（DockedSearchBar）: 虫眼鏡で表示/非表示をトグル
            // ボトムバー時は下部に配置
            if (searchActive) {
                val searchBarAlignment = if (appBarPosition == AppBarPosition.BOTTOM) Alignment.BottomCenter else Alignment.TopCenter
                val searchBarBottomPadding = if (appBarPosition == AppBarPosition.BOTTOM) {
                    // ボトムバーの高さとシステムナビゲーションバーの高さを考慮
                    with(LocalDensity.current) { (bottomBarHeightPx + navigationBarHeight).toDp() }
                } else {
                    0.dp
                }

                DockedSearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = {
                                val q = query.trim()
                                if (q.isNotEmpty()) onSubmitSearch(q) else onClearSearch()
                            },
                            expanded = true,
                            onExpandedChange = { active -> setSearchActive(active) },
                            placeholder = { Text("検索キーワード") },
                            leadingIcon = {
                                Icon(imageVector = Icons.Rounded.Search, contentDescription = "検索")
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = {
                                            query = ""
                                            onClearSearch()
                                        }) {
                                            Icon(
                                                imageVector = Icons.Rounded.Clear,
                                                contentDescription = "検索条件をクリア"
                                            )
                                        }
                                    }
                                    IconButton(onClick = { setSearchActive(false) }) {
                                        Icon(
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "検索を閉じる"
                                        )
                                    }
                                }
                            },
                            colors = SearchBarDefaults.inputFieldColors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                cursorColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    },
                    expanded = true,
                    onExpandedChange = { active -> setSearchActive(active) },
                    modifier = Modifier
                        .align(searchBarAlignment)
                        .padding(horizontal = LocalSpacing.current.s)
                        .padding(bottom = searchBarBottomPadding),
                    colors = SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    // 候補表示: クイックフィルタ + 最近の検索
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 入力のライブ検索（デバウンス）
                        LaunchedEffect(query, searchActive) {
                            if (searchActive) {
                                val q = query.trim()
                                if (q.isNotEmpty()) {
                                    delay(300)
                                    onDebouncedSearch(q)
                                } else {
                                    onClearSearch()
                                }
                            }
                        }
                        Row(modifier = Modifier.padding(horizontal = LocalSpacing.current.s, vertical = LocalSpacing.current.xs)) {
                            QuickFilterChip(label = "画像", onClick = {
                                query = "画像"
                                onDebouncedSearch("画像")
                            })
                            Spacer(Modifier.width(LocalSpacing.current.s))
                            QuickFilterChip(label = "動画", onClick = {
                                query = "動画"
                                onDebouncedSearch("動画")
                            })
                            Spacer(Modifier.width(LocalSpacing.current.s))
                            QuickFilterChip(label = "No.", onClick = {
                                query = "No."
                                onDebouncedSearch("No.")
                            })
                        }
                        Spacer(Modifier.height(LocalSpacing.current.xs))
                        val recent = recentSearchesFlow?.collectAsState(initial = emptyList())?.value ?: emptyList()
                        if (recent.isNotEmpty()) {
                            Text(
                                text = "最近の検索",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = LocalSpacing.current.m, vertical = LocalSpacing.current.xs)
                            )
                            LazyColumn {
                                items(recent) { item ->
                                    androidx.compose.material3.ListItem(
                                        headlineContent = { Text(item) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                query = item
                                                onSubmitSearch(item)
                                                setSearchActive(false)
                                            }
                                            .padding(horizontal = LocalSpacing.current.xs),
                                        leadingContent = {
                                            Icon(Icons.Rounded.Search, contentDescription = "検索")
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // TTS制御パネル
            if (ttsStateFlow != null) {
                val ttsState by ttsStateFlow.collectAsStateWithLifecycle(TtsManager.TtsState.Idle)
                val ttsCurrentResNum by ttsCurrentResNumFlow?.collectAsStateWithLifecycle(null) ?: remember { mutableStateOf(null) }

                // TTS再生中のレス番号が変わったら自動スクロール
                LaunchedEffect(ttsCurrentResNum, items) {
                    if (ttsCurrentResNum != null && (ttsState is TtsManager.TtsState.Playing || ttsState is TtsManager.TtsState.Paused)) {
                        val targetIndex = items.indexOfFirst {
                            it is DetailContent.Text && it.resNum == ttsCurrentResNum
                        }
                        if (targetIndex >= 0) {
                            listState.animateScrollToItem(targetIndex)
                        }
                    }
                }

                if (ttsState is TtsManager.TtsState.Playing || ttsState is TtsManager.TtsState.Paused) {
                    val bottomPx = bottomOffsetPxFlow?.collectAsState(initial = 0)?.value ?: 0
                    // ボトムバーの高さとシステムナビゲーションバーの高さも考慮
                    val totalBottomPx = bottomPx +
                        if (appBarPosition == AppBarPosition.BOTTOM) bottomBarHeightPx + navigationBarHeight else 0
                    val bottomDp = with(LocalDensity.current) { totalBottomPx.toDp() }
                    TtsControlPanel(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = LocalSpacing.current.m, vertical = LocalSpacing.current.s)
                            .padding(bottom = bottomDp),
                        isPlaying = ttsState is TtsManager.TtsState.Playing,
                        currentResNum = ttsCurrentResNum,
                        onPlayPause = {
                            if (ttsState is TtsManager.TtsState.Playing) {
                                onTtsPause?.invoke()
                            } else {
                                onTtsResume?.invoke()
                            }
                        },
                        onStop = { onTtsStop?.invoke() },
                        onSkipPrevious = { onTtsSkipPrevious?.invoke() },
                        onSkipNext = { onTtsSkipNext?.invoke() }
                    )
                }
            }

            // 検索ナビ（↓↑ と 件数表示）— 従来の下部UI相当をComposeで重ねる
            if (searchStateFlow != null) {
                val s by searchStateFlow.collectAsStateWithLifecycle(com.valoser.futaburakari.ui.detail.SearchState(false, 0, 0))
                if (s.active) {
                    val bottomPx = bottomOffsetPxFlow?.collectAsState(initial = 0)?.value ?: 0
                    // ボトムバーの高さとシステムナビゲーションバーの高さも考慮
                    val totalBottomPx = bottomPx +
                        if (appBarPosition == AppBarPosition.BOTTOM) bottomBarHeightPx + navigationBarHeight else 0
                    val bottomDp = with(LocalDensity.current) { totalBottomPx.toDp() }
                    SearchNavigationBar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = LocalSpacing.current.m, vertical = LocalSpacing.current.s)
                            .padding(bottom = bottomDp),
                        current = s.currentIndexDisplay,
                        total = s.total,
                        // VM側のコールバックがあればそれも呼びつつ、ローカルナビゲータでスクロール
                        onPrev = {
                            onSearchPrev?.invoke()
                            navPrev?.invoke()
                        },
                        onNext = {
                            onSearchNext?.invoke()
                            navNext?.invoke()
                        }
                    )
                }
                // タイトルクリック要求: items が利用可能なタイミングで処理し、成功時にだけフラグを落とす
                LaunchedEffect(titleClickPending, items) {
                    if (titleClickPending) {
                        val firstIdx = items.indexOfFirst { it is DetailContent.Text }
                        if (firstIdx >= 0) {
                            val src = items[firstIdx] as DetailContent.Text
                            val snapshot = items
                            // 1) OP（引用元）＋タイトル内容での引用先（内容一致）
                            val byContent = withContext(Dispatchers.Default) {
                                buildSelfAndBackrefItems(snapshot, src, extraCandidates = setOf(title), plainTextOf = plainOfProvider)
                            }
                            // 2) OP の No. を使った引用先（>>No など番号参照）
                            val rn = src.resNum
                            val byNumber = if (!rn.isNullOrBlank()) {
                                withContext(Dispatchers.Default) {
                                    buildResReferencesItems(snapshot, rn, plainTextOf = plainOfProvider)
                                }
                            } else emptyList()
                            // 3) 結合 + 重複排除（表示順は byContent → byNumber）
                            if (byContent.isNotEmpty() || byNumber.isNotEmpty()) {
                                val seen = HashSet<String>()
                                val merged = ArrayList<DetailContent>(byContent.size + byNumber.size)
                                for (c in byContent + byNumber) if (seen.add(c.id)) merged += c
                                resRefItems = merged
                            }
                            titleClickPending = false
                        }
                    }
                }
            }
        }
    }
}

/**
 * シンプルなバナー広告ホスト（Google Mobile Ads の `AdView`）。
 * - 実測高さを `onHeightChanged` で通知し、レイアウト側で下部パディングに反映できるようにする。
 * - 幅は親に追従し、高さは選択した AdSize に依存する。
 */
@Composable
private fun AdBanner(adUnitId: String, onHeightChanged: (Int) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            com.google.android.gms.ads.AdView(ctx).apply {
                setAdSize(com.google.android.gms.ads.AdSize.BANNER)
                this.adUnitId = adUnitId
                loadAd(com.google.android.gms.ads.AdRequest.Builder().build())
                viewTreeObserver.addOnGlobalLayoutListener {
                    onHeightChanged(measuredHeight)
                }
                
            }
        },
        update = { v: com.google.android.gms.ads.AdView -> onHeightChanged(v.measuredHeight) }
    )
}

/**
 * スレ内アイテムのうち `ThreadEndTime` を最後の 1 件だけ残すよう正規化する。
 * それ以外のアイテムの相対順序は維持する。
 */
private fun normalizeThreadEndTime(src: List<DetailContent>): List<DetailContent> {
    val endIdxs = src.withIndex().filter { it.value is DetailContent.ThreadEndTime }.map { it.index }
    if (endIdxs.isEmpty()) return src
    val keep = endIdxs.last()
    val out = ArrayList<DetailContent>(src.size - (endIdxs.size - 1))
    for ((i, item) in src.withIndex()) {
        if (item is DetailContent.ThreadEndTime) {
            if (i == keep) out += item
        } else out += item
    }
    return out
}

/**
 * 検索用ナビゲーションバー（下部オーバーレイ）。
 * 現在位置/総ヒット数を表示し、矢印押下で `onPrev` / `onNext` を呼び出す。
 */
@Composable
private fun SearchNavigationBar(
    modifier: Modifier = Modifier,
    current: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = androidx.compose.material3.MaterialTheme.shapes.medium
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .padding(horizontal = LocalSpacing.current.s, vertical = LocalSpacing.current.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Prev")
            }
            Text(
                text = if (total > 0 && current in 1..total) "$current/$total" else "0/0",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = LocalSpacing.current.s)
            )
            IconButton(onClick = onNext) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next")
            }
        }
    }
}

/**
 * ドック型検索 UI 内で使う簡易サジェスト用の AssistChip。
 */
@Composable
private fun QuickFilterChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/**
 * TTS音声読み上げ制御パネル（下部オーバーレイ）。
 * 再生/一時停止、停止、スキップボタンと現在読み上げ中のレス番号を表示。
 */
@Composable
private fun TtsControlPanel(
    modifier: Modifier = Modifier,
    isPlaying: Boolean,
    currentResNum: String?,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
) {
    androidx.compose.material3.Surface(
        modifier = modifier,
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = androidx.compose.material3.MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = LocalSpacing.current.s, vertical = LocalSpacing.current.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.xs)
        ) {
            IconButton(onClick = onSkipPrevious) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Previous"
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Block else Icons.AutoMirrored.Rounded.Reply,
                    contentDescription = if (isPlaying) "Pause" else "Play"
                )
            }
            IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Stop"
                )
            }
            IconButton(onClick = onSkipNext) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Next"
                )
            }
            if (currentResNum != null) {
                Text(
                    text = "No.$currentResNum",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = LocalSpacing.current.s)
                )
            }
        }
    }
}

@Composable
private fun DetailQuickActions(
    modifier: Modifier = Modifier,
    onReload: () -> Unit,
    onOpenMedia: () -> Unit,
    onOpenNg: () -> Unit,
    onImageEdit: (() -> Unit)?,
    onTtsStart: (() -> Unit)?,
) {
    val spacing = LocalSpacing.current
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier.horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(spacing.s)
    ) {
        FilledTonalButton(onClick = onReload) {
            Icon(Icons.Rounded.Refresh, contentDescription = "再読み込み", modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(spacing.xs))
            Text("再読み込み")
        }
        FilledTonalButton(onClick = onOpenMedia) {
            Icon(Icons.Rounded.Image, contentDescription = "メディア一覧", modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(spacing.xs))
            Text("メディア一覧")
        }
        OutlinedButton(onClick = onOpenNg) {
            Icon(Icons.Rounded.Block, contentDescription = "NG管理", modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(spacing.xs))
            Text("NG管理")
        }
        if (onImageEdit != null) {
            OutlinedButton(onClick = onImageEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = "画像編集", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(spacing.xs))
                Text("画像編集")
            }
        }
        if (onTtsStart != null) {
            OutlinedButton(onClick = onTtsStart) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "読み上げ", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(spacing.xs))
                Text("読み上げ")
            }
        }
    }
}

private fun Modifier.bottomPullRefresh(
    listState: LazyListState,
    isRefreshing: Boolean,
    enabled: Boolean,
    onRefresh: () -> Unit,
    triggerDistance: Dp = 72.dp,
): Modifier = composed {
    if (!enabled) {
        return@composed this
    }
    val refreshed = rememberUpdatedState(onRefresh)
    val refreshingState = rememberUpdatedState(isRefreshing)
    val triggerPx = with(LocalDensity.current) { triggerDistance.toPx() }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    var gestureTriggered by remember { mutableStateOf(false) }

    val reset: () -> Unit = {
        dragAccumulated = 0f
        gestureTriggered = false
    }

    LaunchedEffect(refreshingState.value) {
        if (!refreshingState.value) {
            reset()
        }
    }

    val connection = remember(listState, triggerPx, enabled) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabled) {
                    reset()
                    return Offset.Zero
                }
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y >= 0f) {
                    reset()
                    return Offset.Zero
                }
                if (!listState.isScrolledToEnd()) {
                    reset()
                    return Offset.Zero
                }
                dragAccumulated += -available.y
                if (!gestureTriggered && !refreshingState.value && dragAccumulated >= triggerPx) {
                    gestureTriggered = true
                    refreshed.value.invoke()
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!enabled) {
                    reset()
                    return Offset.Zero
                }
                if (source == NestedScrollSource.UserInput) {
                    val changedDirection = available.y > 0f || consumed.y > 0f
                    if (changedDirection || !listState.isScrolledToEnd()) {
                        reset()
                    }
                } else if (!listState.isScrolledToEnd()) {
                    reset()
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                reset()
                return Velocity.Zero
            }
        }
    }

    this.nestedScroll(connection)
}

private fun LazyListState.isScrolledToEnd(): Boolean {
    val layout = layoutInfo
    if (layout.totalItemsCount == 0) return false
    val lastVisible = layout.visibleItemsInfo.lastOrNull() ?: return false
    if (lastVisible.index < layout.totalItemsCount - 1) return false
    val effectiveEnd = (layout.viewportEndOffset - layout.afterContentPadding)
        .coerceAtMost(layout.viewportEndOffset)
        .coerceAtLeast(0)
    val itemEnd = lastVisible.offset + lastVisible.size
    return itemEnd >= effectiveEnd
}
