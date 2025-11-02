/**
 * 画像カタログ（メイン一覧）画面の Compose 実装。
 *
 * 特徴:
 * - 更新: プル更新（PullToRefresh）で一覧を再読み込み。
 * - 体験: 可視範囲＋先読みの軽量プリフェッチでスクロールを滑らかに。
 * - 表示: カード下部に SurfaceVariant のタイトル領域を設け、返信数バッジは右上に配置。
 * - フィルタリング: OP画像なしスレッドは常時非表示に設定。
 * - エラー: フル画像の取得が失敗した場合は可能ならプレビューへフォールバックし、
 *          それも不可の場合は簡易プレースホルダを表示。
 *          404 検知時は `onImageLoadHttp404` を介して ViewModel に通知し、URL 補正を試みる。
 */
package com.valoser.futaburakari.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items as lazyListItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.size.Dimension
import coil3.size.Precision
import coil3.network.HttpException
import coil3.network.httpHeaders
import coil3.network.NetworkHeaders
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import coil3.compose.SubcomposeAsyncImage
import coil3.request.transitionFactory
import coil3.transition.CrossfadeTransition
import com.valoser.futaburakari.ImageItem
import com.valoser.futaburakari.MatchType
import com.valoser.futaburakari.NgRule
import com.valoser.futaburakari.RuleType
import com.valoser.futaburakari.ui.theme.LocalSpacing
import com.valoser.futaburakari.CatalogPrefetchHint
import com.valoser.futaburakari.ui.common.AppBarPosition

/**
 * 画像カタログの一覧画面（メイン画面）。
 *
 * 機能概要:
 * - 更新: プルリフレッシュを提供。
 * - 操作: トップバーは「再読み込み／ブックマーク選択／検索」の主要操作に絞り、
 *         右上メニューに補助操作（並び順・履歴・管理系）を集約。
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
 * - `topBarPosition`: トップバーの配置位置。
 * - `hasAnyBookmarks` / `hasSelectedBookmark`: ブックマークの登録・選択状況に応じて UI を調整するためのフラグ。
 * - `query`/`onQueryChange`: 検索クエリと変更ハンドラ。
 * - `onReload`: 更新アクション（プルリフレッシュから呼び出し）。
 * - `onPrefetchHint`: 可視範囲＋先読み分のプリフェッチ要求を通知するコールバック。
 * - `onSelectBookmark`: ブックマーク選択ダイアログ等を開くアクション。
 * - `onManageBookmarks`: ブックマーク管理画面を開くアクション（メニューから呼び出し）。
 * - `onOpenSettings`: 設定画面を開くアクション（メニューから呼び出し）。
 * - `onOpenHistory`: 履歴画面を開くアクション（メニューから呼び出し）。
 * - `onImageEdit`/`onBrowseLocalImages`: 画像編集／ローカル画像のメニュー操作。
 * - `onVideoEdit`: 動画編集のメニュー操作。
 * - `promptFeaturesEnabled`: プロンプト機能が有効な場合に追加メニューを表示するフラグ。
 * - `onToggleDisplayMode`: グリッド/リスト表示を即座に切り替えるためのハンドラ。
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
    topBarPosition: AppBarPosition = AppBarPosition.TOP,
    hasAnyBookmarks: Boolean,
    hasSelectedBookmark: Boolean,
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
    onToggleDisplayMode: () -> Unit,
    onItemClick: (ImageItem) -> Unit,
    ngRules: List<NgRule>,
    onImageLoadHttp404: (item: ImageItem, failedUrl: String) -> Unit,
    onImageLoadSuccess: (item: ImageItem, loadedUrl: String) -> Unit,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()

    // NG タイトルルールとクエリ、画像有無で絞り込み（常時OP画像なしスレッドを非表示）
    val hasNgFilters = ngRules.isNotEmpty()
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

    val density = LocalDensity.current
    val sPx by remember(sDp, density) { derivedStateOf { with(density) { sDp.toPx() } } }
    val xsPx by remember(xsDp, density) { derivedStateOf { with(density) { xsDp.toPx() } } }

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

    val toolbarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
        actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    )

    val toolbarWindowInsets = if (topBarPosition == AppBarPosition.TOP) {
        TopAppBarDefaults.windowInsets
    } else {
        WindowInsets(0, 0, 0, 0)
    }

    // システムナビゲーションバーのインセット（Androidの3ボタンナビゲーション対応）
    val systemBarsInsets = WindowInsets.systemBars
    val navigationBarHeight = with(LocalDensity.current) { systemBarsInsets.getBottom(this) }

    val focusManager = LocalFocusManager.current
    val toolbar: @Composable () -> Unit = {
        TopAppBar(
            title = {
                if (searching) {
                    TextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        placeholder = { Text(text = "検索") },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { onQueryChange(""); focusManager.clearFocus() }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "検索キーワードをクリア")
                                }
                            }
                        },
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
                // 最も頻繁に使う操作を前に配置
                IconButton(
                    onClick = {
                        if (hasAnyBookmarks) {
                            onSelectBookmark()
                        } else {
                            onManageBookmarks()
                        }
                    }
                ) {
                    Icon(Icons.Rounded.Bookmarks, contentDescription = "ブックマークを選択")
                }
                IconButton(onClick = {
                    if (searching) {
                        searching = false
                        focusManager.clearFocus()
                    } else {
                        searching = true
                    }
                }) {
                    Icon(Icons.Rounded.Search, contentDescription = "検索")
                }
                IconButton(
                    onClick = { if (!isLoading && hasSelectedBookmark) onReload() },
                    enabled = !isLoading && hasSelectedBookmark
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "再読み込み")
                }
                MoreMenu(
                    onToggleDisplayMode = onToggleDisplayMode,
                    onManageBookmarks = onManageBookmarks,
                    onSelectSortMode = onSelectSortMode,
                    onOpenHistory = onOpenHistory,
                    onOpenSettings = onOpenSettings,
                    onImageEdit = onImageEdit,
                    onVideoEdit = onVideoEdit,
                    onBrowseLocalImages = onBrowseLocalImages,
                    promptFeaturesEnabled = promptFeaturesEnabled,
                    hasSelectedBookmark = hasSelectedBookmark,
                    isListMode = isListMode,
                )
            },
            colors = toolbarColors,
            windowInsets = toolbarWindowInsets
        )
    }

    Scaffold(
        topBar = {
            if (topBarPosition == AppBarPosition.TOP) {
                toolbar()
            }
        },
        bottomBar = {
            if (topBarPosition == AppBarPosition.BOTTOM) {
                Column(
                    modifier = Modifier
                        // システムナビゲーションバーと重ならないようにパディング追加
                        .padding(bottom = with(LocalDensity.current) { navigationBarHeight.toDp() })
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    HorizontalDivider()
                    toolbar()
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            modifier = modifier
                .fillMaxSize()
                .padding(padding),
            state = pullState,
            isRefreshing = isLoading,
            onRefresh = onReload,
            indicator = {
                Box(modifier = Modifier.fillMaxWidth()) {
                    PullToRefreshDefaults.Indicator(
                        modifier = Modifier.align(Alignment.Center),
                        state = pullState,
                        isRefreshing = isLoading
                    )
                }
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    val showFilters = query.isNotBlank() || hasNgFilters
                    if (showFilters) {
                        ActiveFilterRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = LocalSpacing.current.s, vertical = LocalSpacing.current.xs),
                            query = query,
                            hasNgFilters = hasNgFilters,
                            onClearQuery = { onQueryChange("") }
                        )
                    }
                    if (isListMode) {
                        // リスト本体
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
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
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
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
                }

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                if (!isLoading && filtered.isEmpty()) {
                    EmptyCatalogState(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(LocalSpacing.current.l),
                        hasAnyBookmarks = hasAnyBookmarks,
                        hasSelectedBookmark = hasSelectedBookmark,
                        query = query,
                        hasNgFilters = hasNgFilters,
                        onSelectBookmark = onSelectBookmark,
                        onManageBookmarks = onManageBookmarks
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveFilterRow(
    modifier: Modifier = Modifier,
    query: String,
    hasNgFilters: Boolean,
    onClearQuery: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (query.isNotBlank()) {
            AssistChip(
                onClick = onClearQuery,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "検索") },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "検索: $query",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "検索条件をクリア",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            )
        }
        if (hasNgFilters) {
            AssistChip(
                onClick = {},
                enabled = false,
                leadingIcon = { Icon(Icons.Rounded.Block, contentDescription = "NGフィルタ") },
                label = { Text("NGフィルタ適用中") }
            )
        }
    }
}

@Composable
private fun EmptyCatalogState(
    modifier: Modifier = Modifier,
    hasAnyBookmarks: Boolean,
    hasSelectedBookmark: Boolean,
    query: String,
    hasNgFilters: Boolean,
    onSelectBookmark: () -> Unit,
    onManageBookmarks: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when {
            !hasAnyBookmarks -> {
                Text(
                    text = "ブックマークがまだ登録されていません",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(spacing.s))
                Text(
                    text = "「ブックマークを追加」で表示したい板を登録するとカタログが表示されます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(spacing.m))
                Button(onClick = onManageBookmarks) {
                    Text("ブックマークを追加")
                }
            }
            !hasSelectedBookmark -> {
                Text(
                    text = "表示するブックマークを選択してください",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(spacing.s))
                Button(onClick = onSelectBookmark) {
                    Text("ブックマークを選ぶ")
                }
                Spacer(modifier = Modifier.height(spacing.xs))
                TextButton(onClick = onManageBookmarks) {
                    Text("ブックマークを管理")
                }
            }
            else -> {
                Text(
                    text = "表示できるスレッドがありません",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(spacing.s))
                val hints = buildList {
                    if (query.isNotBlank()) add("検索キーワード")
                    if (hasNgFilters) add("NG設定")
                }
                val hintText = if (hints.isEmpty()) {
                    "一覧を更新するか、別のブックマークを選択してください。"
                } else {
                    hints.joinToString("・", postfix = "を確認してください。")
                }
                Text(
                    text = hintText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * 右上「その他」メニュー。ブックマーク管理や編集系の操作を集約し、
 * プロンプト機能が有効な場合のみ「ローカル画像を開く」を追加表示する。
 * 選択時はメニューを閉じてから各ハンドラを呼び出す。
 */
@Composable
private fun MoreMenu(
    onToggleDisplayMode: () -> Unit,
    onManageBookmarks: () -> Unit,
    onSelectSortMode: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onImageEdit: () -> Unit,
    onVideoEdit: () -> Unit,
    onBrowseLocalImages: () -> Unit,
    promptFeaturesEnabled: Boolean,
    hasSelectedBookmark: Boolean,
    isListMode: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "その他")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // よく使う操作グループ
            DropdownMenuItem(
                text = { Text("並び順") },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "並び順") },
                onClick = { expanded = false; onSelectSortMode() },
                enabled = hasSelectedBookmark
            )
            DropdownMenuItem(
                text = { Text(if (isListMode) "グリッド表示に切り替え" else "リスト表示に切り替え") },
                leadingIcon = {
                    val icon = if (isListMode) Icons.Rounded.GridView else Icons.Rounded.ViewList
                    Icon(icon, contentDescription = "表示切替")
                },
                onClick = { expanded = false; onToggleDisplayMode() }
            )
            DropdownMenuItem(
                text = { Text("履歴") },
                leadingIcon = { Icon(Icons.Rounded.History, contentDescription = "履歴") },
                onClick = { expanded = false; onOpenHistory() }
            )

            HorizontalDivider()

            // 管理系操作グループ
            DropdownMenuItem(
                text = { Text("ブックマーク管理") },
                onClick = { expanded = false; onManageBookmarks() }
            )
            DropdownMenuItem(
                text = { Text("設定") },
                leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = "設定") },
                onClick = { expanded = false; onOpenSettings() }
            )

            HorizontalDivider()

            // 編集系操作グループ
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
        onClick = onClick,
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        // Ripple effectを明確にするための設定
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
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
        ),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        // Ripple effectを明確にするための設定
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
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
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(MaterialTheme.colorScheme.errorContainer),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Column(
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    androidx.compose.material3.Icon(
                                                        imageVector = androidx.compose.material.icons.Icons.Rounded.BrokenImage,
                                                        contentDescription = "画像エラー",
                                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                                        modifier = Modifier.size(32.dp)
                                                    )
                                                    Spacer(modifier = Modifier.height(com.valoser.futaburakari.ui.theme.LocalSpacing.current.xs))
                                                    Text(
                                                        text = "読み込みエラー",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        color = MaterialTheme.colorScheme.onErrorContainer
                                                    )
                                                }
                                            }
                                        }
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.errorContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            androidx.compose.material3.Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Rounded.BrokenImage,
                                                contentDescription = "画像エラー",
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.size(32.dp)
                                            )
                                            Spacer(modifier = Modifier.height(com.valoser.futaburakari.ui.theme.LocalSpacing.current.xs))
                                            Text(
                                                text = "読み込みエラー",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
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
