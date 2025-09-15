/**
 * アプリ設定画面のComposeファイル。
 * - 表示/ネットワーク/投稿/キャッシュ/広告/その他のセクションを扱います。
 * - コメント整備のみを行い、コードの修正は一切行いません。
 */
package com.valoser.futaburakari.ui.compose

/**
 * 設定画面（Jetpack Compose）。
 *
 * 機能概要:
 * - 表示設定: グリッド列数 / フォント倍率 / テーマモード。
 * - NG 管理: スレタイ NG 管理画面へのショートカット。
 * - 投稿設定: 投稿時に使う削除キーの保存。
 * - キャッシュ設定: 画像（Coil）/スレッド詳細キャッシュの削除、自動クリーンアップ上限。
 * - 広告表示: Detail 画面へのバナー固定表示の切替。
 * - その他: プライバシーポリシー / 問い合わせ。
 *
 * 反映方法:
 * - 設定は `SharedPreferences` に保存。
 * - テーマ/フォントの変更は `Activity#recreate()` を呼び出して即時反映。
 *
 * パラメータ:
 * - `onBack`: 上部の戻る押下時に呼ばれるハンドラ。
 */

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.valoser.futaburakari.ui.theme.LocalSpacing
import coil3.imageLoader
import com.valoser.futaburakari.AppPreferences
import com.valoser.futaburakari.NgManagerActivity
import com.valoser.futaburakari.R
import com.valoser.futaburakari.RuleType
import com.valoser.futaburakari.cache.DetailCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * アプリの設定画面コンポーザブル。
 *
 * 概要:
 * - 表示/NG/投稿/キャッシュ/広告/その他の各セクションで設定を編集し、`SharedPreferences` に保存します。
 * - テーマやフォント倍率の変更は `Activity#recreate()` を呼び即時反映します。
 *
 * パラメータ:
 * - `onBack`: 上部ナビゲーション「戻る」押下時に呼ばれるハンドラ（画面を閉じる等）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }

    // 状態（現在の設定値を Preferences から読み込み）
    var gridSpan by remember { mutableStateOf(prefs.getString("pref_key_grid_span", "4") ?: "4") }
    var fontScale by remember { mutableStateOf(prefs.getString("pref_key_font_scale", "1.0") ?: "1.0") }
    var themeMode by remember { mutableStateOf(prefs.getString("pref_key_theme_mode", "system") ?: "system") }
    // Expressive 配色モード: Dynamic Color と併用するか（タイポ/シェイプ/余白のみ Expressive 適用）
    var expressiveDynamicColor by remember { mutableStateOf(prefs.getBoolean("pref_key_expressive_use_dynamic_color", false)) }
    // 旧「カラーモード」設定は廃止
    var autoCleanup by remember { mutableStateOf(prefs.getString("pref_key_auto_cleanup_limit_mb", "0") ?: "0") }
    var adsEnabled by remember { mutableStateOf(prefs.getBoolean("pref_key_ads_enabled", false)) }
    // 同時接続数（1..4）。AppPreferences に保存（フル画像アップグレードはこの設定に統合）
    var concurrencyLevel by remember { mutableStateOf(AppPreferences.getConcurrencyLevel(ctx).toString()) }
    // バックグラウンド監視トグルは常時有効化のため廃止

    // 投稿用パスワード入力ダイアログの状態
    var showPwdDialog by remember { mutableStateOf(false) }
    var pwdText by remember { mutableStateOf("") }
    var clearingCache by remember { mutableStateOf(false) }

    // ドロップダウン用の表示ラベルと値の配列（strings.xmlから取得）
    val gridEntries = remember { ctx.resources.getStringArray(R.array.pref_grid_span_entries).toList() }
    val gridValues = remember { ctx.resources.getStringArray(R.array.pref_grid_span_values).toList() }
    val fontEntries = remember { ctx.resources.getStringArray(R.array.pref_font_scale_entries).toList() }
    val fontValues = remember { ctx.resources.getStringArray(R.array.pref_font_scale_values).toList() }
    val themeEntries = remember { ctx.resources.getStringArray(R.array.pref_theme_mode_entries).toList() }
    val themeValues = remember { ctx.resources.getStringArray(R.array.pref_theme_mode_values).toList() }
    // removed: color mode entries/values
    val cleanupEntries = remember { ctx.resources.getStringArray(R.array.pref_auto_cleanup_entries).toList() }
    val cleanupValues = remember { ctx.resources.getStringArray(R.array.pref_auto_cleanup_values).toList() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = ctx.getString(R.string.settings_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
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
                // カタログ等のグリッド列数
                DropdownPreferenceRow(
                    title = "グリッド列数",
                    entries = gridEntries,
                    values = gridValues,
                    value = gridSpan,
                    onValueChange = { v -> gridSpan = v; prefs.edit().putString("pref_key_grid_span", v).apply() }
                )
            }
            item {
                // アプリ全体のフォント倍率。保存後にActivityを再生成して反映
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
                // ライト/ダーク/システムに追従。保存後に再生成
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
                // Expressive有効時に、配色のみ端末のDynamic Colorを使用（Android 12+で有効）。
                // タイポ/シェイプ/余白はExpressiveのまま適用されます。
                SwitchRow(
                    title = "Dynamic Colorと併用 (Expressive)",
                    checked = expressiveDynamicColor,
                    summary = "端末のDynamic Colorを使い、Expressiveはタイポ/シェイプ/余白のみ適用"
                ) { on ->
                    expressiveDynamicColor = on
                    prefs.edit().putBoolean("pref_key_expressive_use_dynamic_color", on).apply()
                    (ctx as? Activity)?.recreate()
                }
            }
            // removed: color mode selection
            item {
                // スレッドタイトルに対するNG管理画面への遷移（種類をスレタイに固定）
                ListRow(
                    title = "スレタイNG管理",
                    summary = "カタログのスレッドタイトルに対するNGを設定します"
                ) {
                    ctx.startActivity(Intent(ctx, NgManagerActivity::class.java).apply {
                        putExtra(NgManagerActivity.EXTRA_LIMIT_RULE_TYPE, RuleType.TITLE.name)
                    })
                }
            }

            // ネットワーク（同時接続数）
            item { SectionHeader(text = "ネットワーク") }
            item {
                DropdownPreferenceRow(
                    title = "同時接続数",
                    entries = listOf(
                        "1: Dispatcher(1/1), メタデータ(1), 並列(1)",
                        "2: Dispatcher(2/2), メタデータ(1), 並列(2)",
                        "3: Dispatcher(3/3), メタデータ(1), 並列(3)",
                        "4: Dispatcher(4/4), メタデータ(1), 並列(4)",
                    ),
                    values = (1..4).map { it.toString() },
                    value = concurrencyLevel,
                    onValueChange = { v ->
                        concurrencyLevel = v
                        AppPreferences.setConcurrencyLevel(ctx, v.toInt())
                        android.widget.Toast.makeText(
                            ctx,
                            "同時接続数を保存しました。完全反映にはアプリ再起動が必要です。",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        // Activity 再生成で ViewModel 側の並列度は反映されやすい
                        (ctx as? Activity)?.recreate()
                    }
                )
            }
            // フル画像アップグレード同時数の個別設定は廃止（同時接続数に統合）

            item { Divider(modifier = Modifier.padding(vertical = LocalSpacing.current.s)) }
            item { SectionHeader(text = "投稿設定") }
            item {
                // 投稿時に使う削除キー（パスワード）を保存
                ListRow(title = "投稿用パスワード", summary = "タップして変更") { showPwdDialog = true }
            }

            item { Divider(modifier = Modifier.padding(vertical = LocalSpacing.current.s)) }
            item { SectionHeader(text = "キャッシュ設定") }
            item {
                val scope = rememberCoroutineScope()
                // 画像（Coil Memory/Disk）とスレッド詳細のキャッシュを全削除
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
                // 自動クリーンアップで保持するキャッシュの上限（MB）。0は無効
                DropdownPreferenceRow(
                    title = "自動クリーンアップ上限",
                    entries = cleanupEntries,
                    values = cleanupValues,
                    value = autoCleanup,
                    onValueChange = { v -> autoCleanup = v; prefs.edit().putString("pref_key_auto_cleanup_limit_mb", v).apply() }
                )
            }

            item { Divider(modifier = Modifier.padding(vertical = LocalSpacing.current.s)) }
            item { SectionHeader(text = "広告表示") }
            item {
                // Detail画面にバナー広告を固定表示するか
                SwitchRow(title = "広告を表示", checked = adsEnabled, summary = "Detail画面下部にバナー広告を固定表示します") {
                    adsEnabled = it
                    prefs.edit().putBoolean("pref_key_ads_enabled", it).apply()
                }
            }

            // removed: background monitoring toggle (always enabled)

            // その他セクション（必要な1本だけ区切り線を表示）
            item { Divider(modifier = Modifier.padding(vertical = LocalSpacing.current.s)) }
            item { SectionHeader(text = "その他") }
            item {
                // 外部ブラウザでプライバシーポリシーを開く
                ListRow(title = "プライバシーポリシー", summary = "") {
                    val url = "https://note.com/inqueuet/n/nb86f8e3f405a"
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
            item {
                // メールで問い合わせ
                ListRow(title = ctx.getString(R.string.settings_inquiry), summary = "") {
                    ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:admin@inqueuet.com")))
                }
            }
        }

        // 投稿用パスワード入力ダイアログ
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

/** セクション見出しのテキスト表示。設定グループの区切りに使用。 */
@Composable
private fun SectionHeader(text: String) {
    // セクションタイトル
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.s)
    )
}

/**
 * 単純な設定行（タイトル＋任意サマリ）。
 * 行全体がクリック対象となり、押下時に `onClick` を呼び出します。
 */
@Composable
private fun ListRow(title: String, summary: String, onClick: () -> Unit) {
    // 設定の1行（タイトル＋サマリ）。クリックで `onClick` 実行
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.m)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (summary.isNotBlank()) {
            Spacer(modifier = Modifier.size(LocalSpacing.current.xxs))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}

/**
 * ドロップダウンで値を選択する設定行。
 * `entries` は表示ラベル、`values` は保存値。`value` が現在値で、変更時に `onValueChange` をコール。
 */
@Composable
private fun DropdownPreferenceRow(
    title: String,
    entries: List<String>,
    values: List<String>,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // 現在値に対応するラベルを算出（見つからない場合は値をそのまま表示）
    val currentLabel = entries.getOrNull(values.indexOf(value)) ?: value

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.m)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.size(LocalSpacing.current.xxs))
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

/** タイトル＋任意サマリ＋トグルスイッチの設定行。`checked` の変化は `onToggle` で受け取ります。 */
@Composable
private fun SwitchRow(title: String, checked: Boolean, summary: String = "", onToggle: (Boolean) -> Unit) {
    // タイトル＋サマリ＋スイッチの1行
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.m),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotBlank()) {
                Spacer(modifier = Modifier.size(LocalSpacing.current.xxs))
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
        }
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
