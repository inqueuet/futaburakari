package com.valoser.futaburakari.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.Bookmark
import com.valoser.futaburakari.ui.theme.LocalSpacing

/**
 * ブックマークの一覧表示と追加/編集/削除を行う画面コンポーザブル。
 *
 * 機能概要:
 * - 右下の FAB で新規追加ダイアログを開く。
 * - 各行の編集/削除アイコンで編集ダイアログ・削除確認を表示。
 * - 行のタップで `onSelectBookmark` をコールバック。
 *
 * パラメータ:
 * - `title`: 上部アプリバーのタイトル文言。
 * - `bookmarks`: 一覧表示するブックマークリスト。
 * - `selectedBookmarkUrl`: 現在選択されているブックマークURL（クエリを含む場合あり）。
 * - `onBack`: 上部ナビゲーションの戻る押下時のハンドラ。
 * - `onAddBookmark`: 追加確定時（名前, URL）のハンドラ。
 * - `onUpdateBookmark`: 編集確定時（旧URL, 名前, 新URL）のハンドラ。
 * - `onDeleteBookmark`: 削除確定時のハンドラ。
 * - `onSelectBookmark`: 行タップ時に選択されたブックマークを通知するハンドラ。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookmarkScreen(
    title: String,
    bookmarks: List<Bookmark>,
    selectedBookmarkUrl: String?,
    onBack: () -> Unit,
    onAddBookmark: (name: String, url: String) -> Unit,
    onUpdateBookmark: (oldUrl: String, name: String, url: String) -> Unit,
    onDeleteBookmark: (bookmark: Bookmark) -> Unit,
    onSelectBookmark: (bookmark: Bookmark) -> Unit,
) {
    val showEditor = remember { mutableStateOf(false) }
    val editorTitle = remember { mutableStateOf("") }
    val nameState = remember { mutableStateOf("") }
    val urlState = remember { mutableStateOf("") }
    val editingOldUrl = remember { mutableStateOf<String?>(null) }

    val showDeleteConfirm = remember { mutableStateOf(false) }
    val pendingDelete = remember { mutableStateOf<Bookmark?>(null) }
    val currentSelectedUrl = selectedBookmarkUrl
    val suggestedPresets = remember(bookmarks) {
        PRESET_BOOKMARKS.filter { preset ->
            val presetBase = preset.url.substringBefore("?")
            bookmarks.none { it.url.substringBefore("?").equals(presetBase, ignoreCase = true) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
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
        floatingActionButton = {
            // 新規追加ダイアログを開く
            FloatingActionButton(onClick = {
                editorTitle.value = "ブックマークを追加"
                nameState.value = ""
                urlState.value = ""
                editingOldUrl.value = null
                showEditor.value = true
            }) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = "追加")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = LocalSpacing.current.s)
        ) {
            if (suggestedPresets.isNotEmpty()) {
                item {
                    PresetBookmarkSection(
                        presets = suggestedPresets,
                        onAddPreset = { preset ->
                            onAddBookmark(preset.name, preset.url)
                        }
                    )
                }
            }
            if (bookmarks.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.xl),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "ブックマークが登録されていません",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(LocalSpacing.current.s))
                        Text(
                            text = "右下の＋ボタンから追加してください。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // URL をキーに安定した項目識別を行い、行を描画（ソートは行わない）
                items(bookmarks, key = { it.url }) { bm ->
                    BookmarkRow(
                        bookmark = bm,
                        selected = sameBookmarkUrl(currentSelectedUrl, bm.url),
                        onEdit = {
                            // 既存ブックマークを編集するためにダイアログへ値を投入
                            editorTitle.value = "ブックマークを編集"
                            nameState.value = bm.name
                            urlState.value = bm.url
                            editingOldUrl.value = bm.url
                            showEditor.value = true
                        },
                        onDelete = {
                            // 削除確認ダイアログ表示のために対象を保持
                            pendingDelete.value = bm
                            showDeleteConfirm.value = true
                        },
                        onClick = { onSelectBookmark(bm) }
                    )
                }
            }
        }
    }

    if (showEditor.value) {
        // 追加/編集共通の入力ダイアログ
        EditBookmarkDialog(
            title = editorTitle.value,
            nameState = nameState,
            urlState = urlState,
            onDismiss = { showEditor.value = false },
            onConfirm = {
                val oldUrl = editingOldUrl.value
                if (oldUrl == null) {
                    onAddBookmark(nameState.value.trim(), urlState.value.trim())
                } else {
                    onUpdateBookmark(oldUrl, nameState.value.trim(), urlState.value.trim())
                }
                showEditor.value = false
            }
        )
    }

    if (showDeleteConfirm.value) {
        val target = pendingDelete.value
        if (target != null) {
            // 削除確認ダイアログ
            ConfirmDialog(
                message = "\"${target.name}\" を削除しますか？",
                onConfirm = {
                    onDeleteBookmark(target)
                    showDeleteConfirm.value = false
                },
                onDismiss = { showDeleteConfirm.value = false }
            )
        }
    }
}

private fun sameBookmarkUrl(selected: String?, candidate: String): Boolean {
    if (selected.isNullOrBlank()) return false
    if (selected.equals(candidate, ignoreCase = true)) return true
    val selectedBase = selected.substringBefore("?")
    val candidateBase = candidate.substringBefore("?")
    return selectedBase.equals(candidateBase, ignoreCase = true)
}

private fun isValidCatalogUrl(url: String): Boolean {
    if (url.isBlank()) return false
    val regex = Regex("^https?://[\\w.-]+/.+/futaba\\.php(?:\\?.*)?\$", RegexOption.IGNORE_CASE)
    return regex.matches(url.trim())
}

private val PRESET_BOOKMARKS = listOf(
    Bookmark("二次元裏", "https://may.2chan.net/b/futaba.php"),
    Bookmark("実況", "https://dec.2chan.net/50/futaba.php"),
    Bookmark("どうぶつ", "https://dat.2chan.net/d/futaba.php"),
    Bookmark("しょくぶつ", "https://zip.2chan.net/z/futaba.php"),
)

@Composable
private fun PresetBookmarkSection(
    presets: List<Bookmark>,
    onAddPreset: (Bookmark) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.l, vertical = spacing.s)
    ) {
        Text(
            text = "おすすめの板",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.s),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            presets.forEach { preset ->
                AssistChip(
                    onClick = { onAddPreset(preset) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "プリセットを追加",
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text(preset.name) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = "URL は「https://*.2chan.net/*/futaba.php」の形式です。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * ブックマークリストの 1 行を表示するコンポーザブル。
 * 行のタップで `onClick` を呼び、右側のアイコンで編集/削除を行う。
 *
 * パラメータ:
 * - `bookmark`: 表示対象のブックマーク。
 * - `selected`: 現在選択中かどうか。選択中はラジオボタンと背景で強調表示する。
 * - `onEdit`: 編集アイコン押下時のハンドラ。
 * - `onDelete`: 削除アイコン押下時のハンドラ。
 * - `onClick`: 行全体のタップ時のハンドラ。
 */
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    selected: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.l, vertical = spacing.m)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .clickable { onClick() }
                .padding(horizontal = spacing.m, vertical = spacing.s),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(modifier = Modifier.width(spacing.s))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bookmark.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(spacing.xxs))
                Text(
                    text = bookmark.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.s)) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Rounded.Edit, contentDescription = "編集")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "削除")
                }
            }
        }
    }
}

/**
 * ブックマークの追加/編集用ダイアログ。
 * `nameState` と `urlState` は呼び出し元で保持し、入力値をバインドする。
 * 入力エラーがある場合は、フィールド横にエラーメッセージを表示してダイアログを閉じない。
 *
 * パラメータ:
 * - `title`: ダイアログタイトル（追加/編集で切り替え）。
 * - `nameState`: 名前入力の状態ホルダー。
 * - `urlState`: URL 入力の状態ホルダー。
 * - `onDismiss`: キャンセルなどで閉じる時のハンドラ。
 * - `onConfirm`: 保存確定時のハンドラ（入力が有効な場合のみコールされる）。
 */
@Composable
private fun EditBookmarkDialog(
    title: String,
    nameState: MutableState<String>,
    urlState: MutableState<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var nameError by remember { mutableStateOf<String?>(null) }
    var urlError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = nameState.value,
                    onValueChange = {
                        nameState.value = it
                        nameError = null
                    },
                    label = { Text("名前") },
                    isError = nameError != null,
                    supportingText = {
                        nameError?.let {
                            Text(text = it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(LocalSpacing.current.s))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = urlState.value,
                    onValueChange = {
                        urlState.value = it
                        urlError = null
                    },
                    label = { Text("URL") },
                    isError = urlError != null,
                    supportingText = {
                        val helperText = urlError ?: "例: https://may.2chan.net/b/futaba.php"
                        val color = if (urlError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Text(text = helperText, color = color)
                    }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val name = nameState.value.trim()
                val url = urlState.value.trim()
                var hasError = false

                if (name.isBlank()) {
                    nameError = "名前を入力してください"
                    hasError = true
                }
                if (url.isBlank()) {
                    urlError = "URLを入力してください"
                    hasError = true
                } else if (!isValidCatalogUrl(url)) {
                    urlError = "ふたばのカタログURLを入力してください（例: https://may.2chan.net/b/futaba.php）"
                    hasError = true
                }

                if (!hasError) {
                    onConfirm()
                }
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

/**
 * ブックマーク削除用の確認ダイアログ。本文メッセージを受け取り、`削除` と `キャンセル` を表示する。
 *
 * パラメータ:
 * - `message`: 本文に表示する確認メッセージ。
 * - `onConfirm`: 削除確定時のハンドラ。
 * - `onDismiss`: キャンセル/外側タップ時のハンドラ。
 */
@Composable
private fun ConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("削除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
