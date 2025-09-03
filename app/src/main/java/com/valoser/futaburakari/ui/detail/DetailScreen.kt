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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

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
                    IconButton(onClick = onOpenMedia) {
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

            if (useComposeList && itemsLive != null) {
                val items = itemsLive.observeAsState(emptyList()).value
                val searchQuery = currentQueryFlow?.collectAsState(initial = null)?.value
                val refreshing = isRefreshingLive?.observeAsState(false)?.value ?: false
                val swipeState = rememberSwipeRefreshState(isRefreshing = refreshing)
                // Share list state between list and fast scroller
                val listState = rememberLazyListState(
                    initialFirstVisibleItemIndex = initialScrollIndex.coerceAtLeast(0),
                    initialFirstVisibleItemScrollOffset = initialScrollOffset.coerceAtLeast(0)
                )
                var fastScrollActive by remember { mutableStateOf(false) }
                // Bottom padding so content and scroller don't overlap bottom container
                val bottomPx = bottomOffsetPxFlow?.collectAsState(initial = 0)?.value ?: 0
                val bottomDp = with(LocalDensity.current) { bottomPx.toDp() }
                SwipeRefresh(state = swipeState, onRefresh = onReload) {
                    val endPadding = DefaultFastScrollerWidth + 8.dp
                    DetailListCompose(
                        items = items,
                        searchQuery = searchQuery,
                        onQuoteClick = onQuoteClick,
                        onSodaneClick = null,
                        onThreadEndTimeClick = onThreadEndTimeClick,
                        onResNumClick = onResNumClick,
                        onResNumConfirmClick = onResNumConfirmClick,
                        onResNumDelClick = onResNumDelClick,
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
