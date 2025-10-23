/**
 * アプリ設定画面の Compose 実装。
 * - 表示/カタログモード/NG 管理/ネットワーク/投稿/キャッシュ/広告/その他の設定を1画面に集約し、必要に応じてアクティビティ再生成やトースト通知で反映。
 * - 選択した項目は `SharedPreferences` や `AppPreferences` へ保存し、ストレージ上限のパーセンテージ変換などもその場で行う。
 * - キャッシュ関連は使用状況の表示・個別削除・一括削除・自動クリーンアップ閾値の調整を提供。
 * - カタログモード設定（cx/cy/cl）は設定変更後、次回のプルリフレッシュやブックマーク再選択時に反映される。
 */
package com.valoser.futaburakari.ui.compose

/**
 * 設定画面（Jetpack Compose）。
 *
 * 機能概要:
 * - 表示設定: グリッド列数 / フォント倍率 / テーマモード / Expressive × Dynamic Color の切替。
 * - カタログモード設定: カタログの横サイズ（cx） / 縦サイズ（cy） / 文字数（cl）。
 * - NG 管理: スレタイ NG 管理画面へのショートカット。
 * - 投稿設定: 投稿時に使う削除キーの保存。
 * - キャッシュ設定: メモリ/キャッシュ使用状況の表示、画像（Coil）/スレッド詳細キャッシュの削除、自動クリーンアップ上限。
 * - 広告表示: Detail 画面へのバナー固定表示の切替。
 * - その他: プライバシーポリシー / 問い合わせ。
 *
 * 反映方法:
 * - 設定は `SharedPreferences` および `AppPreferences` に保存。
 * - テーマ/フォントの変更は `Activity#recreate()` を呼び出して即時反映。
 * - カタログモード設定は設定変更後、次回のプルリフレッシュやブックマーク再選択時に反映（板ごとのTTL管理あり）。
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
import com.valoser.futaburakari.cache.DetailCacheManagerProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.valoser.futaburakari.MyApplication
import kotlin.math.roundToInt

/**
 * アプリの設定画面コンポーザブル。
 *
 * 概要:
 * - 表示/カタログモード/NG/投稿/ネットワーク/キャッシュ/広告/その他の各セクションから `SharedPreferences` や `AppPreferences` の値を編集。
 * - テーマやフォント倍率、Expressive×Dynamic Color の切替は `Activity#recreate()` で即時反映し、同時接続数などはトーストで案内。
 * - カタログモード設定（cx/cy/cl）は設定変更後、次回のプルリフレッシュやブックマーク再選択時に反映される。
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
    var catalogDisplayMode by remember { mutableStateOf(prefs.getString("pref_key_catalog_display_mode", "grid") ?: "grid") }
    // カタログモード設定
    var catalogCx by remember { mutableStateOf(prefs.getString("pref_key_catalog_cx", "20") ?: "20") }
    var catalogCy by remember { mutableStateOf(prefs.getString("pref_key_catalog_cy", "10") ?: "10") }
    var catalogCl by remember {
        val stored = prefs.getString("pref_key_catalog_cl", "10")
        val normalized = stored?.toIntOrNull()?.coerceIn(3, 15)?.toString() ?: "10"
        if (normalized != stored) {
            prefs.edit().putString("pref_key_catalog_cl", normalized).apply()
        }
        mutableStateOf(normalized)
    }
    // Expressive 配色モード: Dynamic Color と併用するか（タイポ/シェイプ/余白のみ Expressive 適用）
    var expressiveDynamicColor by remember { mutableStateOf(prefs.getBoolean("pref_key_expressive_use_dynamic_color", false)) }
    // 旧「カラーモード」設定は廃止

    // 自動クリーンアップ設定の初期化（レガシー値からの移行処理を含む）
    var autoCleanup by remember {
        val legacyValue = prefs.getString("pref_key_auto_cleanup_limit_mb", "0") ?: "0"
        val currentValue = prefs.getString("pref_key_auto_cleanup_limit_percent", null)

        val initialValue = if (currentValue != null) {
            // 既にパーセンテージ設定がある場合はそれを使用
            currentValue
        } else {
            // レガシー値からパーセンテージに移行
            val migratedValue = AppPreferences.migrateLegacyCacheLimit(ctx, legacyValue)
            // 移行後の値を保存
            prefs.edit().putString("pref_key_auto_cleanup_limit_percent", migratedValue).apply()
            // レガシーキーを削除（完全移行）
            prefs.edit().remove("pref_key_auto_cleanup_limit_mb").apply()
            migratedValue
        }
        mutableStateOf(initialValue)
    }
    var adsEnabled by remember { mutableStateOf(prefs.getBoolean("pref_key_ads_enabled", true)) }
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
    val catalogDisplayEntries = remember { ctx.resources.getStringArray(R.array.pref_catalog_display_mode_entries).toList() }
    val catalogDisplayValues = remember { ctx.resources.getStringArray(R.array.pref_catalog_display_mode_values).toList() }
    val catalogCharLimitOptions = remember { (3..15).map { it.toString() } }
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
                // カタログ表示モード（グリッド/リスト）
                DropdownPreferenceRow(
                    title = "カタログ表示モード",
                    entries = catalogDisplayEntries,
                    values = catalogDisplayValues,
                    value = catalogDisplayMode,
                    onValueChange = { v -> catalogDisplayMode = v; prefs.edit().putString("pref_key_catalog_display_mode", v).apply() }
                )
            }
            item {
                // カタログ等のグリッド列数（グリッド表示時のみ有効）
                DropdownPreferenceRow(
                    title = "グリッド列数",
                    entries = gridEntries,
                    values = gridValues,
                    value = gridSpan,
                    summary = if (catalogDisplayMode == "list") "リスト表示では使用されません" else "",
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

            item { SectionHeader(text = "カタログモードの設定") }
            item {
                DropdownPreferenceRow(
                    title = "カタログの横サイズ",
                    entries = listOf("10", "15", "20", "25", "30"),
                    values = listOf("10", "15", "20", "25", "30"),
                    value = catalogCx,
                    onValueChange = { v -> catalogCx = v; prefs.edit().putString("pref_key_catalog_cx", v).apply() }
                )
            }
            item {
                DropdownPreferenceRow(
                    title = "カタログの縦サイズ",
                    entries = listOf("5", "8", "10", "12", "15"),
                    values = listOf("5", "8", "10", "12", "15"),
                    value = catalogCy,
                    onValueChange = { v -> catalogCy = v; prefs.edit().putString("pref_key_catalog_cy", v).apply() }
                )
            }
            item {
                DropdownPreferenceRow(
                    title = "文字数",
                    entries = catalogCharLimitOptions,
                    values = catalogCharLimitOptions,
                    value = catalogCl,
                    onValueChange = { v -> catalogCl = v; prefs.edit().putString("pref_key_catalog_cl", v).apply() }
                )
            }

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
            item {
                var hideDeletedRes by remember { mutableStateOf(AppPreferences.getHideDeletedRes(ctx)) }
                SwitchRow(
                    title = "削除レスを非表示",
                    checked = hideDeletedRes,
                    summary = "「スレッドを立てた人によって削除されました」というレスを隠します"
                ) { on ->
                    hideDeletedRes = on
                    AppPreferences.setHideDeletedRes(ctx, on)
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
            item { SectionHeader(text = "キャッシュ管理") }

            // メモリ使用量情報の表示
            item {
                var memoryInfo by remember { mutableStateOf("メモリ情報を取得中...") }
                val scope = rememberCoroutineScope()

                LaunchedEffect(Unit) {
                    scope.launch(Dispatchers.IO) {
                        val runtime = Runtime.getRuntime()
                        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                        val maxMemory = runtime.maxMemory()
                        val memoryUsageRatio = usedMemory.toFloat() / maxMemory.toFloat()
                        val coilInfo = MyApplication.getCoilCacheInfo(ctx)

                        withContext(Dispatchers.Main) {
                            memoryInfo = "システムメモリ: ${(memoryUsageRatio * 100).toInt()}% (${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB)\n画像キャッシュ: $coilInfo"
                        }
                    }
                }

                ListRow(
                    title = "使用状況",
                    summary = memoryInfo
                ) {
                    scope.launch(Dispatchers.IO) {
                        val runtime = Runtime.getRuntime()
                        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
                        val maxMemory = runtime.maxMemory()
                        val memoryUsageRatio = usedMemory.toFloat() / maxMemory.toFloat()
                        val coilInfo = MyApplication.getCoilCacheInfo(ctx)

                        withContext(Dispatchers.Main) {
                            memoryInfo = "システムメモリ: ${(memoryUsageRatio * 100).toInt()}% (${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB)\n画像キャッシュ: $coilInfo"
                        }
                    }
                }
            }

            // 画像メモリキャッシュのクリア
            item {
                val scope = rememberCoroutineScope()
                ListRow(
                    title = "画像メモリキャッシュをクリア",
                    summary = "メモリ上の画像キャッシュのみを削除します（すぐに反映）"
                ) {
                    scope.launch(Dispatchers.IO) {
                        MyApplication.clearCoilImageCache(ctx)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "画像メモリキャッシュをクリアしました", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // 画像ディスクキャッシュのクリア
            item {
                val scope = rememberCoroutineScope()
                ListRow(
                    title = "画像ディスクキャッシュをクリア",
                    summary = "ディスク上の画像キャッシュのみを削除します（読み込み速度が低下する可能性があります）"
                ) {
                    scope.launch(Dispatchers.IO) {
                        MyApplication.clearCoilDiskCache(ctx)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(ctx, "画像ディスクキャッシュをクリアしました", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }

            // 全キャッシュの削除（確認ダイアログ付き）
            item {
                val scope = rememberCoroutineScope()
                var showClearDialog by remember { mutableStateOf(false) }

                ListRow(
                    title = "すべてのキャッシュを削除",
                    summary = "画像キャッシュ（メモリ・ディスク）とスレッド詳細キャッシュをすべて削除します"
                ) {
                    showClearDialog = true
                }

                if (showClearDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearDialog = false },
                        title = { Text("キャッシュ削除の確認") },
                        text = { Text("すべてのキャッシュを削除しますか？\n\n・画像キャッシュ（メモリ・ディスク）\n・スレッド詳細キャッシュ\n\nこの操作は取り消せません。") },
                        confirmButton = {
                            TextButton(onClick = {
                                showClearDialog = false
                                if (clearingCache) return@TextButton
                                clearingCache = true
                                val imageLoader = ctx.imageLoader
                                scope.launch(Dispatchers.IO) {
                                    runCatching { imageLoader.memoryCache?.clear() }
                                    runCatching { imageLoader.diskCache?.clear() }
                                    runCatching { DetailCacheManagerProvider.get(ctx).clearAllCache() }
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(ctx, "すべてのキャッシュを削除しました", android.widget.Toast.LENGTH_SHORT).show()
                                        clearingCache = false
                                    }
                                }
                            }) { Text("削除") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearDialog = false }) { Text("キャンセル") }
                        }
                    )
                }
            }

            // 自動クリーンアップ設定（パーセンテージベース）
            item {
                val availableGB = AppPreferences.getAvailableStorageGB(ctx)
                val currentLimitBytes = AppPreferences.calculateCacheLimitBytes(ctx, autoCleanup)
                val currentLimitGB = currentLimitBytes / (1024.0 * 1024.0 * 1024.0)

                val summaryText = if (autoCleanup == "0") {
                    "利用可能容量: ${availableGB.roundToInt()}GB"
                } else {
                    "利用可能容量: ${availableGB.roundToInt()}GB（上限: ${currentLimitGB.roundToInt()}GB）"
                }

                DropdownPreferenceRow(
                    title = "自動クリーンアップ上限",
                    entries = cleanupEntries,
                    values = cleanupValues,
                    value = autoCleanup,
                    summary = summaryText,
                    onValueChange = { v ->
                        autoCleanup = v
                        prefs.edit().putString("pref_key_auto_cleanup_limit_percent", v).apply()
                    }
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
                // リソースで指定したプライバシーポリシー URL を外部ブラウザで表示
                val privacyPolicyUrl = ctx.getString(R.string.privacy_policy_url)
                ListRow(title = "プライバシーポリシー", summary = "", enabled = privacyPolicyUrl.isNotBlank()) {
                    if (privacyPolicyUrl.isNotBlank()) {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyPolicyUrl)))
                    }
                }
            }
            item {
                // OSS 向けの問い合わせ窓口（既定値は GitHub Issues）へ遷移
                val supportUrl = ctx.getString(R.string.support_url)
                ListRow(title = ctx.getString(R.string.settings_inquiry), summary = "", enabled = supportUrl.isNotBlank()) {
                    if (supportUrl.isNotBlank()) {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(supportUrl)))
                    }
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
private fun ListRow(title: String, summary: String, enabled: Boolean = true, onClick: () -> Unit) {
    // 設定の1行（タイトル＋サマリ）。`enabled` に応じてクリック可否を切り替える
    val summaryColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.4f)
    val titleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.6f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = LocalSpacing.current.l, vertical = LocalSpacing.current.m)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
        if (summary.isNotBlank()) {
            Spacer(modifier = Modifier.size(LocalSpacing.current.xxs))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = summaryColor)
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
    summary: String = "",
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
            if (summary.isNotBlank()) {
                Spacer(modifier = Modifier.size(LocalSpacing.current.xxs))
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
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
