package com.valoser.futaburakari.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.Bookmark

/**
 * ブックマーク一覧と追加/編集/削除 UI を提供する画面コンポーザブル。
 * - 右下の FAB から新規追加ダイアログを表示します。
 * - 各行の編集/削除アイコンで編集ダイアログ・削除確認を表示します。
 * - 行のタップで `onSelectBookmark` を呼び出します。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkScreen(
    title: String,
    bookmarks: List<Bookmark>,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
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
            }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "追加")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            // URL をキーとして安定ソートし、行を描画
            items(bookmarks, key = { it.url }) { bm ->
                BookmarkRow(
                    bookmark = bm,
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

@Composable
/**
 * ブックマークリストの 1 行を表示。
 * 行のタップで `onClick`、右側アイコンで編集/削除を行います。
 */
private fun BookmarkRow(
    bookmark: Bookmark,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = bookmark.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = bookmark.url, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "編集")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "削除")
                }
            }
        }
    }
}

@Composable
/**
 * ブックマークの追加/編集用ダイアログ。
 * `nameState` と `urlState` は呼び出し元で保持して双方向バインドします。
 */
private fun EditBookmarkDialog(
    title: String,
    nameState: MutableState<String>,
    urlState: MutableState<String>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = nameState.value,
                    onValueChange = { nameState.value = it },
                    label = { Text("名前") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = urlState.value,
                    onValueChange = { urlState.value = it },
                    label = { Text("URL") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@Composable
/**
 * 汎用の確認ダイアログ。メッセージと確定/キャンセルのコールバックを受け取ります。
 */
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
