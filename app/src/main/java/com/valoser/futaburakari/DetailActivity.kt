package com.valoser.futaburakari

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Html
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import coil.load
import androidx.recyclerview.widget.GridLayoutManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
// import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.activity.viewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.valoser.futaburakari.databinding.ActivityDetailBinding
import androidx.activity.compose.setContent
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import androidx.preference.PreferenceManager
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.LoadAdError

import com.valoser.futaburakari.worker.ThreadMonitorWorker
import dagger.hilt.android.AndroidEntryPoint
import com.valoser.futaburakari.ui.detail.DetailScreenScaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.valoser.futaburakari.search.RecentSearchStore

@AndroidEntryPoint
class DetailActivity : BaseActivity(), SearchManagerCallback {

    private lateinit var binding: ActivityDetailBinding
    private val viewModel: DetailViewModel by viewModels()
    private lateinit var detailAdapter: DetailAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var detailSearchManager: DetailSearchManager
    private lateinit var scrollStore: ScrollPositionStore

    private var pendingScrollPosition: Pair<Int, Int>? = null

    private var currentUrl: String? = null

    private var isRequestingMore = false   // 追加：多重呼び出し防止

    private var isInitialLoad = true // ★クラスのプロパティとして初期化

    private var isFastScrolling = false // 追加

    // メインスレッドハンドラ / 既読更新デバウンス
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var markViewedRunnable: Runnable? = null
    private var markViewedJob: kotlinx.coroutines.Job? = null

    // 本文検索のためのプレーンテキストキャッシュ（Text.id -> plainText）
    private var plainTextCache: Map<String, String> = emptyMap()
    private var buildPlainCacheJob: kotlinx.coroutines.Job? = null

    private val ngStore by lazy { NgStore(this) }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private var suppressNextRestore: Boolean = false

    private lateinit var prefs: SharedPreferences
    private val adsEnabledFlowInternal = MutableStateFlow(false)
    private val adsEnabledFlow = adsEnabledFlowInternal.asStateFlow()
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            // 広告設定の即時反映
            "pref_key_ads_enabled" -> {
                setupAdBanner()
                val enabled = prefs.getBoolean("pref_key_ads_enabled", false)
                adsEnabledFlowInternal.value = enabled
            }
            // NGルール変更を即時反映（NgStore は DefaultSharedPreferences を使用）
            "ng_rules_json" -> viewModel.reapplyNgFilter()
        }
    }

    private val replyActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(this, "送信しました。更新します。", Toast.LENGTH_SHORT).show()
            reloadDetails()
        }
    }
    // NG管理画面から戻ったらフィルタを再適用
    private val ngManagerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.reapplyNgFilter()
    }

    private var toolbarTitleText: String = ""
    private val bottomOffsetFlowInternal = MutableStateFlow(0)
    private val bottomOffsetFlow = bottomOffsetFlowInternal.asStateFlow()
    private val searchBarActiveFlowInternal = MutableStateFlow(false)
    private val searchBarActiveFlow = searchBarActiveFlowInternal.asStateFlow()
    private val recentSearchStore by lazy { RecentSearchStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        // Title for Compose TopBar and history
        toolbarTitleText = intent.getStringExtra(EXTRA_TITLE) ?: ""
        currentUrl = intent.getStringExtra(EXTRA_URL)
        // Initialize scroll store early to provide initial state for Compose list
        scrollStore = ScrollPositionStore(this)
        val initialScroll: Pair<Int, Int> = currentUrl?.let { url ->
            val key = UrlNormalizer.threadKey(url)
            scrollStore.getScrollState(key)
        } ?: (0 to 0)
        // Hide legacy toolbar (Compose TopBar を使用)
        binding.toolbar.isVisible = false
        // Switch to Compose container with Scaffold TopBar; legacy content is hosted inside
        val colorModePref = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")
        val useComposeList = true // フェーズ2: LazyColumnを有効化
        val showAdsPref = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("pref_key_ads_enabled", false)
        adsEnabledFlowInternal.value = showAdsPref
        val adUnitId = getString(R.string.admob_banner_id)
        setContent {
            FutaburakariTheme(colorMode = colorModePref) {
                val showAds by adsEnabledFlow.collectAsState()
                DetailScreenScaffold(
                    binding = binding,
                    title = toolbarTitleText,
                    onBack = {
                        onBackPressedDispatcher.onBackPressed()
                    },
                    onReply = { launchReplyActivity("") },
                    onReload = { reloadDetails() },
                    onOpenNg = { openNgManager() },
                    onOpenMedia = { },
                    onSodaneClick = { resNum -> viewModel.postSodaNe(resNum) },
                    onDeletePost = { resNum, onlyImage ->
                        val threadUrl = currentUrl ?: return@DetailScreenScaffold
                        val boardBasePath = threadUrl.substringBeforeLast("/").substringBeforeLast("/") + "/"
                        val postUrl = boardBasePath + "futaba.php?guid=on"
                        val pwd = AppPreferences.getPwd(this)
                        viewModel.deletePost(
                            postUrl = postUrl,
                            referer = threadUrl,
                            resNum = resNum,
                            pwd = pwd ?: "",
                            onlyImage = onlyImage,
                        )
                    },
                    onSubmitSearch = { q ->
                        recentSearchStore.add(q)
                        detailSearchManager.performSearch(q)
                    },
                    onDebouncedSearch = { q -> detailSearchManager.performSearch(q) },
                    onClearSearch = { detailSearchManager.clearSearch() },
                    onReapplyNgFilter = { viewModel.reapplyNgFilter() },
                    searchStateFlow = detailSearchManager.searchState,
                    bottomOffsetPxFlow = bottomOffsetFlow,
                    searchActiveFlow = searchBarActiveFlow,
                    onSearchActiveChange = { active -> searchBarActiveFlowInternal.value = active },
                    recentSearchesFlow = recentSearchStore.items,
                    useComposeList = useComposeList,
                    showAds = showAds,
                    adUnitId = adUnitId,
                    onBottomPaddingChange = { h -> bottomOffsetFlowInternal.value = h },
                    // Compose list scroll state persistence
                    initialScrollIndex = initialScroll.first,
                    initialScrollOffset = initialScroll.second,
                    onSaveScroll = { pos, off ->
                        val url = currentUrl ?: return@DetailScreenScaffold
                        val key = UrlNormalizer.threadKey(url)
                        scrollStore.saveScrollState(key, pos, off)
                    },
                    itemsLive = viewModel.detailContent,
                    currentQueryFlow = detailSearchManager.currentQueryFlow,
                    getSodaneState = { rn -> viewModel.getSodaNeState(rn) },
                    // Compose側で引用一覧を表示するため、ここでは何もしない
                    onQuoteClick = null,
                    onResNumClick = { resNum, resBody ->
                        if (resBody.isEmpty()) confirmAndDelete(resNum) else launchReplyActivity(resBody)
                    },
                    onResNumConfirmClick = { resNum -> showResReferencesPopup(resNum) },
                    onResNumDelClick = { resNum ->
                        // keep same as adapter behavior
                        val url = currentUrl ?: return@DetailScreenScaffold
                        val threadId = url.substringAfterLast("/").substringBefore(".htm")
                        val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/"
                        val postUrl = boardBasePath + "futaba.php?guid=on"
                        val pwd = AppPreferences.getPwd(this)
                        viewModel.deletePost(postUrl, url, resNum, pwd ?: "", onlyImage = false)
                    },
                    onBodyClick = { quotedBody -> launchReplyActivity(quotedBody) },
                    // Compose側でNG追加ダイアログを表示するため、ここでは何もしない
                    onAddNgFromBody = { _ -> },
                    onThreadEndTimeClick = { reloadDetails() },
                    onImageLoaded = {
                        detailSearchManager.realignToCurrentHitIfActive()
                    },
                    isRefreshingLive = viewModel.isLoading,
                    onVisibleMaxOrdinal = { ord -> markViewedByOrdinal(ord) },
                    threadUrl = currentUrl,
                    onNearListEnd = {
                        val url = currentUrl ?: return@DetailScreenScaffold
                        if (isRequestingMore) return@DetailScreenScaffold
                        // Compose側でファストスクロール中は抑制済み
                        isRequestingMore = true
                        suppressNextRestore = true
                        val postCount = countPostItems()
                        viewModel.checkForUpdates(url, postCount) { _ ->
                            isRequestingMore = false
                        }
                    }
                )
            }
        }

        // Hilt により viewModel は注入済み（by viewModels()）
        // SharedPreferences 準備（設定変更のリッスンに使用）
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        binding.toolbarTitle.text = toolbarTitleText

        // 履歴に記録（タイトルがない場合はURL末尾などで代替も可）
        currentUrl?.let { url ->
            val title = binding.toolbarTitle.text?.toString().orEmpty().ifBlank { url }
            HistoryManager.addOrUpdate(this, url, title)
            // すぐ閉じた場合でも本文を含めてローカルに残せるよう、単発のスナップショット取得を即時キュー
            ThreadMonitorWorker.snapshotNow(this, url)
        }

        if (!useComposeList) {
            setupRecyclerView()
        } else {
            // Compose リストのみ使用するが、検索マネージャ等が adapter を参照するため
            // 最低限のインスタンスだけ確保しておく（RecyclerView には接続しない）
            if (!this::detailAdapter.isInitialized) detailAdapter = DetailAdapter()
            binding.detailRecyclerView.isVisible = false
            binding.swipeRefreshLayout.isEnabled = false
            // Hide legacy fast scroller views when using Compose list
            binding.fastScrollTrack.visibility = View.GONE
            binding.fastScrollThumb.visibility = View.GONE
        }

        // DetailSearchManager は (binding, callback) で生成
        detailSearchManager = DetailSearchManager(binding, this)

        // bottom_container の高さ監視はレガシーUI使用時のみ
        if (!useComposeList) {
            binding.bottomContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateRecyclerBottomPadding()
                // 検索UIや広告の高さが変わったときに、ヒット項目が隠れないよう再吸着
                detailSearchManager.realignToCurrentHitIfActive()
                // Compose 側のオフセットにも反映（重なり回避）
                bottomOffsetFlowInternal.value = binding.bottomContainer.height
            }
        }

        observeViewModel()
        currentUrl?.let { viewModel.fetchDetails(it) }

        // 端末戻る：検索展開中は閉じる
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Compose検索バーが開いていれば先に閉じる
                if (searchBarActiveFlowInternal.value) {
                    searchBarActiveFlowInternal.value = false
                    return
                }
                if (detailSearchManager.handleOnBackPressed()) return
                // ★ ここでスクロール位置を保存
                saveScroll()

                // デフォルトの戻るへ委譲
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        if (!useComposeList) {
            binding.swipeRefreshLayout.setOnRefreshListener { reloadDetails() }
        }

        // Ensure bottom container sits above navigation bar (legacy only)
        if (!useComposeList) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.bottomContainer) { v, insets ->
                val sys = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, sys.bottom)
                // RecyclerView bottom padding mirrors bottomContainer height; this will be picked up
                androidx.core.view.WindowInsetsCompat.CONSUMED
            }
        }
    }

    private fun setupAdBanner() {
        // 既定は OFF（設定で有効化したときのみ表示）
        val showAds = prefs.getBoolean("pref_key_ads_enabled", false)
        val adView = binding.adView
        if (showAds) {
            adView.isVisible = true
            // Basic diagnostics to surface load status
            adView.setAdListener(object : AdListener() {
                override fun onAdLoaded() {
                    // Ad loaded successfully; bottom container height may change
                    updateRecyclerBottomPadding()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    // Toast.makeText(this@DetailActivity, "広告の読み込み失敗: ${error.code}", Toast.LENGTH_SHORT).show()
                }
            })
            // 再開してからロード（OFF→ON直後のケースを考慮）
            adView.resume()
            val adRequest = AdRequest.Builder().build()
            adView.loadAd(adRequest)
            // Keep RecyclerView padding in sync with bottom container height
            updateRecyclerBottomPadding()
        } else {
            // 完全に非表示・停止（既に読み込んだ広告が残らないよう念のためクリア）
            adView.isVisible = false
            // 一部バージョンでは非null指定のため、空リスナーで解除相当とする
            adView.setAdListener(object : AdListener() {})
            adView.pause()
            // 子ビューの強制除去は再ロード不可の原因になるため行わない
            updateRecyclerBottomPadding()
        }
    }

    override fun onStart() {
        super.onStart()
        // 設定変更（広告ON/OFF）を戻り時にも反映
        // レガシーUI使用時のみバナー制御（Compose移行時はCompose側で表示）
        if (binding.detailRecyclerView.isVisible) {
            setupAdBanner()
        }
        // 設定変更の監視を開始
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        // アクティビティが表示されるたびにスクロール位置の復元を試みる
        // isInitialLoadフラグはリロード時の復元制御に利用するため残す
        isInitialLoad = true
        if (!suppressNextRestore) {
            restoreScroll()
        }
    }

    override fun onStop() {
        // 設定変更の監視を停止
        if (this::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        }
        super.onStop()
    }

    private fun setRecyclerBottomPaddingDp(dp: Int) {
        val density = resources.displayMetrics.density
        val px = (dp * density).toInt()
        val rv = binding.detailRecyclerView
        rv.setPadding(rv.paddingLeft, rv.paddingTop, rv.paddingRight, px)
    }

    private fun updateRecyclerBottomPadding() {
        val rv = binding.detailRecyclerView
        val bottom = binding.bottomContainer.height
        if (rv.paddingBottom != bottom) {
            rv.setPadding(rv.paddingLeft, rv.paddingTop, rv.paddingRight, bottom)
        }
    }

    // -------------------------
    // RecyclerView 設定
    // -------------------------
    private fun setupRecyclerView() {
        detailAdapter = DetailAdapter()
        layoutManager = LinearLayoutManager(this@DetailActivity)

        // 「そうだね」状態
        detailAdapter.getSodaNeState = { resNum -> viewModel.getSodaNeState(resNum) }

        // 画像読み込み完了時にスクロール位置と検索位置を再補正する
        detailAdapter.onImageLoaded = {
            applyPendingScroll()
            detailSearchManager.realignToCurrentHitIfActive()
        }

        // ユーザーが手動でスクロールを開始したら、保留中の自動スクロールをキャンセルする
        binding.detailRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                // ユーザーが指でドラッグし始めたら、自動復元を停止
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    pendingScrollPosition = null
                }
            }
        })

        // 引用（> / >> / >>> ...）タップ → ポップアップ表示
        detailAdapter.onQuoteClickListener = { _ ->
            // レガシー経路は非対応（Composeへ移行済み）
        }

        // ID タップ → メニュー（同一ID表示 / NG追加）
        detailAdapter.onIdClickListener = { id ->
            val items = arrayOf("同一IDの投稿を表示", "このIDをNGに追加")
            AlertDialog.Builder(this)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> {
                            // レガシー経路は非対応（Composeへ移行済み）
                        }
                        1 -> {
                            val source = currentUrl?.let { UrlNormalizer.threadKey(it) }
                            ngStore.addRule(RuleType.ID, id, MatchType.EXACT, sourceKey = source, ephemeral = true)
                            viewModel.reapplyNgFilter()
                            Toast.makeText(this, "追加しました", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }

        // 「そうだね」: 楽観的にUIを+1してから送信
        detailAdapter.onSodaNeClickListener = { resNum ->
            detailAdapter.bumpSodaneOptimistic(resNum)
            viewModel.postSodaNe(resNum)
        }

        // スレ終端 → リロード
        detailAdapter.onThreadEndTimeClickListener = {
            binding.swipeRefreshLayout.isRefreshing = true
            reloadDetails()
        }

        // ★ 変更点 1: No.xxxタップ時の処理を共通メソッド呼び出しに変更
        // レス番号(No.xxx)タップ → メニューの「返信」 → 返信画面へ
        detailAdapter.onResNumClickListener = { resNum, resBody ->
            if (resBody.isEmpty()) {
                // 削除の場合
                confirmAndDelete(resNum)
            } else {
                // 返信の場合
                launchReplyActivity(resBody)
            }
        }

        // ★ 変更点 2: 本文タップ時の処理を新規追加
        detailAdapter.onBodyClickListener = { quotedBody ->
            launchReplyActivity(quotedBody)
        }

        // 本文長押し → NG追加
        detailAdapter.onAddNgFromBodyListener = { bodyText ->
            val pat = bodyText.trim()
            if (pat.isNotEmpty()) {
                val source = currentUrl?.let { UrlNormalizer.threadKey(it) }
                ngStore.addRule(RuleType.BODY, pat, MatchType.SUBSTRING, sourceKey = source, ephemeral = true)
                viewModel.reapplyNgFilter()
                Toast.makeText(this, "追加しました", Toast.LENGTH_SHORT).show()
            }
        }

        // setupRecyclerView() 内に追記
        detailAdapter.onResNumConfirmClickListener = { resNum ->
            showResReferencesPopup(resNum)
        }
        // del（管理削除）
        detailAdapter.onResNumDelClickListener = { resNum ->
            // 確認ダイアログ（誤タップ防止）。不要なら直接呼んでもOK。
            AlertDialog.Builder(this)
                .setTitle("del(通報) 実行")
                .setMessage("No.$resNum を通報しますか？")
                .setPositiveButton("実行") { _, _ ->
                    viewModel.deleteViaDelPhp(resNum)
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
        // レス番号(No.xxx)タップ → 返信画面へ（引用文付き）
        //detailAdapter.onResNumClickListener = { _, resBody ->
        //    currentUrl?.let { url ->
        //        val threadId = url.substringAfterLast("/").substringBefore(".htm")
        //        val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/"
        //        val boardPostUrl = boardBasePath + "futaba.php"
        //        val intent = Intent(this, ReplyActivity::class.java).apply {
        //            putExtra(ReplyActivity.EXTRA_THREAD_ID, threadId)
        //            putExtra(ReplyActivity.EXTRA_THREAD_TITLE, binding.toolbarTitle.text.toString())
        //            putExtra(ReplyActivity.EXTRA_BOARD_URL, boardPostUrl)
        //            putExtra(ReplyActivity.EXTRA_QUOTE_TEXT, resBody)
        //        }
        //        replyActivityResultLauncher.launch(intent)
        //    }
        //}

        binding.detailRecyclerView.apply {
            adapter = detailAdapter
            layoutManager = this@DetailActivity.layoutManager
            // 動的に高さが変わるアイテムがあるため固定サイズは無効化
            setHasFixedSize(false)
            itemAnimator = null
            setItemViewCacheSize(150)

            // 「塊の末尾だけ」線を引くデコレーション
            // 画面端ピッタリで良ければ paddingStartDp/paddingEndDp は 0 のままでOK。
            // 例えば少し内側に寄せたいなら 8dp などに調整してください。
            addItemDecoration(BlockDividerDecoration(detailAdapter, context, paddingStartDp = 0, paddingEndDp = 0))

        }

        // ★ 追加：無限スクロール（底から1件手前で発火）
        binding.detailRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    markViewedByCurrentScroll()
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                if (isRequestingMore) return
                if (isFastScrolling) return

                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible == RecyclerView.NO_POSITION) return

                val lastContentIndex = findLastContentAdapterIndex()
                if (lastContentIndex < 0) return

                val threshold = 1
                // ★ ThreadEndTime は判定から除外：可視最終位置が実質末尾 - threshold 以上なら発火
                if (lastVisible >= lastContentIndex - threshold) {
                    val url = currentUrl ?: return

                    isRequestingMore = true
                    suppressNextRestore = true

                    // ★ レス数だけをサーバへ渡す
                    val postCount = countPostItems()
                    viewModel.checkForUpdates(url, postCount) { hasNew ->
                        // 新着有無に関わらず解除
                        isRequestingMore = false
                    }
                }
            }
        })

        // ★ FastScroll 初期化（ドラッグ状態コールバックを渡す）
        FastScrollHelper(
            recyclerView = binding.detailRecyclerView,
            fastScrollTrack = binding.fastScrollTrack,
            fastScrollThumb = binding.fastScrollThumb,
            layoutManager = layoutManager
        ) { dragging ->
            isFastScrolling = dragging
            // プル更新の誤発火を防ぐ
            binding.swipeRefreshLayout.isEnabled = !dragging
        }
    }

    

    // -------------------------
    // LiveData監視
    // -------------------------
    private fun observeViewModel() {
        viewModel.detailContent.observe(this, Observer { list ->
            binding.swipeRefreshLayout.isRefreshing = false

            // ★ ここで ThreadEndTime を最後の1件に絞る
            val normalized = normalizeThreadEndTime(list)

            // 履歴の未読数更新用に最新投稿番号（Text件数）を反映
            runCatching {
                val latestReplyNo = normalized.count { it is DetailContent.Text }
                val threadUrl = currentUrl
                if (latestReplyNo > 0 && !threadUrl.isNullOrBlank()) {
                    HistoryManager.applyFetchResult(this, threadUrl, latestReplyNo)
                }
            }

            // 履歴のサムネイル更新（最初のメディアを採用）
            runCatching {
                val media = normalized.firstOrNull {
                    it is DetailContent.Image || it is DetailContent.Video
                }
                val url = when (media) {
                    is DetailContent.Image -> media.imageUrl
                    is DetailContent.Video -> media.videoUrl
                    else -> null
                }
                val threadUrl = currentUrl
                if (!url.isNullOrBlank() && !threadUrl.isNullOrBlank()) {
                    HistoryManager.updateThumbnail(this, threadUrl, url)
                }
            }

            // ↓↓↓ レガシーRecyclerView使用時のみ Adapter に反映 ↓↓↓
            if (binding.detailRecyclerView.isVisible) {
                detailAdapter.submitList(normalized) {
                    // リストの更新が完了したタイミングで、保留中のスクロールを適用する
                    applyPendingScroll()
                    // リスト更新後に検索ヒットも再吸着
                    detailSearchManager.realignToCurrentHitIfActive()
                }
            }

            // プレーンテキストキャッシュをバックグラウンドで構築
            buildPlainCacheJob?.cancel()
            buildPlainCacheJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val cache = normalized.asSequence()
                    .filterIsInstance<DetailContent.Text>()
                    .associate { t ->
                        val plain = android.text.Html.fromHtml(t.htmlContent, android.text.Html.FROM_HTML_MODE_COMPACT)
                            .toString()
                        t.id to plain
                    }
                withContext(kotlinx.coroutines.Dispatchers.Main) { plainTextCache = cache }
            }

            // 検索ナビの表示切替は DetailSearchManager.performSearch/clearSearch に委譲

            // suppressNextRestoreフラグのリセットのみ残す
            if (suppressNextRestore) {
                suppressNextRestore = false
            }
        })

        viewModel.error.observe(this, Observer { err ->
            binding.swipeRefreshLayout.isRefreshing = false
            err?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        })

        viewModel.isLoading.observe(this, Observer { isLoading ->
            if (!binding.swipeRefreshLayout.isRefreshing) {
                binding.progressBar.isVisible = isLoading
            }
        })

        // ★ 追加: 「そうだね」更新を購読して Adapter に反映（レガシーRecyclerView使用時のみ）
        if (binding.detailRecyclerView.isVisible) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.sodaneUpdate.collect { (resNum, count) ->
                        detailAdapter.updateSodane(resNum, count)
                    }
                }
            }
        }

    }

    private fun reloadDetails() {
        currentUrl?.let { url ->
            suppressNextRestore = false
            // 現在の位置を保存し、その場で復元対象としてキューに積む
            saveScroll()
            restoreScroll()
            detailSearchManager.clearSearch()
            isInitialLoad = true // ★リロード時は再度復元を許可する
            viewModel.fetchDetails(url, forceRefresh = true)
        }
    }

    override fun onPause() {
        super.onPause()
        // AdMob: 一時停止
        if (::binding.isInitialized && binding.detailRecyclerView.isVisible) {
            binding.adView.pause()
        }
        // スクロール保存はCompose側で行う
    }

    override fun onResume() {
        super.onResume()
        // AdMob: 再開
        if (::binding.isInitialized && binding.detailRecyclerView.isVisible) {
            binding.adView.resume()
        }
    }

    override fun onDestroy() {
        // AdMob: 解放
        if (::binding.isInitialized && binding.detailRecyclerView.isVisible) {
            binding.adView.destroy()
        }
        super.onDestroy()
    }

    private fun saveScroll() {
        // When using Compose list, RecyclerView is hidden; skip legacy save
        if (::binding.isInitialized && !binding.detailRecyclerView.isVisible) return
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION) {
            val v = layoutManager.findViewByPosition(first)
            val off = v?.top ?: 0
            currentUrl?.let { url ->
                val key = UrlNormalizer.threadKey(url)  // ★ ここで正規化
                scrollStore.saveScrollState(key, first, off)
            }
        }
    }

    private fun restoreScroll() {
        if (!::scrollStore.isInitialized) {
            return
        }
        currentUrl?.let { url ->
            val key = UrlNormalizer.threadKey(url)     // ★ ここで正規化
            val (pos, off) = scrollStore.getScrollState(key)
            // 既存保存がない (0,0) は保留しない（後の画像読み込みで“先頭へ戻る”の再適用を防止）
            if (pos > 0 || off > 0) {
                pendingScrollPosition = pos to off
            } else {
                pendingScrollPosition = null
            }
            //applyPendingScroll()
        }
    }

    // ↓↓↓★ このメソッドをまるごと追加 ★↓↓↓
    private fun applyPendingScroll() {
        // 保留中のスクロール位置がある場合のみ実行
        pendingScrollPosition?.let { (pos, off) ->
            // (0,0) は意図的な指定とみなさずスキップ
            if (pos == 0 && off == 0) {
                pendingScrollPosition = null
                return
            }
            // アダプターにアイテムがあり、位置が有効な範囲内か確認
            if (detailAdapter.itemCount > 0 && pos < detailAdapter.itemCount) {
                // RecyclerViewのレイアウトが完了した後にスクロールを実行
                binding.detailRecyclerView.post {
                    layoutManager.scrollToPositionWithOffset(pos, off)
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Top bar is now Compose. Keep menu unused for now.
        return false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
        R.id.action_reply -> {
            launchReplyActivity("")
            true
        }
        R.id.action_reload -> { reloadDetails(); true }
        R.id.action_ng_manage -> { openNgManager(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ===== SearchManagerCallback 実装 =====
    override fun getDetailContent(): List<DetailContent>? = viewModel.detailContent.value
    override fun showToast(message: String, duration: Int) { Toast.makeText(this, message, duration).show() }
    override fun getStringResource(resId: Int): String = getString(resId)
    override fun getStringResource(resId: Int, vararg formatArgs: Any): String = getString(resId, *formatArgs)
    override fun onSearchCleared() {
        // Compose 検索ナビに移行済みのため特にUI更新不要
    }
    override fun isBindingInitialized(): Boolean = ::binding.isInitialized

    // ★ 変更点 4: 返信画面を起動する共通メソッド
    private fun launchReplyActivity(quote: String) {
        currentUrl?.let { url ->
            val threadId = url.substringAfterLast("/").substringBefore(".htm")
            val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/"
            val boardPostUrl = boardBasePath + "futaba.php"
            val intent = Intent(this, ReplyActivity::class.java).apply {
                putExtra(ReplyActivity.EXTRA_THREAD_ID, threadId)
                putExtra(ReplyActivity.EXTRA_THREAD_TITLE, toolbarTitleText)
                putExtra(ReplyActivity.EXTRA_BOARD_URL, boardPostUrl)
                putExtra(ReplyActivity.EXTRA_QUOTE_TEXT, quote)
            }
            replyActivityResultLauncher.launch(intent)
        }
    }

    private fun openNgManager() {
        ngManagerLauncher.launch(
            Intent(this, NgManagerActivity::class.java).apply {
                // DetailActivityからの起動時はスレタイNGは不要
                putExtra(NgManagerActivity.EXTRA_HIDE_TITLE, true)
            }
        )
    }

    // showAddNgDialog はComposeに移行済みのため削除しました

    // =========================================================
    // ここから：ポップアップ表示（引用 / ID）と検索ヘルパー
    // =========================================================

    // 引用ポップアップ：> / >> / >>> など多段対応（複数候補にも対応）
    // showQuotePopup/showIdPostsPopup はComposeへ移行済み

    // BottomSheet に DetailAdapter で並べる（遷移は無効化）
    // 旧Viewベースのシート表示やメディア一覧はComposeへ移行済み

    // 現在のスクロール位置から「見えた最大の投稿序数」を算出して既読更新
    private fun markViewedByCurrentScroll() {
        // デバウンス（300ms）してバックグラウンドで実行
        markViewedRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            markViewedJob?.cancel()
            markViewedJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val url = currentUrl ?: return@launch
                val list = viewModel.detailContent.value ?: return@launch
                if (list.isEmpty()) return@launch
                val maxOrdinal = computeMaxVisiblePostOrdinal(list)
                if (maxOrdinal <= 0) return@launch
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val current = HistoryManager.getAll(this@DetailActivity).firstOrNull { it.url == url }
                    val curViewed = current?.lastViewedReplyNo ?: 0
                    if (maxOrdinal > curViewed) {
                        HistoryManager.markViewed(this@DetailActivity, url, maxOrdinal)
                    }
                }
            }
        }
        markViewedRunnable = r
        mainHandler.postDelayed(r, 300L)
    }

    // Compose リスト用：可視最大序数が通知されたら既読を更新（デバウンスあり）
    private fun markViewedByOrdinal(maxOrdinal: Int) {
        if (maxOrdinal <= 0) return
        markViewedRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            markViewedJob?.cancel()
            markViewedJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val url = currentUrl ?: return@launch
                val current = HistoryManager.getAll(this@DetailActivity).firstOrNull { it.url == url }
                val curViewed = current?.lastViewedReplyNo ?: 0
                if (maxOrdinal > curViewed) {
                    HistoryManager.markViewed(this@DetailActivity, url, maxOrdinal)
                }
            }
        }
        markViewedRunnable = r
        mainHandler.postDelayed(r, 300L)
    }

    // 画面に50%以上見えているアイテムから、その所属する投稿（Text単位）の序数を計算し、その最大値を返す
    private fun computeMaxVisiblePostOrdinal(items: List<DetailContent>): Int {
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return 0
        var maxOrdinal = 0
        for (pos in first..last) {
            val v = layoutManager.findViewByPosition(pos) ?: continue
            val visible = android.graphics.Rect()
            val isVisible = v.getLocalVisibleRect(visible)
            if (!isVisible) continue
            val ratio = visible.height().toFloat() / (v.height.takeIf { it > 0 } ?: 1)
            if (ratio < 0.5f) continue
            val ordinal = postOrdinalForAdapterPosition(items, pos)
            if (ordinal > maxOrdinal) maxOrdinal = ordinal
        }
        // 一番下まで到達している場合の補正（見切れている末尾を既読にしやすく）
        val contentLastIndex = findLastContentAdapterIndex()
        if (last >= contentLastIndex) {
            val lastOrdinal = postOrdinalForAdapterPosition(items, contentLastIndex)
            if (lastOrdinal > maxOrdinal) maxOrdinal = lastOrdinal
        }
        return maxOrdinal
    }

    // 与えられたアダプタ位置が属する投稿（Text単位）の序数（1始まり）を返す
    private fun postOrdinalForAdapterPosition(items: List<DetailContent>, pos: Int): Int {
        if (pos < 0 || pos >= items.size) return 0
        var ordinal = 0
        var i = 0
        while (i <= pos && i < items.size) {
            if (items[i] is DetailContent.Text) ordinal++
            i++
        }
        // もし pos が Text 以外（画像/動画）の場合でも、直前の Text にぶら下げた投稿としてカウントされる
        return ordinal
    }

    // 「No.xxx」「ファイル名」「本文一部」いずれかで対象を検索
    private fun findContentByText(all: List<DetailContent>, searchText: String): DetailContent? {
        // 1) No.\d+
        Regex("""No\.(\d+)""").find(searchText)?.groupValues?.getOrNull(1)?.let { num ->
            val hit = all.firstOrNull {
                it is DetailContent.Text && (plainTextCache[it.id]?.contains("No.$num") == true)
            }
            if (hit != null) return hit
        }

        // 2) 画像/動画 ファイル名末尾一致
        for (c in all) {
            when (c) {
                is DetailContent.Image -> if (c.fileName == searchText || c.imageUrl.endsWith(searchText)) return c
                is DetailContent.Video -> if (c.fileName == searchText || c.videoUrl.endsWith(searchText)) return c
                else -> {}
            }
        }

        // 3) 本文 部分一致（空白圧縮・大文字小文字無視）
        val needle = searchText.trim().replace(Regex("\\s+"), " ")
        return all.firstOrNull {
            it is DetailContent.Text && (plainTextCache[it.id]
                ?.trim()
                ?.replace(Regex("\\s+"), " ")
                ?.contains(needle, ignoreCase = true) == true)
        }
    }

    // 行頭が '>' 1個の引用行（最初の1つ）を返す（旧：単一版）
    private fun extractFirstLevelQuoteCore(item: DetailContent.Text): String? {
        val plain = Html.fromHtml(item.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
        val m = Regex("^>([^>].+)$", RegexOption.MULTILINE).find(plain)
        return m?.groupValues?.getOrNull(1)?.trim()
    }

    // 行頭が '>' 1個の引用行を「複数」返す（多段で複数候補がある場合に使用）
    private fun extractFirstLevelQuoteCores(item: DetailContent.Text): List<String> {
        val plain = Html.fromHtml(item.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
        return Regex("^>([^>].+)$", RegexOption.MULTILINE)
            .findAll(plain)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun showToastOnUiThread(message: String, duration: Int) {
        runOnUiThread { Toast.makeText(this, message, duration).show() }
    }

    private fun confirmAndDelete(resNum: String) {
        val pwd = AppPreferences.getPwd(this) ?: ""
        val threadUrl = currentUrl ?: return
        val boardBase = threadUrl.substringBeforeLast("/").substringBeforeLast("/") + "/"
        val postUrl = boardBase + "futaba.php?guid=on"

        // 削除方法を選択するダイアログ
        AlertDialog.Builder(this)
            .setTitle("No.$resNum の削除")
            .setMessage("削除方法を選択してください")
            .setPositiveButton("画像のみ削除") { _, _ ->
                viewModel.deletePost(
                    postUrl = postUrl,
                    referer = threadUrl,
                    resNum = resNum,
                    pwd = pwd,
                    onlyImage = true,
                )
            }
            .setNeutralButton("レスごと削除") { _, _ ->
                viewModel.deletePost(
                    postUrl = postUrl,
                    referer = threadUrl,
                    resNum = resNum,
                    pwd = pwd,
                    onlyImage = false,
                )
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun extractResNo(c: DetailContent): Int? = when (c) {
        is DetailContent.Text -> {
            val plain = Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
            Regex("""No\.(\d+)""").find(plain)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        else -> null
    }

    // 末尾の ThreadEndTime を除いた「実質の最終アダプタ位置」を返す
    private fun findLastContentAdapterIndex(): Int {
        val list = viewModel.detailContent.value ?: return -1
        // 末尾から走査して Text/Image/Video の最終位置を返す
        for (i in list.size - 1 downTo 0) {
            when (list[i]) {
                is DetailContent.Text, is DetailContent.Image, is DetailContent.Video -> return i
                else -> {}
            }
        }
        return -1
    }

    // 末尾の ThreadEndTime を1件だけ残す
    private fun normalizeThreadEndTime(src: List<DetailContent>): List<DetailContent> {
        val endIndexes = src.withIndex()
            .filter { it.value is DetailContent.ThreadEndTime }
            .map { it.index }

        if (endIndexes.isEmpty()) return src
        val keepIndex = endIndexes.last()

        val out = ArrayList<DetailContent>(src.size - (endIndexes.size - 1))
        for ((i, item) in src.withIndex()) {
            if (item is DetailContent.ThreadEndTime) {
                if (i == keepIndex) out += item
            } else {
                out += item
            }
        }
        return out
    }

    // レス数（Text/Image/Video の件数）を返す
    private fun countPostItems(): Int {
        val list = viewModel.detailContent.value ?: return 0
        return list.count { it is DetailContent.Text || it is DetailContent.Image || it is DetailContent.Video }
    }

    // 追加：No の参照（引用）を一覧表示
    private fun showResReferencesPopup(resNum: String) {
        val all = viewModel.detailContent.value ?: return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val hitIndexes = all.withIndex().filter { (_, c) ->
                c is DetailContent.Text && matchesResRefCached(c, resNum)
            }.map { it.index }

            if (hitIndexes.isEmpty()) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@DetailActivity, "No.$resNum の引用は見つかりませんでした", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val groups = mutableListOf<List<DetailContent>>()
            for (i in hitIndexes) {
                val group = mutableListOf<DetailContent>()
                group += all[i]
                var j = i + 1
                while (j < all.size) {
                    when (val c = all[j]) {
                        is DetailContent.Image, is DetailContent.Video -> { group += c; j++ }
                        is DetailContent.Text, is DetailContent.ThreadEndTime -> break
                    }
                }
                groups += group
            }

            val distinctGroups = groups.distinctBy { it.firstOrNull()?.id }

            // レス番号順に整序
            val ordered = distinctGroups
                .sortedWith(compareBy<List<DetailContent>> { grp ->
                    val head = grp.firstOrNull()
                    when (head) {
                        null -> Int.MAX_VALUE
                        else -> extractResNo(head) ?: Int.MAX_VALUE
                    }
                })
                .flatten()

            // Compose側の参照一覧シートに統合済み（ここではUI遷移は行わない）
            withContext(kotlinx.coroutines.Dispatchers.Main) { }
        }
    }

    // 追加: 共通のマッチ関数
    private fun matchesResRefCached(text: DetailContent.Text, resNum: String): Boolean {
        val plain = plainTextCache[text.id] ?: Html.fromHtml(text.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()

        // 見た目の差異を吸収（ZWSP, 全角, 記号類）
        val norm = Normalizer.normalize(
            plain
                .replace("\u200B", "")  // ZWSP除去
                .replace('　', ' ')      // 全角空白→半角
                .replace('＞', '>')      // 全角> → >
                .replace('≫', '>')      // ≫    → >
            , Normalizer.Form.NFKC
        )

        val esc = Regex.escape(resNum)

        val textPatterns = listOf(
            // 1) No. の直接表記: "No.1234", "No 1234", "no.1234"
            Regex("""\bNo\.?\s*$esc\b""", RegexOption.IGNORE_CASE),

            // 2) 引用表記（行頭に '>' が1つ以上）
            //    ">No.1234", ">>1234", ">>> No.1234" など
            Regex("""^>+\s*(?:No\.?\s*)?$esc\b""",
                setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),

            // 3) 行頭以外でも ">>1234" / ">> No.1234" を拾う（安全側）
            Regex("""\B>+\s*(?:No\.?\s*)?$esc\b""", RegexOption.IGNORE_CASE),

            // 4) 裸の数字（前後が数字じゃない）—必要な場合のみ残す
            Regex("""(?<!\d)$esc(?!\d)""")
        )
        if (textPatterns.any { it.containsMatchIn(norm) }) return true

        // HTML実体化や属性での参照（保険）
        val htmlPatterns = listOf(
            Regex("""data-res\s*=\s*["']\s*$esc\s*["']""", RegexOption.IGNORE_CASE),
            Regex("""&gt;+\s*(?:No\.?\s*)?$esc\b""", RegexOption.IGNORE_CASE) // &gt;&gt;No.1234
        )
        return htmlPatterns.any { it.containsMatchIn(text.htmlContent) }
    }

}
