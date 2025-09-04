package com.valoser.futaburakari.ui.compose

/**
 * NGルール管理画面。
 * - NG ID / NGワード(本文) / スレタイNG の一覧表示・追加・編集・削除を行う。
 * - 右下のFABから追加。`limitType` が指定されている場合は種類選択をスキップし、その種類の編集ダイアログを直接表示する。
 * - ルールは `rules` の内容を表示し、各行のメニューから編集/削除が可能。
 */

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.MatchType
import com.valoser.futaburakari.NgRule
import com.valoser.futaburakari.RuleType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NgManagerScreen(
    title: String,
    rules: List<NgRule>,
    onBack: () -> Unit,
    onAddRule: (type: RuleType, pattern: String, match: MatchType?) -> Unit,
    onUpdateRule: (ruleId: String, pattern: String, match: MatchType?) -> Unit,
    onDeleteRule: (ruleId: String) -> Unit,
    limitType: RuleType? = null,
    hideTitleOption: Boolean = false
) {
    var showTypePicker by remember { mutableStateOf(false) }
    var editTarget: NgRule? by remember { mutableStateOf(null) }
    var deleteTarget: NgRule? by remember { mutableStateOf(null) }
    var query by remember { mutableStateOf("") }
    var activeTypeFilter by remember { mutableStateOf<RuleType?>(null) }

    // スクロール時にトップバーを縮める
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val listState = rememberLazyListState()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            // 追加ボタン。種類固定の場合は直接編集ダイアログ、未指定の場合は種類選択ダイアログを表示
            FloatingActionButton(onClick = {
                if (limitType != null) {
                    // 種類が固定されているため、空の初期値で編集ダイアログを直ちに開く
                    editTarget = NgRule("", limitType, pattern = "", match = defaultMatchFor(limitType))
                } else {
                    showTypePicker = true
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = "追加")
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            // 検索とタイプフィルタ（limitType が未指定の時のみ表示）
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("検索（パターン/種類）") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "クリア")
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors()
                )

                if (limitType == null) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TypeFilterChip(
                            label = "すべて",
                            selected = activeTypeFilter == null,
                            onClick = { activeTypeFilter = null }
                        )
                        TypeFilterChip(
                            label = "ID",
                            selected = activeTypeFilter == RuleType.ID,
                            onClick = { activeTypeFilter = RuleType.ID }
                        )
                        TypeFilterChip(
                            label = "本文",
                            selected = activeTypeFilter == RuleType.BODY,
                            onClick = { activeTypeFilter = RuleType.BODY }
                        )
                        if (!hideTitleOption) {
                            TypeFilterChip(
                                label = "スレタイ",
                                selected = activeTypeFilter == RuleType.TITLE,
                                onClick = { activeTypeFilter = RuleType.TITLE }
                            )
                        }
                    }
                }
            }

            val filtered = rules.filter { r ->
                val matchesType = activeTypeFilter?.let { it == r.type } ?: true
                val q = query.trim()
                val matchesQuery = if (q.isEmpty()) true else run {
                    val typeLabel = when (r.type) { RuleType.ID -> "ID"; RuleType.BODY -> "本文"; RuleType.TITLE -> "スレタイ" }
                    r.pattern.contains(q, ignoreCase = true) || typeLabel.contains(q, ignoreCase = true)
                }
                matchesType && matchesQuery
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                items(filtered, key = { it.id }) { rule ->
                    RuleItem(
                        rule = rule,
                        onEdit = { editTarget = rule },
                        onDelete = { deleteTarget = rule }
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("該当するNGがありません", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("検索条件を見直すか、右下の＋で追加", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (showTypePicker && limitType == null) {
        TypePickerDialog(
            onDismiss = { showTypePicker = false },
            onPick = { t ->
                showTypePicker = false
                editTarget = NgRule("", t, pattern = "", match = defaultMatchFor(t))
            },
            hideTitleOption = hideTitleOption
        )
    }

    // 追加/編集ダイアログの表示制御。`id` が空なら新規追加、そうでなければ更新
    editTarget?.let { tgt ->
        RuleEditDialog(
            initial = tgt,
            isNew = tgt.id.isBlank(),
            onDismiss = { editTarget = null },
            onConfirm = { pattern, match ->
                if (tgt.id.isBlank()) {
                    onAddRule(tgt.type, pattern, match)
                } else {
                    onUpdateRule(tgt.id, pattern, match)
                }
                editTarget = null
            }
        )
    }

    // 削除確認ダイアログの表示制御
    deleteTarget?.let { tgt ->
        ConfirmDeleteDialog(
            rule = tgt,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                onDeleteRule(tgt.id)
                deleteTarget = null
            }
        )
    }
}

@Composable
private fun RuleItem(
    rule: NgRule,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    // 1行分のNGルール表示。カードのクリックで編集、右端メニューから編集/削除
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable { onEdit() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                val typeLabel = when (rule.type) {
                    RuleType.ID -> "ID"
                    RuleType.BODY -> "本文"
                    RuleType.TITLE -> "タイトル"
                }
                // `match` が null の場合は種類に応じた既定値で表示
                val mt = rule.match ?: when (rule.type) {
                    RuleType.ID -> MatchType.EXACT
                    RuleType.BODY -> MatchType.SUBSTRING
                    RuleType.TITLE -> MatchType.SUBSTRING
                }
                val matchLabel = when (mt) {
                    MatchType.EXACT -> "完全一致"
                    MatchType.PREFIX -> "前方一致"
                    MatchType.SUBSTRING -> "部分一致"
                    MatchType.REGEX -> "正規表現"
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = {},
                        label = { Text(typeLabel) },
                        colors = AssistChipDefaults.assistChipColors()
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text(matchLabel) }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = rule.pattern,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "メニュー")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    text = { Text("編集") },
                    onClick = { menu = false; onEdit() }
                )
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                    text = { Text("削除") },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
    }
}

@Composable
private fun TypePickerDialog(
    onDismiss: () -> Unit,
    onPick: (RuleType) -> Unit,
    hideTitleOption: Boolean
) {
    // 追加時の種類選択ダイアログ。`hideTitleOption` が true の場合はスレタイNGを非表示
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NGの種類") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TypePickRow(label = "ID") { onPick(RuleType.ID) }
                TypePickRow(label = "本文ワード") { onPick(RuleType.BODY) }
                if (!hideTitleOption) TypePickRow(label = "スレタイ") { onPick(RuleType.TITLE) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}

@Composable
private fun RuleEditDialog(
    initial: NgRule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (pattern: String, match: MatchType?) -> Unit
) {
    // ルールの追加/編集ダイアログ。
    // - 入力欄ラベルは種類に応じて変化。
    // - IDの場合は常に完全一致に固定し、マッチ種別は選択不可。
    // - 確定時はトリムし、空文字の場合は何もせず閉じる。
    var pattern by remember { mutableStateOf(initial.pattern) }
    var match by remember { mutableStateOf(initial.match ?: defaultMatchFor(initial.type)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) labelForNewTitle(initial.type) else labelForEditTitle(initial.type)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    singleLine = false,
                    label = { Text(if (initial.type == RuleType.TITLE) "含めたくないスレタイ語句" else if (initial.type == RuleType.ID) "例: abc123" else "含めたくない語句") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (initial.type != RuleType.ID) {
                    MatchTypeSelector(selected = match, onChange = { match = it })
                } else {
                    // IDはマッチ方法を固定（完全一致）
                    match = MatchType.EXACT
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmed = pattern.trim()
                    if (trimmed.isNotEmpty()) onConfirm(trimmed, match)
                    else onDismiss()
                }
            ) { Text(if (isNew) "追加" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
private fun MatchTypeSelector(selected: MatchType?, onChange: (MatchType) -> Unit) {
    // 本文/スレタイ用のマッチ方法選択（部分一致/前方一致/正規表現）。
    // 選択がnullの場合はUI上は部分一致が選択されたものとして扱う。
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("マッチ方法", style = MaterialTheme.typography.labelLarge)
        RadioRow(
            label = "部分一致",
            checked = (selected ?: MatchType.SUBSTRING) == MatchType.SUBSTRING,
            onClick = { onChange(MatchType.SUBSTRING) }
        )
        RadioRow(
            label = "前方一致",
            checked = selected == MatchType.PREFIX,
            onClick = { onChange(MatchType.PREFIX) }
        )
        RadioRow(
            label = "正規表現",
            checked = selected == MatchType.REGEX,
            onClick = { onChange(MatchType.REGEX) }
        )
    }
}

@Composable
private fun RadioRow(label: String, checked: Boolean, onClick: () -> Unit) {
    // ラジオボタンとテキストの1行
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.RadioButton(selected = checked, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun ConfirmDeleteDialog(rule: NgRule, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    // 削除確認ダイアログ。対象のパターン文字列を表示して確認を促す
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("削除") },
        text = { Text("このNGを削除しますか？\n${rule.pattern}") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("削除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/**
 * 種類ごとの既定マッチ方法。
 * - ID: 完全一致
 * - 本文/スレタイ: 部分一致
 */
private fun defaultMatchFor(type: RuleType): MatchType =
    when (type) {
        RuleType.ID -> MatchType.EXACT
        RuleType.BODY, RuleType.TITLE -> MatchType.SUBSTRING
    }

/** ダイアログタイトル（新規追加時） */
private fun labelForNewTitle(type: RuleType): String = when (type) {
    RuleType.ID -> "NG IDを追加"
    RuleType.BODY -> "NGワードを追加"
    RuleType.TITLE -> "スレタイNGを追加"
}

/** ダイアログタイトル（編集時） */
private fun labelForEditTitle(type: RuleType): String = when (type) {
    RuleType.ID -> "NG IDを編集"
    RuleType.BODY -> "NGワードを編集"
    RuleType.TITLE -> "スレタイNGを編集"
}

@Composable
private fun TypeFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        colors = if (selected) AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) else AssistChipDefaults.assistChipColors()
    )
}

@Composable
private fun TypePickRow(label: String, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
