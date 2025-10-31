package com.valoser.futaburakari

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.ui.compose.HistoryScreen
import com.valoser.futaburakari.ui.compose.HistorySortMode
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.valoser.futaburakari.cache.DetailCacheManager

/**
 * 履歴一覧画面のアクティビティ。
 *
 * - UI状態（未読のみ/ソート種別）を専用 `SharedPreferences` に保存し、復帰時に復元。
 * - `HistoryManager` から履歴を取得して未読/ソート条件を適用し、合間でキャッシュクリーンアップ結果に応じたサムネイル整理も行う。
 * - `ACTION_HISTORY_CHANGED` ブロードキャストやライフサイクルの ON_RESUME で再計算して最新状態を表示。
 * - 項目削除/全削除では履歴・キャッシュ・`ThreadMonitorWorker` を連動して後始末する。
 */
@AndroidEntryPoint
class HistoryActivity : BaseActivity() {

    @Inject
    lateinit var detailCacheManager: DetailCacheManager

    /**
     * 履歴画面のCompose UIを初期化し、
     * 表示状態（未読のみ/ソート）とデータ（履歴取得/クリーンアップ/再計算）の連携を設定する。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uiPrefs = getSharedPreferences("com.valoser.futaburakari.history.ui", MODE_PRIVATE)
        val initialUnreadOnly = uiPrefs.getBoolean("unread_only", false)
        val initialSort = when (uiPrefs.getString("sort_mode", HistorySortMode.MIXED.name)) {
            HistorySortMode.UPDATED.name -> HistorySortMode.UPDATED
            HistorySortMode.VIEWED.name -> HistorySortMode.VIEWED
            HistorySortMode.UNREAD.name -> HistorySortMode.UNREAD
            else -> HistorySortMode.MIXED
        }


        setContent {
            FutaburakariTheme(expressive = true) {
                var showUnreadOnly by remember { mutableStateOf(initialUnreadOnly) }
                var sortMode by remember { mutableStateOf(initialSort) }
                var entries by remember { mutableStateOf(listOf<HistoryEntry>()) }
                val lifecycleOwner = LocalLifecycleOwner.current

                // 履歴を取得し、ディスク制限に応じたクリーンアップ→フィルタ→ソートまで実施して `entries` を更新する
                suspend fun computeAndSet() {
                    val base = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        HistoryManager.getAll(this@HistoryActivity)
                    }
                    // 自動クリーンアップ: ユーザー設定の上限（パーセンテージ設定を優先し、旧MB設定をフォールバック）を超えないよう、
                    // enforceLimit で削除されたエントリを控えておき、後段で対応するサムネイルも削除する。
                    val cleanedEntries = mutableListOf<com.valoser.futaburakari.HistoryEntry>()
                    try {
                        val p = PreferenceManager.getDefaultSharedPreferences(this@HistoryActivity)

                        // 新しいパーセンテージベース設定を優先、レガシーMB設定をフォールバック
                        val percentageKey = p.getString("pref_key_auto_cleanup_limit_percent", null)
                        val limitBytes = if (percentageKey != null) {
                            // パーセンテージベース設定が存在する場合
                            AppPreferences.calculateCacheLimitBytes(this@HistoryActivity, percentageKey)
                        } else {
                            // レガシーMB設定をチェック
                            val mb = p.getString("pref_key_auto_cleanup_limit_mb", "0")?.toLongOrNull() ?: 0L
                            if (mb > 0) mb * 1024L * 1024L else 0L
                        }

                        if (limitBytes > 0) {
                            val cm = detailCacheManager
                            // ディスク走査/削除はIOスレッドで実行
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                cm.enforceLimit(limitBytes, base) { entry ->
                                    cleanedEntries += entry
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // クリーンアップ処理中の例外は握りつぶす
                    }
                    if (cleanedEntries.isNotEmpty()) {
                        cleanedEntries.forEach { entry ->
                            try {
                                HistoryManager.clearThumbnail(this@HistoryActivity, entry.url)
                            } catch (_: Exception) {
                                // サムネイルの削除失敗は無視
                            }
                        }
                    }
                    // 未読のみ表示が有効な場合は未読件数>0のスレッドだけ残す
                    val filtered = if (showUnreadOnly) base.filter { it.unreadCount > 0 } else base
                    val list = when (sortMode) {
                        // MIXED: 未読を先頭に、未読は更新日時、既読は閲覧日時を優先。
                        // 最後に閲覧日時で安定ソートして見た順を保つ。
                        HistorySortMode.MIXED -> filtered.sortedWith(
                            compareByDescending<com.valoser.futaburakari.HistoryEntry> { it.unreadCount > 0 }
                                .thenByDescending { if (it.unreadCount > 0) it.lastUpdatedAt else it.lastViewedAt }
                                .thenByDescending { it.lastViewedAt }
                        )
                        // UPDATED: 最終更新日時の降順
                        HistorySortMode.UPDATED -> filtered.sortedByDescending { it.lastUpdatedAt }
                        // VIEWED: 最終閲覧日時の降順
                        HistorySortMode.VIEWED -> filtered.sortedByDescending { it.lastViewedAt }
                        // UNREAD: 未読件数の降順、同数なら更新日時の降順
                        HistorySortMode.UNREAD -> filtered.sortedWith(
                            compareByDescending<com.valoser.futaburakari.HistoryEntry> { it.unreadCount }
                                .thenByDescending { it.lastUpdatedAt }
                        )
                    }
                    entries = list
                }

                // UI状態の変更（未読のみ/ソート）を検知して再計算し、
                // 併せて状態を `uiPrefs` に永続化する。
                LaunchedEffect(showUnreadOnly, sortMode) {
                    computeAndSet()
                    // 保存: ユーザーの表示設定を次回起動時に復元できるようにする
                    uiPrefs.edit()
                        .putBoolean("unread_only", showUnreadOnly)
                        .putString("sort_mode", sortMode.name)
                        .apply()
                }

                // 変更ブロードキャスト受信で再読込。
                // ライフサイクルに合わせて登録/解除し、API 33 以降はエクスポート不可で登録する。
                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (intent?.action == HistoryManager.ACTION_HISTORY_CHANGED) {
                                this@HistoryActivity.lifecycleScope.launch {
                                    computeAndSet()
                                }
                            }
                        }
                    }
                    val filter = IntentFilter(HistoryManager.ACTION_HISTORY_CHANGED)
                    if (Build.VERSION.SDK_INT >= 33) {
                        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        @Suppress("DEPRECATION")
                        registerReceiver(receiver, filter)
                    }
                    onDispose { runCatching { unregisterReceiver(receiver) } }
                }

                // 画面へ戻ってきたタイミング（ON_RESUME）でも最新状態へ再計算する。
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            this@HistoryActivity.lifecycleScope.launch {
                                computeAndSet()
                            }
                        }
                    }
                    val lifecycle = lifecycleOwner.lifecycle
                    lifecycle.addObserver(observer)
                    onDispose { lifecycle.removeObserver(observer) }
                }

                var showConfirm by remember { mutableStateOf(false) }

                // 履歴一覧のUI本体。各種コールバックでナビゲーションや削除、
                // 表示切替（未読のみ/ソート）を扱い、必要に応じて再計算する。
                HistoryScreen(
                    title = getString(R.string.history_title),
                    entries = entries,
                    showUnreadOnly = showUnreadOnly,
                    sortMode = sortMode,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onToggleUnreadOnly = { showUnreadOnly = !showUnreadOnly },
                    onSelectSort = { sort -> sortMode = sort },
                    onClearAll = {
                        showConfirm = true
                    },
                    onClickItem = { entry ->
                        val intent = Intent(this@HistoryActivity, DetailActivity::class.java).apply {
                            putExtra(DetailActivity.EXTRA_URL, entry.url)
                            putExtra(DetailActivity.EXTRA_TITLE, entry.title)
                        }
                        startActivity(intent)
                    },
                    onDeleteItem = { item ->
                        // 履歴アイテムの削除時: 履歴本体の削除、関連ワーカーの停止、
                        // 当該URLのキャッシュ/アーカイブを無効化・削除してから再計算。
                        com.valoser.futaburakari.worker.ThreadMonitorWorker.cancelByKey(this@HistoryActivity, item.key)
                        detailCacheManager.apply {
                            invalidateCache(item.url)
                            clearArchiveForUrl(item.url)
                        }
                        this@HistoryActivity.lifecycleScope.launch {
                            HistoryManager.delete(this@HistoryActivity, item.key)
                            computeAndSet()
                        }
                    }
                )

                if (showConfirm) {
                    // 全削除確認ダイアログ: OKで履歴/関連ワーカー/全キャッシュを一括クリアし再計算。
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showConfirm = false },
                        title = { androidx.compose.material3.Text(text = getString(R.string.history_title)) },
                        text = { androidx.compose.material3.Text(text = getString(R.string.confirm_clear_history)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showConfirm = false
                                com.valoser.futaburakari.worker.ThreadMonitorWorker.cancelAll(this@HistoryActivity)
                                detailCacheManager.clearAllCache()
                                this@HistoryActivity.lifecycleScope.launch {
                                    HistoryManager.clear(this@HistoryActivity)
                                    computeAndSet()
                                }
                            }) { androidx.compose.material3.Text(text = getString(android.R.string.ok)) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showConfirm = false }) { androidx.compose.material3.Text(text = getString(android.R.string.cancel)) }
                        }
                    )
                }
            }
        }
    }
}
