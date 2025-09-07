package com.valoser.futaburakari.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Reply
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.DetailContent
import androidx.compose.ui.viewinterop.AndroidView
import com.valoser.futaburakari.ui.detail.FastScroller
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import com.valoser.futaburakari.ui.detail.buildIdPostsItems
import com.valoser.futaburakari.ui.detail.buildResReferencesItems

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
 * - 広告: バナーの実測高さを下部インセットとして反映（呼び出し側へ状態通知可能）。
 * - パフォーマンス: ID/No./引用/ファイル名/被引用の集計は `Dispatchers.Default` で実行し、結果のみを状態反映。
 * - メディア: メディア一覧は内部シートで扱い、`onOpenMedia` は互換維持のためのダミーとして引数に残す。
 * - AppBar: 戻る/更新/検索/メディア一覧のアイコンに加え、
 *            右上メニュー（More）から「返信 → NG 管理 → 画像編集（任意）」を提供。
 *
 * パラメータ要約:
 * - `title`: AppBar に表示するタイトル。
 * - `onBack`/`onReply`/`onReload`/`onOpenNg`: ナビゲーションと主要アクションのハンドラ（返信/NG はメニューから）。
 * - `onOpenMedia`: 互換維持用（内部でメディアシートを表示するため実体は未使用）。
 * - `onImageEdit`: 画像編集画面への遷移ハンドラ（null の場合は非表示）。
 * - `onSodaneClick`: 「そうだね」押下時のハンドラ（null で非表示）。
 * - `onDeletePost`: 削除要求のハンドラ（レス番/画像のみ指定）。
 * - `onSubmitSearch`/`onDebouncedSearch`/`onClearSearch`: 検索の確定/遅延/クリア時ハンドラ。
 * - `onReapplyNgFilter`: NG ルール変更後に再適用するためのフック。
 * - `searchStateFlow`/`searchActiveFlow`/`onSearchActiveChange`: 検索 UI の状態連携。
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
    onBack: () -> Unit,
    onReply: () -> Unit,
    onReload: () -> Unit,
    onOpenNg: () -> Unit,
    onOpenMedia: () -> Unit,
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
    // Compose専用: 広告バーの表示と高さ通知
    showAds: Boolean = false,
    adUnitId: String? = null,
    onBottomPaddingChange: ((Int) -> Unit)? = null,
    // スレURL（NGルールのsourceKey用）
    threadUrl: String? = null,
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    onSaveScroll: ((Int, Int) -> Unit)? = null,
    itemsFlow: StateFlow<List<DetailContent>>? = null,
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
    // そうだねのサーバ応答（resNum -> count）
    sodaneUpdates: kotlinx.coroutines.flow.Flow<Pair<String, Int>>? = null,
) {
    var query by remember { mutableStateOf("") }
    var reportTarget by remember { mutableStateOf<String?>(null) }
    val localSearchActive = remember { mutableStateOf(false) }
    val searchActive: Boolean = searchActiveFlow?.collectAsState(initial = false)?.value ?: localSearchActive.value
    val setSearchActive = remember(onSearchActiveChange) {
        { active: Boolean -> onSearchActiveChange?.invoke(active) ?: run { localSearchActive.value = active } }
    }

    // ダイアログ/シート用のUI状態を上位（topBar/本文の両方）で共有できるように保持
    var titleClickPending by remember { mutableStateOf(false) }
    var openMediaSheet by remember { mutableStateOf(false) }
            var idMenuTarget by remember { mutableStateOf<String?>(null) }
            var idSheetItems by remember { mutableStateOf<List<DetailContent>?>(null) }
            var resRefItems by remember { mutableStateOf<List<DetailContent>?>(null) }
            // NG追加（Compose）用の状態
            var pendingNgId by remember { mutableStateOf<String?>(null) }
            var pendingNgBody by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
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
                IconButton(onClick = onReload) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Reload")
                }
                IconButton(onClick = { setSearchActive(!searchActive) }) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search")
                }
                // メディア一覧はComposeのシートで内製。互換のためコールバック引数は保持
                IconButton(onClick = { openMediaSheet = true }) {
                    Icon(Icons.Rounded.Image, contentDescription = "Media List")
                }
                // 返信/NG/画像編集をオーバーフローメニューに集約
                var moreExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { moreExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = moreExpanded,
                    onDismissRequest = { moreExpanded = false }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("返信") },
                        onClick = { moreExpanded = false; onReply() }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("NG 管理") },
                        onClick = { moreExpanded = false; onOpenNg() }
                    )
                    if (onImageEdit != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("画像編集") },
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
                )
            )
        }
    ) { contentPadding: PaddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
        ) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            // UI をブロックしないためのスコープ（重い集計はメインスレッド外で実行）
            val scope = rememberCoroutineScope()
            val ngStore = remember(ctx) { com.valoser.futaburakari.NgStore(ctx) }
            // Hoisted "そうだね" 表示カウント
            val sodaneCounts = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
            LaunchedEffect(sodaneUpdates) {
                sodaneUpdates?.let { flow ->
                    flow.collect { (rn, count) -> sodaneCounts[rn] = count }
                }
            }
            // 下のダイアログ/シートからも参照できるよう items / listState を上位に保持
            val raw = itemsFlow?.collectAsStateWithLifecycle(emptyList())?.value ?: emptyList()
            val items = remember(raw) { normalizeThreadEndTime(raw) }
            // リストと高速スクロールで同じ listState を共有
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialScrollIndex.coerceAtLeast(0),
                initialFirstVisibleItemScrollOffset = initialScrollOffset.coerceAtLeast(0)
            )
            // 検索ナビ（Compose 内でリストに吸着）を上位スコープで保持
            var navPrev by remember { mutableStateOf<(() -> Unit)?>(null) }
            var navNext by remember { mutableStateOf<(() -> Unit)?>(null) }
            if (itemsFlow != null) {
                val searchQuery = currentQueryFlow?.collectAsStateWithLifecycle(null)?.value
                val refreshing = isRefreshingFlow?.collectAsStateWithLifecycle(false)?.value ?: false
                val pullState = rememberPullToRefreshState()
                var fastScrollActive by remember { mutableStateOf(false) }
                // 下部余白: 既存の Flow があればそれを優先。無ければ広告の実測高さから算出
                val legacyPx = bottomOffsetPxFlow?.collectAsState(initial = 0)?.value
                var adPx by remember { mutableStateOf(0) }
                val bottomPx = legacyPx ?: adPx
                val bottomDp = with(LocalDensity.current) { bottomPx.toDp() }
                var deleteTarget by remember { mutableStateOf<String?>(null) }

                // 無限スクロール検知: 末尾のコンテンツ（Text/Image/Video）近辺に到達したら通知。
                // 同一サイズの items に対しては 1 回だけ発火（重複抑止）。
                var lastTriggeredSize by remember { mutableStateOf(-1) }
                LaunchedEffect(items, refreshing, fastScrollActive) {
                    if (refreshing || fastScrollActive) return@LaunchedEffect
                    val li = listState.layoutInfo
                    val lastVisible = li.visibleItemsInfo.lastOrNull()?.index ?: -1
                    if (lastVisible < 0) return@LaunchedEffect
                    // 実質末尾（ThreadEndTime を除く）
                    val lastContentIndex = run {
                        var idx = -1
                        for (i in items.indices.reversed()) {
                            when (items[i]) {
                                is com.valoser.futaburakari.DetailContent.Text,
                                is com.valoser.futaburakari.DetailContent.Image,
                                is com.valoser.futaburakari.DetailContent.Video -> { idx = i; break }
                                else -> {}
                            }
                        }
                        idx
                    }
                    if (lastContentIndex < 0) return@LaunchedEffect
                    val threshold = 1
                    if (items.size != lastTriggeredSize && lastVisible >= lastContentIndex - threshold) {
                        lastTriggeredSize = items.size
                        onNearListEnd?.invoke()
                    }
                }
                PullToRefreshBox(
                    state = pullState,
                    isRefreshing = refreshing,
                    onRefresh = onReload,
                ) {
                    val endPadding = DefaultFastScrollerWidth + 8.dp
                    DetailListCompose(
                        items = items,
                        searchQuery = searchQuery,
                        threadTitle = title,
                        onQuoteClick = { token ->
                            // 引用トークンがファイル名（xxx.jpg 等）の場合はファイル名参照の集計を優先。
                            val snapshot = items
                            val core = token.trimStart().dropWhile { it == '>' || it == '＞' }.trim()
                            val isFilename = Regex("""(?i)^[A-Za-z0-9._-]+\.(jpg|jpeg|png|gif|webp|bmp|mp4|webm|avi|mov|mkv)$""").matches(core)
                            scope.launch {
                                val list = withContext(Dispatchers.Default) {
                                    if (isFilename) buildFilenameReferencesItems(snapshot, core)
                                    else buildQuoteAndBackrefItems(snapshot, token, threadTitle = title)
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
                            if (resBody.isEmpty()) deleteTarget = resNum else onResNumClick?.invoke(resNum, resBody)
                        },
                        onResNumConfirmClick = { resNum ->
                            // No. 参照の集計は重いためバックグラウンドで実施し、完了後にシートへ反映
                            val snapshot = items
                            scope.launch {
                                val list = withContext(Dispatchers.Default) {
                                    buildResReferencesItems(snapshot, resNum)
                                }
                                if (list.isNotEmpty()) {
                                    resRefItems = list
                                } else {
                                    // フォールバック（必要なら従来の処理へ委譲）
                                    onResNumConfirmClick?.invoke(resNum)
                                }
                            }
                        },
                        onResNumDelClick = { resNum -> reportTarget = resNum },
                        onIdClick = { id -> idMenuTarget = id },
                        onBodyClick = onBodyClick,
                        onAddNgFromBody = { body -> pendingNgBody = body },
                        // ファイル名参照の集計もバックグラウンドで実施し、完了後にシートへ反映
                        onFileNameClick = { fn ->
                            val snapshot = items
                            scope.launch {
                                val list = withContext(Dispatchers.Default) {
                                    buildFilenameReferencesItems(snapshot, fn)
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
                                    buildSelfAndBackrefItems(snapshot, src)
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
                        onImageLoaded = onImageLoaded,
                        onVisibleMaxOrdinal = onVisibleMaxOrdinal,
                        listState = listState,
                        initialScrollIndex = initialScrollIndex,
                        initialScrollOffset = initialScrollOffset,
                        onSaveScroll = onSaveScroll,
                        // 左端に 8dp の余白を追加
                        contentPadding = PaddingValues(start = 8.dp, end = endPadding, bottom = bottomDp),
                        onProvideSearchNavigator = { p, n ->
                            navPrev = p
                            navNext = n
                        }
                    )
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
                // Material3 PullToRefreshBox によるインジケータ描画
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
                                        buildIdPostsItems(snapshot, target)
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
                            ngStore.addRule(com.valoser.futaburakari.RuleType.ID, toAdd, com.valoser.futaburakari.MatchType.EXACT, sourceKey = source, ephemeral = true)
                            onReapplyNgFilter?.invoke()
                            android.widget.Toast.makeText(ctx, "追加しました", android.widget.Toast.LENGTH_SHORT).show()
                            pendingNgId = null
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
                            Spacer(Modifier.height(8.dp))
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
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            val pat = text.trim()
                            if (pat.isNotEmpty()) {
                                val source = threadUrl?.let { com.valoser.futaburakari.UrlNormalizer.threadKey(it) }
                                ngStore.addRule(com.valoser.futaburakari.RuleType.BODY, pat, match, sourceKey = source, ephemeral = true)
                                onReapplyNgFilter?.invoke()
                                android.widget.Toast.makeText(ctx, "追加しました", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            pendingNgBody = null
                        }) { Text("追加") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { pendingNgBody = null }) { Text("キャンセル") }
                    }
                )
            }

            // 同一ID投稿一覧のシート（Compose）
            val idItems = idSheetItems
            if (idItems != null) {
                val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { idSheetItems = null },
                    sheetState = sheetState
                ) {
                    val screenHeight = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
                    val maxHeight = with(LocalDensity.current) { (screenHeight * 0.9f).dp }
                    androidx.compose.foundation.layout.Box(modifier = Modifier.heightIn(max = maxHeight)) {
                        DetailListCompose(
                            items = idItems,
                            searchQuery = null,
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
                            onImageLoaded = onImageLoaded,
                            onVisibleMaxOrdinal = null,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // 引用/No./ファイル名参照の一覧シート（Compose）
            // 集計結果があればボトムシートで表示（描画時点では重い処理は完了済み）
            val refItems = resRefItems
            if (refItems != null) {
                val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
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
                            onImageLoaded = onImageLoaded,
                            onVisibleMaxOrdinal = null,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
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
                    // Compose 標準のグリッドで表示
                    val images = remember(items) {
                        data class Entry(val imageIdx: Int, val parentTextIdx: Int, val url: String)
                        fun findParentTextPosition(from: Int): Int {
                            for (i in from downTo 0) if (items[i] is com.valoser.futaburakari.DetailContent.Text) return i
                            return from
                        }
                        items.mapIndexedNotNull { i, c ->
                            when (c) {
                                is com.valoser.futaburakari.DetailContent.Image -> Entry(i, findParentTextPosition(i), c.imageUrl)
                                is com.valoser.futaburakari.DetailContent.Video -> Entry(i, findParentTextPosition(i), c.videoUrl)
                                else -> null
                            }
                        }
                    }
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(images.size) { idx ->
                            val e = images[idx]
                            coil.compose.AsyncImage(
                                model = e.url,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(4.dp)
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .clickable {
                                        scope.launch { listState.scrollToItem(e.parentTextIdx) }
                                        openMediaSheet = false
                                    },
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }
                    }
                }
            }

            // 検索バー（DockedSearchBar）: 虫眼鏡で表示/非表示をトグル
            if (searchActive) {
                DockedSearchBar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 8.dp),
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = {
                        val q = query.trim()
                        if (q.isNotEmpty()) onSubmitSearch(q) else onClearSearch()
                        setSearchActive(false)
                    },
                    active = true,
                    onActiveChange = { active -> setSearchActive(active) },
                    placeholder = { Text("検索キーワード") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                onClearSearch()
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear"
                                )
                            }
                        }
                    },
                    colors = SearchBarDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        inputFieldColors = SearchBarDefaults.inputFieldColors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
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
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            QuickFilterChip(label = "画像", onClick = {
                                query = "画像"
                                onDebouncedSearch("画像")
                            })
                            Spacer(Modifier.width(8.dp))
                            QuickFilterChip(label = "動画", onClick = {
                                query = "動画"
                                onDebouncedSearch("動画")
                            })
                            Spacer(Modifier.width(8.dp))
                            QuickFilterChip(label = "No.", onClick = {
                                query = "No."
                            })
                        }
                        Spacer(Modifier.height(4.dp))
                        val recent = recentSearchesFlow?.collectAsState(initial = emptyList())?.value ?: emptyList()
                        if (recent.isNotEmpty()) {
                            Text(
                                text = "最近の検索",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
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
                                            .padding(horizontal = 4.dp),
                                        leadingContent = {
                                            Icon(Icons.Rounded.Search, contentDescription = null)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 検索ナビ（↓↑ と 件数表示）— 従来の下部UI相当をComposeで重ねる
            if (searchStateFlow != null) {
                val s by searchStateFlow.collectAsStateWithLifecycle(com.valoser.futaburakari.ui.detail.SearchState(false, 0, 0))
                if (s.active) {
                    val bottomPx = bottomOffsetPxFlow?.collectAsState(initial = 0)?.value ?: 0
                    val bottomDp = with(LocalDensity.current) { bottomPx.toDp() }
                    SearchNavigationBar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
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
                if (titleClickPending) {
                    val firstIdx = items.indexOfFirst { it is DetailContent.Text }
                    if (firstIdx >= 0) {
                        val src = items[firstIdx] as DetailContent.Text
                        // 1) OP（引用元）＋タイトル内容での引用先（内容一致）
                        val byContent = buildSelfAndBackrefItems(items, src, extraCandidates = setOf(title))
                        // 2) OP の No. を使った引用先（>>No など番号参照）
                        val rn = src.resNum
                        val byNumber = if (!rn.isNullOrBlank()) buildResReferencesItems(items, rn) else emptyList()
                        // 3) 結合 + 重複排除（表示順は byContent → byNumber）
                        if (byContent.isNotEmpty() || byNumber.isNotEmpty()) {
                            val seen = HashSet<String>()
                            val merged = ArrayList<DetailContent>(byContent.size + byNumber.size)
                            for (c in byContent + byNumber) if (seen.add(c.id)) merged += c
                            resRefItems = merged
                            titleClickPending = false
                        }
                        // ヒットしなければフラグは保持
                    }
                    // items がまだ空の場合もフラグ保持
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
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Prev")
            }
            Text(
                text = if (total > 0 && current in 1..total) "$current/$total" else "0/0",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 8.dp)
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
