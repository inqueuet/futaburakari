package com.example.hutaburakari

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.ImageRequest
import com.example.hutaburakari.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : AppCompatActivity(), SearchView.OnQueryTextListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var imageAdapter: ImageAdapter
    private var currentSelectedUrl: String? = null
    private var allItems: List<ImageItem> = emptyList()

    // 自動更新機能用のフィールド（改良版）
    private var isAutoUpdateEnabled = false
    private var scrollDirection = 0 // 1: down, -1: up, 0: none
    private var isAtTop = false
    private var isAtBottom = false
    private val autoUpdateDelayMs = 1000L // 1秒間の遅延
    private lateinit var gridLayoutManager: GridLayoutManager

    // 追加：より厳密な判定用のフィールド
    private var continuousScrollDistance = 0
    private var lastScrollPosition = 0
    private var bounceScrollCount = 0
    private val minScrollDistanceThreshold = 200 // 最小スクロール距離（ピクセル）
    private val minBounceCountThreshold = 3 // 最小バウンス回数

    private val bookmarkActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        loadAndFetchInitialData()
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                Log.d("MainActivity", "Selected image URI: $uri")
                val promptInfo = MetadataExtractor.extract(this@MainActivity, uri.toString())
                Log.d("MainActivity", "Extracted prompt info: $promptInfo")

                val intent = Intent(this@MainActivity, ImageDisplayActivity::class.java).apply {
                    putExtra(ImageDisplayActivity.EXTRA_IMAGE_URI, uri.toString())
                    putExtra(ImageDisplayActivity.EXTRA_PROMPT_INFO, promptInfo)
                }
                startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupAutoUpdateScroll() // 自動更新機能のセットアップ
        observeViewModel()
        setupClickListener()

        // スワイプ更新のリスナーを設定
        binding.swipeRefreshLayout.setOnRefreshListener {
            fetchDataForCurrentUrl()
        }

        loadAndFetchInitialData()
    }

    // 自動更新機能のセットアップ（改良版）
    private fun setupAutoUpdateScroll() {
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)

                // スクロール方向を記録
                val newScrollDirection = when {
                    dy > 0 -> 1  // 下方向
                    dy < 0 -> -1 // 上方向
                    else -> 0    // 停止
                }

                // 連続スクロール距離を累積
                if (newScrollDirection == scrollDirection && newScrollDirection != 0) {
                    continuousScrollDistance += kotlin.math.abs(dy)
                } else {
                    continuousScrollDistance = kotlin.math.abs(dy)
                }

                scrollDirection = newScrollDirection
                lastScrollPosition += dy

                checkBoundaryScroll()
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)

                // スクロールが停止した時の処理
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    handleScrollStop()
                    // 停止時にカウンターをリセット
                    continuousScrollDistance = 0
                }
            }
        })
    }

    private fun checkBoundaryScroll() {
        val firstVisiblePosition = gridLayoutManager.findFirstVisibleItemPosition()
        val lastVisiblePosition = gridLayoutManager.findLastVisibleItemPosition()
        val totalItemCount = imageAdapter.itemCount

        // 上端と下端にいるかチェック
        isAtTop = firstVisiblePosition <= 0
        isAtBottom = lastVisiblePosition >= totalItemCount - 1

        // バウンス効果の判定を厳密に
        val shouldShowElasticEffect = shouldTriggerBounceEffect()

        if (shouldShowElasticEffect) {
            showElasticEffect()
            // バウンス回数をカウント
            bounceScrollCount++
        }
    }

    private fun shouldTriggerBounceEffect(): Boolean {
        // 最小スクロール距離に達していない場合は発動しない
        if (continuousScrollDistance < minScrollDistanceThreshold) {
            return false
        }

        // 境界での逆方向スクロール判定
        val isTopBounce = isAtTop && scrollDirection < 0 && !binding.swipeRefreshLayout.isRefreshing
        val isBottomBounce = isAtBottom && scrollDirection > 0 && !binding.swipeRefreshLayout.isRefreshing

        return isTopBounce || isBottomBounce
    }

    private fun handleScrollStop() {
        // より厳密な条件で自動更新を判定
        val shouldTriggerUpdate = shouldTriggerAutoUpdate()

        if (shouldTriggerUpdate) {
            triggerAutoUpdate()
        }

        // 停止時にバウンスカウンターをリセット
        bounceScrollCount = 0
    }

    private fun shouldTriggerAutoUpdate(): Boolean {
        // 基本的な境界条件
        val isAtBoundary = (isAtTop && scrollDirection < 0) || (isAtBottom && scrollDirection > 0)

        // 厳密な条件をすべて満たす場合のみ更新を実行
        return isAtBoundary &&
                continuousScrollDistance >= minScrollDistanceThreshold &&
                bounceScrollCount >= minBounceCountThreshold &&
                !isAutoUpdateEnabled &&
                !binding.swipeRefreshLayout.isRefreshing
    }

    private fun showElasticEffect() {
        // より目立つエラスティック効果で明確なフィードバック
        binding.recyclerView.animate()
            .scaleY(0.96f)
            .scaleX(0.98f)
            .setDuration(150)
            .withEndAction {
                binding.recyclerView.animate()
                    .scaleY(1.0f)
                    .scaleX(1.0f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }

    private fun triggerAutoUpdate() {
        if (isAutoUpdateEnabled) return // 既に更新中の場合は何もしない

        isAutoUpdateEnabled = true

        // 更新予告のトーストを表示（オプション）
        // showToastOnUiThread("更新を確認中...", Toast.LENGTH_SHORT)

        // 小さなローディング表示
        showAutoUpdateIndicator(true)

        // 指定時間後に更新を実行
        binding.recyclerView.postDelayed({
            performAutoUpdate()
        }, autoUpdateDelayMs)
    }

    private fun performAutoUpdate() {
        currentSelectedUrl?.let { url ->
            // 現在のアイテム数を記録
            val currentItemCount = imageAdapter.itemCount

            // バックグラウンドで更新を実行
            viewModel.checkForUpdates(url, currentItemCount) { hasUpdates ->
                runOnUiThread {
                    showAutoUpdateIndicator(false)
                    isAutoUpdateEnabled = false

                    if (hasUpdates) {
                        // showToastOnUiThread("新しいスレッドが追加されました", Toast.LENGTH_SHORT)
                    } else {
                        // showToastOnUiThread("更新はありません", Toast.LENGTH_SHORT)
                    }
                }
            }
        }
    }

    private fun showAutoUpdateIndicator(show: Boolean) {
        // カタログ用の小さなローディングインジケーターを表示/非表示
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showToastOnUiThread(message: String, duration: Int) {
        runOnUiThread {
            Toast.makeText(this, message, duration).show()
        }
    }

    private fun loadAndFetchInitialData() {
        currentSelectedUrl = BookmarkManager.getSelectedBookmarkUrl(this)
        binding.toolbar.subtitle = getCurrentBookmarkName()
        fetchDataForCurrentUrl()
    }

    private fun fetchDataForCurrentUrl() {
        currentSelectedUrl?.let { url ->
            lifecycleScope.launch {
                val boardBaseUrl = url.substringBefore("futaba.php")
                if (boardBaseUrl.isNotEmpty() && url.contains("futaba.php")) {
                    applyCatalogSettings(boardBaseUrl)
                } else {
                    // Log.e("MainActivity", "Cannot derive boardBaseUrl from: $url. Skipping applyCatalogSettings.")
                }
                viewModel.fetchImagesFromUrl(url)
            }
        } ?: run {
            showBookmarkSelectionDialog()
        }
    }

    private suspend fun applyCatalogSettings(boardBaseUrl: String) {
        val settings = mapOf(
            "mode" to "catset",
            "cx" to "20",
            "cy" to "10",
            "cl" to "10"
        )
        try {
            NetworkClient.applySettings(boardBaseUrl, settings)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "設定の適用に失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.setOnQueryTextListener(this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reload -> {
                binding.swipeRefreshLayout.isRefreshing = true
                fetchDataForCurrentUrl()
                Toast.makeText(this, getString(R.string.reloading), Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_select_bookmark -> {
                showBookmarkSelectionDialog()
                true
            }
            R.id.action_manage_bookmarks -> {
                val intent = Intent(this, BookmarkActivity::class.java)
                bookmarkActivityResultLauncher.launch(intent)
                true
            }
            R.id.action_image_edit -> {
                val intent = Intent(this, ImagePickerActivity::class.java)
                startActivity(intent)
                true
            }
            R.id.action_browse_local_images -> {
                pickImageLauncher.launch("image/*")
                true
            }
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        filterImages(newText)
        return true
    }

    private fun filterImages(query: String?) {
        val filteredList = if (query.isNullOrEmpty()) {
            allItems
        } else {
            allItems.filter { it.title.contains(query, ignoreCase = true) }
        }
        imageAdapter.submitList(filteredList)
    }

    private fun getCurrentBookmarkName(): String {
        val bookmarks = BookmarkManager.getBookmarks(this)
        return bookmarks.find { it.url == currentSelectedUrl }?.name ?: "ブックマーク未選択"
    }

    private fun showBookmarkSelectionDialog() {
        val bookmarks = BookmarkManager.getBookmarks(this)
        val bookmarkNames = bookmarks.map { it.name }.toTypedArray()

        if (bookmarkNames.isEmpty()) {
            Toast.makeText(this, "ブックマークがありません。まずはブックマークを登録してください。", Toast.LENGTH_LONG).show()
            val intent = Intent(this, BookmarkActivity::class.java)
            bookmarkActivityResultLauncher.launch(intent)
            return
        }

        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, bookmarkNames)

        AlertDialog.Builder(this)
            .setTitle("ブックマークを選択")
            .setAdapter(adapter) { dialog, which ->
                val selectedBookmark = bookmarks[which]
                currentSelectedUrl = selectedBookmark.url
                binding.toolbar.subtitle = selectedBookmark.name
                BookmarkManager.saveSelectedBookmarkUrl(this, selectedBookmark.url)
                fetchDataForCurrentUrl()
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun setupRecyclerView() {
        imageAdapter = ImageAdapter()
        gridLayoutManager = GridLayoutManager(this@MainActivity, 4)
        binding.recyclerView.apply {
            layoutManager = gridLayoutManager
            adapter = imageAdapter
            setItemViewCacheSize(50)
            setHasFixedSize(true)
        }
    }

    private fun setupClickListener() {
        imageAdapter.onItemClick = { item ->
            val baseUrlString = currentSelectedUrl
            val imageUrlString: String = item.fullImageUrl ?: item.previewUrl

            if (!baseUrlString.isNullOrBlank() && !imageUrlString.isNullOrBlank()) {
                try {
                    val absoluteUrl = URL(URL(baseUrlString), imageUrlString).toString()
                    Log.d("MainActivity_Prefetch", "Resolved absolute URL: $absoluteUrl")

                    val imageLoader = this.imageLoader
                    val request = ImageRequest.Builder(this)
                        .data(absoluteUrl)
                        .lifecycle(lifecycle = null)
                        .build()
                    imageLoader.enqueue(request)
                } catch (e: Exception) {
                    Log.e("MainActivity_Prefetch", "Prefetch failed for base:'$baseUrlString', image:'$imageUrlString'", e)
                }
            }

            val intent = Intent(this, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_URL, item.detailUrl)
                putExtra(DetailActivity.EXTRA_TITLE, item.title)  // ←ここを修正
            }
            startActivity(intent)
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            if (!binding.swipeRefreshLayout.isRefreshing) {
                binding.progressBar.isVisible = isLoading
            }
            if (!isLoading) {
                binding.swipeRefreshLayout.isRefreshing = false
            }
            // 読み込み中でもスワイプ更新中ならリストは表示したままにする
            binding.recyclerView.isVisible = !isLoading || binding.swipeRefreshLayout.isRefreshing
        }

        viewModel.images.observe(this) { items ->
            allItems = items
            filterImages(null)

            // 一覧反映後に画面内＋先読み分をプリフェッチ
            lifecycleScope.launch(Dispatchers.IO) {
                val first = 0
                val oneScreen = gridLayoutManager.spanCount * 4
                val last = (first + oneScreen).coerceAtMost(items.lastIndex)
                val slice = items.subList(first, last + 1)

                slice.mapNotNull { it.fullImageUrl ?: it.previewUrl }
                    .forEach { url ->
                        val req = ImageRequest.Builder(this@MainActivity)
                            .data(url)
                            .size(coil.size.Size.ORIGINAL)
                            .precision(coil.size.Precision.EXACT)
                            //.lifecycle(null) // バックグラウンド専用
                            .build()
                        this@MainActivity.imageLoader.enqueue(req)
                    }
            }
        }

        viewModel.error.observe(this) { errorMessage ->
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }
}
