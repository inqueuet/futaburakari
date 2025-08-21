package com.example.hutaburakari

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hutaburakari.databinding.ActivityDetailBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

class DetailActivity : AppCompatActivity(), SearchManagerCallback {

    private lateinit var binding: ActivityDetailBinding
    private lateinit var viewModel: DetailViewModel
    private lateinit var detailAdapter: DetailAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var detailSearchManager: DetailSearchManager
    private lateinit var scrollStore: ScrollPositionStore

    private var currentUrl: String? = null

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
    }

    private var suppressNextRestore: Boolean = false

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

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        viewModel = ViewModelProvider(this)[DetailViewModel::class.java]
        scrollStore = ScrollPositionStore(this)

        currentUrl = intent.getStringExtra(EXTRA_URL)
        binding.toolbarTitle.text = intent.getStringExtra(EXTRA_TITLE) ?: ""

        setupRecyclerView()

        // DetailSearchManager は (binding, callback) で生成
        detailSearchManager = DetailSearchManager(binding, this)
        detailSearchManager.setupSearchNavigation()

        observeViewModel()
        currentUrl?.let { viewModel.fetchDetails(it) }

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

    // -------------------------
    // RecyclerView 設定
    // -------------------------
    private fun setupRecyclerView() {
        detailAdapter = DetailAdapter()
        layoutManager = LinearLayoutManager(this@DetailActivity)

        // 「そうだね」状態
        detailAdapter.getSodaNeState = { resNum -> viewModel.getSodaNeState(resNum) }

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

        // 「そうだね」
        detailAdapter.onSodaNeClickListener = { resNum ->
            viewModel.postSodaNe(resNum)
        }

        // スレ終端 → リロード
        detailAdapter.onThreadEndTimeClickListener = {
            binding.swipeRefreshLayout.isRefreshing = true
            reloadDetails()
        }

        // レス番号(No.xxx)タップ → 返信画面へ（引用文付き）
        detailAdapter.onResNumClickListener = { _, resBody ->
            currentUrl?.let { url ->
                val threadId = url.substringAfterLast("/").substringBefore(".htm")
                val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/"
                val boardPostUrl = boardBasePath + "futaba.php"
                val intent = Intent(this, ReplyActivity::class.java).apply {
                    putExtra(ReplyActivity.EXTRA_THREAD_ID, threadId)
                    putExtra(ReplyActivity.EXTRA_THREAD_TITLE, binding.toolbarTitle.text.toString())
                    putExtra(ReplyActivity.EXTRA_BOARD_URL, boardPostUrl)
                    putExtra(ReplyActivity.EXTRA_QUOTE_TEXT, resBody)
                }
                replyActivityResultLauncher.launch(intent)
            }
        }

        binding.detailRecyclerView.apply {
            adapter = detailAdapter
            layoutManager = this@DetailActivity.layoutManager
            setHasFixedSize(true)
            itemAnimator = null
            setItemViewCacheSize(100)
            // XMLで clipToPadding=false / paddingEnd / paddingBottom を付与済み
        }

        // ★ ファストスクロール初期化（スクロール“死に”対策 & つまみ操作）
        FastScrollHelper(
            recyclerView = binding.detailRecyclerView,
            fastScrollTrack = binding.fastScrollTrack,
            fastScrollThumb = binding.fastScrollThumb,
            layoutManager = layoutManager
        )
    }

    // -------------------------
    // LiveData監視
    // -------------------------
    private fun observeViewModel() {
        viewModel.detailContent.observe(this, Observer { list ->
            binding.swipeRefreshLayout.isRefreshing = false
            detailAdapter.submitList(list)

            // （必要なら）検索ナビの表示切替
            binding.searchNavigationControls.isVisible = detailSearchManager.isSearchActive()

            // スクロール復元
            if (!suppressNextRestore) {
                restoreScroll()
            } else {
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
    }

    private fun reloadDetails() {
        currentUrl?.let { url ->
            suppressNextRestore = false
            saveScroll()
            detailSearchManager.clearSearch()
            viewModel.fetchDetails(url, forceRefresh = true)
        }
    }

    override fun onPause() {
        super.onPause()
        saveScroll()
    }

    private fun saveScroll() {
        val first = layoutManager.findFirstVisibleItemPosition()
        if (first != RecyclerView.NO_POSITION) {
            val v = layoutManager.findViewByPosition(first)
            val off = v?.top ?: 0
            currentUrl?.let { url -> scrollStore.saveScrollState(url, first, off) }
        }
    }

    private fun restoreScroll() {
        currentUrl?.let { url ->
            val (pos, off) = scrollStore.getScrollState(url)
            binding.detailRecyclerView.post {
                if (pos >= 0) layoutManager.scrollToPositionWithOffset(pos, off)
                else layoutManager.scrollToPosition(0)
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
                replyActivityResultLauncher.launch(intent)
            }
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
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@DetailActivity)
            adapter = DetailAdapter().apply {
                onQuoteClickListener = null
                onIdClickListener = null
                onSodaNeClickListener = null
                onResNumClickListener = null
                onThreadEndTimeClickListener = null
                getSodaNeState = { false }
                submitList(items)
            }
        }
        dialog.setContentView(recycler)
        dialog.show()
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

    private fun extractResNo(c: DetailContent): Int? = when (c) {
        is DetailContent.Text -> {
            val plain = Html.fromHtml(c.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
            Regex("""No\.(\d+)""").find(plain)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        else -> null
    }

}