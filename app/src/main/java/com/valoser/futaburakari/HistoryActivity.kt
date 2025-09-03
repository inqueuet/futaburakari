package com.valoser.futaburakari

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.ui.compose.HistoryScreen
import com.valoser.futaburakari.ui.compose.HistorySortMode
import com.valoser.futaburakari.ui.theme.FutaburakariTheme

class HistoryActivity : BaseActivity() {

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

        val colorModePref = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")

        setContent {
            FutaburakariTheme(colorMode = colorModePref) {
                var showUnreadOnly by remember { mutableStateOf(initialUnreadOnly) }
                var sortMode by remember { mutableStateOf(initialSort) }
                var entries by remember { mutableStateOf(listOf<HistoryEntry>()) }

                fun computeAndSet() {
                    val base = HistoryManager.getAll(this@HistoryActivity)
                    // 自動クリーンアップ
                    runCatching {
                        val p = PreferenceManager.getDefaultSharedPreferences(this@HistoryActivity)
                        val mb = p.getString("pref_key_auto_cleanup_limit_mb", "0")?.toLongOrNull() ?: 0L
                        if (mb > 0) {
                            val limitBytes = mb * 1024L * 1024L
                            val cm = com.valoser.futaburakari.cache.DetailCacheManager(this@HistoryActivity)
                            cm.enforceLimit(limitBytes, base) { entry ->
                                HistoryManager.clearThumbnail(this@HistoryActivity, entry.url)
                            }
                        }
                    }
                    val filtered = if (showUnreadOnly) base.filter { it.unreadCount > 0 } else base
                    val list = when (sortMode) {
                        HistorySortMode.MIXED -> filtered.sortedWith(
                            compareByDescending<com.valoser.futaburakari.HistoryEntry> { it.unreadCount > 0 }
                                .thenByDescending { if (it.unreadCount > 0) it.lastUpdatedAt else it.lastViewedAt }
                                .thenByDescending { it.lastViewedAt }
                        )
                        HistorySortMode.UPDATED -> filtered.sortedByDescending { it.lastUpdatedAt }
                        HistorySortMode.VIEWED -> filtered.sortedByDescending { it.lastViewedAt }
                        HistorySortMode.UNREAD -> filtered.sortedWith(
                            compareByDescending<com.valoser.futaburakari.HistoryEntry> { it.unreadCount }
                                .thenByDescending { it.lastUpdatedAt }
                        )
                    }
                    entries = list
                }

                LaunchedEffect(showUnreadOnly, sortMode) {
                    computeAndSet()
                    // 保存
                    uiPrefs.edit()
                        .putBoolean("unread_only", showUnreadOnly)
                        .putString("sort_mode", sortMode.name)
                        .apply()
                }

                // 変更ブロードキャスト受信で再読込
                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (intent?.action == HistoryManager.ACTION_HISTORY_CHANGED) {
                                computeAndSet()
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

                var showConfirm by remember { mutableStateOf(false) }

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
                        HistoryManager.delete(this@HistoryActivity, item.key)
                        com.valoser.futaburakari.worker.ThreadMonitorWorker.cancelByKey(this@HistoryActivity, item.key)
                        com.valoser.futaburakari.cache.DetailCacheManager(this@HistoryActivity).apply {
                            invalidateCache(item.url)
                            clearArchiveForUrl(item.url)
                        }
                        computeAndSet()
                    }
                )

                if (showConfirm) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showConfirm = false },
                        title = { androidx.compose.material3.Text(text = getString(R.string.history_title)) },
                        text = { androidx.compose.material3.Text(text = getString(R.string.confirm_clear_history)) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                showConfirm = false
                                HistoryManager.clear(this@HistoryActivity)
                                com.valoser.futaburakari.worker.ThreadMonitorWorker.cancelAll(this@HistoryActivity)
                                com.valoser.futaburakari.cache.DetailCacheManager(this@HistoryActivity).clearAllCache()
                                computeAndSet()
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
