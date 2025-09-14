/**
 * 画像カタログ（メイン一覧）画面の Compose 実装。
 *
 * 特徴:
 * - 更新: プル更新（PullToRefresh）と、端での強いオーバースクロール（バウンス）検知での自動再読み込み。
 * - 体験: 可視範囲＋先読みの軽量プリフェッチでスクロールを滑らかに。
 * - 表示: カード下部にグラデーション＋タイトル、右下に返信数バッジ。
 * - エラー: プレビューは使用せず、フル画像の取得が失敗した場合は簡易プレースホルダを表示。
 *          404 検知時は `onImageLoadHttp404` を介して ViewModel に通知し、URL 補正を試みる。
 */
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
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
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
import coil3.request.ErrorResult
import coil3.request.SuccessResult
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

/**
 * 画像カタログの一覧画面（メイン画面）。
 *
 * 機能概要:
 * - 更新: プルリフレッシュに加え、端での強いオーバースクロール（バウンス）でも再読み込みを実行（連発抑止あり）。
 * - 操作: トップバーに「再読み込み／ブックマーク選択／履歴／検索」を配置。
 *         右上メニューには「ブックマーク管理 → 設定 → ローカル画像を開く → 画像編集」を用意。
 * - トップバー: 通常時はサブタイトル（選択中ブックマーク名）のみを大きく表示。タイトルは非表示。
 *               検索中はタイトル領域を検索ボックスに切り替える。
 * - 絞込: NG タイトルルールと検索クエリで一覧をフィルタし、グリッド表示。
 * - 体験: 可視範囲＋先読み分のみを軽量プリフェッチしてスクロールを滑らかにする。
 * - 表示: カード下部にグラデーションとタイトル、右下に返信数バッジを重ねて視認性を確保。
 * - エラー: プレビューにフォールバックせず、失敗時は簡易プレースホルダを表示。
 *
 * パラメータ:
 * - `modifier`: ルートレイアウト用の修飾子。
 * - `title`/`subtitle`: 上部タイトル/サブタイトル（通常はタイトルを表示せず、サブタイトルのみ表示）。
 * - `items`: 表示対象のアイテム一覧（呼び出し側で取得）。
 * - `isLoading`: 読み込み中インジケータの表示制御。
 * - `spanCount`: グリッド列数。
 * - `query`/`onQueryChange`: 検索クエリと変更ハンドラ。
 * - `onReload`: 更新アクション（プル/バウンス発火時も呼ぶ）。
 * - `onSelectBookmark`: ブックマーク選択ダイアログ等を開くアクション。
 * - `onManageBookmarks`: ブックマーク管理画面を開くアクション（メニューから呼び出し）。
 * - `onOpenSettings`: 設定画面を開くアクション（メニューから呼び出し）。
 * - `onOpenHistory`: 履歴画面を開くアクション（トップバーのアイコン）。
 * - `onImageEdit`/`onBrowseLocalImages`: 画像編集／ローカル画像のメニュー操作。
 * - `onItemClick`: アイテムタップ時のハンドラ。
 * - `ngRules`: NG タイトルルール一覧（TITLE のみ対象）。
 * - `onImageLoadHttp404`: 画像ロードが 404 で失敗した際に呼ばれるコールバック。
 *                         ViewModel 側で URL 補正（代替URLの探索・差し替え）を行うために使用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
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
    onImageLoadHttp404: (item: ImageItem, failedUrl: String) -> Unit,
    onImageLoadSuccess: (item: ImageItem, loadedUrl: String) -> Unit,
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

    // CompositionLocals はコンポーズ文脈内で取得しておき、
    // LaunchedEffect 内では非 @Composable な値（Dp など）として参照する
    val spacing = LocalSpacing.current
    val sDp = spacing.s
    val xsDp = spacing.xs

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

    // 404のローカル抑止は廃止（VM側の回数管理と候補探索に委任）

    // 軽量プリフェッチ（可視範囲＋先読み分のみを事前ロード）
    // 実表示サイズと同一のサイズでプリフェッチし、メモリキャッシュのヒット率を最大化する
    val context = LocalContext.current
    val prefetchDensity = LocalDensity.current
    LaunchedEffect(items, gridState, spanCount) {
        snapshotFlow {
            val layout = gridState.layoutInfo
            val first = layout.visibleItemsInfo.firstOrNull()?.index ?: 0
            val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
            // ビューポート幅
            val viewportWidthPx = layout.viewportSize.width
            Triple(first, last, viewportWidthPx)
        }
            .distinctUntilChanged()
            .collectLatest { (first, last, viewportWidthPx) ->
                if (last <= 0 || items.isEmpty()) return@collectLatest

                // 1行あたりのアイテム幅（コンテンツ左右余白＋カード内余白を考慮）
                val sPx = with(prefetchDensity) { sDp.toPx() }
                val xsPx = with(prefetchDensity) { xsDp.toPx() }
                val contentWidthPx = (viewportWidthPx - (sPx * 2)).coerceAtLeast(0f)
                val cellWidthPx = ((contentWidthPx / spanCount) - (xsPx * 2)).coerceAtLeast(64f)
                val cellHeightPx = (cellWidthPx * 4f / 3f)

                // 先読み行数は画面内の行数の約2倍（2画面分）
                val visibleCount = (last - first + 1).coerceAtLeast(spanCount)
                val rowsVisible = (visibleCount + spanCount - 1) / spanCount
                val prefetchRows = (rowsVisible * 2).coerceAtLeast(1)
                val prefetchAhead = (prefetchRows * spanCount)

                val end = (last + prefetchAhead).coerceAtMost(items.lastIndex)
                val start = first.coerceAtLeast(0)

                // 1) 先にプレビューをプリフェッチ（画面に入った瞬間に確実に出す）
                val previewTargets = items.subList(start, end + 1)
                    .map { it.detailUrl to it.previewUrl }

                previewTargets.chunked(4).forEach { batch ->
                    batch.forEach { (referer, url) ->
                        val req = ImageRequest.Builder(context)
                            .data(url)
                            .size(Dimension.Pixels(cellWidthPx.toInt()), Dimension.Pixels(cellHeightPx.toInt()))
                            .precision(Precision.EXACT)
                            // 優先度は未使用（互換性のため）。先にプレビューをキューへ入れる運用でカバー
                            .httpHeaders(
                                NetworkHeaders.Builder()
                                    .add("Referer", referer)
                                    .add("Accept", "*/*")
                                    .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                                    .add("User-Agent", com.valoser.futaburakari.Ua.STRING)
                                    .build()
                            )
                            .build()
                        context.imageLoader.enqueue(req)
                    }
                    delay(5)
                }

                // 2) 未検証フルは控えめに裏取り（LOW優先度）
                val prefetchTargets = items.subList(start, end + 1)
                    .mapNotNull { item ->
                        // プレビュー不可かつフル未確定ならプリフェッチしない
                        val full = item.fullImageUrl
                        val preferPreview = item.preferPreviewOnly
                        val hadFull = item.hadFullSuccess
                        // 大容量動画はプリフェッチ対象から除外
                        val isVideo = full?.lowercase()?.let { u ->
                            u.endsWith(".webm") || u.endsWith(".mp4") || u.endsWith(".mkv")
                        } ?: false
                        when {
                            !full.isNullOrBlank() && !preferPreview && !hadFull && !isVideo -> Triple(item, item.detailUrl, full) // 未検証フルを裏取り
                            else -> null // プレビューはプリフェッチしない/動画は除外
                        }
                    }

                // 過度な同時リクエストを避けつつ並列プリフェッチ（ユーザー設定の同時数に合わせる）
                val concurrency = com.valoser.futaburakari.AppPreferences.getFullUpgradeConcurrency(context).coerceIn(1, 4)
                prefetchTargets.chunked(concurrency).forEach { batch ->
                    batch.forEach { (item, referer, url) ->
                        val req = ImageRequest.Builder(context)
                            .data(url)
                            .size(Dimension.Pixels(cellWidthPx.toInt()), Dimension.Pixels(cellHeightPx.toInt()))
                            .precision(Precision.EXACT)
                            // 優先度は未使用（互換性のため）。バッチ1件＋待機で実質低優先度化
                            .httpHeaders(
                                NetworkHeaders.Builder()
                                    .add("Referer", referer)
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
                            .build()
                        context.imageLoader.enqueue(req)
                    }
                    // キュー充満速度を抑制（軽く間隔を空ける）
                    delay(50)
                }
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
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Rounded.History, contentDescription = "履歴")
                    }
                    IconButton(onClick = { searching = !searching }) {
                        Icon(Icons.Rounded.Search, contentDescription = "検索")
                    }
                    MoreMenu(
                        onManageBookmarks = onManageBookmarks,
                        onImageEdit = onImageEdit,
                        onBrowseLocalImages = onBrowseLocalImages,
                        onSettings = onOpenSettings,
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

            // 中央インジケータ（ぐるぐる）
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

/**
 * 右上「その他」メニュー。ブックマーク管理・設定・ローカル画像・画像編集を提供。
 * 選択時はメニューを閉じてから各ハンドラを呼び出す。
 */
@Composable
private fun MoreMenu(
    onManageBookmarks: () -> Unit,
    onImageEdit: () -> Unit,
    onBrowseLocalImages: () -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "その他")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("ブックマーク管理") }, onClick = { expanded = false; onManageBookmarks() })
            DropdownMenuItem(text = { Text("設定") }, onClick = { expanded = false; onSettings() })
            DropdownMenuItem(text = { Text("ローカル画像を開く") }, onClick = { expanded = false; onBrowseLocalImages() })
            DropdownMenuItem(text = { Text("画像編集") }, onClick = { expanded = false; onImageEdit() })
        }
    }
}

/**
 * カタログアイテムのカード表示。
 * 下部グラデーション上にタイトルを配置し、返信数は右下バッジとして上位レイヤーに重ねる。
 * 動画拡張子（.webm/.mp4/.mkv）は中央に再生アイコンを重ねる。
 * エラー時の挙動: プレビューは使用せず、フル画像の取得を試行。
 * 取得に失敗（例: HTTP 4xx）した場合は簡易プレースホルダを表示し、404 は `onImageLoadHttp404` に通知して
 * ViewModel 側で代替URLの探索・補正を試みる。
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
            .padding(com.valoser.futaburakari.ui.theme.LocalSpacing.current.xs),
        onClick = onClick
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // ViewModel の判定を信頼し、表示URLはモデルから決定
            val displayUrl = when {
                // 検証済みURLがあれば最優先（未検証フルは使用しない）
                !item.lastVerifiedFullUrl.isNullOrBlank() -> item.lastVerifiedFullUrl
                // プレビューを即時表示
                !item.previewUnavailable -> item.previewUrl
                else -> null
            }

            // サムネイル（幅:高さ = 3:4）でアイテムの高さを一定に保つ
            // 実表示サイズを Coil に伝えてキャッシュ共有を確実にする
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // 実表示幅そのものを使用（余白の二重減算を避ける）
                val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                val heightPx = (widthPx * 4f / 3f)
                // 再挑戦経路はVMの404修正に一本化。UIからの能動的フル化要求は行わない。

                if (!displayUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f),
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(displayUrl)
                            .size(Dimension.Pixels(widthPx.toInt()), Dimension.Pixels(heightPx.toInt()))
                            .precision(Precision.EXACT)
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
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(3f / 4f)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        },
                        error = {
                            // フル画像の読み込みエラー時は、可能ならプレビュー画像を表示する
                            if (displayUrl == item.fullImageUrl && !item.previewUnavailable) {
                                SubcomposeAsyncImage(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(3f / 4f),
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
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(3f / 4f)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        }
                                    },
                                    error = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(3f / 4f)
                                        ) {
                                            Text(
                                                text = "画像を表示できません",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.align(Alignment.Center)
                                            )
                                        }
                                    }
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(3f / 4f)
                                ) {
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
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f)
                    ) {
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

            // 注記は不要（画像が表示されればOKのためUI反映しない）

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
                    .padding(start = com.valoser.futaburakari.ui.theme.LocalSpacing.current.s, end = com.valoser.futaburakari.ui.theme.LocalSpacing.current.s, bottom = com.valoser.futaburakari.ui.theme.LocalSpacing.current.s)
            )

            // 返信数バッジ（右下）。タイトル/オーバーレイより後に描画し、上に重ねる
            if (!item.replyCount.isNullOrBlank()) {
                Text(
                    text = item.replyCount,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(com.valoser.futaburakari.ui.theme.LocalSpacing.current.xs)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = com.valoser.futaburakari.ui.theme.LocalSpacing.current.s, vertical = com.valoser.futaburakari.ui.theme.LocalSpacing.current.xxs)
                )
            }
        }
    }
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
