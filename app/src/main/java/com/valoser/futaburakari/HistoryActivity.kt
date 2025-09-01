package com.valoser.futaburakari

import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
// import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.valoser.futaburakari.databinding.ActivityHistoryBinding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class HistoryActivity : BaseActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter
    private var showUnreadOnly: Boolean = false
    private var sortMode: SortMode = SortMode.MIXED

    private enum class SortMode { MIXED, UPDATED, VIEWED, UNREAD }

    private val historyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == HistoryManager.ACTION_HISTORY_CHANGED) {
                refresh()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.history_title)

        adapter = HistoryAdapter { entry ->
            val intent = Intent(this, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_URL, entry.url)
                putExtra(DetailActivity.EXTRA_TITLE, entry.title)
            }
            startActivity(intent)
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // Pad bottom for gesture nav/system bars
        val rv = binding.recyclerView
        val origBottom = rv.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(rv) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, origBottom + sys.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // swipe to delete
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                val item = adapter.currentList.getOrNull(pos) ?: return
                HistoryManager.delete(this@HistoryActivity, item.key)
                // 監視も停止
                com.valoser.futaburakari.worker.ThreadMonitorWorker.cancelByKey(this@HistoryActivity, item.key)
                // キャッシュ/アーカイブも削除
                com.valoser.futaburakari.cache.DetailCacheManager(this@HistoryActivity).apply {
                    invalidateCache(item.url)
                    clearArchiveForUrl(item.url)
                }
                refresh()
            }
        })
        touchHelper.attachToRecyclerView(binding.recyclerView)

        loadPrefs()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.history_menu, menu)
        // メニューの状態反映
        val unreadItem = menu.findItem(R.id.action_toggle_unread_only)
        unreadItem.title = if (showUnreadOnly) "未読のみ表示（ON）" else "未読のみ表示（OFF）"
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
        R.id.action_clear_history -> {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.confirm_clear_history))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    HistoryManager.clear(this)
                    // 監視を全停止
                    com.valoser.futaburakari.worker.ThreadMonitorWorker.cancelAll(this)
                    // すべてのキャッシュ/アーカイブも削除
                    com.valoser.futaburakari.cache.DetailCacheManager(this).clearAllCache()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        R.id.action_toggle_unread_only -> {
            showUnreadOnly = !showUnreadOnly
            savePrefs()
            invalidateOptionsMenu()
            refresh()
            true
        }
        R.id.sort_mixed -> { sortMode = SortMode.MIXED; savePrefs(); refresh(); true }
        R.id.sort_updated -> { sortMode = SortMode.UPDATED; savePrefs(); refresh(); true }
        R.id.sort_viewed -> { sortMode = SortMode.VIEWED; savePrefs(); refresh(); true }
        R.id.sort_unread -> { sortMode = SortMode.UNREAD; savePrefs(); refresh(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val base = HistoryManager.getAll(this)

        // 自動クリーンアップ（設定値に基づき媒体と詳細キャッシュを間引き）
        runCatching {
            val p = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            val mb = p.getString("pref_key_auto_cleanup_limit_mb", "0")?.toLongOrNull() ?: 0L
            if (mb > 0) {
                val limitBytes = mb * 1024L * 1024L
                val cm = com.valoser.futaburakari.cache.DetailCacheManager(this)
                cm.enforceLimit(limitBytes, base) { entry ->
                    // サムネイルは無効化（存在しないローカルURIを避ける）
                    HistoryManager.clearThumbnail(this, entry.url)
                }
            }
        }
        val filtered = if (showUnreadOnly) base.filter { it.unreadCount > 0 } else base
        val list = when (sortMode) {
            SortMode.MIXED -> filtered.sortedWith(compareByDescending<com.valoser.futaburakari.HistoryEntry> { it.unreadCount > 0 }
                .thenByDescending { if (it.unreadCount > 0) it.lastUpdatedAt else it.lastViewedAt }
                .thenByDescending { it.lastViewedAt })
            SortMode.UPDATED -> filtered.sortedByDescending { it.lastUpdatedAt }
            SortMode.VIEWED -> filtered.sortedByDescending { it.lastViewedAt }
            SortMode.UNREAD -> filtered.sortedWith(compareByDescending<com.valoser.futaburakari.HistoryEntry> { it.unreadCount }
                .thenByDescending { it.lastUpdatedAt })
        }
        binding.emptyView.text = getString(R.string.no_history)
        binding.emptyView.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        adapter.submitList(list)
    }

    private fun loadPrefs() {
        val p = getSharedPreferences("com.valoser.futaburakari.history.ui", MODE_PRIVATE)
        showUnreadOnly = p.getBoolean("unread_only", false)
        sortMode = when (p.getString("sort_mode", SortMode.MIXED.name)) {
            SortMode.UPDATED.name -> SortMode.UPDATED
            SortMode.VIEWED.name -> SortMode.VIEWED
            SortMode.UNREAD.name -> SortMode.UNREAD
            else -> SortMode.MIXED
        }
    }

    private fun savePrefs() {
        val p = getSharedPreferences("com.valoser.futaburakari.history.ui", MODE_PRIVATE)
        p.edit().putBoolean("unread_only", showUnreadOnly)
            .putString("sort_mode", sortMode.name)
            .apply()
    }

    override fun onStart() {
        super.onStart()
        // 再表示時に最新状態へ
        refresh()
        // レシーバ登録（Android 13+ は exported 指定が必須）
        val filter = IntentFilter(HistoryManager.ACTION_HISTORY_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(historyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(historyReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(historyReceiver) }
        super.onStop()
    }
}
