/**
 * 画像カタログ（メイン一覧）画面の Compose 実装。
 *
 * 特徴:
 * - 更新: プル更新（PullToRefresh）と、端での強いオーバースクロール（バウンス）検知での自動再読み込み。
 * - 体験: 可視範囲＋先読みの軽量プリフェッチでスクロールを滑らかに。
 * - 表示: カード下部に SurfaceVariant のタイトル領域を設け、返信数バッジは右上に配置。
 * - フィルタリング: OP画像なしスレッドは常時非表示に設定。
 * - エラー: フル画像の取得が失敗した場合は可能ならプレビューへフォールバックし、
 *          それも不可の場合は簡易プレースホルダを表示。
 *          404 検知時は `onImageLoadHttp404` を介して ViewModel に通知し、URL 補正を試みる。
 */
package com.valoser.futaburakari.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as lazyListItems
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
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Precision
import coil3.network.HttpException
import coil3.network.httpHeaders
import coil3.network.NetworkHeaders
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import coil3.compose.SubcomposeAsyncImage
import coil3.request.transitionFactory
import coil3.transition.CrossfadeTransition
import com.valoser.futaburakari.ImageItem
import com.valoser.futaburakari.MatchType
import com.valoser.futaburakari.NgRule
import com.valoser.futaburakari.RuleType
import com.valoser.futaburakari.ui.theme.LocalSpacing
import com.valoser.futaburakari.CatalogPrefetchHint

/**
 * 画像カタログの一覧画面（メイン画面）。
 *
 * 機能概要:
 * - 更新: プルリフレッシュに加え、端での強いオーバースクロール（バウンス）でも再読み込みを実行（連発抑止あり）。
 * - 操作: トップバーに「再読み込み／ブックマーク選択／並び順／履歴／検索」を配置。
 *         右上メニューには「ブックマーク管理 → 設定 → 画像編集」を常設し、
 *         プロンプト機能が有効な場合のみ「ローカル画像を開く」を追加表示。
 * - トップバー: 通常時はサブタイトル（選択中ブックマーク名）のみを大きく表示。タイトルは非表示。
 *               検索中はタイトル領域を検索ボックスに切り替える。
 * - 絞込: NG タイトルルール、検索クエリ、画像有無（常時適用）で一覧をフィルタし、グリッド表示。
 * - 体験: 可視範囲＋先読み分のみを軽量プリフェッチし、`onPrefetchHint` で呼び出し側へ通知。
 * - 表示: カード下部は SurfaceVariant の帯でタイトルを表示し、返信数バッジは右上に固定。
 * - エラー: フル画像が失敗した場合はプレビューへフォールバックし、プレビューも不可の場合は簡易プレースホルダを表示。
 *
 * パラメータ:
 * - `modifier`: ルートレイアウト用の修飾子。
 * - `title`/`subtitle`: 上部タイトル/サブタイトル（通常はタイトルを表示せず、サブタイトルのみ表示）。
 * - `items`: 表示対象のアイテム一覧（呼び出し側で取得）。
 * - `isLoading`: 読み込み中インジケータの表示制御。
 * - `spanCount`: グリッド列数。
 * - `query`/`onQueryChange`: 検索クエリと変更ハンドラ。
 * - `onReload`: 更新アクション（プル/バウンス発火時も呼ぶ）。
 * - `onPrefetchHint`: 可視範囲＋先読み分のプリフェッチ要求を通知するコールバック。
 * - `onSelectBookmark`: ブックマーク選択ダイアログ等を開くアクション。
 * - `onManageBookmarks`: ブックマーク管理画面を開くアクション（メニューから呼び出し）。
 * - `onOpenSettings`: 設定画面を開くアクション（メニューから呼び出し）。
 * - `onOpenHistory`: 履歴画面を開くアクション（トップバーのアイコン）。
 * - `onImageEdit`/`onBrowseLocalImages`: 画像編集／ローカル画像のメニュー操作。
 * - `onVideoEdit`: 動画編集のメニュー操作。
 * - `promptFeaturesEnabled`: プロンプト機能が有効な場合に追加メニューを表示するフラグ。
 * - `onItemClick`: アイテムタップ時のハンドラ。
 * - `ngRules`: NG タイトルルール一覧（TITLE のみ対象）。
 * - `onImageLoadHttp404`: 画像ロードが 404 で失敗した際に呼ばれるコールバック。
 *                         ViewModel 側で URL 補正（代替URLの探索・差し替え）を行うために使用。
 * - `onImageLoadSuccess`: フル画像が取得できた際に呼ばれるコールバック。検証済み URL の保持に使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainCatalogScreen(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String?,
    listIdentity: String,
    items: List<ImageItem>,
    isLoading: Boolean,
    spanCount: Int,
    catalogDisplayMode: String = "grid",
    query: String,
    onQueryChange: (String) -> Unit,
    onReload: () -> Unit,
    onPrefetchHint: (CatalogPrefetchHint) -> Unit,
    onSelectBookmark: () -> Unit,
    onSelectSortMode: () -> Unit,
    onManageBookmarks: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onImageEdit: () -> Unit,
    onVideoEdit: () -> Unit,
    onBrowseLocalImages: () -> Unit,
    promptFeaturesEnabled: Boolean = true,
    onItemClick: (ImageItem) -> Unit,
    ngRules: List<NgRule>,
    onImageLoadHttp404: (item: ImageItem, failedUrl: String) -> Unit,
    onImageLoadSuccess: (item: ImageItem, loadedUrl: String) -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()

    // NG タイトルルールとクエリ、画像有無で絞り込み（常時OP画像なしスレッドを非表示）
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
            .filter { item ->
                hasImages(item)
            }
            .toList()
    }

    val isListMode = catalogDisplayMode == "list"
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()

    LaunchedEffect(listIdentity) {
        // ソースが切り替わったら新しい一覧の先頭へ戻す
        listState.scrollToItem(0)
        gridState.scrollToItem(0)
    }

    // CompositionLocals はコンポーズ文脈内で取得しておき、
    // LaunchedEffect 内では非 @Composable な値（Dp など）として参照する
    val spacing = LocalSpacing.current
    val sDp = spacing.s
    val xsDp = spacing.xs

    // バウンス（端でのオーバースクロール）検出用の状態
    val density = LocalDensity.current
    val sPx by remember(sDp, density) { derivedStateOf { with(density) { sDp.toPx() } } }
    val xsPx by remember(xsDp, density) { derivedStateOf { with(density) { xsDp.toPx() } } }
    val minBouncePx = with(density) { 120.dp.toPx() }.coerceAtLeast(48f) // トリガーに必要な最小距離
    var continuousOverscrollPx by remember { mutableStateOf(0f) } // 連続オーバースクロール距離の累積
    var lastScrollDirection by remember { mutableStateOf(0) } // 1: 下方向, -1: 上方向, 0: なし
    var autoUpdatePending by remember { mutableStateOf(false) } // 誤連発防止のディレイ中フラグ

    val bounceConnection = remember(isLoading, isListMode) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val atTop = if (isListMode) {
                    listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                } else {
                    gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                }
                val visibleLast = if (isListMode) {
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                } else {
                    gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                }
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
    LaunchedEffect(gridState, listState, isLoading, items, isListMode) {
        snapshotFlow { if (isListMode) listState.isScrollInProgress else gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (!inProgress) {
                    val visibleLast = if (isListMode) {
                        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    } else {
                        gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    }
                    val atTop = if (isListMode) {
                        listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                    } else {
                        gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                    }
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

    // 404のローカル抑止は廃止（VM側の回数管理と候補探索に委任）

    // 軽量プリフェッチ（可視範囲＋先読み分のみを事前ロード）
    // 実表示サイズと同一のサイズでプリフェッチし、メモリキャッシュのヒット率を最大化する
    LaunchedEffect(filtered, gridState, listState, spanCount, sPx, xsPx, isListMode) {
        snapshotFlow {
            if (isListMode) {
                val layout = listState.layoutInfo
                val first = layout.visibleItemsInfo.firstOrNull()?.index ?: 0
                val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                val viewportWidthPx = layout.viewportSize.width
                Triple(first, last, viewportWidthPx)
            } else {
                val layout = gridState.layoutInfo
                val first = layout.visibleItemsInfo.firstOrNull()?.index ?: 0
                val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                val viewportWidthPx = layout.viewportSize.width
                Triple(first, last, viewportWidthPx)
            }
        }
            .distinctUntilChanged()
            .collectLatest { (first, last, viewportWidthPx) ->
                if (last <= 0 || filtered.isEmpty()) return@collectLatest

                val contentWidthPx = (viewportWidthPx - (sPx * 2)).coerceAtLeast(0f)
                val cellWidthPx = if (isListMode) {
                    // リスト表示: サムネイル幅は約120dp相当の固定値を px 換算して利用
                    120.dp.value * 3f // 適切なdp値を使用
                } else {
                    ((contentWidthPx / spanCount) - (xsPx * 2)).coerceAtLeast(64f)
                }
                val cellHeightPx = cellWidthPx * 4f / 3f

                // 先読み行数は画面内の行数の約2倍（2画面分）
                val visibleCount = (last - first + 1).coerceAtLeast(if (isListMode) 1 else spanCount)
                val rowsVisible = if (isListMode) visibleCount else (visibleCount + spanCount - 1) / spanCount
                val prefetchRows = (rowsVisible * 2).coerceAtLeast(1)
                val prefetchAhead = if (isListMode) prefetchRows else prefetchRows * spanCount

                val end = (last + prefetchAhead).coerceAtMost(filtered.lastIndex)
                val start = first.coerceAtLeast(0)
                if (end < start) return@collectLatest

                val targets = filtered.subList(start, end + 1)
                if (targets.isEmpty()) return@collectLatest

                onPrefetchHint(
                    CatalogPrefetchHint(
                        items = targets.toList(),
                        cellWidthPx = cellWidthPx.toInt(),
                        cellHeightPx = cellHeightPx.toInt(),
                    )
                )
            }
    }

    // 再読み込み直後の能動的なフル化は行わない（経路を404修正に一本化）

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
                        // サブタイトルのみを大きく表示（タイトルは非表示）
                        val sub = subtitle.orEmpty()
                        if (sub.isNotBlank()) {
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { if (!isLoading) onReload() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "再読み込み")
                    }
                    IconButton(onClick = onSelectBookmark) {
                        Icon(Icons.Rounded.BookmarkBorder, contentDescription = "ブックマーク選択")
                    }
                    IconButton(onClick = onSelectSortMode) {
                        Icon(Icons.Rounded.Sort, contentDescription = "カタログ並び順")
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Rounded.History, contentDescription = "履歴")
                    }
                    IconButton(onClick = { searching = !searching }) {
                        Icon(Icons.Rounded.Search, contentDescription = "検索")
                    }
                    MoreMenu(
                        onManageBookmarks = onManageBookmarks,
                        onImageEdit = onImageEdit,
                        onVideoEdit = onVideoEdit,
                        onBrowseLocalImages = onBrowseLocalImages,
                        onSettings = onOpenSettings,
                        promptFeaturesEnabled = promptFeaturesEnabled,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
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
            if (isListMode) {
                // リスト本体
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(LocalSpacing.current.s)
                ) {
                    lazyListItems(
                        filtered,
                        key = { it.detailUrl },
                        contentType = { "image_list_item" }
                    ) { item ->
                        CatalogListItem(
                            item = item,
                            onClick = remember(item.detailUrl) { { onItemClick(item) } },
                            onImageLoadHttp404 = onImageLoadHttp404,
                            onImageLoadSuccess = onImageLoadSuccess,
                        )
                    }
                }
            } else {
                // グリッド本体
                LazyVerticalGrid(
                    modifier = Modifier.fillMaxSize(),
                    state = gridState,
                    columns = GridCells.Fixed(spanCount.coerceAtLeast(1)),
                    contentPadding = PaddingValues(LocalSpacing.current.s)
                ) {
                    // 安定キーと contentType でリサイクルを効率化
                    items(
                        filtered,
                        key = { it.detailUrl },
                        contentType = { "image_card" }
                    ) { item ->
                        CatalogCard(
                            item = item,
                            // Stable 参照でリコンポジションを最小化
                            onClick = remember(item.detailUrl) { { onItemClick(item) } },
                            onImageLoadHttp404 = onImageLoadHttp404,
                            onImageLoadSuccess = onImageLoadSuccess,
                        )
                    }
                }
            }

            // 中央インジケータ（ぐるぐる）
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/**
 * 右上「その他」メニュー。ブックマーク管理・設定・画像編集・動画編集を提供し、
 * プロンプト機能が有効な場合のみ「ローカル画像を開く」を追加表示する。
 * 選択時はメニューを閉じてから各ハンドラを呼び出す。
 */
@Composable
private fun MoreMenu(
    onManageBookmarks: () -> Unit,
    onImageEdit: () -> Unit,
    onVideoEdit: () -> Unit,
    onBrowseLocalImages: () -> Unit,
    onSettings: () -> Unit,
    promptFeaturesEnabled: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "その他")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("ブックマーク管理") }, onClick = { expanded = false; onManageBookmarks() })
            DropdownMenuItem(text = { Text("設定") }, onClick = { expanded = false; onSettings() })
            if (promptFeaturesEnabled) {
                DropdownMenuItem(
                    text = { Text("ローカル画像を開く") },
                    onClick = { expanded = false; onBrowseLocalImages() }
                )
            }
            DropdownMenuItem(text = { Text("画像編集") }, onClick = { expanded = false; onImageEdit() })
            DropdownMenuItem(text = { Text("動画編集") }, onClick = { expanded = false; onVideoEdit() })
        }
    }
}

/**
 * カタログアイテムのリスト形式表示。
 * 左側にサムネイル、右側にスレタイ・返信数を配置する、としあきアプリ風のレイアウト。
 * 画像とテキストは重ならないよう分離して配置する。
 */
@Composable
private fun CatalogListItem(
    item: ImageItem,
    onClick: () -> Unit,
    onImageLoadHttp404: (item: ImageItem, failedUrl: String) -> Unit,
    onImageLoadSuccess: (item: ImageItem, loadedUrl: String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = com.valoser.futaburakari.ui.theme.LocalSpacing.current.xs),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(com.valoser.futaburakari.ui.theme.LocalSpacing.current.s)
        ) {
            // 左側: サムネイル（固定サイズ）
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .aspectRatio(3f / 4f)
            ) {
                val displayUrl = when {
                    !item.lastVerifiedFullUrl.isNullOrBlank() -> item.lastVerifiedFullUrl
                    !item.previewUnavailable -> item.previewUrl
                    else -> null
                }

                if (!displayUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        modifier = Modifier.fillMaxSize(),
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(displayUrl)
                            .httpHeaders(
                                NetworkHeaders.Builder()
                                    .add("Referer", item.detailUrl)
                                    .add("Accept", "*/*")
                                    .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                                    .add("User-Agent", com.valoser.futaburakari.Ua.STRING)
                                    .build()
                            )
                            .listener(
                                onSuccess = { request, _ ->
                                    val loaded = request.data?.toString()
                                    if (loaded?.contains("/src/") == true && !item.hadFullSuccess) {
                                        onImageLoadSuccess(item, loaded)
                                    }
                                },
                                onError = { request, result ->
                                    val ex = result.throwable
                                    if (ex is HttpException && ex.response.code == 404) {
                                        val failed = request.data?.toString() ?: ""
                                        if (failed.isNotEmpty()) onImageLoadHttp404(item, failed)
                                    }
                                }
                            )
                            .build(),
                        imageLoader = LocalContext.current.imageLoader,
                        contentDescription = item.title,
                        loading = {
                            Box(Modifier.fillMaxSize()) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            }
                        },
                        error = {
                            Box(Modifier.fillMaxSize()) {
                                Text(
                                    text = "画像なし",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    )
                } else {
                    Box(Modifier.fillMaxSize()) {
                        Text(
                            text = "画像なし",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }

                // 動画アイコン
                val isVideo = displayUrl?.let { url ->
                    url.lowercase().endsWith(".webm") || url.lowercase().endsWith(".mp4") || url.lowercase().endsWith(".mkv")
                } ?: false
                if (isVideo) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                            .padding(com.valoser.futaburakari.ui.theme.LocalSpacing.current.xs)
                    )
                }
            }

            Spacer(modifier = Modifier.width(com.valoser.futaburakari.ui.theme.LocalSpacing.current.m))

            // 右側: スレタイと返信数
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.replyCount.isNullOrBlank()) {
                    Text(
                        text = "返信: ${item.replyCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

/**
 * カタログアイテムのカード表示。
 * 画像とタイトルを分離して配置（画像が上、タイトルが下）。
 * 動画拡張子（.webm/.mp4/.mkv）は中央に再生アイコンを重ねる。
 * エラー時の挙動: 検証済みのフル画像があればそれを優先し、失敗した場合はプレビューへフォールバック。
 * プレビューも取得できない場合は簡易プレースホルダを表示し、HTTP 404 は `onImageLoadHttp404` に通知して
 * ViewModel 側で代替 URL の探索・補正を試みる。
 */
@Composable
private fun CatalogCard(
    item: ImageItem,
    onClick: () -> Unit,
    onImageLoadHttp404: (item: ImageItem, failedUrl: String) -> Unit,
    onImageLoadSuccess: (item: ImageItem, loadedUrl: String) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(com.valoser.futaburakari.ui.theme.LocalSpacing.current.xs)
            .aspectRatio(3f / 4f), // カード全体を4:3に
        onClick = onClick,
        border = androidx.compose.foundation.BorderStroke(
            width = 2.dp,
            color = MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ViewModel の判定を信頼し、表示URLはモデルから決定
            val displayUrl = when {
                // 検証済みURLがあれば最優先（未検証フルは使用しない）
                !item.lastVerifiedFullUrl.isNullOrBlank() -> item.lastVerifiedFullUrl
                // プレビューを即時表示
                !item.previewUnavailable -> item.previewUrl
                else -> null
            }

            // 画像部分（タイトル領域を確保するため、weightで残りの空間を使用）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // 実表示サイズを Coil に伝えてキャッシュ共有を確実にする
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // 実表示幅そのものを使用（余白の二重減算を避ける）
                    val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                    val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
                    // 再挑戦経路はVMの404修正に一本化。UIからの能動的フル化要求は行わない。

                    if (!displayUrl.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            modifier = Modifier.fillMaxSize(),
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(displayUrl)
                                .size(Dimension.Pixels(widthPx.toInt()), Dimension.Pixels(heightPx.toInt()))
                                .precision(Precision.INEXACT)
                                // 画像（プレビュー⇄フル）切替時のフラッシュ感を抑える
                                .transitionFactory(CrossfadeTransition.Factory())
                                .httpHeaders(
                                    NetworkHeaders.Builder()
                                        .add("Referer", item.detailUrl)
                                        .add("Accept", "*/*")
                                        .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                                        .add("User-Agent", com.valoser.futaburakari.Ua.STRING)
                                        .build()
                                )
                                .listener(
                                    onSuccess = { request, _ ->
                                        val loaded = request.data?.toString()
                                        if (loaded?.contains("/src/") == true && !item.hadFullSuccess) {
                                            onImageLoadSuccess(item, loaded)
                                        }
                                    },
                                    onError = { request, result ->
                                        val ex = result.throwable
                                        if (ex is HttpException && ex.response.code == 404) {
                                            val failed = request.data?.toString() ?: ""
                                            if (failed.isNotEmpty()) onImageLoadHttp404(item, failed)
                                        }
                                    }
                                )
                            .build(),
                            imageLoader = LocalContext.current.imageLoader,
                            contentDescription = item.title,
                            loading = {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                            },
                            error = {
                                // フル画像の読み込みエラー時は、可能ならプレビュー画像を表示する
                                if (displayUrl == item.fullImageUrl && !item.previewUnavailable) {
                                    SubcomposeAsyncImage(
                                        modifier = Modifier.fillMaxSize(),
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(item.previewUrl)
                                            .size(Dimension.Pixels(widthPx.toInt()), Dimension.Pixels(heightPx.toInt()))
                                            .precision(Precision.EXACT)
                                            // フォールバック時もクロスフェードで切替を穏やかに
                                            .transitionFactory(CrossfadeTransition.Factory())
                                            .httpHeaders(
                                                NetworkHeaders.Builder()
                                                    .add("Referer", item.detailUrl)
                                                    .add("Accept", "*/*")
                                                    .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                                                    .add("User-Agent", com.valoser.futaburakari.Ua.STRING)
                                                    .build()
                                            )
                                            .build(),
                                        imageLoader = LocalContext.current.imageLoader,
                                        contentDescription = item.title,
                                        loading = {
                                            Box(modifier = Modifier.fillMaxSize()) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.align(Alignment.Center)
                                                )
                                            }
                                        },
                                        error = {
                                            Box(modifier = Modifier.fillMaxSize()) {
                                                Text(
                                                    text = "画像を表示できません",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.align(Alignment.Center)
                                                )
                                            }
                                        }
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        Text(
                                            text = "画像を表示できません",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.align(Alignment.Center)
                                        )
                                    }
                                }
                            }
                        )
                    } else {
                        val giveUp = item.preferPreviewOnly || item.previewUnavailable
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (!giveUp) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                            } else {
                                Text(
                                    text = "画像を表示できません",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }

                // 動画の場合は中央に再生アイコンを重ねる（表示URL基準）
                val isVideo = displayUrl?.let { url ->
                    url.lowercase().endsWith(".webm") || url.lowercase().endsWith(".mp4") || url.lowercase().endsWith(".mkv")
                } ?: false
                if (isVideo) {
                    Surface(
                        modifier = Modifier
                            .size(36.dp)
                            .align(Alignment.Center),
                        color = Color.Black.copy(alpha = 0.2f),
                        shape = MaterialTheme.shapes.small
                    ) {}
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // 返信数を右上に表示
                if (!item.replyCount.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(com.valoser.futaburakari.ui.theme.LocalSpacing.current.xs),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = item.replyCount,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(
                                horizontal = com.valoser.futaburakari.ui.theme.LocalSpacing.current.xs,
                                vertical = com.valoser.futaburakari.ui.theme.LocalSpacing.current.xxs
                            )
                        )
                    }
                }
            }

            // タイトル部分（画像の下に分離して配置、高さを固定）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(com.valoser.futaburakari.ui.theme.LocalSpacing.current.s)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * アイテムが画像を持っているかどうかを判定する。
 * プレビュー画像が利用不可の場合は画像なしと判定し、それ以外は画像ありとする。
 */
private fun hasImages(item: ImageItem): Boolean {
    return !item.previewUnavailable
}

/**
 * タイトルに対して NG ルールを適用してマッチ判定する。
 * `RuleType.TITLE` のみを対象とし、`MatchType` に応じて一致判定を行う。
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
