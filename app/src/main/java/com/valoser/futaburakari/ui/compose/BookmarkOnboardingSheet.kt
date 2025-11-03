package com.valoser.futaburakari.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.Bookmark
import com.valoser.futaburakari.BookmarkPresets
import com.valoser.futaburakari.ui.theme.LocalSpacing
import androidx.compose.material3.rememberModalBottomSheetState

/**
 * Onboarding sheet prompting users to pick starter bookmarks.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookmarkOnboardingSheet(
    presets: List<BookmarkPresets.Preset>,
    existing: List<Bookmark>,
    onDismiss: () -> Unit,
    onConfirm: (List<BookmarkPresets.Preset>) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val spacing = LocalSpacing.current
    val categories = remember { BookmarkPresets.categories() }
    var selectedCategory by rememberSaveable { mutableStateOf(categories.firstOrNull() ?: "すべて") }
    var query by rememberSaveable { mutableStateOf("") }
    val selectedPresets = remember { mutableStateMapOf<String, BookmarkPresets.Preset>() }

    val filtered = remember(query, selectedCategory, existing) {
        BookmarkPresets.filteredPresets(
            query = query,
            category = selectedCategory,
            existingBookmarks = existing
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.l, vertical = spacing.m)
        ) {
            Text(
                text = "表示したい板を選択",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = Modifier.height(spacing.s))
            Text(
                text = "よく閲覧するカテゴリを選ぶと、カタログにすぐコンテンツが並びます。後からブックマーク画面でも変更できます。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(spacing.m))

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = { query = it },
                label = { Text("キーワードで検索") },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(spacing.s))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                verticalArrangement = Arrangement.spacedBy(spacing.xs)
            ) {
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                        label = { Text(category) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.m))

            if (selectedPresets.isNotEmpty()) {
                val count = selectedPresets.size
                Text(
                    text = "$count 件選択済み",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(spacing.s))
            }

            val listContent = if (filtered.isEmpty()) {
                emptyList<BookmarkPresets.Preset>()
            } else {
                filtered
            }

            if (listContent.isEmpty()) {
                Text(
                    text = "該当する板が見つかりませんでした。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(spacing.s)
                ) {
                    items(listContent, key = { it.baseUrl }) { preset ->
                        val selected = selectedPresets.containsKey(preset.baseUrl)
                        PresetRow(
                            preset = preset,
                            selected = selected,
                            onToggle = {
                                if (selected) {
                                    selectedPresets.remove(preset.baseUrl)
                                } else {
                                    selectedPresets[preset.baseUrl] = preset
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(spacing.l))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("あとで")
                }
                Spacer(modifier = Modifier.width(spacing.s))
                Button(
                    enabled = selectedPresets.isNotEmpty(),
                    onClick = { onConfirm(selectedPresets.values.toList()) }
                ) {
                    val label = if (selectedPresets.isEmpty()) {
                        "追加する"
                    } else {
                        "追加する (${selectedPresets.size})"
                    }
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: BookmarkPresets.Preset,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val spacing = LocalSpacing.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        onClick = onToggle
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.m, vertical = spacing.s),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = preset.category,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "選択済み",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
