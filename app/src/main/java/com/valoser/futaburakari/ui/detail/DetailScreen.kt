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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.valoser.futaburakari.DetailContent
import com.valoser.futaburakari.databinding.ActivityDetailBinding
import com.valoser.futaburakari.ui.detail.FastScroller
import coil.load
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import android.text.Html
import com.valoser.futaburakari.BlockDividerDecoration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import com.valoser.futaburakari.ui.detail.buildIdPostsItems
import com.valoser.futaburakari.ui.detail.buildResReferencesItems

/**
 * Hybrid container for gradual Compose migration.
 * Currently embeds the existing XML root inside Compose.
 */
@Composable
fun DetailScreenHybrid(binding: ActivityDetailBinding) {
    AndroidView(factory = { binding.root })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreenScaffold(
    binding: ActivityDetailBinding,
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
    searchStateFlow: StateFlow<com.valoser.futaburakari.DetailSearchManager.SearchState>? = null,
    onSearchPrev: (() -> Unit)? = null,
    onSearchNext: (() -> Unit)? = null,
    bottomOffsetPxFlow: StateFlow<Int>? = null,
    searchActiveFlow: StateFlow<Boolean>? = null,
    onSearchActiveChange: ((Boolean) -> Unit)? = null,
    recentSearchesFlow: StateFlow<List<String>>? = null,
    useComposeList: Boolean = false,
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    onSaveScroll: ((Int, Int) -> Unit)? = null,
    itemsLive: androidx.lifecycle.LiveData<List<DetailContent>>? = null,
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
    isRefreshingLive: androidx.lifecycle.LiveData<Boolean>? = null,
    onVisibleMaxOrdinal: ((Int) -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    val localSearchActive = remember { mutableStateOf(false) }
    val searchActive: Boolean = searchActiveFlow?.collectAsState(initial = false)?.value ?: localSearchActive.value
    val setSearchActive = remember(onSearchActiveChange) {
        { active: Boolean -> onSearchActiveChange?.invoke(active) ?: run { localSearchActive.value = active } }
    }

    // Hoisted UI states for dialogs/sheets so both topBar and content can access
    var openMediaSheet by remember { mutableStateOf(false) }
            var idMenuTarget by remember { mutableStateOf<String?>(null) }
            var idSheetItems by remember { mutableStateOf<List<DetailContent>?>(null) }
            var resRefItems by remember { mutableStateOf<List<DetailContent>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 2, style = MaterialTheme.typography.titleMedium) },
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
            // Legacy content underneath（広告などのレイアウト維持用）
            AndroidView(
                modifier = Modifier.matchParentSize(),
                factory = { binding.root },
                update = { /* no-op */ }
            )

            // Hoist items/listState so they are visible to dialogs/sheets below
            val items = itemsLive?.observeAsState(emptyList())?.value ?: emptyList()
            // Share list state between list and fast scroller
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = initialScrollIndex.coerceAtLeast(0),
                initialFirstVisibleItemScrollOffset = initialScrollOffset.coerceAtLeast(0)
            )
            if (useComposeList && itemsLive != null) {
                val searchQuery = currentQueryFlow?.collectAsState(initial = null)?.value
                val refreshing = isRefreshingLive?.observeAsState(false)?.value ?: false
                val swipeState = rememberSwipeRefreshState(isRefreshing = refreshing)
                var fastScrollActive by remember { mutableStateOf(false) }
                // Bottom padding so content and scroller don't overlap bottom container
                val bottomPx = bottomOffsetPxFlow?.collectAsState(initial = 0)?.value ?: 0
                val bottomDp = with(LocalDensity.current) { bottomPx.toDp() }
                var deleteTarget by remember { mutableStateOf<String?>(null) }
                SwipeRefresh(state = swipeState, onRefresh = onReload) {
                    val endPadding = DefaultFastScrollerWidth + 8.dp
                    DetailListCompose(
                        items = items,
                        searchQuery = searchQuery,
                        onQuoteClick = onQuoteClick,
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
                        onResNumDelClick = onResNumDelClick,
                        onIdClick = { id -> idMenuTarget = id },
                        onBodyClick = onBodyClick,
                        onAddNgFromBody = onAddNgFromBody,
                        getSodaneState = getSodaneState,
                        onImageLoaded = onImageLoaded,
                        onVisibleMaxOrdinal = onVisibleMaxOrdinal,
                        listState = listState,
                        initialScrollIndex = initialScrollIndex,
                        initialScrollOffset = initialScrollOffset,
                        onSaveScroll = onSaveScroll,
                        contentPadding = PaddingValues(end = endPadding, bottom = bottomDp)
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
                                // 同一ID投稿一覧: 既存のViewシートの代わりに今はNG追加のみ提供（必要なら後続でComposeシート実装）
                                onOpenNg()
                                idMenuTarget = null
                            }) { Text("NGに追加") }
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { idMenuTarget = null }) { Text("キャンセル") }
                    }
                )
            }

            // 同一ID投稿一覧のシート
            val idItems = idSheetItems
            if (idItems != null) {
                val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { idSheetItems = null },
                    sheetState = sheetState
                ) {
                    AndroidView(factory = { ctx ->
                        val rv = androidx.recyclerview.widget.RecyclerView(ctx).apply {
                            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
                        }
                        val adapter = com.valoser.futaburakari.DetailAdapter().apply {
                            onQuoteClickListener = null
                            onIdClickListener = null
                            onSodaNeClickListener = null
                            onResNumClickListener = null
                            onResNumConfirmClickListener = null
                            onResNumDelClickListener = null
                            getSodaNeState = { false }
                            submitList(idItems)
                        }
                        rv.adapter = adapter
                        rv.addItemDecoration(
                            BlockDividerDecoration(adapter, ctx, paddingStartDp = 0, paddingEndDp = 0)
                        )
                        rv
                    })
                }
            }

            // 引用/No.参照一覧のシート
            val refItems = resRefItems
            if (refItems != null) {
                val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { resRefItems = null },
                    sheetState = sheetState
                ) {
                    AndroidView(factory = { ctx ->
                        val rv = androidx.recyclerview.widget.RecyclerView(ctx).apply {
                            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
                        }
                        val adapter = com.valoser.futaburakari.DetailAdapter().apply {
                            onQuoteClickListener = null
                            onIdClickListener = null
                            onSodaNeClickListener = null
                            onResNumClickListener = null
                            onResNumConfirmClickListener = null
                            onResNumDelClickListener = null
                            getSodaNeState = { false }
                            submitList(refItems)
                        }
                        rv.adapter = adapter
                        rv.addItemDecoration(
                            BlockDividerDecoration(adapter, ctx, paddingStartDp = 0, paddingEndDp = 0)
                        )
                        rv
                    })
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
                    // Build grid via AndroidView RecyclerView to reuse coil.load
                    val data = remember(items) {
                        // Build image entries with parent text index
                        data class ImageEntry(val imageIdx: Int, val parentTextIdx: Int, val url: String)
                        val list = mutableListOf<ImageEntry>()
                        fun findParentTextPosition(from: Int): Int {
                            for (i in from downTo 0) if (items[i] is com.valoser.futaburakari.DetailContent.Text) return i
                            return from
                        }
                        items.withIndex().forEach { (i, c) ->
                            when (c) {
                                is com.valoser.futaburakari.DetailContent.Image -> list += ImageEntry(i, findParentTextPosition(i), c.imageUrl)
                                is com.valoser.futaburakari.DetailContent.Video -> list += ImageEntry(i, findParentTextPosition(i), c.videoUrl)
                                else -> {}
                            }
                        }
                        list
                    }
                    AndroidView(factory = { ctx ->
                        val rv = androidx.recyclerview.widget.RecyclerView(ctx).apply {
                            layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 3)
                            setHasFixedSize(true)
                        }
                        class ImageGridAdapter(
                            private val data: List<Pair<String, Int>>,
                            private val onClick: (Int) -> Unit
                        ) : androidx.recyclerview.widget.RecyclerView.Adapter<ImageGridAdapter.VH>() {
                            inner class VH(val iv: android.widget.ImageView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(iv)
                            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
                                val d = parent.resources.displayMetrics.density
                                val sizePx = (110 * d).toInt()
                                val iv = android.widget.ImageView(parent.context).apply {
                                    layoutParams = android.view.ViewGroup.MarginLayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, sizePx).apply {
                                        val m = (4 * d).toInt(); setMargins(m, m, m, m)
                                    }
                                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                    setBackgroundColor(0xFF222222.toInt())
                                }
                                return VH(iv)
                            }
                            override fun onBindViewHolder(holder: VH, position: Int) {
                                val (url, _) = data[position]
                                holder.iv.load(url)
                                holder.iv.setOnClickListener { onClick(position) }
                            }
                            override fun getItemCount(): Int = data.size
                        }
                        val pairs = data.map { it.url to it.parentTextIdx }
                        val adapter = ImageGridAdapter(pairs) { idx ->
                            val target = pairs[idx].second
                            // scroll and dismiss
                            scope.launch { listState.scrollToItem(target) }
                            openMediaSheet = false
                        }
                        rv.adapter = adapter
                        rv
                    })
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
                val s by searchStateFlow.collectAsState()
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
                        onPrev = { onSearchPrev?.invoke() },
                        onNext = { onSearchNext?.invoke() }
                    )
                }
            }
        }
    }
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
