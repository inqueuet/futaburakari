package com.valoser.futaburakari.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.valoser.futaburakari.ImageItem
import com.valoser.futaburakari.MatchType
import com.valoser.futaburakari.NgRule
import com.valoser.futaburakari.RuleType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * 画像カタログの一覧画面。
 * - プルリフレッシュおよび端での強いオーバースクロール（バウンス）で再読み込みを実行。
 * - 検索欄の表示切替、ブックマーク選択/管理、履歴、設定などのメニュー操作を提供。
 * - NG タイトルルールとクエリで一覧をフィルタし、グリッド表示します。
 * - 可視範囲＋先読み分の軽量プリフェッチでスクロール体験を滑らかにします。
 */
fun MainCatalogScreen(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String?,
    items: List<ImageItem>,
    isLoading: Boolean,
    spanCount: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    onReload: () -> Unit,
    onSelectBookmark: () -> Unit,
    onManageBookmarks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onImageEdit: () -> Unit,
    onBrowseLocalImages: () -> Unit,
    onItemClick: (ImageItem) -> Unit,
    ngRules: List<NgRule>,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()

    // NG タイトルルールとクエリで絞り込み
    val filtered = remember(items, query, ngRules) {
        val titleRules = ngRules.filter { it.type == RuleType.TITLE }
        items.asSequence()
            .filter { item ->
                val title = item.title.orEmpty()
                titleRules.none { r -> matchTitle(title, r) }
            }
            .filter { item ->
                if (query.isBlank()) true else item.title.contains(query, ignoreCase = true)
            }
            .toList()
    }

    val gridState = rememberLazyGridState()

    // バウンス（端でのオーバースクロール）検出用の状態
    val density = LocalDensity.current
    val minBouncePx = with(density) { 120.dp.toPx() }.coerceAtLeast(48f) // トリガーに必要な最小距離
    var continuousOverscrollPx by remember { mutableStateOf(0f) } // 連続オーバースクロール距離の累積
    var lastScrollDirection by remember { mutableStateOf(0) } // 1: 下方向, -1: 上方向, 0: なし
    var autoUpdatePending by remember { mutableStateOf(false) } // 誤連発防止のディレイ中フラグ

    val bounceConnection = remember(isLoading) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val atTop = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                val visibleLast = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                val atBottom = items.isNotEmpty() && visibleLast >= items.lastIndex

                val dy = available.y
                val dir = if (dy > 0f) 1 else if (dy < 0f) -1 else 0
                lastScrollDirection = dir

                val pullingTop = atTop && dy > 0f
                val pullingBottom = atBottom && dy < 0f

                if (pullingTop || pullingBottom) {
                    continuousOverscrollPx += kotlin.math.abs(dy)
                } else {
                    continuousOverscrollPx = 0f
                }
                return Offset.Zero
            }
        }
    }

    // スクロール停止検知 → 端に到達して十分に引っ張られていたら自動更新
    LaunchedEffect(gridState, isLoading, items) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (!inProgress) {
                    val visibleLast = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    val atTop = gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                    val atBottom = items.isNotEmpty() && visibleLast >= items.lastIndex
                    val atBoundary = (atTop && lastScrollDirection > 0) || (atBottom && lastScrollDirection < 0)
                    if (!isLoading && atBoundary && continuousOverscrollPx >= minBouncePx && !autoUpdatePending) {
                        autoUpdatePending = true
                        // 少し待ってから実際の更新（誤判定や連発を避ける）
                        launch {
                            delay(1000L)
                            onReload()
                            autoUpdatePending = false
                        }
                    }
                    continuousOverscrollPx = 0f
                }
            }
    }

    // 軽量プリフェッチ（可視範囲＋先読み分の少量だけ）
    val context = LocalContext.current
    LaunchedEffect(items, gridState) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            first to last
        }
            .distinctUntilChanged()
            .collect { (first, last) ->
                if (last <= 0 || items.isEmpty()) return@collect
                val prefetchAhead = 8
                val end = (last + prefetchAhead).coerceAtMost(items.lastIndex)
                val start = first.coerceAtLeast(0)
                val dm = context.resources.displayMetrics
                val thumbPx = (200 * dm.density).toInt().coerceAtLeast(120)
                val urls = items.subList(start, end + 1)
                    .mapNotNull { it.fullImageUrl ?: it.previewUrl }
                urls.chunked(4).forEach { batch ->
                    batch.forEach { url ->
                        val req = ImageRequest.Builder(context)
                            .data(url)
                            .size(thumbPx)
                            .build()
                        context.imageLoader.enqueue(req)
                    }
                    delay(50)
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        TextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = query,
                            onValueChange = onQueryChange,
                            singleLine = true,
                            placeholder = { Text(text = "検索") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            )
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { if (!isLoading) onReload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "再読み込み")
                    }
                    IconButton(onClick = onSelectBookmark) {
                        Icon(Icons.Default.BookmarkBorder, contentDescription = "ブックマーク選択")
                    }
                    IconButton(onClick = onManageBookmarks) {
                        Icon(Icons.Default.Bookmarks, contentDescription = "ブックマーク管理")
                    }
                    IconButton(onClick = { searching = !searching }) {
                        Icon(Icons.Default.Search, contentDescription = "検索")
                    }
                    MoreMenu(
                        onImageEdit = onImageEdit,
                        onBrowseLocalImages = onBrowseLocalImages,
                        onHistory = onOpenHistory,
                        onSettings = onOpenSettings,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    scrolledContainerColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { padding ->
        PullToRefreshBox(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                // 端でのオーバースクロール距離を計測するための NestedScroll を追加
                .nestedScroll(bounceConnection),
            state = pullState,
            isRefreshing = isLoading,
            onRefresh = onReload,
            // トップのインジケータは使わず、中央に独自インジケータを重ねる
            indicator = {}
        ) {
            // グリッド本体
            LazyVerticalGrid(
                modifier = Modifier.fillMaxSize(),
                state = gridState,
                columns = GridCells.Fixed(spanCount.coerceAtLeast(1)),
                contentPadding = PaddingValues(4.dp)
            ) {
                // 安定キーに detailUrl を使用
                items(filtered, key = { it.detailUrl }) { item ->
                    CatalogCard(item = item, onClick = { onItemClick(item) })
                }
            }

            // 中央インジケータ（ぐるぐる）
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
/**
 * 右上「その他」メニュー。画像編集・ローカル画像・履歴・設定を提供。
 */
private fun MoreMenu(
    onImageEdit: () -> Unit,
    onBrowseLocalImages: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "その他")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("画像編集") }, onClick = { expanded = false; onImageEdit() })
            DropdownMenuItem(text = { Text("ローカル画像を開く") }, onClick = { expanded = false; onBrowseLocalImages() })
            DropdownMenuItem(text = { Text("履歴") }, onClick = { expanded = false; onHistory() })
            DropdownMenuItem(text = { Text("設定") }, onClick = { expanded = false; onSettings() })
        }
    }
}

@Composable
private fun CatalogCard(item: ImageItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(4.dp),
        onClick = onClick
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // サムネイル（幅:高さ = 3:4）でアイテムの高さを一定に保つ
            AsyncImage(
                modifier = Modifier
                    .fillMaxWidth()
                    // 長方形アスペクトでサイズを統一（幅:高さ = 3:4）
                    .aspectRatio(3f / 4f),
                model = item.fullImageUrl ?: item.previewUrl,
                contentDescription = item.title
            )

            // 動画の場合は中央に再生アイコンを重ねる
            val isVideo = (item.fullImageUrl ?: item.previewUrl).let { url ->
                url.lowercase().endsWith(".webm") || url.lowercase().endsWith(".mp4") || url.lowercase().endsWith(".mkv")
            }
            if (isVideo) {
                Surface(
                    modifier = Modifier
                        .size(36.dp)
                        .align(Alignment.Center),
                    color = Color.Black.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.small
                ) {}
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // 返信数バッジ（右下）
            if (!item.replyCount.isNullOrBlank()) {
                Text(
                    text = item.replyCount,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // タイトル用のオーバーレイ（固定高さでレイアウトを安定化）
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    // 2 行のタイトルに対応するため少し高めにする
                    .height(56.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.55f),
                                Color.Black.copy(alpha = 0.0f)
                            )
                        )
                    )
            )
            Text(
                text = item.title,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 6.dp, end = 6.dp, bottom = 8.dp)
            )
        }
    }
}

/**
 * タイトルに対して NG ルールを適用してマッチ判定する。
 */
private fun matchTitle(title: String, rule: NgRule): Boolean {
    val pattern = rule.pattern
    val mt = rule.match ?: MatchType.SUBSTRING
    return when (mt) {
        MatchType.EXACT -> title == pattern
        MatchType.PREFIX -> title.startsWith(pattern, ignoreCase = true)
        MatchType.SUBSTRING -> title.contains(pattern, ignoreCase = true)
        MatchType.REGEX -> runCatching { Regex(pattern, setOf(RegexOption.IGNORE_CASE)).containsMatchIn(title) }.getOrDefault(false)
    }
}
