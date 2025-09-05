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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import coil.compose.AsyncImage
import com.valoser.futaburakari.HistoryEntry
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import com.valoser.futaburakari.R

/**
 * 履歴の並び替えモード。
 * - MIXED: 新着優先（未読や更新を加味した混在順）
 * - UPDATED: 最終更新時刻順
 * - VIEWED: 最終閲覧時刻順
 * - UNREAD: 未読件数順
 */
enum class HistorySortMode { MIXED, UPDATED, VIEWED, UNREAD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
/**
 * 閲覧履歴の一覧画面。
 * 未読のみ表示の切り替え、並び替え、全削除、スワイプによる削除を提供する。
 * （現状 Undo は未実装で、スワイプ確定時に即時削除する）
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
    // 右上のメニュー開閉状態
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // スワイプ削除の即時反映のためのローカル表示用リスト（Undo 未実装）
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
                    // 未読のみのトグルとその他メニュー
                    IconButton(onClick = onToggleUnreadOnly) {
                        Icon(Icons.Rounded.FilterList, contentDescription = if (showUnreadOnly) "未読のみ（ON）" else "未読のみ（OFF）")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "メニュー")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        // メニュー: 全削除と並び替えモードの選択（選択状態の表示は持たない）
                        DropdownMenuItem(
                            text = { Text("履歴をすべて削除") },
                            onClick = { menuExpanded = false; onClearAll() },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("並び替え: 新着優先") },
                            onClick = { menuExpanded = false; onSelectSort(HistorySortMode.MIXED) }
                        )
                        DropdownMenuItem(
                            text = { Text("並び替え: 更新順") },
                            onClick = { menuExpanded = false; onSelectSort(HistorySortMode.UPDATED) }
                        )
                        DropdownMenuItem(
                            text = { Text("並び替え: 閲覧順") },
                            onClick = { menuExpanded = false; onSelectSort(HistorySortMode.VIEWED) }
                        )
                        DropdownMenuItem(
                            text = { Text("並び替え: 未読数") },
                            onClick = { menuExpanded = false; onSelectSort(HistorySortMode.UNREAD) }
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
        // 将来的な Undo 提示に利用予定のスナックバー（現状は未使用）
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        if (localEntries.isEmpty()) {
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
                    val dismissState = rememberSwipeToDismissBoxState(
                        // スワイプ確定時に即時削除（Undoは一旦無効化）
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.StartToEnd || value == SwipeToDismissBoxValue.EndToStart) {
                                val current = localEntries
                                val idx = current.indexOfFirst { it.key == e.key }
                                if (idx >= 0) localEntries = current.toMutableList().also { it.removeAt(idx) }
                                onDeleteItem(e)
                                true
                            } else false
                        }
                    )
                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = true,
                        enableDismissFromEndToStart = true,
                        backgroundContent = {
                            DismissBackground(state = dismissState)
                        },
                        content = {
                            HistoryRow(entry = e, onClick = { onClickItem(e) })
                        }
                    )
                }
            }
        }
    }
}

@Composable
/**
 * 履歴の 1 行を表示する行コンポーザブル。
 * サムネイル、タイトル、URL、時刻、未読バッジを表示し、タップで `onClick` を呼びます。
 */
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable { onClick() }
    ) {
        val thumbModifier = Modifier
            .size(72.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(Color(0xFFBDBDBD))

        // サムネイルが無い場合はプレースホルダーのボックスを表示
        if (entry.thumbnailUrl.isNullOrBlank()) {
            Box(thumbModifier)
        } else {
            AsyncImage(
                model = entry.thumbnailUrl,
                contentDescription = null,
                modifier = thumbModifier
            )
        }

        Spacer(modifier = Modifier.size(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(2.dp))
            Text(entry.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = buildTimeText(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }

        // 未読がある場合のみバッジを表示（1000 以上は "999+"）
        if (entry.unreadCount > 0) {
            Spacer(modifier = Modifier.size(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = if (entry.unreadCount > 999) "999+" else entry.unreadCount.toString(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

    }
}

@Composable
/**
 * スワイプ削除時の背景コンテンツ。左右いずれの方向でも削除アイコンを表示します。
 */
private fun DismissBackground(state: androidx.compose.material3.SwipeToDismissBoxState) {
    val isStart = state.targetValue == SwipeToDismissBoxValue.StartToEnd
    val isEnd = state.targetValue == SwipeToDismissBoxValue.EndToStart
    val color = if (isStart || isEnd) MaterialTheme.colorScheme.errorContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 16.dp),
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
