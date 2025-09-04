package com.valoser.futaburakari.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Search
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
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.DetailContent
import androidx.compose.ui.viewinterop.AndroidView
import com.valoser.futaburakari.ui.detail.FastScroller
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import com.valoser.futaburakari.ui.detail.buildIdPostsItems
import com.valoser.futaburakari.ui.detail.buildResReferencesItems

/**
 * Compose-based detail screen scaffold.
 * Hosts the thread list, search UI, and optional ad banner.
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

    // Hoisted UI states for dialogs/sheets so both topBar and content can access
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
                    IconButton(onClick = onReply) {
                        Icon(Icons.Filled.Reply, contentDescription = "Reply")
                    }
                    IconButton(onClick = onReload) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    IconButton(onClick = { setSearchActive(!searchActive) }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = onOpenNg) {
                        Icon(Icons.Filled.Block, contentDescription = "NG Manage")
                    }
                    // Prefer Compose sheet; keep callback available if needed
                    IconButton(onClick = { openMediaSheet = true }) {
                        Icon(Icons.Filled.Image, contentDescription = "Media List")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
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
            val ngStore = remember(ctx) { com.valoser.futaburakari.NgStore(ctx) }
            // Hoisted "そうだね" 表示カウント
            val sodaneCounts = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }
            LaunchedEffect(sodaneUpdates) {
                sodaneUpdates?.let { flow ->
                    flow.collect { (rn, count) -> sodaneCounts[rn] = count }
                }
            }
            // Hoist items/listState so they are visible to dialogs/sheets below
            val raw = itemsFlow?.collectAsStateWithLifecycle(emptyList())?.value ?: emptyList()
            val items = remember(raw) { normalizeThreadEndTime(raw) }
            // Share list state between list and fast scroller
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialScrollIndex.coerceAtLeast(0),
                initialFirstVisibleItemScrollOffset = initialScrollOffset.coerceAtLeast(0)
            )
            // 検索ナビ（Compose内でリストに吸着）を上位スコープで保持
            var navPrev by remember { mutableStateOf<(() -> Unit)?>(null) }
            var navNext by remember { mutableStateOf<(() -> Unit)?>(null) }
            if (itemsFlow != null) {
                val searchQuery = currentQueryFlow?.collectAsStateWithLifecycle(null)?.value
                val refreshing = isRefreshingFlow?.collectAsStateWithLifecycle(false)?.value ?: false
                val swipeState = rememberSwipeRefreshState(isRefreshing = refreshing)
                var fastScrollActive by remember { mutableStateOf(false) }
                // Bottom padding: legacy Flow があれば使用。無ければ広告高さから算出
                val legacyPx = bottomOffsetPxFlow?.collectAsState(initial = 0)?.value
                var adPx by remember { mutableStateOf(0) }
                val bottomPx = legacyPx ?: adPx
                val bottomDp = with(LocalDensity.current) { bottomPx.toDp() }
                var deleteTarget by remember { mutableStateOf<String?>(null) }

                // 無限スクロール検知：末尾のコンテンツ(Text/Image/Video)近辺に到達したら通知
                // 同一サイズのitemsに対しては1回だけ発火する（重複抑止）
                var lastTriggeredSize by remember { mutableStateOf(-1) }
                LaunchedEffect(items, refreshing, fastScrollActive) {
                    if (refreshing || fastScrollActive) return@LaunchedEffect
                    val li = listState.layoutInfo
                    val lastVisible = li.visibleItemsInfo.lastOrNull()?.index ?: -1
                    if (lastVisible < 0) return@LaunchedEffect
                    // 実質末尾（ThreadEndTimeを除く）
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
                SwipeRefresh(state = swipeState, onRefresh = onReload) {
                    val endPadding = DefaultFastScrollerWidth + 8.dp
                    DetailListCompose(
                        items = items,
                        searchQuery = searchQuery,
                        threadTitle = title,
                        onQuoteClick = { token ->
                            // 引用先タップ時: 引用元（一致行を含むレス）と、そのレスを引用している投稿の両方を表示
                            val list = buildQuoteAndBackrefItems(items, token, threadTitle = title)
                            if (list.isNotEmpty()) {
                                resRefItems = list
                            } else {
                                onQuoteClick?.invoke(token)
                            }
                        },
                        onSodaneClick = onSodaneClick,
                        onThreadEndTimeClick = onThreadEndTimeClick,
                        onResNumClick = { resNum, resBody ->
                            if (resBody.isEmpty()) deleteTarget = resNum else onResNumClick?.invoke(resNum, resBody)
                        },
                        onResNumConfirmClick = { resNum ->
                            val list = buildResReferencesItems(items, resNum)
                            if (list.isNotEmpty()) {
                                resRefItems = list
                            } else {
                                // fallback to original if needed
                                onResNumConfirmClick?.invoke(resNum)
                            }
                        },
                        onResNumDelClick = { resNum -> reportTarget = resNum },
                        onIdClick = { id -> idMenuTarget = id },
                        onBodyClick = onBodyClick,
                        onAddNgFromBody = { body -> pendingNgBody = body },
                        onBodyShowBackRefs = { src ->
                            val list = buildSelfAndBackrefItems(items, src)
                            if (list.isNotEmpty()) {
                                resRefItems = list
                            } else {
                                // fallback: 何もヒットしなければ何もしない（必要ならToastなど）
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
                        contentPadding = PaddingValues(end = endPadding, bottom = bottomDp),
                        onProvideSearchNavigator = { p, n ->
                            navPrev = p
                            navNext = n
                        }
                    )
                }
                // Delete confirmation (Compose)
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
                // Compose FastScroller overlay (right edge)
                FastScroller(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 4.dp),
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
                // Accompanist SwipeRefresh draws its own indicator inside SwipeRefresh
            }

            // IDメニュー（Composeダイアログ）
            val idTarget = idMenuTarget
            if (idTarget != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { idMenuTarget = null },
                    title = { Text("ID: $idTarget") },
                    text = { Text("操作を選択してください") },
                    confirmButton = {
                        Row {
                            androidx.compose.material3.TextButton(onClick = {
                                // 同一IDの投稿一覧を作成し、シートを開く
                                val list = buildIdPostsItems(items, idTarget)
                                idMenuTarget = null
                                idSheetItems = list
                            }) { Text("同一IDの投稿") }
                            androidx.compose.material3.TextButton(onClick = {
                                pendingNgId = idTarget
                                idMenuTarget = null
                            }) { Text("NGに追加") }
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { idMenuTarget = null }) { Text("キャンセル") }
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

            // 引用/No.参照一覧のシート（Compose）
            val refItems = resRefItems
            if (refItems != null) {
                val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { resRefItems = null },
                    sheetState = sheetState
                ) {
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

            // Helper はファイル末尾の top-level 定義を利用

            // メディア一覧（Compose ModalBottomSheet）
            if (openMediaSheet) {
                val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
                val scope = rememberCoroutineScope()
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { openMediaSheet = false },
                    sheetState = sheetState
                ) {
                    // Compose純正のグリッドで表示
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

            // Compose検索バー（DockedSearchBar）: 虫眼鏡で表示/非表示をトグル
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
                        Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                onClearSearch()
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
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
                                            Icon(Icons.Filled.Search, contentDescription = null)
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
                        // If VM callbacks are provided, invoke them AND scroll via local navigator.
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
                        // 2) OPのNo.を使った引用先（>>No など番号参照）
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

// Ensure only the last ThreadEndTime remains; keep order for other items
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
