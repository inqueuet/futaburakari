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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.PreferenceManager
import coil.imageLoader
import coil.request.ImageRequest
import android.widget.TextView
import com.valoser.futaburakari.databinding.ActivityMainBinding
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.valoser.futaburakari.ui.compose.MainCatalogScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import dagger.hilt.android.AndroidEntryPoint
import com.google.gson.Gson
import dagger.hilt.android.EntryPointAccessors
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
                spanCountState.intValue = getGridSpanCount()
            }
            "pref_key_font_scale" -> {
                // Recreate to apply new font scale immediately
                recreate()
            }
            // NGルール変更（設定画面など）
            "ng_rules_json" -> {
                ngRulesState.value = ngStore.getRules()
            }
            "pref_key_color_mode" -> {
                colorModeState.value = getColorModePref()
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
    private val catsetAppliedTimestamps = mutableMapOf<String, Long>() // boardKey -> appliedAt
    private val catsetPrefsName = "com.valoser.futaburakari.catalog"
    private val catsetPrefsKey = "applied_boards"
    private val catsetPrefsTsKey = "applied_boards_ts"
    private val CATSET_TTL_MS = 3L * 24 * 60 * 60 * 1000 // 3日

    private var prefetchJob: Job? = null
    private var autoUpdateRunnable: Runnable? = null
    // 下端プル検出用
    private var bottomPullAccumulatedPx: Int = 0
    private var lastBottomPullAtMs: Long = 0L
    private val ngStore by lazy { NgStore(this) }
    // Compose UI state
    private val spanCountState = mutableIntStateOf(4)
    private val toolbarSubtitleState = mutableStateOf("")
    private val isLoadingState = mutableStateOf(false)
    private val itemsState = mutableStateOf<List<ImageItem>>(emptyList())
    private val ngRulesState = mutableStateOf<List<NgRule>>(emptyList())
    private val colorModeState = mutableStateOf<String?>(null)

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
                    val promptInfo = MetadataExtractor.extract(this@MainActivity, uri.toString(), networkClient)
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

        // Compose用初期化
        spanCountState.intValue = getGridSpanCount()
        ngRulesState.value = ngStore.getRules()
        colorModeState.value = getColorModePref()

        // Compose UI
        setContent {
            FutaburakariTheme(colorMode = colorModeState.value) {
                MainCatalogScreen(
                    title = getString(R.string.app_name),
                    subtitle = toolbarSubtitleState.value,
                    items = itemsState.value,
                    isLoading = isLoadingState.value,
                    spanCount = spanCountState.intValue,
                    query = currentQuery.orEmpty(),
                    onQueryChange = { q -> currentQuery = q },
                    onReload = {
                        cancelAutoUpdate()
                        fetchDataForCurrentUrl()
                        Toast.makeText(this, getString(R.string.reloading), Toast.LENGTH_SHORT).show()
                    },
                    onSelectBookmark = { showBookmarkSelectionDialog() },
                    onManageBookmarks = {
                        val intent = Intent(this, BookmarkActivity::class.java)
                        bookmarkActivityResultLauncher.launch(intent)
                    },
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    onOpenHistory = { startActivity(Intent(this, HistoryActivity::class.java)) },
                    onImageEdit = { startActivity(Intent(this, ImagePickerActivity::class.java)) },
                    onBrowseLocalImages = { pickImageLauncher.launch("image/*") },
                    onItemClick = { item -> handleItemClick(item) },
                    ngRules = ngRulesState.value,
                )
            }
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        // 端末再起動後も catset 適用済みボードをスキップできるように永続化されたセットを読み込み
        restoreAppliedBoards()

        observeViewModel()
        loadAndFetchInitialData()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        // 遅延実行の取り消し（画面破棄後に走らないように）
        autoUpdateRunnable = null
        isAutoUpdateEnabled = false
        setAutoUpdateIndicator(false)
    }

    override fun onResume() {
        super.onResume()
        // 設定でのNG変更を反映（Composeへ）
        ngRulesState.value = ngStore.getRules()
    }

    private fun configureSwipeRefreshIndicatorPosition() { /* no-op in Compose */ }

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
                } else if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    // ユーザ操作開始時は保留中の自動更新を確実にキャンセル
                    cancelAutoUpdate()
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
        var completed = false
        try {
            // 画面終了・破棄後に実行されないように二重ガード
            if (isFinishing || isDestroyed) return
            currentSelectedUrl?.let { url ->
                val currentItemCount = imageAdapter.itemCount
                viewModel.checkForUpdates(url, currentItemCount) { _ ->
                    completed = true
                    runOnUiThread {
                        setAutoUpdateIndicator(false)
                        isAutoUpdateEnabled = false
                    }
                }
            }
        } catch (_: Throwable) {
            // 例外時も確実にOFF
            completed = true
        } finally {
            if (!completed) {
                // コールバック未達などの保険
                setAutoUpdateIndicator(false)
                isAutoUpdateEnabled = false
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
        toolbarSubtitleState.value = getCurrentBookmarkName()
        fetchDataForCurrentUrl()
    }

    private fun setAutoUpdateIndicator(show: Boolean) {
        autoIndicatorShown = show
        // Compose側でローディングを制御するため、ここではUI更新は不要
    }

    private fun syncLoadingUi() {
        // Compose移行により、従来のView更新は不要
    }

    private fun fetchDataForCurrentUrl() {
        currentSelectedUrl?.let { url ->
            // 初期表示の遅延を避けるため、まずは即座に一覧を取得開始
            viewModel.fetchImagesFromUrl(url)

            // カタログ設定の適用は非同期で実行し、適用が「今回新規で行われた」場合のみ再取得を掛ける
            lifecycleScope.launch {
                val boardBaseUrl = url.substringBefore("futaba.php")
                if (boardBaseUrl.isNotEmpty() && url.contains("futaba.php")) {
                    val boardKey = boardBaseUrl.trimEnd('/')
                    val wasApplied = isCatsetAppliedRecent(boardKey)
                    try {
                        applyCatalogSettings(boardBaseUrl)
                        // 今回新規に適用された場合のみ、一覧を更新して差分を反映
                        if (!wasApplied) {
                            viewModel.fetchImagesFromUrl(url)
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "カタログ設定の適用に失敗", e)
                    }
                }
            }
        } ?: run {
            // 選択中のURLがない場合は、ブックマーク選択ダイアログを表示
            showBookmarkSelectionDialog()
        }
    }

    private suspend fun applyCatalogSettings(boardBaseUrl: String) {
        // 例: https://zip.2chan.net/1/ までをキーとする
        val boardKey = boardBaseUrl.trimEnd('/')
        if (isCatsetAppliedRecent(boardKey)) return

        val settings = mapOf("mode" to "catset", "cx" to "20", "cy" to "10", "cl" to "10")
        try {
            networkClient.applySettings(boardBaseUrl, settings)
            catsetAppliedBoards.add(boardKey)
            catsetAppliedTimestamps[boardKey] = System.currentTimeMillis()
            persistAppliedBoards()
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

    private fun restoreAppliedBoards() {
        val sp = getSharedPreferences(catsetPrefsName, MODE_PRIVATE)
        // 旧形式（Set<String>）互換
        val legacy = sp.getStringSet(catsetPrefsKey, null)

        // 新形式（JSON: Map<String, Long>）
        val json = sp.getString(catsetPrefsTsKey, null)
        catsetAppliedBoards.clear()
        catsetAppliedTimestamps.clear()
        if (!json.isNullOrBlank()) {
            runCatching {
                val map: Map<String, Double> = Gson().fromJson(json, Map::class.java) as Map<String, Double>
                val now = System.currentTimeMillis()
                map.forEach { (k, v) ->
                    val ts = v.toLong()
                    // TTLを過ぎたものは復元しない（再適用対象）
                    if (now - ts < CATSET_TTL_MS) {
                        catsetAppliedBoards.add(k)
                        catsetAppliedTimestamps[k] = ts
                    }
                }
            }
        } else if (legacy != null) {
            // 旧保存分はTTL起点が無いので「今」を時刻として復元
            val now = System.currentTimeMillis()
            catsetAppliedBoards.addAll(legacy)
            legacy.forEach { catsetAppliedTimestamps[it] = now }
            persistAppliedBoards()
        }
    }

    private fun persistAppliedBoards() {
        val sp = getSharedPreferences(catsetPrefsName, MODE_PRIVATE)
        // 旧形式も一応更新（後方互換）
        sp.edit().putStringSet(catsetPrefsKey, catsetAppliedBoards).apply()
        // 新形式としてJSON保存
        val json = Gson().toJson(catsetAppliedTimestamps)
        sp.edit().putString(catsetPrefsTsKey, json).apply()
    }

    private fun isCatsetAppliedRecent(boardKey: String): Boolean {
        val ts = catsetAppliedTimestamps[boardKey] ?: return false
        return (System.currentTimeMillis() - ts) < CATSET_TTL_MS
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
                cancelAutoUpdate()
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
        // タイトルNG適用 → 検索クエリ適用 の順。
        val rules = ngStore.getRules().filter { it.type == RuleType.TITLE }
        val titleFiltered = if (rules.isEmpty()) allItems else allItems.filter { item ->
            val title = item.title.orEmpty()
            !rules.any { r -> matchTitle(title, r) }
        }
        val filteredList = if (query.isNullOrEmpty()) {
            titleFiltered
        } else {
            titleFiltered.filter { it.title.contains(query, ignoreCase = true) }
        }
        imageAdapter.submitList(filteredList)
    }

    private fun matchTitle(title: String, rule: NgRule): Boolean {
        val pattern = rule.pattern
        val mt = rule.match ?: MatchType.SUBSTRING
        return when (mt) {
            MatchType.EXACT -> title == pattern
            MatchType.PREFIX -> title.startsWith(pattern, ignoreCase = true)
            MatchType.SUBSTRING -> title.contains(pattern, ignoreCase = true)
            MatchType.REGEX -> runCatching { Regex(pattern, setOf(RegexOption.IGNORE_CASE)).containsMatchIn(title) }.getOrDefault(false)
        }
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
                toolbarSubtitleState.value = selectedBookmark.name
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
            // 高さが変わるカードや画像があるため固定サイズは無効化
            setHasFixedSize(false)
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
            isLoadingState.value = isLoading
        }

        viewModel.images.observe(this) { items ->
            // Compose UIへ反映
            setAutoUpdateIndicator(false)
            allItems = items
            itemsState.value = items

            // いったん既存プリフェッチを止める
            prefetchJob?.cancel()

            // レイアウトが確定してから可視範囲を算出
            /*binding.recyclerView.post {
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
            }*/
        }

        viewModel.error.observe(this) { errorMessage ->
            // エラー時の表示
            setAutoUpdateIndicator(false)
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun cancelAutoUpdate() {
        // RecyclerViewコールバックはComposeでは不要
        autoUpdateRunnable = null
        isAutoUpdateEnabled = false
        setAutoUpdateIndicator(false)
    }
    private val networkClient: NetworkClient by lazy {
        EntryPointAccessors.fromApplication(applicationContext, NetworkEntryPoint::class.java).networkClient()
    }

    private fun handleItemClick(item: ImageItem) {
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
            putExtra(DetailActivity.EXTRA_TITLE, item.title)
        }
        startActivity(intent)
    }

    private fun getColorModePref(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getString("pref_key_color_mode", "green")
    }
}
