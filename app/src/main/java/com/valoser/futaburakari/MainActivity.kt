package com.valoser.futaburakari

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
// import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.PreferenceManager
import coil.imageLoader
import coil.request.ImageRequest
import android.widget.TextView
import com.valoser.futaburakari.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.net.URL

@AndroidEntryPoint
class MainActivity : BaseActivity(), SearchView.OnQueryTextListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var imageAdapter: ImageAdapter
    private var currentSelectedUrl: String? = null
    private var allItems: List<ImageItem> = emptyList()
    private var currentQuery: String? = null
    private var lastIsLoading: Boolean = false
    private var autoIndicatorShown: Boolean = false

    // 自動更新機能用のフィールド（改良版）
    private var isAutoUpdateEnabled = false
    private var scrollDirection = 0 // 1: down, -1: up, 0: none
    private var isAtTop = false
    private var isAtBottom = false
    private val autoUpdateDelayMs = 1000L // 1秒間の遅延
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var prefs: SharedPreferences
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "pref_key_grid_span" -> {
                val newSpan = getGridSpanCount()
                if (::gridLayoutManager.isInitialized) {
                    gridLayoutManager.spanCount = newSpan
                    binding.recyclerView.requestLayout()
                }
            }
            "pref_key_font_scale" -> {
                // Recreate to apply new font scale immediately
                recreate()
            }
        }
    }

    // 追加：より厳密な判定用のフィールド
    private var continuousScrollDistance = 0
    private var lastScrollPosition = 0
    private var bounceScrollCount = 0
    private val minScrollDistanceDp = 120 // 最小スクロール距離の目安（dp）
    private val minBounceCountThreshold = 3 // 最小バウンス回数

    // catset適用フラグを「板（例: https://zip.2chan.net/1/）」単位で保持
    private val catsetAppliedBoards = mutableSetOf<String>()

    private var prefetchJob: Job? = null
    private var autoUpdateRunnable: Runnable? = null
    // 下端プル検出用
    private var bottomPullAccumulatedPx: Int = 0
    private var lastBottomPullAtMs: Long = 0L

    private val bookmarkActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        loadAndFetchInitialData()
    }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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
        enableToolbarMultiline()

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        setupRecyclerView()
        setupAutoUpdateScroll() // 自動更新機能のセットアップ
        observeViewModel()
        setupClickListener()

        // スワイプインジケータを中央に配置
        configureSwipeRefreshIndicatorPosition()

        // スワイプ更新のリスナーを設定
        binding.swipeRefreshLayout.setOnRefreshListener {
            syncLoadingUi()
            fetchDataForCurrentUrl()
        }

        loadAndFetchInitialData()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        // 遅延実行の取り消し（画面破棄後に走らないように）
        autoUpdateRunnable?.let { binding.recyclerView.removeCallbacks(it) }
        autoUpdateRunnable = null
        isAutoUpdateEnabled = false
        setAutoUpdateIndicator(false)
    }

    private fun configureSwipeRefreshIndicatorPosition() {
        val srl = binding.swipeRefreshLayout
        // 初期レイアウト後に中央へ配置
        srl.post {
            val h = srl.height.takeIf { it > 0 } ?: return@post
            val target = h / 2
            try {
                srl.setProgressViewEndTarget(true, target)
            } catch (_: Throwable) {
                // 一部端末でsetProgressViewEndTargetが不安定な場合はオフセット指定にフォールバック
                val dm = resources.displayMetrics
                val circle = (40 * dm.density).toInt() // おおよその直径
                val start = (target - circle).coerceAtLeast(0)
                val end = (target + circle).coerceAtMost(h)
                try { srl.setProgressViewOffset(true, start, end) } catch (_: Throwable) {}
            }
        }
        // レイアウト変化（回転等）でも再調整
        srl.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val h = v.height
            if (h > 0) {
                try { srl.setProgressViewEndTarget(true, h / 2) } catch (_: Throwable) {}
            }
        }
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

                // 下端に到達している状態でさらに下方向(dy>0)へ引っ張った距離を蓄積
                val atBottomNow = gridLayoutManager.findLastVisibleItemPosition() >= (imageAdapter.itemCount - 1)
                if (atBottomNow && dy > 0) {
                    bottomPullAccumulatedPx += kotlin.math.abs(dy)
                    lastBottomPullAtMs = System.currentTimeMillis()
                } else if (!atBottomNow || dy < 0) {
                    bottomPullAccumulatedPx = 0
                }
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
        if (continuousScrollDistance < minScrollDistanceThresholdPx()) {
            return false
        }

        // 境界での逆方向スクロール判定
        val isTopBounce = isAtTop && scrollDirection < 0 && !binding.swipeRefreshLayout.isRefreshing
        val isBottomBounce =
            isAtBottom && scrollDirection > 0 && !binding.swipeRefreshLayout.isRefreshing

        return isTopBounce || isBottomBounce
    }

    private fun handleScrollStop() {
        // 下端プルでの明示リロードを優先
        if (shouldTriggerBottomPullToRefresh()) {
            binding.swipeRefreshLayout.isRefreshing = true
            syncLoadingUi()
            fetchDataForCurrentUrl()
            bottomPullAccumulatedPx = 0
        } else {
            // より厳密な条件で自動更新を判定
            val shouldTriggerUpdate = shouldTriggerAutoUpdate()
            if (shouldTriggerUpdate) {
                triggerAutoUpdate()
            }
        }

        // 停止時にバウンスカウンターをリセット
        bounceScrollCount = 0
    }

    private fun shouldTriggerAutoUpdate(): Boolean {
        // 基本的な境界条件
        val isAtBoundary = (isAtTop && scrollDirection < 0) || (isAtBottom && scrollDirection > 0)

        // 厳密な条件をすべて満たす場合のみ更新を実行
        return isAtBoundary &&
                continuousScrollDistance >= minScrollDistanceThresholdPx() &&
                bounceScrollCount >= minBounceCountThreshold &&
                !isAutoUpdateEnabled &&
                !binding.swipeRefreshLayout.isRefreshing
    }

    private fun shouldTriggerBottomPullToRefresh(): Boolean {
        val recent = (System.currentTimeMillis() - lastBottomPullAtMs) <= 800
        val canScrollFurtherDown = binding.recyclerView.canScrollVertically(1)
        val overscrollEnabled = binding.recyclerView.overScrollMode != View.OVER_SCROLL_NEVER
        return isAtBottom &&
                !canScrollFurtherDown &&
                overscrollEnabled &&
                bottomPullAccumulatedPx >= minScrollDistanceThresholdPx() &&
                recent &&
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
        setAutoUpdateIndicator(true)

        // 指定時間後に更新を実行（取り消し可能なRunnableを保持）
        autoUpdateRunnable?.let { binding.recyclerView.removeCallbacks(it) }
        autoUpdateRunnable = Runnable {
            performAutoUpdate()
        }
        binding.recyclerView.postDelayed(autoUpdateRunnable!!, autoUpdateDelayMs)
    }

    private fun performAutoUpdate() {
        // 画面終了・破棄後に実行されないように二重ガード
        if (isFinishing || isDestroyed) {
            isAutoUpdateEnabled = false
            setAutoUpdateIndicator(false)
            return
        }
        currentSelectedUrl?.let { url ->
            // 現在のアイテム数を記録
            val currentItemCount = imageAdapter.itemCount

            // バックグラウンドで更新を実行
            viewModel.checkForUpdates(url, currentItemCount) { hasUpdates ->
                runOnUiThread {
                    setAutoUpdateIndicator(false)
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
        setAutoUpdateIndicator(show)
    }

    private fun minScrollDistanceThresholdPx(): Int {
        val dm = resources.displayMetrics
        return (minScrollDistanceDp * dm.density).toInt().coerceAtLeast(48)
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

    private fun setAutoUpdateIndicator(show: Boolean) {
        autoIndicatorShown = show
        syncLoadingUi()
    }

    private fun syncLoadingUi() {
        val refreshing = binding.swipeRefreshLayout.isRefreshing
        binding.progressBar.isVisible = (lastIsLoading || autoIndicatorShown) && !refreshing
        binding.recyclerView.isVisible = !lastIsLoading || refreshing
    }

    private fun fetchDataForCurrentUrl() {
        currentSelectedUrl?.let { url ->
            // 1つのコルーチンにまとめることで、処理の順序を保証する
            lifecycleScope.launch {
                val boardBaseUrl = url.substringBefore("futaba.php")
                if (boardBaseUrl.isNotEmpty() && url.contains("futaba.php")) {
                    // 最初に設定を適用し、完了するまで待つ
                    // 設定適用が失敗しても画像取得は走るように try-catch で囲む
                    try {
                        applyCatalogSettings(boardBaseUrl)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "カタログ設定の適用に失敗", e)
                    }
                }
                // 設定適用の完了後、画像取得を開始する
                viewModel.fetchImagesFromUrl(url)
            }
        } ?: run {
            // 選択中のURLがない場合は、ブックマーク選択ダイアログを表示
            showBookmarkSelectionDialog()
        }
    }

    private suspend fun applyCatalogSettings(boardBaseUrl: String) {
        // 例: https://zip.2chan.net/1/ までをキーとする
        val boardKey = boardBaseUrl.trimEnd('/')
        if (catsetAppliedBoards.contains(boardKey)) return

        val settings = mapOf("mode" to "catset", "cx" to "20", "cy" to "10", "cl" to "10")
        try {
            NetworkClient.applySettings(boardBaseUrl, settings)
            catsetAppliedBoards.add(boardKey)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "設定の適用に失敗: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
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
                syncLoadingUi()
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

            R.id.action_history -> {
                val intent = Intent(this, HistoryActivity::class.java)
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
        currentQuery = newText
        filterImages(currentQuery)
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
            Toast.makeText(
                this,
                "ブックマークがありません。まずはブックマークを登録してください。",
                Toast.LENGTH_LONG
            ).show()
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
        gridLayoutManager = GridLayoutManager(this@MainActivity, getGridSpanCount())
        binding.recyclerView.apply {
            layoutManager = gridLayoutManager
            adapter = imageAdapter
            setItemViewCacheSize(50)
            setHasFixedSize(true)
        }
    }

    

    private fun enableToolbarMultiline() {
        val toolbar = binding.toolbar
        toolbar.post {
            for (i in 0 until toolbar.childCount) {
                val v = toolbar.getChildAt(i)
                if (v is TextView) {
                    v.isSingleLine = false
                    v.maxLines = 2
                    v.ellipsize = TextUtils.TruncateAt.END
                }
            }
        }
    }

    private fun getGridSpanCount(): Int {
        val value = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_grid_span", "4") ?: "4"
        return value.toIntOrNull()?.coerceIn(1, 8) ?: 4
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
                    Log.e(
                        "MainActivity_Prefetch",
                        "Prefetch failed for base:'$baseUrlString', image:'$imageUrlString'",
                        e
                    )
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
            lastIsLoading = isLoading
            if (!isLoading) binding.swipeRefreshLayout.isRefreshing = false
            syncLoadingUi()
        }

        viewModel.images.observe(this) { items ->
            // 取得完了時は確実にリフレッシュ終了
            binding.swipeRefreshLayout.isRefreshing = false
            syncLoadingUi()
            allItems = items
            // ユーザー入力中の検索クエリを維持
            filterImages(currentQuery)

            // いったん既存プリフェッチを止める
            prefetchJob?.cancel()

            // レイアウトが確定してから可視範囲を算出
            binding.recyclerView.post {
                prefetchJob = lifecycleScope.launch {
                    // 画面に表示されるおおよその件数（縦4行分）
                    val span = gridLayoutManager.spanCount           // 例: 4
                    val rowsOnScreen = 4
                    val visibleMax = (span * rowsOnScreen).coerceAtMost(items.size)

                    val first = gridLayoutManager.findFirstVisibleItemPosition().coerceAtLeast(0)
                    val last = (first + visibleMax - 1).coerceAtMost(items.lastIndex)

                    // 先読み件数を少量（例: 8件）だけ
                    val prefetchAhead = 8
                    val end = (last + prefetchAhead).coerceAtMost(items.lastIndex)

                    val slice = items.subList(first, end + 1)

                    // サムネに十分なサイズ（例: 200dp）
                    val dm = resources.displayMetrics
                    val thumbPx = (200 * dm.density).toInt().coerceAtLeast(120)

                    // Coilはenqueueが非同期。大量同時投下を避けて小分けに投げる
                    slice.mapNotNull { it.fullImageUrl ?: it.previewUrl }
                        .chunked(4) // 小さなバッチで
                        .forEach { batch ->
                            batch.forEach { url ->
                                val req = ImageRequest.Builder(this@MainActivity)
                                    .data(url)
                                    .size(thumbPx) // 一辺だけでもOK（正方サムネ想定）
                                    .precision(coil.size.Precision.INEXACT) // サムネは厳密不要
                                    .allowHardware(true)
                                    // .memoryCacheKey(url + "_thumb") // 必要ならキー分離
                                    .build()
                                this@MainActivity.imageLoader.enqueue(req)
                            }
                            // UI初速を阻害しない程度に短い隙間を空ける
                            delay(50)
                        }
                }
            }
        }

        viewModel.error.observe(this) { errorMessage ->
            // エラー時も確実にリフレッシュ終了
            binding.swipeRefreshLayout.isRefreshing = false
            syncLoadingUi()
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }
}
