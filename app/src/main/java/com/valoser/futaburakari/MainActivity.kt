package com.valoser.futaburakari

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import coil.imageLoader
import coil.request.ImageRequest
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.valoser.futaburakari.ui.compose.MainCatalogScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

@AndroidEntryPoint
class MainActivity : BaseActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var currentSelectedUrl: String? = null
    // Compose検索クエリ状態
    private val queryState = mutableStateOf("")
    private var lastIsLoading: Boolean = false
    private var autoIndicatorShown: Boolean = false

    // 自動更新機能用のフィールド（改良版）
    private var isAutoUpdateEnabled = false
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

    // Compose移行により、スクロール判定はComposable側に実装

    // catset適用フラグを「板（例: https://zip.2chan.net/1/）」単位で保持
    private val catsetAppliedBoards = mutableSetOf<String>()
    private val catsetAppliedTimestamps = mutableMapOf<String, Long>() // boardKey -> appliedAt
    private val catsetPrefsName = "com.valoser.futaburakari.catalog"
    private val catsetPrefsKey = "applied_boards"
    private val catsetPrefsTsKey = "applied_boards_ts"
    private val CATSET_TTL_MS = 3L * 24 * 60 * 60 * 1000 // 3日

    private var autoUpdateRunnable: Runnable? = null
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
                    query = queryState.value,
                    onQueryChange = { q -> queryState.value = q },
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

    // no-op: kept for backward compatibility during transition
    private fun configureSwipeRefreshIndicatorPosition() { }
    // RecyclerView時代の処理はComposeへ移行済み
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

    // region Compose-friendly helpers (migrated from View-era)

    private fun getGridSpanCount(): Int {
        val value = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_grid_span", "4") ?: "4"
        return value.toIntOrNull()?.coerceIn(1, 8) ?: 4
    }

    private fun loadAndFetchInitialData() {
        currentSelectedUrl = BookmarkManager.getSelectedBookmarkUrl(this)
        toolbarSubtitleState.value = getCurrentBookmarkName()
        fetchDataForCurrentUrl()
    }

    private fun fetchDataForCurrentUrl() {
        val url = currentSelectedUrl
        if (url.isNullOrBlank()) {
            showBookmarkSelectionDialog()
            return
        }
        viewModel.fetchImagesFromUrl(url)

        // Apply catalog settings asynchronously; if newly applied, refetch once
        lifecycleScope.launch {
            val boardBaseUrl = url.substringBefore("futaba.php")
            if (boardBaseUrl.isNotEmpty() && url.contains("futaba.php")) {
                val boardKey = boardBaseUrl.trimEnd('/')
                val wasApplied = isCatsetAppliedRecent(boardKey)
                runCatching { applyCatalogSettings(boardBaseUrl) }
                if (!wasApplied) viewModel.fetchImagesFromUrl(url)
            }
        }
    }

    private suspend fun applyCatalogSettings(boardBaseUrl: String) {
        val boardKey = boardBaseUrl.trimEnd('/')
        if (isCatsetAppliedRecent(boardKey)) return
        val settings = mapOf("mode" to "catset", "cx" to "20", "cy" to "10", "cl" to "10")
        withContext(Dispatchers.IO) {
            networkClient.applySettings(boardBaseUrl, settings)
        }
        catsetAppliedBoards.add(boardKey)
        catsetAppliedTimestamps[boardKey] = System.currentTimeMillis()
        persistAppliedBoards()
    }

    private fun restoreAppliedBoards() {
        val sp = getSharedPreferences(catsetPrefsName, MODE_PRIVATE)
        val legacy = sp.getStringSet(catsetPrefsKey, null)
        val json = sp.getString(catsetPrefsTsKey, null)
        catsetAppliedBoards.clear()
        catsetAppliedTimestamps.clear()
        if (!json.isNullOrBlank()) {
            runCatching {
                val map: Map<String, Double> = Gson().fromJson(json, Map::class.java) as Map<String, Double>
                val now = System.currentTimeMillis()
                map.forEach { (k, v) ->
                    val ts = v.toLong()
                    if (now - ts < CATSET_TTL_MS) {
                        catsetAppliedBoards.add(k)
                        catsetAppliedTimestamps[k] = ts
                    }
                }
            }
        } else if (legacy != null) {
            val now = System.currentTimeMillis()
            catsetAppliedBoards.addAll(legacy)
            legacy.forEach { catsetAppliedTimestamps[it] = now }
            persistAppliedBoards()
        }
    }

    private fun persistAppliedBoards() {
        val sp = getSharedPreferences(catsetPrefsName, MODE_PRIVATE)
        sp.edit().putStringSet(catsetPrefsKey, catsetAppliedBoards).apply()
        val json = Gson().toJson(catsetAppliedTimestamps)
        sp.edit().putString(catsetPrefsTsKey, json).apply()
    }

    private fun isCatsetAppliedRecent(boardKey: String): Boolean {
        val ts = catsetAppliedTimestamps[boardKey] ?: return false
        return (System.currentTimeMillis() - ts) < CATSET_TTL_MS
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            lastIsLoading = isLoading
            isLoadingState.value = isLoading
        }

        viewModel.images.observe(this) { items ->
            setAutoUpdateIndicator(false)
            itemsState.value = items
        }

        viewModel.error.observe(this) { errorMessage ->
            setAutoUpdateIndicator(false)
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    private fun setAutoUpdateIndicator(show: Boolean) {
        autoIndicatorShown = show
    }

    private fun getCurrentBookmarkName(): String {
        val bookmarks = BookmarkManager.getBookmarks(this)
        return bookmarks.find { it.url == currentSelectedUrl }?.name ?: "ブックマーク未選択"
    }

    private fun showBookmarkSelectionDialog() {
        val bookmarks = BookmarkManager.getBookmarks(this)
        val names = bookmarks.map { it.name }.toTypedArray()
        if (names.isEmpty()) {
            Toast.makeText(this, "ブックマークがありません。まずはブックマークを登録してください。", Toast.LENGTH_LONG).show()
            val intent = Intent(this, BookmarkActivity::class.java)
            bookmarkActivityResultLauncher.launch(intent)
            return
        }
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, names)
        AlertDialog.Builder(this)
            .setTitle("ブックマークを選択")
            .setAdapter(adapter) { dialog, which ->
                val selected = bookmarks[which]
                currentSelectedUrl = selected.url
                toolbarSubtitleState.value = selected.name
                BookmarkManager.saveSelectedBookmarkUrl(this, selected.url)
                fetchDataForCurrentUrl()
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // endregion

    private fun getColorModePref(): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getString("pref_key_color_mode", "green")
    }
}
