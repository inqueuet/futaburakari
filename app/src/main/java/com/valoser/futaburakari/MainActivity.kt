/*
 * カタログ一覧を表示するメインアクティビティ。
 * - ブックマーク選択/管理、設定・履歴・画像編集への遷移を提供。
 * - カタログの取得・表示と、設定項目（NGルール/列数/フォントスケール等）の反映を行う。
 */
package com.valoser.futaburakari

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.network.httpHeaders
import coil3.network.NetworkHeaders
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableIntStateOf
import com.valoser.futaburakari.ui.compose.MainCatalogScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import android.view.Choreographer
import kotlin.coroutines.resume
import java.net.URL

@AndroidEntryPoint
/**
 * メイン画面（カタログ一覧）アクティビティ。
 *
 * - ブックマーク選択・管理、設定/履歴/画像編集への遷移を提供。
 * - カタログ（画像リスト）を取得・表示し、アイテムタップで詳細画面へ遷移。
 * - NGルール、グリッド列数、フォントスケール、配色モードなどの設定変更を反映。
 * - Futaba の catset（カタログ表示設定）を板単位で適用し、3日間の TTL で再適用を抑制。
 * - 端末内画像のメタデータ抽出→表示（ImageDisplayActivity）にも対応。
 * - TopBar: タイトルは表示せず、サブタイトル（選択中ブックマーク名）のみを大きめに表示する。
 *
 * 関連:
 * - UI: `ui.compose.MainCatalogScreen`
 * - データ取得/整形: `MainViewModel`
 */
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
    // 設定変更の反映（列数/フォントスケール/NGルール/配色モード）
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "pref_key_grid_span" -> {
                spanCountState.intValue = getGridSpanCount()
            }
            "pref_key_font_scale" -> {
                // フォントスケールの反映には再生成が必要
                recreate()
            }
            // NGルール変更（設定画面など）
            "ng_rules_json" -> {
                ngRulesState.value = ngStore.getRules()
            }
            // 旧カラー設定は廃止（現在はテーマ側で動的/既定に統合）
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

    // ブックマーク画面から戻った後にデータ再取得
    private val bookmarkActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        loadAndFetchInitialData()
    }

    // 端末内のローカル画像を選択（GetContent）し、メタデータを抽出して表示画面へ渡す
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

        // Compose UI
        setContent {
            FutaburakariTheme(expressive = true) {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val errorMessage by viewModel.error.observeAsState()
                var showBookmarkDialog by rememberSaveable { mutableStateOf(currentSelectedUrl.isNullOrBlank()) }

                LaunchedEffect(errorMessage) {
                    val msg = errorMessage
                    if (!msg.isNullOrBlank()) snackbarHostState.showSnackbar(msg)
                }

                Box {
                    MainCatalogScreen(
                        title = "",
                        subtitle = toolbarSubtitleState.value,
                        items = itemsState.value,
                        isLoading = isLoadingState.value,
                        spanCount = spanCountState.intValue,
                        query = queryState.value,
                        onQueryChange = { q -> queryState.value = q },
                        onReload = {
                            cancelAutoUpdate()
                            fetchDataForCurrentUrl()
                            scope.launch { snackbarHostState.showSnackbar(getString(R.string.reloading)) }
                        },
                    onSelectBookmark = {
                        val bms = BookmarkManager.getBookmarks(this@MainActivity)
                        if (bms.isEmpty()) {
                            scope.launch { snackbarHostState.showSnackbar("ブックマークがありません。まずはブックマークを登録してください。") }
                            val intent = Intent(this@MainActivity, BookmarkActivity::class.java)
                            bookmarkActivityResultLauncher.launch(intent)
                        } else {
                            showBookmarkDialog = true
                        }
                    },
                    onManageBookmarks = {
                        val intent = Intent(this@MainActivity, BookmarkActivity::class.java)
                        bookmarkActivityResultLauncher.launch(intent)
                    },
                    onOpenSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                    onOpenHistory = { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) },
                    onImageEdit = { startActivity(Intent(this@MainActivity, ImagePickerActivity::class.java)) },
                    onBrowseLocalImages = { pickImageLauncher.launch("image/*") },
                    onItemClick = { item -> handleItemClick(item) },
                    ngRules = ngRulesState.value,
                    onImageLoadHttp404 = { item, failedUrl ->
                        viewModel.fixImageIf404(item.detailUrl, failedUrl)
                    },
                    onImageLoadSuccess = { item, loadedUrl ->
                        viewModel.notifyFullImageSuccess(item.detailUrl, loadedUrl)
                    },
                    )

                    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

                    if (showBookmarkDialog) {
                        val bookmarks = remember { BookmarkManager.getBookmarks(this@MainActivity) }
                        AlertDialog(
                            onDismissRequest = { showBookmarkDialog = false },
                            title = { androidx.compose.material3.Text("ブックマークを選択") },
                            text = {
                                LazyColumn {
                                    items(bookmarks) { b ->
                                        androidx.compose.material3.Text(
                                            text = b.name,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    currentSelectedUrl = b.url
                                                    toolbarSubtitleState.value = b.name
                                                    BookmarkManager.saveSelectedBookmarkUrl(this@MainActivity, b.url)
                                                    showBookmarkDialog = false
                                                    fetchDataForCurrentUrl()
                                                }
                                                .padding(vertical = com.valoser.futaburakari.ui.theme.LocalSpacing.current.m)
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showBookmarkDialog = false }) { androidx.compose.material3.Text("閉じる") }
                            }
                        )
                    }
                }
            }
        }

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        // 端末再起動後も catset 適用済みボードをスキップできるよう、永続化されたセットを読み込み
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

    // 何もしない: 旧実装との互換のために残置（Compose 移行で不要）
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

    /**
     * アイテム押下時の処理。
     * - 画像のプリフェッチを試みた上で、詳細画面へ遷移する。
     */
    private fun handleItemClick(item: ImageItem) {
        val baseUrlString = currentSelectedUrl
        val imageUrlString: String = when {
            !item.fullImageUrl.isNullOrBlank() && !item.preferPreviewOnly -> item.fullImageUrl
            !item.previewUnavailable -> item.previewUrl
            else -> item.fullImageUrl ?: item.previewUrl
        }

        if (!baseUrlString.isNullOrBlank() && !imageUrlString.isNullOrBlank()) {
            try {
                val absoluteUrl = URL(URL(baseUrlString), imageUrlString).toString()
                Log.d("MainActivity_Prefetch", "Resolved absolute URL: $absoluteUrl")

                // 詳細画面での表示体験向上のため、画像を事前にプリフェッチ（Coil）
                val imageLoader = this.imageLoader
                val request = ImageRequest.Builder(this)
                    .data(absoluteUrl)
                    .httpHeaders(
                        NetworkHeaders.Builder()
                            .add("Referer", item.detailUrl)
                            .build()
                    )
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

    // region Compose向けヘルパー（View時代からの移行）

    // 設定からグリッド列数を取得（1..8 の範囲に丸め込み）
    private fun getGridSpanCount(): Int {
        val value = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_grid_span", "4") ?: "4"
        return value.toIntOrNull()?.coerceIn(1, 8) ?: 4
    }

    /**
     * 初期の選択ブックマークと副題を設定し、現在のURLでデータ取得を開始する。
     */
    private fun loadAndFetchInitialData() {
        currentSelectedUrl = BookmarkManager.getSelectedBookmarkUrl(this)
        toolbarSubtitleState.value = getCurrentBookmarkName()
        fetchDataForCurrentUrl()
    }

    /**
     * 現在のURLで画像一覧を取得する。
     * - 必要に応じて catset を適用し、新規適用時は再フェッチで反映する。
     */
    private fun fetchDataForCurrentUrl() {
        val url = currentSelectedUrl
        if (url.isNullOrBlank()) return
        viewModel.fetchImagesFromUrl(url)

        // 初回フレーム描画後に非同期で catset を適用。新規適用時のみ再フェッチする。
        lifecycleScope.launch {
            // 先に1フレーム描画して遷移時のカクつきを避ける
            awaitNextFrame()
            val boardBaseUrl = url.substringBefore("futaba.php")
            if (boardBaseUrl.isNotEmpty() && url.contains("futaba.php")) {
                val boardKey = boardBaseUrl.trimEnd('/')
                val wasApplied = isCatsetAppliedRecent(boardKey)
                runCatching { applyCatalogSettings(boardBaseUrl) }
                if (!wasApplied) viewModel.fetchImagesFromUrl(url)
            }
        }
    }

    // 次の描画フレームを待機するサスペンド関数
    private suspend fun awaitNextFrame() = suspendCancellableCoroutine { cont ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { if (!cont.isCompleted) cont.resume(Unit) }
        choreographer.postFrameCallback(callback)
        cont.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }

    // 板のカタログ設定(catset)を適用し、適用済み情報を永続化
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

    // 起動時に、適用済みボード情報を読み込む（TTL内のもののみ有効）
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

    // 適用済みボード情報を永続化
    private fun persistAppliedBoards() {
        val sp = getSharedPreferences(catsetPrefsName, MODE_PRIVATE)
        sp.edit().putStringSet(catsetPrefsKey, catsetAppliedBoards).apply()
        val json = Gson().toJson(catsetAppliedTimestamps)
        sp.edit().putString(catsetPrefsTsKey, json).apply()
    }

    // TTL内に catset が適用済みかどうか
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

        viewModel.error.observe(this) { _ ->
            setAutoUpdateIndicator(false)
        }
    }

    private fun setAutoUpdateIndicator(show: Boolean) {
        autoIndicatorShown = show
    }

    private fun getCurrentBookmarkName(): String {
        val bookmarks = BookmarkManager.getBookmarks(this)
        return bookmarks.find { it.url == currentSelectedUrl }?.name ?: "ブックマーク未選択"
    }

    // 旧ダイアログは廃止。setContent 内の Compose AlertDialog を使用

    // endregion

    // メモ: 配色モードの個別アクセサは廃止（テーマへ統合）
}
