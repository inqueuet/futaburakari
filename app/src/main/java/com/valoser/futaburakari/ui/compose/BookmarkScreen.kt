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
    Bookmark("ホロライブ", "https://dec.2chan.net/84/futaba.php?mode=cat"),
    Bookmark("避難所", "https://www.2chan.net/hinan/futaba.php?mode=cat"),
    Bookmark("野球", "https://zip.2chan.net/1/futaba.php?mode=cat"),
    Bookmark("サッカー", "https://zip.2chan.net/12/futaba.php?mode=cat"),
    Bookmark("麻雀", "https://may.2chan.net/25/futaba.php?mode=cat"),
    Bookmark("うま", "https://may.2chan.net/26/futaba.php?mode=cat"),
    Bookmark("ねこ", "https://may.2chan.net/27/futaba.php?mode=cat"),
    Bookmark("どうぶつ", "https://dat.2chan.net/d/futaba.php?mode=cat"),
    Bookmark("しょくぶつ", "https://zip.2chan.net/z/futaba.php?mode=cat"),
    Bookmark("虫", "https://dat.2chan.net/w/futaba.php?mode=cat"),
    Bookmark("アクア", "https://dat.2chan.net/49/futaba.php?mode=cat"),
    Bookmark("アウトドア", "https://dec.2chan.net/62/futaba.php?mode=cat"),
    Bookmark("料理", "https://dat.2chan.net/t/futaba.php?mode=cat"),
    Bookmark("甘味", "https://dat.2chan.net/20/futaba.php?mode=cat"),
    Bookmark("ラーメン", "https://dat.2chan.net/21/futaba.php?mode=cat"),
    Bookmark("のりもの", "https://dat.2chan.net/e/futaba.php?mode=cat"),
    Bookmark("二輪", "https://dat.2chan.net/j/futaba.php?mode=cat"),
    Bookmark("自転車", "https://nov.2chan.net/37/futaba.php?mode=cat"),
    Bookmark("カメラ", "https://dat.2chan.net/45/futaba.php?mode=cat"),
    Bookmark("家電", "https://dat.2chan.net/48/futaba.php?mode=cat"),
    Bookmark("鉄道", "https://dat.2chan.net/r/futaba.php?mode=cat"),
    Bookmark("二次元", "https://dat.2chan.net/img2/futaba.php?mode=cat"),
    Bookmark("二次元裏 (dec)", "https://dec.2chan.net/dec/futaba.php?mode=cat"),
    Bookmark("二次元裏 (jun)", "https://jun.2chan.net/jun/futaba.php?mode=cat"),
    Bookmark("二次元裏 (may)", "https://may.2chan.net/b/futaba.php?mode=cat"),
    Bookmark("転載不可", "https://dec.2chan.net/58/futaba.php?mode=cat"),
    Bookmark("転載可", "https://dec.2chan.net/59/futaba.php?mode=cat"),
    Bookmark("二次元ID", "https://may.2chan.net/id/futaba.php?mode=cat"),
    Bookmark("スピグラ", "https://dat.2chan.net/23/futaba.php?mode=cat"),
    Bookmark("二次元ネタ", "https://dat.2chan.net/16/futaba.php?mode=cat"),
    Bookmark("二次元業界", "https://dat.2chan.net/43/futaba.php?mode=cat"),
    Bookmark("FGO", "https://dec.2chan.net/74/futaba.php?mode=cat"),
    Bookmark("アイマス", "https://dec.2chan.net/75/futaba.php?mode=cat"),
    Bookmark("ZOIDS", "https://dec.2chan.net/86/futaba.php?mode=cat"),
    Bookmark("ウメハラ総合", "https://dec.2chan.net/78/futaba.php?mode=cat"),
    Bookmark("ゲーム", "https://jun.2chan.net/31/futaba.php?mode=cat"),
    Bookmark("ネトゲ", "https://nov.2chan.net/28/futaba.php?mode=cat"),
    Bookmark("ソシャゲ", "https://dec.2chan.net/56/futaba.php?mode=cat"),
    Bookmark("艦これ", "https://dec.2chan.net/60/futaba.php?mode=cat"),
    Bookmark("モアイ", "https://dec.2chan.net/69/futaba.php?mode=cat"),
    Bookmark("刀剣乱舞", "https://dec.2chan.net/65/futaba.php?mode=cat"),
    Bookmark("占い", "https://dec.2chan.net/64/futaba.php?mode=cat"),
    Bookmark("ファッション", "https://dec.2chan.net/66/futaba.php?mode=cat"),
    Bookmark("旅行", "https://dec.2chan.net/67/futaba.php?mode=cat"),
    Bookmark("子育て", "https://dec.2chan.net/68/futaba.php?mode=cat"),
    Bookmark("webm", "https://may.2chan.net/webm/futaba.php?mode=cat"),
    Bookmark("そうだね", "https://dec.2chan.net/71/futaba.php?mode=cat"),
    Bookmark("任天堂", "https://dec.2chan.net/82/futaba.php?mode=cat"),
    Bookmark("ソニー", "https://dec.2chan.net/61/futaba.php?mode=cat"),
    Bookmark("ネットキャラ", "https://dat.2chan.net/10/futaba.php?mode=cat"),
    Bookmark("なりきり", "https://nov.2chan.net/34/futaba.php?mode=cat"),
    Bookmark("自作絵", "https://zip.2chan.net/11/futaba.php?mode=cat"),
    Bookmark("自作絵裏", "https://zip.2chan.net/14/futaba.php?mode=cat"),
    Bookmark("女装", "https://zip.2chan.net/32/futaba.php?mode=cat"),
    Bookmark("ばら", "https://zip.2chan.net/15/futaba.php?mode=cat"),
    Bookmark("ゆり", "https://zip.2chan.net/7/futaba.php?mode=cat"),
    Bookmark("やおい", "https://zip.2chan.net/8/futaba.php?mode=cat"),
    Bookmark("自作PC", "https://zip.2chan.net/3/futaba.php?mode=cat"),
    Bookmark("特撮", "https://cgi.2chan.net/g/futaba.php?mode=cat"),
    Bookmark("ろぼ", "https://zip.2chan.net/2/futaba.php?mode=cat"),
    Bookmark("映画", "https://dec.2chan.net/63/futaba.php?mode=cat"),
    Bookmark("おもちゃ", "https://dat.2chan.net/44/futaba.php?mode=cat"),
    Bookmark("模型", "https://dat.2chan.net/v/futaba.php?mode=cat"),
    Bookmark("模型裏 (nov)", "https://nov.2chan.net/y/futaba.php?mode=cat"),
    Bookmark("模型裏 (jun)", "https://jun.2chan.net/47/futaba.php?mode=cat"),
    Bookmark("VTuber", "https://dec.2chan.net/73/futaba.php?mode=cat"),
    Bookmark("合成音声", "https://dec.2chan.net/81/futaba.php?mode=cat"),
    Bookmark("3DCG", "https://dat.2chan.net/x/futaba.php?mode=cat"),
    Bookmark("人工知能", "https://dec.2chan.net/85/futaba.php?mode=cat"),
    Bookmark("政治", "https://nov.2chan.net/35/futaba.php?mode=cat"),
    Bookmark("経済", "https://nov.2chan.net/36/futaba.php?mode=cat"),
    Bookmark("宗教", "https://dec.2chan.net/79/futaba.php?mode=cat"),
    Bookmark("三次実況", "https://dec.2chan.net/50/futaba.php?mode=cat"),
    Bookmark("軍", "https://cgi.2chan.net/f/futaba.php?mode=cat"),
    Bookmark("軍裏", "https://may.2chan.net/39/futaba.php?mode=cat"),
    Bookmark("数学", "https://cgi.2chan.net/m/futaba.php?mode=cat"),
    Bookmark("flash", "https://cgi.2chan.net/i/futaba.php?mode=cat"),
    Bookmark("壁紙", "https://cgi.2chan.net/k/futaba.php?mode=cat"),
    Bookmark("壁紙二", "https://dat.2chan.net/l/futaba.php?mode=cat"),
    Bookmark("東方", "https://may.2chan.net/40/futaba.php?mode=cat"),
    Bookmark("東方裏", "https://dec.2chan.net/55/futaba.php?mode=cat"),
    Bookmark("お絵かき", "https://zip.2chan.net/p/futaba.php?mode=cat"),
    Bookmark("落書き", "https://nov.2chan.net/q/futaba.php?mode=cat"),
    Bookmark("落書き裏", "https://cgi.2chan.net/u/futaba.php?mode=cat"),
    Bookmark("ニュース表", "https://zip.2chan.net/6/futaba.php?mode=cat"),
    Bookmark("昭和", "https://dec.2chan.net/76/futaba.php?mode=cat"),
    Bookmark("平成", "https://dec.2chan.net/77/futaba.php?mode=cat"),
    Bookmark("発電", "https://dec.2chan.net/53/futaba.php?mode=cat"),
    Bookmark("自然災害", "https://dec.2chan.net/52/futaba.php?mode=cat"),
    Bookmark("コロナ", "https://dec.2chan.net/83/futaba.php?mode=cat"),
    Bookmark("雑談", "https://img.2chan.net/9/futaba.php?mode=cat"),
    Bookmark("新板提案", "https://dec.2chan.net/70/futaba.php?mode=cat"),
    Bookmark("IPv6", "https://ipv6.2chan.net/54/futaba.php?mode=cat"),
    Bookmark("レイアウト", "https://may.2chan.net/layout/futaba.php?mode=cat"),
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
