package com.valoser.futaburakari

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Html
import android.view.Menu
import android.view.MenuItem
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import java.text.Normalizer
import androidx.preference.PreferenceManager
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.LoadAdError

import com.valoser.futaburakari.worker.ThreadMonitorWorker
import dagger.hilt.android.AndroidEntryPoint

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

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private var suppressNextRestore: Boolean = false

    private lateinit var prefs: SharedPreferences
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "pref_key_ads_enabled") {
            setupAdBanner()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ★ ここですぐに初期化
        scrollStore = ScrollPositionStore(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        // Hilt により viewModel は注入済み（by viewModels()）
        // SharedPreferences 準備（設定変更のリッスンに使用）
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        currentUrl = intent.getStringExtra(EXTRA_URL)
        binding.toolbarTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: ""

        // 履歴に記録（タイトルがない場合はURL末尾などで代替も可）
        currentUrl?.let { url ->
            val title = binding.toolbarTitle.text?.toString().orEmpty().ifBlank { url }
            HistoryManager.addOrUpdate(this, url, title)
        }

        setupRecyclerView()

        // DetailSearchManager は (binding, callback) で生成
        detailSearchManager = DetailSearchManager(binding, this)
        detailSearchManager.setupSearchNavigation()

        // bottom_container（検索ナビ + 広告）の高さ変化に追従してRecyclerViewの下パディングを更新
        binding.bottomContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateRecyclerBottomPadding()
            // 検索UIや広告の高さが変わったときに、ヒット項目が隠れないよう再吸着
            detailSearchManager.realignToCurrentHitIfActive()
        }

        observeViewModel()
        currentUrl?.let { url ->
            val key = UrlNormalizer.threadKey(url)
            val isArchived = runCatching { HistoryManager.getAll(this) }
                .getOrDefault(emptyList())
                .any { it.key == key && it.isArchived }

            // アーカイブ済みはキャッシュのみ、未アーカイブはネットワークで最新取得
            viewModel.fetchDetails(url, forceRefresh = !isArchived)

            // 監視は未アーカイブのみスケジュール
            if (!isArchived) {
                com.valoser.futaburakari.worker.ThreadMonitorWorker.schedule(this, url)
            }
        }

        // 端末戻る：検索展開中は閉じる
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (detailSearchManager.handleOnBackPressed()) return
                // ★ ここでスクロール位置を保存
                saveScroll()

                // デフォルトの戻るへ委譲
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        binding.swipeRefreshLayout.setOnRefreshListener { reloadDetails() }
        binding.backButton.setOnClickListener {
            saveScroll()
            onBackPressedDispatcher.onBackPressed()
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
        setupAdBanner()
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
        detailAdapter.onQuoteClickListener = { token ->
            // 先頭の '>' 個数を引用レベルに
            val levelRaw = token.takeWhile { it == '>' }.length
            val quoteLevel = if (levelRaw <= 0) 1 else levelRaw
            val core = token.drop(levelRaw).trim()
            QuotePopupFragment.showForQuote(supportFragmentManager, core, quoteLevel)
        }

        // ID タップ → 同一IDの投稿をポップアップ表示
        detailAdapter.onIdClickListener = { id ->
            QuotePopupFragment.showForId(supportFragmentManager, id)
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

        // setupRecyclerView() 内に追記
        detailAdapter.onResNumConfirmClickListener = { resNum ->
            showResReferencesPopup(resNum)
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

            // ↓↓↓★ submitListに完了コールバックを追加 ★↓↓↓
            detailAdapter.submitList(normalized) {
                // リストの更新が完了したタイミングで、保留中のスクロールを適用する
                applyPendingScroll()
                // リスト更新後に検索ヒットも再吸着
                detailSearchManager.realignToCurrentHitIfActive()
            }

            // （必要なら）検索ナビの表示切替
            binding.searchNavigationControls.isVisible = detailSearchManager.isSearchActive()

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

        // ★ 追加: 「そうだね」更新を購読して Adapter に反映
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sodaneUpdate.collect { (resNum, count) ->
                    detailAdapter.updateSodane(resNum, count)
                }
            }
        }

    }

    private fun reloadDetails() {
        currentUrl?.let { url ->
            suppressNextRestore = false
            saveScroll()
            detailSearchManager.clearSearch()
            isInitialLoad = true // ★リロード時は再度復元を許可する
            viewModel.fetchDetails(url, forceRefresh = true)
        }
    }

    override fun onPause() {
        super.onPause()
        // AdMob: 一時停止
        if (::binding.isInitialized) {
            binding.adView.pause()
        }
        // 最終的な既読反映（現在の可視範囲から）
        runCatching { markViewedByCurrentScroll() }
        saveScroll()
    }

    override fun onResume() {
        super.onResume()
        // AdMob: 再開
        if (::binding.isInitialized) {
            binding.adView.resume()
        }
        // サーバ側のそうだね最新値だけを軽量同期
        currentUrl?.let { viewModel.refreshSodaneCountsOnly(it) }
    }

    override fun onDestroy() {
        // AdMob: 解放
        if (::binding.isInitialized) {
            binding.adView.destroy()
        }
        super.onDestroy()
    }

    private fun saveScroll() {
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
            pendingScrollPosition = pos to off
            //applyPendingScroll()
        }
    }

    // ↓↓↓★ このメソッドをまるごと追加 ★↓↓↓
    private fun applyPendingScroll() {
        // 保留中のスクロール位置がある場合のみ実行
        pendingScrollPosition?.let { (pos, off) ->
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
        menuInflater.inflate(R.menu.detail_menu, menu)
        detailSearchManager.setupSearch(menu) // 検索メニューの初期化
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
        R.id.action_reply -> {
            currentUrl?.let { url ->
                val threadId = url.substringAfterLast("/").substringBefore(".htm")
                val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/"
                val boardPostUrl = boardBasePath + "futaba.php"
                val intent = Intent(this, ReplyActivity::class.java).apply {
                    putExtra(ReplyActivity.EXTRA_THREAD_ID, threadId)
                    putExtra(ReplyActivity.EXTRA_THREAD_TITLE, binding.toolbarTitle.text.toString())
                    putExtra(ReplyActivity.EXTRA_BOARD_URL, boardPostUrl)
                    putExtra(ReplyActivity.EXTRA_QUOTE_TEXT, "")
                }
                //replyActivityResultLauncher.launch(intent)
            }
            launchReplyActivity("")
            true
        }
        R.id.action_reload -> { binding.swipeRefreshLayout.isRefreshing = true; reloadDetails(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ===== SearchManagerCallback 実装 =====
    override fun getDetailContent(): List<DetailContent>? = viewModel.detailContent.value
    override fun getDetailAdapter(): DetailAdapter = detailAdapter
    override fun getLayoutManager(): LinearLayoutManager = layoutManager
    override fun showToast(message: String, duration: Int) { Toast.makeText(this, message, duration).show() }
    override fun getStringResource(resId: Int): String = getString(resId)
    override fun getStringResource(resId: Int, vararg formatArgs: Any): String = getString(resId, *formatArgs)
    override fun onSearchCleared() {
        // 検索クリア時のUIを必要に応じて更新
        binding.searchNavigationControls.isVisible = false
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
                putExtra(ReplyActivity.EXTRA_THREAD_TITLE, binding.toolbarTitle.text.toString())
                putExtra(ReplyActivity.EXTRA_BOARD_URL, boardPostUrl)
                putExtra(ReplyActivity.EXTRA_QUOTE_TEXT, quote)
            }
            replyActivityResultLauncher.launch(intent)
        }
    }

    // =========================================================
    // ここから：ポップアップ表示（引用 / ID）と検索ヘルパー
    // =========================================================

    // 引用ポップアップ：> / >> / >>> など多段対応（複数候補にも対応）
    private fun showQuotePopup(quotedText: String, quoteLevel: Int) {
        val all = viewModel.detailContent.value ?: return

        // 1) 本文に needle を含む「全Textインデックス」を集める
        val needle = quotedText.trim().replace(Regex("\\s+"), " ")
        val textIndexes = all.withIndex().filter { (_, c) ->
            c is DetailContent.Text &&
                    Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT)
                        .toString()
                        .replace(Regex("\\s+"), " ")
                        .contains(needle, ignoreCase = true)
        }.map { it.index }

        if (textIndexes.isEmpty()) {
            showToastOnUiThread("引用先が見つかりませんでした", Toast.LENGTH_SHORT)
            return
        }

        // 2) 直後の画像/動画も同梱して収集
        val result = mutableListOf<DetailContent>()
        for (i in textIndexes) {
            result += all[i]
            var j = i + 1
            while (j < all.size) {
                when (val c = all[j]) {
                    is DetailContent.Image, is DetailContent.Video -> { result += c; j++ }
                    is DetailContent.Text, is DetailContent.ThreadEndTime -> break
                }
            }
        }

        val ordered = result
            .distinctBy { it.id }
            .sortedWith(compareBy<DetailContent> { extractResNo(it) ?: Int.MAX_VALUE })
        showContentListBottomSheet(ordered)
    }

    // IDポップアップ：同一IDの投稿一覧（テキスト＋直後の画像/動画も同梱）
    private fun showIdPostsPopup(id: String) {
        val all = viewModel.detailContent.value ?: return
        val key = "ID:$id"

        // IDを含むテキストのインデックス
        val textIndexes = all.withIndex().filter { (_, c) ->
            c is DetailContent.Text &&
                    Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString().contains(key)
        }.map { it.index }

        if (textIndexes.isEmpty()) {
            showToastOnUiThread("同じIDの投稿が見つかりませんでした", Toast.LENGTH_SHORT)
            return
        }

        val result = mutableListOf<DetailContent>()
        for (i in textIndexes) {
            result += all[i] // テキスト本体

            // 直後のメディア（次の Text/ThreadEndTime まで）を同梱
            var j = i + 1
            while (j < all.size) {
                when (val c = all[j]) {
                    is DetailContent.Image,
                    is DetailContent.Video -> { result += c; j++ }
                    is DetailContent.Text,
                    is DetailContent.ThreadEndTime -> break
                }
            }
        }

        val ordered = result
            .distinctBy { it.id }
            .sortedWith(compareBy<DetailContent> { extractResNo(it) ?: Int.MAX_VALUE })
        showContentListBottomSheet(ordered)
    }

    // BottomSheet に DetailAdapter で並べる（遷移は無効化）
    private fun showContentListBottomSheet(items: List<DetailContent>) {
        val dialog = BottomSheetDialog(this)
        val popupAdapter = DetailAdapter().apply {
            onQuoteClickListener = null
            onIdClickListener = null
            onSodaNeClickListener = null
            onResNumClickListener = null
            onThreadEndTimeClickListener = null
            getSodaNeState = { false }
            submitList(items)
        }

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DetailActivity)
            adapter = popupAdapter
            // Draw dividers only at end of a block (same as other popups)
            addItemDecoration(
                BlockDividerDecoration(popupAdapter, context, paddingStartDp = 0, paddingEndDp = 0)
            )
        }
        dialog.setContentView(recycler)
        dialog.show()
    }

    // 現在のスクロール位置から「見えた最大の投稿序数」を算出して既読更新
    private fun markViewedByCurrentScroll() {
        val url = currentUrl ?: return
        val list = viewModel.detailContent.value ?: return
        if (list.isEmpty()) return

        val maxOrdinal = computeMaxVisiblePostOrdinal(list)
        if (maxOrdinal <= 0) return
        // 既読の巻き戻しはしないため、履歴の現状値と比較して進んだときだけ保存
        val current = HistoryManager.getAll(this).firstOrNull { it.url == url }
        val curViewed = current?.lastViewedReplyNo ?: 0
        if (maxOrdinal > curViewed) {
            HistoryManager.markViewed(this, url, maxOrdinal)
        }
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
                it is DetailContent.Text &&
                        Html.fromHtml(it.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString().contains("No.$num")
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
            it is DetailContent.Text &&
                    Html.fromHtml(it.htmlContent, Html.FROM_HTML_MODE_COMPACT)
                        .toString()
                        .trim()
                        .replace(Regex("\\s+"), " ")
                        .contains(needle, ignoreCase = true)
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

        // 引用判定用のパターン：
        // 新: 本文どこでもヒットOK（誤検知を抑えるための境界条件つき）
        val patterns = listOf(
            // 「No. 1234」, 「No.1234」など
            Regex("""\bNo\.?\s*$resNum\b""", RegexOption.IGNORE_CASE),
            // 「>>1234」
            Regex("""\B>>\s*$resNum\b""", RegexOption.IGNORE_CASE),
            // 予防的に“裸の数字”でも一致させる（前後が数字でないことを保証）
            // 例: 1234 が 12345 に誤マッチしない
            Regex("""(?<!\d)$resNum(?!\d)""")
        )

        // 「確認」ポップアップ用の検索ロジック（既存のヒット抽出箇所）を置き換え
        val hitIndexes = all.withIndex().filter { (_, c) ->
            c is DetailContent.Text && matchesResRef(c.htmlContent, resNum)
        }.map { it.index }

        if (hitIndexes.isEmpty()) {
            Toast.makeText(this, "No.$resNum の引用は見つかりませんでした", Toast.LENGTH_SHORT).show()
            return
        }

        // Text 本体＋直後の Image/Video を同梱
        val result = mutableListOf<DetailContent>()
        for (i in hitIndexes) {
            result += all[i]
            var j = i + 1
            while (j < all.size) {
                when (val c = all[j]) {
                    is DetailContent.Image, is DetailContent.Video -> { result += c; j++ }
                    is DetailContent.Text, is DetailContent.ThreadEndTime -> break
                }
            }
        }

        // レス番号順に整序（見やすさ向上）
        val ordered = result
            .distinctBy { it.id }
            .sortedWith(compareBy<DetailContent> { extractResNo(it) ?: Int.MAX_VALUE })

        showContentListBottomSheet(ordered)
    }

    // 追加: 共通のマッチ関数
    private fun matchesResRef(html: String, resNum: String): Boolean {
        val plain = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()

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
        return htmlPatterns.any { it.containsMatchIn(html) }
    }

}
