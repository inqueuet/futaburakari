package com.valoser.futaburakari

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
// import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.valoser.futaburakari.databinding.ActivityHistoryBinding

class HistoryActivity : BaseActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var adapter: HistoryAdapter

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

        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.history_menu, menu)
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
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val list = HistoryManager.getAll(this)
        binding.emptyView.text = getString(R.string.no_history)
        binding.emptyView.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        adapter.submitList(list)
    }
}
