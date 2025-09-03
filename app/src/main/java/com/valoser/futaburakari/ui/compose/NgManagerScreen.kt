package com.valoser.futaburakari.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                if (limitType != null) {
                    // ダイレクトに編集ダイアログ
                    editTarget = NgRule("", limitType, pattern = "", match = defaultMatchFor(limitType))
                } else {
                    showTypePicker = true
                }
            }) {
                Icon(Icons.Default.Add, contentDescription = "追加")
            }
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            items(rules, key = { it.id }) { rule ->
                RuleItem(
                    rule = rule,
                    onEdit = { editTarget = rule },
                    onDelete = { deleteTarget = rule }
                )
            }
            if (rules.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("NGがありません")
                        Spacer(Modifier.height(8.dp))
                        Text("右下の＋から追加できます")
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
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
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable { menu = true }
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
                Text(text = "$typeLabel（$matchLabel）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(text = rule.pattern, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NGの種類") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(RuleType.ID) },
                    text = "ID"
                )
                Text(
                    modifier = Modifier.fillMaxWidth().clickable { onPick(RuleType.BODY) },
                    text = "本文ワード"
                )
                if (!hideTitleOption) {
                    Text(
                        modifier = Modifier.fillMaxWidth().clickable { onPick(RuleType.TITLE) },
                        text = "スレタイ"
                    )
                }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("削除") },
        text = { Text("このNGを削除しますか？\n${rule.pattern}") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("削除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

private fun defaultMatchFor(type: RuleType): MatchType =
    when (type) {
        RuleType.ID -> MatchType.EXACT
        RuleType.BODY, RuleType.TITLE -> MatchType.SUBSTRING
    }

private fun labelForNewTitle(type: RuleType): String = when (type) {
    RuleType.ID -> "NG IDを追加"
    RuleType.BODY -> "NGワードを追加"
    RuleType.TITLE -> "スレタイNGを追加"
}

private fun labelForEditTitle(type: RuleType): String = when (type) {
    RuleType.ID -> "NG IDを編集"
    RuleType.BODY -> "NGワードを編集"
    RuleType.TITLE -> "スレタイNGを編集"
}
