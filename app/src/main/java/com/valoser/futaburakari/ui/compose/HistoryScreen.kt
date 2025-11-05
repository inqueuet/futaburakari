package com.valoser.futaburakari.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.valoser.futaburakari.HistoryEntry
import com.valoser.futaburakari.HistoryManager
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import com.valoser.futaburakari.R
import com.valoser.futaburakari.image.ImageKeys
import com.valoser.futaburakari.ui.theme.LocalSpacing
import com.valoser.futaburakari.cache.DetailCacheManagerProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 履歴の並び替えモード。
 * - MIXED: 新着優先（未読や更新を加味した混在順）
 * - UPDATED: 最終更新時刻順
 * - VIEWED: 最終閲覧時刻順
 * - UNREAD: 未読件数順
 */
enum class HistorySortMode { MIXED, UPDATED, VIEWED, UNREAD }

/**
 * 閲覧履歴の一覧画面。
 * 未読のみ表示の切り替え、並び替え、全削除、スワイプによる削除を提供する。
 * 削除時はスナックバーで Undo を提示し、未読フィルタ/並び替えはシートに集約した操作で変更する。
 *
 * パラメータ:
 * - `title`: 上部アプリバーのタイトル文言。
 * - `entries`: 表示する履歴エントリのリスト（表示順は呼び出し側に依存）。
 * - `showUnreadOnly`: 未読のみ表示フラグ。
 * - `sortMode`: 並び替えモード。
 * - `onBack`: 戻る押下時のハンドラ。
 * - `onToggleUnreadOnly`: 未読のみのトグル切り替えハンドラ。
 * - `onSelectSort`: 並び替えモード選択時のハンドラ。
 * - `onClearAll`: 履歴を全削除するハンドラ。
 * - `onClickItem`: 行タップ時のハンドラ。
 * - `onDeleteItem`: 行スワイプ確定時の削除ハンドラ。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    title: String,
    entries: List<HistoryEntry>,
    showUnreadOnly: Boolean,
    sortMode: HistorySortMode,
    onBack: () -> Unit,
    onToggleUnreadOnly: () -> Unit,
    onSelectSort: (HistorySortMode) -> Unit,
    onClearAll: () -> Unit,
    onClickItem: (HistoryEntry) -> Unit,
    onDeleteItem: (HistoryEntry) -> Unit,
) {
    // 右上のメニュー開閉状態と表示設定シートの制御
    var menuExpanded by remember { mutableStateOf(false) }
    var filterSheetVisible by remember { mutableStateOf(false) }
    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // スワイプ削除の即時反映のためのローカル表示用リスト（Undo あり）
    var localEntries by remember(entries) { mutableStateOf(entries) }
    LaunchedEffect(entries) { localEntries = entries }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    val filterActive = showUnreadOnly || sortMode != HistorySortMode.MIXED
                    // 並び替え・未読フィルタをまとめた設定シートを開く
                    IconButton(onClick = { filterSheetVisible = true }) {
                        Icon(
                            imageVector = Icons.Rounded.FilterList,
                            contentDescription = "表示設定",
                            tint = if (filterActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "メニュー")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        // メニュー: 全削除のみ提供
                        DropdownMenuItem(
                            text = { Text("履歴をすべて削除") },
                            onClick = { menuExpanded = false; onClearAll() },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) }
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                )
            )
        },
        // スワイプ削除の Undo 提示に利用するスナックバー
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        AnimatedContent(
            targetState = localEntries.isEmpty(),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "history-empty-switch"
        ) { isEmpty ->
            if (isEmpty) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    val ctx = LocalContext.current
                    Text(ctx.getString(R.string.no_history), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    // 安定したキーとして `HistoryEntry.key` を使用（表示順は渡された `entries` に従う）
                    items(localEntries, key = { it.key }) { e ->
                        val dismissState = rememberSwipeToDismissBoxState()
                        LaunchedEffect(dismissState.currentValue) {
                            val value = dismissState.currentValue
                            if (value == SwipeToDismissBoxValue.StartToEnd || value == SwipeToDismissBoxValue.EndToStart) {
                                val current = localEntries
                                val idx = current.indexOfFirst { it.key == e.key }
                                if (idx >= 0) {
                                    localEntries = current.toMutableList().also { it.removeAt(idx) }
                                    dismissState.reset()
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = context.getString(R.string.history_item_deleted),
                                            actionLabel = context.getString(R.string.undo),
                                            withDismissAction = true,
                                            duration = SnackbarDuration.Short
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            localEntries = localEntries.toMutableList().also { list ->
                                                val insertIndex = idx.coerceIn(0, list.size)
                                                list.add(insertIndex, e)
                                            }
                                        } else {
                                            onDeleteItem(e)
                                        }
                                    }
                                } else {
                                    dismissState.reset()
                                }
                            }
                        }
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = true,
                            enableDismissFromEndToStart = true,
                            backgroundContent = {
                                DismissBackground(state = dismissState)
                            },
                            content = {
                                // 履歴の1行を描画（アニメーションは環境依存のため一旦除外）
                                HistoryRow(entry = e, onClick = { onClickItem(e) })
                            }
                        )
                    }
                }
            }
        }
    }

    if (filterSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { filterSheetVisible = false },
            sheetState = filterSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.m)
            ) {
                Text("表示設定", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(LocalSpacing.current.m))
                Text("並び替え", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(LocalSpacing.current.s))
                val sortOptions = listOf(
                    HistorySortMode.MIXED to "新着優先",
                    HistorySortMode.UPDATED to "更新順",
                    HistorySortMode.VIEWED to "閲覧順",
                    HistorySortMode.UNREAD to "未読数"
                )
                sortOptions.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = sortMode == mode,
                                onClick = {
                                    if (sortMode != mode) onSelectSort(mode)
                                },
                                role = Role.RadioButton
                            )
                            .padding(vertical = LocalSpacing.current.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = sortMode == mode,
                            onClick = {
                                if (sortMode != mode) onSelectSort(mode)
                            }
                        )
                        Spacer(Modifier.width(LocalSpacing.current.m))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(LocalSpacing.current.m))
                HorizontalDivider()
                Spacer(Modifier.height(LocalSpacing.current.m))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = showUnreadOnly,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                if (checked != showUnreadOnly) onToggleUnreadOnly()
                            }
                        )
                        .padding(vertical = LocalSpacing.current.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("未読のみ表示", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(LocalSpacing.current.xxs))
                        Text(
                            "未読の履歴だけを一覧に表示します",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = showUnreadOnly,
                        onCheckedChange = null
                    )
                }
                Spacer(Modifier.height(LocalSpacing.current.m))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { filterSheetVisible = false }) {
                        Text("閉じる")
                    }
                }
            }
        }
    }
}

/**
 * 履歴の 1 行を表示する行コンポーザブル。
 * サムネイル、タイトル、URL、時刻、未読バッジを表示し、タップで `onClick` を呼びます。
 */
@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalSpacing.current.m, vertical = LocalSpacing.current.m)
            .clickable { onClick() }
    ) {
        val thumbModifier = Modifier
            .size(72.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)

        val context = LocalContext.current
        val appContext = context.applicationContext
        val cacheManager = remember(appContext) { DetailCacheManagerProvider.get(appContext) }

        val localFallbackPath by produceState<String?>(initialValue = null, entry.url) {
            value = withContext(Dispatchers.IO) {
                val dir = cacheManager.getArchiveDirForUrl(entry.url)
                val files = dir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".jpg", ignoreCase = true) ||
                        file.name.endsWith(".jpeg", ignoreCase = true) ||
                        file.name.endsWith(".png", ignoreCase = true) ||
                        file.name.endsWith(".webp", ignoreCase = true) ||
                        file.name.endsWith(".avif", ignoreCase = true))
                } ?: emptyArray()
                files.maxByOrNull { it.lastModified() }?.absolutePath
            }
        }

        LaunchedEffect(entry.url, localFallbackPath) {
            if (entry.thumbnailUrl.isNullOrBlank() && !localFallbackPath.isNullOrBlank()) {
                HistoryManager.updateThumbnail(context, entry.url, "file://${localFallbackPath}")
            }
        }

        val effectiveThumbUrl = entry.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: localFallbackPath?.let { "file://$it" }

        if (effectiveThumbUrl == null) {
            Box(thumbModifier, contentAlignment = Alignment.Center) {
                Text(
                    text = "画像なし",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(effectiveThumbUrl)
                    .memoryCacheKey(ImageKeys.thumb(effectiveThumbUrl))
                    .placeholderMemoryCacheKey(ImageKeys.thumb(effectiveThumbUrl))
                    .diskCacheKey(effectiveThumbUrl)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .apply {
                        entry.thumbnailReferer()?.let { referer ->
                            httpHeaders(buildHistoryHeaders(referer))
                        }
                    }
                    .build(),
                contentDescription = null,
                modifier = thumbModifier
            )
        }

        Spacer(modifier = Modifier.size(LocalSpacing.current.m))

        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(LocalSpacing.current.xxs))
            Text(entry.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(LocalSpacing.current.xxs))
            Text(
                text = buildTimeText(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        // 未読がある場合のみバッジを表示（1000 以上は "999+"）
        if (entry.unreadCount > 0) {
            Spacer(modifier = Modifier.size(LocalSpacing.current.s))
            Surface(
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (entry.unreadCount > 999) "999+" else entry.unreadCount.toString(),
                    modifier = Modifier.padding(horizontal = LocalSpacing.current.s, vertical = LocalSpacing.current.xxs),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

    }
}

/**
 * スワイプ削除時の背景コンテンツ。左右いずれの方向でも削除アイコンを表示します。
 */
@Composable
private fun DismissBackground(state: androidx.compose.material3.SwipeToDismissBoxState) {
    val isStart = state.targetValue == SwipeToDismissBoxValue.StartToEnd
    val isEnd = state.targetValue == SwipeToDismissBoxValue.EndToStart
    val color = if (isStart || isEnd) MaterialTheme.colorScheme.errorContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = LocalSpacing.current.l),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isStart) {
            Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(modifier = Modifier)
        } else if (isEnd) {
            Spacer(modifier = Modifier)
            Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
        } else {
            Spacer(modifier = Modifier)
            Spacer(modifier = Modifier)
        }
    }
}

/**
 * Coil リクエストに設定する Referer 値を履歴エントリから推定する。
 * スレッド URL が設定されていれば最優先で利用し、なければページ URL を fallback とする。
 */
private fun HistoryEntry.thumbnailReferer(): String? {
    return when {
        !threadUrl.isNullOrBlank() -> threadUrl
        url.isNotBlank() -> url
        else -> null
    }
}

private fun buildHistoryHeaders(referer: String): NetworkHeaders {
    return NetworkHeaders.Builder()
        .add("Referer", referer)
        .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .add("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
        .build()
}

/**
 * 行に表示する時刻テキストを組み立てる。
 * アーカイブ > 未読ありの更新時刻 > 最終閲覧時刻 の優先順で表示。
 */
private fun buildTimeText(item: HistoryEntry): String {
    val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
    return if (item.isArchived && item.archivedAt > 0L) {
        val t = df.format(java.util.Date(item.archivedAt))
        "アーカイブ: $t"
    } else if (item.unreadCount > 0 && item.lastUpdatedAt > 0L) {
        val t = df.format(java.util.Date(item.lastUpdatedAt))
        "更新: $t  •  未読 ${item.unreadCount}"
    } else {
        val t = df.format(java.util.Date(item.lastViewedAt))
        "閲覧: $t"
    }
}
