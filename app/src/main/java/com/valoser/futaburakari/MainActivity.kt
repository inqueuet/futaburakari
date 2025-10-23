/*
 * カタログ一覧を表示するメインアクティビティ。
 * - ブックマーク選択/管理、設定・履歴・画像編集への遷移を提供。
 * - カタログの取得・表示と、設定項目（NGルール/列数/フォントスケール/カタログモード設定等）の反映を行う。
 * - OP画像なしスレッドは常時非表示に設定。
 * - カタログモード設定（cx/cy/cl）は設定画面で変更可能で、catset適用時に反映される。
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
import kotlinx.coroutines.flow.collectLatest
import android.view.Choreographer
import kotlin.coroutines.resume
import java.net.URL

@AndroidEntryPoint
/**
 * メイン画面（カタログ一覧）アクティビティ。
 *
 * - ブックマーク選択・管理、設定/履歴/画像編集への遷移を提供。
 * - カタログ（画像リスト）を取得・表示し、アイテムタップで詳細画面へ遷移。
 * - NGルール、グリッド列数、フォントスケール、カタログ表示モード、カタログモード設定（cx/cy/cl）などの設定変更を反映。
 * - OP画像なしスレッドは常時非表示に設定。
 * - Futaba の catset（カタログ表示設定）を板単位で適用し、3日間の TTL で再適用を抑制。設定画面で指定したcx/cy/cl値を使用。
 * - 端末内画像のメタデータ抽出→表示（ImageDisplayActivity）にも対応。
 * - TopBar: Compose 側 (`MainCatalogScreen`) でタイトル非表示、サブタイトル（選択中ブックマーク名）のみを大きめに表示。
 *
 * 関連:
 * - UI: `ui.compose.MainCatalogScreen`
 * - データ取得/整形: `MainViewModel`
 */
class MainActivity : BaseActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var currentSelectedUrl: String? = null
    // 検索クエリ（Compose 側で双方向バインド）
    private val queryState = mutableStateOf("")
    private var lastIsLoading: Boolean = false
    // 自動更新インジケータの互換用フラグ（Compose 版では視覚表示なし）
    private var autoIndicatorShown: Boolean = false

    // RecyclerView 時代の自動更新判定の名残。Compose 版では true にならず互換維持のみ。
    private var isAutoUpdateEnabled = false
    private lateinit var prefs: SharedPreferences
    // 設定変更の反映（列数/フォントスケール/NGルール/カタログ表示モード/カタログモード設定）
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "pref_key_grid_span" -> {
                spanCountState.intValue = getGridSpanCount()
            }
            "pref_key_catalog_display_mode" -> {
                catalogDisplayModeState.value = getCatalogDisplayMode()
            }
            "pref_key_font_scale" -> {
                // フォントスケールの反映には再生成が必要
                recreate()
            }
            // NGルール変更（設定画面など）
            "ng_rules_json" -> {
                ngRulesState.value = ngStore.getRules()
            }
            // カタログモード設定変更時は現在の板のTTLをクリアして次回リロード時に新設定を適用
            "pref_key_catalog_cx", "pref_key_catalog_cy", "pref_key_catalog_cl" -> {
                clearCurrentBoardCatsetState()
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
    private val catalogDisplayModeState = mutableStateOf("grid")
    private val toolbarSubtitleState = mutableStateOf("")
    private val isLoadingState = mutableStateOf(false)
    private val itemsState = mutableStateOf<List<ImageItem>>(emptyList())
    private val listIdentityState = mutableStateOf("")
    private val ngRulesState = mutableStateOf<List<NgRule>>(emptyList())
    private var listIdentityVersion = 0L
    private var pendingScrollReset = false

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
        catalogDisplayModeState.value = getCatalogDisplayMode()
        ngRulesState.value = ngStore.getRules()

        // Compose UI
        setContent {
            FutaburakariTheme(expressive = true) {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val errorMessage by viewModel.error.observeAsState()
                var showBookmarkDialog by rememberSaveable { mutableStateOf(currentSelectedUrl.isNullOrBlank()) }
                var showSortModeDialog by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(errorMessage) {
                    val msg = errorMessage
                    if (!msg.isNullOrBlank()) snackbarHostState.showSnackbar(msg)
                }

                Box {
                    MainCatalogScreen(
                        title = "",
                        subtitle = toolbarSubtitleState.value,
                        listIdentity = listIdentityState.value,
                        items = itemsState.value,
                        isLoading = isLoadingState.value,
                        spanCount = spanCountState.intValue,
                        catalogDisplayMode = catalogDisplayModeState.value,
                        query = queryState.value,
                        onQueryChange = { q -> queryState.value = q },
                        onReload = {
                            cancelAutoUpdate()
                            fetchDataForCurrentUrl()
                            scope.launch { snackbarHostState.showSnackbar(getString(R.string.reloading)) }
                        },
                        onPrefetchHint = { hint -> viewModel.submitCatalogPrefetchHint(hint) },
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
                        onSelectSortMode = {
                            showSortModeDialog = true
                        },
                        onManageBookmarks = {
                            val intent = Intent(this@MainActivity, BookmarkActivity::class.java)
                            bookmarkActivityResultLauncher.launch(intent)
                        },
                        onOpenSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
                        onOpenHistory = { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) },
                        onImageEdit = { startActivity(Intent(this@MainActivity, ImagePickerActivity::class.java)) },
                        onVideoEdit = { startActivity(Intent(this@MainActivity, com.valoser.futaburakari.videoeditor.presentation.ui.EditorActivity::class.java)) },
                        onBrowseLocalImages = { pickImageLauncher.launch("image/*") },
                        onItemClick = { item -> handleItemClick(item) },
                        ngRules = ngRulesState.value,
                        onImageLoadHttp404 = { item, failedUrl ->
                            viewModel.fixImageIf404NoHtml(item.detailUrl, failedUrl)
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
                                                    pendingScrollReset = true
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

                    if (showSortModeDialog) {
                        val sortModes = listOf(
                            "カタログ" to "",
                            "新順" to "1",
                            "古順" to "2",
                            "多順" to "3",
                            "少順" to "4",
                            "勢順" to "6"
                        )
                        AlertDialog(
                            onDismissRequest = { showSortModeDialog = false },
                            title = { androidx.compose.material3.Text("カタログ並び順を選択") },
                            text = {
                                LazyColumn {
                                    items(sortModes) { (label, sortValue) ->
                                        androidx.compose.material3.Text(
                                            text = label,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    val baseUrl = currentSelectedUrl
                                                    if (!baseUrl.isNullOrBlank() && baseUrl.contains("futaba.php")) {
                                                        // ベースURLを取得（クエリパラメータを除去）
                                                        val urlBase = baseUrl.substringBefore("?")
                                                        // 新しいURLを構築
                                                        val newUrl = if (sortValue.isEmpty()) {
                                                            "$urlBase?mode=cat"
                                                        } else {
                                                            "$urlBase?mode=cat&sort=$sortValue"
                                                        }
                                                        currentSelectedUrl = newUrl
                                                        BookmarkManager.saveSelectedBookmarkUrl(this@MainActivity, newUrl)
                                                        showSortModeDialog = false
                                                        pendingScrollReset = true
                                                        fetchDataForCurrentUrl()
                                                    }
                                                }
                                                .padding(vertical = com.valoser.futaburakari.ui.theme.LocalSpacing.current.m)
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showSortModeDialog = false }) { androidx.compose.material3.Text("閉じる") }
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

    override fun onPause() {
        super.onPause()
        // アプリ共通の画像プロンプトキャッシュに対しフラッシュを要求
        metadataCache.flush().invokeOnCompletion { error ->
            if (error != null) {
                Log.e("MainActivity", "Failed to flush metadata cache", error)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 設定でのNG変更を反映（Composeへ）
        ngRulesState.value = ngStore.getRules()
    }

    // 何もしない: 旧実装との互換のために残置（Compose 移行で不要）
    private fun configureSwipeRefreshIndicatorPosition() { }
    // RecyclerView時代の処理はComposeへ移行済み
    /**
     * 自動更新（旧RecyclerView由来の仕組み）を明示的に停止する。
     * Compose 版ではスクロール連動等は未使用のためインジケータ制御のみを行う。
     */
    private fun cancelAutoUpdate() {
        // RecyclerViewコールバックはComposeでは不要
        autoUpdateRunnable = null
        isAutoUpdateEnabled = false
        setAutoUpdateIndicator(false)
    }
    private val networkClient: NetworkClient by lazy {
        EntryPointAccessors.fromApplication(applicationContext, NetworkEntryPoint::class.java).networkClient()
    }

    // Hilt のシングルトン MetadataCache を EntryPoint 経由で解決
    private val metadataCache: MetadataCache by lazy {
        MetadataCacheEntryPoint.resolve(applicationContext)
    }

    /**
     * アイテム押下時の処理。
     * - 画像のプリフェッチを試みた上で、詳細画面へ遷移する。
     */
    private fun handleItemClick(item: ImageItem) {
        val baseUrlString = currentSelectedUrl
        val imageUrlString: String = when {
            // カタログと同じ方針: フルは実描画成功が確認できた場合に優先
            !item.fullImageUrl.isNullOrBlank() && item.hadFullSuccess -> item.fullImageUrl
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

    // 設定からカタログ表示モードを取得
    private fun getCatalogDisplayMode(): String {
        return PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_catalog_display_mode", "grid") ?: "grid"
    }

    /**
     * 初期の選択ブックマークと副題を設定し、現在のURLでデータ取得を開始する。
     */
    private fun loadAndFetchInitialData() {
        currentSelectedUrl = BookmarkManager.getSelectedBookmarkUrl(this)
        toolbarSubtitleState.value = getCurrentBookmarkName()
        pendingScrollReset = true
        fetchDataForCurrentUrl()
    }

    private fun updateListIdentity(url: String?, force: Boolean = false) {
        val target = url.orEmpty()
        val currentBase = listIdentityState.value.substringBefore('#', "")
        if (force || currentBase != target) {
            listIdentityVersion++
            listIdentityState.value = "$target#${listIdentityVersion}"
        }
    }

    /**
     * 現在のURLで画像一覧を取得する。
     * - mode クエリがない Futaba カタログ URL には `mode=cat` を自動で付加する。
     * - 必要に応じて catset を適用し、新規適用時は再フェッチで反映する。
     */
    private fun fetchDataForCurrentUrl() {
        var url = currentSelectedUrl
        if (url.isNullOrBlank()) return

        // futaba.php を含む URL で mode パラメータが欠けていれば cat を自動付加
        if (url.contains("futaba.php") && !url.contains("mode=")) {
            url = if (url.contains("?")) {
                "$url&mode=cat"
            } else {
                "$url?mode=cat"
            }
            currentSelectedUrl = url
        }

        updateListIdentity(url, force = pendingScrollReset)
        pendingScrollReset = false

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

    /**
     * 次の描画フレームのコールバックを1回待機する。
     * 画面遷移直後のカクつきを避けるため、重い処理の前に1フレーム挟む用途に使用。
     */
    private suspend fun awaitNextFrame() = suspendCancellableCoroutine { cont ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { if (!cont.isCompleted) cont.resume(Unit) }
        choreographer.postFrameCallback(callback)
        cont.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }

    /**
     * 板のカタログ設定(catset)を適用し、適用済み情報を永続化する。
     * TTL 内に適用済みであれば再適用をスキップする。
     * 設定画面で指定されたcx（横サイズ）、cy（縦サイズ）、cl（文字数）の値を使用する。
     */
    private suspend fun applyCatalogSettings(boardBaseUrl: String) {
        val boardKey = boardBaseUrl.trimEnd('/')
        if (isCatsetAppliedRecent(boardKey)) return

        // 設定から値を取得
        val cx = prefs.getString("pref_key_catalog_cx", "20") ?: "20"
        val cy = prefs.getString("pref_key_catalog_cy", "10") ?: "10"
        val rawCl = prefs.getString("pref_key_catalog_cl", "10")
        val clInt = rawCl?.toIntOrNull()?.coerceIn(3, 15) ?: 10
        val cl = clInt.toString()
        if (cl != rawCl) {
            prefs.edit().putString("pref_key_catalog_cl", cl).apply()
        }

        val settings = mapOf("mode" to "catset", "cx" to cx, "cy" to cy, "cl" to cl)
        withContext(Dispatchers.IO) {
            networkClient.applySettings(boardBaseUrl, settings)
        }
        catsetAppliedBoards.add(boardKey)
        catsetAppliedTimestamps[boardKey] = System.currentTimeMillis()
        persistAppliedBoards()
    }

    /**
     * 起動時に、適用済みボード情報を読み込む。
     * TTL を過ぎたものは破棄し、有効なもののみを復元する。
     */
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

    /**
     * 適用済みボード情報（ボード集合とタイムスタンプ）を SharedPreferences へ保存する。
     */
    private fun persistAppliedBoards() {
        val sp = getSharedPreferences(catsetPrefsName, MODE_PRIVATE)
        sp.edit().putStringSet(catsetPrefsKey, catsetAppliedBoards).apply()
        val json = Gson().toJson(catsetAppliedTimestamps)
        sp.edit().putString(catsetPrefsTsKey, json).apply()
    }

    /**
     * 指定ボードに対して catset が TTL 内に適用済みか判定する。
     */
    private fun isCatsetAppliedRecent(boardKey: String): Boolean {
        val ts = catsetAppliedTimestamps[boardKey] ?: return false
        return (System.currentTimeMillis() - ts) < CATSET_TTL_MS
    }

    /**
     * 現在選択中の板のcatset適用済み状態をクリアする。
     * カタログモード設定（cx/cy/cl）変更時に呼ばれ、次回のプルリフレッシュや再選択時に新しい設定が適用される。
     * 他の板のTTLは維持され、不要な通信を避けられる。
     */
    private fun clearCurrentBoardCatsetState() {
        val url = currentSelectedUrl
        if (!url.isNullOrBlank()) {
            val boardBaseUrl = url.substringBefore("futaba.php")
            if (boardBaseUrl.isNotEmpty() && url.contains("futaba.php")) {
                val boardKey = boardBaseUrl.trimEnd('/')
                // 現在の板のみTTLをクリア
                catsetAppliedBoards.remove(boardKey)
                catsetAppliedTimestamps.remove(boardKey)
                persistAppliedBoards()
            }
        }
    }

    /**
     * ViewModel の公開状態を監視して UI の状態（ローディング/一覧/エラー）に反映する。
     */
    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            lastIsLoading = isLoading
            isLoadingState.value = isLoading
        }

        // 差分更新: ViewModel から順序維持済みリストをそのまま受け取る
        lifecycleScope.launch {
            viewModel.imageList.collectLatest { list ->
                setAutoUpdateIndicator(false)
                itemsState.value = list
            }
        }

        viewModel.error.observe(this) { _ ->
            setAutoUpdateIndicator(false)
        }
    }

    /**
     * 自動更新インジケータの表示状態を更新。
     * 現在は視覚的な表示は行わず、状態のみ保持する。
     */
    private fun setAutoUpdateIndicator(show: Boolean) {
        autoIndicatorShown = show
    }

    /**
     * 現在選択中のブックマーク名を取得する（未選択時は既定文言）。
     */
    private fun getCurrentBookmarkName(): String {
        val bookmarks = BookmarkManager.getBookmarks(this)
        return bookmarks.find { it.url == currentSelectedUrl }?.name ?: "ブックマーク未選択"
    }

    // 旧ダイアログは廃止。setContent 内の Compose AlertDialog を使用

    // endregion

    // メモ: 配色モードの個別アクセサは廃止（テーマへ統合）
}
