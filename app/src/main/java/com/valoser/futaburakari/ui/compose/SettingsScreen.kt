package com.valoser.futaburakari.ui.compose

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import coil.imageLoader
import com.valoser.futaburakari.AppPreferences
import com.valoser.futaburakari.NgManagerActivity
import com.valoser.futaburakari.R
import com.valoser.futaburakari.RuleType
import com.valoser.futaburakari.cache.DetailCacheManager
import com.valoser.futaburakari.worker.ThreadMonitorWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }
    val bgPrefs = remember { ctx.getSharedPreferences(ThreadMonitorWorker.PREFS_BG, android.content.Context.MODE_PRIVATE) }

    // States
    var gridSpan by remember { mutableStateOf(prefs.getString("pref_key_grid_span", "4") ?: "4") }
    var fontScale by remember { mutableStateOf(prefs.getString("pref_key_font_scale", "1.0") ?: "1.0") }
    var themeMode by remember { mutableStateOf(prefs.getString("pref_key_theme_mode", "system") ?: "system") }
    var colorMode by remember { mutableStateOf(prefs.getString("pref_key_color_mode", "green") ?: "green") }
    var autoCleanup by remember { mutableStateOf(prefs.getString("pref_key_auto_cleanup_limit_mb", "0") ?: "0") }
    var adsEnabled by remember { mutableStateOf(prefs.getBoolean("pref_key_ads_enabled", false)) }
    var bgEnabled by remember { mutableStateOf(bgPrefs.getBoolean(ThreadMonitorWorker.KEY_BG_ENABLED, false)) }

    // Dialog state for password
    var showPwdDialog by remember { mutableStateOf(false) }
    var pwdText by remember { mutableStateOf("") }
    var clearingCache by remember { mutableStateOf(false) }

    val gridEntries = remember { ctx.resources.getStringArray(R.array.pref_grid_span_entries).toList() }
    val gridValues = remember { ctx.resources.getStringArray(R.array.pref_grid_span_values).toList() }
    val fontEntries = remember { ctx.resources.getStringArray(R.array.pref_font_scale_entries).toList() }
    val fontValues = remember { ctx.resources.getStringArray(R.array.pref_font_scale_values).toList() }
    val themeEntries = remember { ctx.resources.getStringArray(R.array.pref_theme_mode_entries).toList() }
    val themeValues = remember { ctx.resources.getStringArray(R.array.pref_theme_mode_values).toList() }
    val colorEntries = remember { ctx.resources.getStringArray(R.array.pref_color_mode_entries).toList() }
    val colorValues = remember { ctx.resources.getStringArray(R.array.pref_color_mode_values).toList() }
    val cleanupEntries = remember { ctx.resources.getStringArray(R.array.pref_auto_cleanup_entries).toList() }
    val cleanupValues = remember { ctx.resources.getStringArray(R.array.pref_auto_cleanup_values).toList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = ctx.getString(R.string.settings_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "戻る") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            item { SectionHeader(text = "表示設定") }
            item {
                DropdownPreferenceRow(
                    title = "グリッド列数",
                    entries = gridEntries,
                    values = gridValues,
                    value = gridSpan,
                    onValueChange = { v -> gridSpan = v; prefs.edit().putString("pref_key_grid_span", v).apply() }
                )
            }
            item {
                DropdownPreferenceRow(
                    title = "フォントサイズ",
                    entries = fontEntries,
                    values = fontValues,
                    value = fontScale,
                    onValueChange = { v ->
                        fontScale = v
                        prefs.edit().putString("pref_key_font_scale", v).apply()
                        (ctx as? Activity)?.recreate()
                    }
                )
            }
            item {
                DropdownPreferenceRow(
                    title = "テーマモード",
                    entries = themeEntries,
                    values = themeValues,
                    value = themeMode,
                    onValueChange = { v ->
                        themeMode = v
                        prefs.edit().putString("pref_key_theme_mode", v).apply()
                        (ctx as? Activity)?.recreate()
                    }
                )
            }
            item {
                DropdownPreferenceRow(
                    title = "カラーモード",
                    entries = colorEntries,
                    values = colorValues,
                    value = colorMode,
                    onValueChange = { v ->
                        colorMode = v
                        prefs.edit().putString("pref_key_color_mode", v).apply()
                        (ctx as? Activity)?.recreate()
                    }
                )
            }
            item {
                ListRow(
                    title = "スレタイNG管理",
                    summary = "カタログのスレッドタイトルに対するNGを設定します"
                ) {
                    ctx.startActivity(Intent(ctx, NgManagerActivity::class.java).apply {
                        putExtra(NgManagerActivity.EXTRA_LIMIT_RULE_TYPE, RuleType.TITLE.name)
                    })
                }
            }

            item { Divider(modifier = Modifier.padding(vertical = 6.dp)) }
            item { SectionHeader(text = "投稿設定") }
            item {
                ListRow(title = "投稿用パスワード", summary = "タップして変更") { showPwdDialog = true }
            }

            item { Divider(modifier = Modifier.padding(vertical = 6.dp)) }
            item { SectionHeader(text = "キャッシュ設定") }
            item {
                val scope = rememberCoroutineScope()
                ListRow(title = "キャッシュを削除", summary = "画像とスレッド内容のキャッシュをすべて削除します。") {
                    if (clearingCache) return@ListRow
                    clearingCache = true
                    val imageLoader = ctx.imageLoader
                    scope.launch(Dispatchers.IO) {
                        runCatching { imageLoader.memoryCache?.clear() }
                        runCatching { imageLoader.diskCache?.clear() }
                        runCatching { DetailCacheManager(ctx).clearAllCache() }
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "すべてのキャッシュを削除しました", android.widget.Toast.LENGTH_SHORT).show()
                            clearingCache = false
                        }
                    }
                }
            }
            item {
                DropdownPreferenceRow(
                    title = "自動クリーンアップ上限",
                    entries = cleanupEntries,
                    values = cleanupValues,
                    value = autoCleanup,
                    onValueChange = { v -> autoCleanup = v; prefs.edit().putString("pref_key_auto_cleanup_limit_mb", v).apply() }
                )
            }

            item { Divider(modifier = Modifier.padding(vertical = 6.dp)) }
            item { SectionHeader(text = "広告表示") }
            item {
                SwitchRow(title = "広告を表示", checked = adsEnabled, summary = "Detail画面下部にバナー広告を固定表示します") {
                    adsEnabled = it
                    prefs.edit().putBoolean("pref_key_ads_enabled", it).apply()
                }
            }

            item { Divider(modifier = Modifier.padding(vertical = 6.dp)) }
            item { SectionHeader(text = "バックグラウンド") }
            item {
                SwitchRow(title = ctx.getString(R.string.bg_monitor), checked = bgEnabled, summary = ctx.getString(R.string.bg_monitor_summary)) { on ->
                    bgEnabled = on
                    bgPrefs.edit().putBoolean(ThreadMonitorWorker.KEY_BG_ENABLED, on).apply()
                    if (!on) {
                        ThreadMonitorWorker.cancelAll(ctx)
                    } else {
                        // 履歴にある全スレッドをスケジュール
                        runCatching {
                            com.valoser.futaburakari.HistoryManager.getAll(ctx).forEach { e ->
                                ThreadMonitorWorker.schedule(ctx, e.url)
                            }
                        }
                    }
                }
            }

            item { Divider(modifier = Modifier.padding(vertical = 6.dp)) }
            item { SectionHeader(text = "その他") }
            item {
                ListRow(title = "プライバシーポリシー", summary = "") {
                    val url = "https://note.com/inqueuet/n/nb86f8e3f405a"
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            item {
                ListRow(title = ctx.getString(R.string.settings_inquiry), summary = "") {
                    ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:admin@inqueuet.com")))
                }
            }
        }

        if (showPwdDialog) {
            AlertDialog(
                onDismissRequest = { showPwdDialog = false },
                title = { Text("新しいパスワードを入力") },
                text = {
                    OutlinedTextField(
                        value = pwdText,
                        onValueChange = { pwdText = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        AppPreferences.savePwd(ctx, pwdText)
                        android.widget.Toast.makeText(ctx, "パスワードを保存しました", android.widget.Toast.LENGTH_SHORT).show()
                        showPwdDialog = false
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { showPwdDialog = false }) { Text("キャンセル") } }
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun ListRow(title: String, summary: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (summary.isNotBlank()) {
            Spacer(modifier = Modifier.size(2.dp))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun DropdownPreferenceRow(
    title: String,
    entries: List<String>,
    values: List<String>,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // derive summary from current value
    val currentLabel = entries.getOrNull(values.indexOf(value)) ?: value

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.size(2.dp))
            Text(currentLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            values.forEachIndexed { index, v ->
                val label = entries.getOrNull(index) ?: v
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        if (v != value) onValueChange(v)
                    }
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, summary: String = "", onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotBlank()) {
                Spacer(modifier = Modifier.size(2.dp))
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
