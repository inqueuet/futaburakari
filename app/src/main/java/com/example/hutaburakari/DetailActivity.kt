package com.example.hutaburakari

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hutaburakari.databinding.ActivityDetailBinding
import java.util.ArrayDeque
import java.util.regex.Pattern

class DetailActivity : AppCompatActivity(), SearchManagerCallback {

    private lateinit var binding: ActivityDetailBinding
    private val viewModel: DetailViewModel by viewModels()
    private lateinit var detailAdapter: DetailAdapter
    private lateinit var scrollPositionStore: ScrollPositionStore
    private var currentUrl: String? = null
    private lateinit var layoutManager: LinearLayoutManager
    private val scrollHistory = ArrayDeque<Pair<Int, Int>>(2)
    private lateinit var detailSearchManager: DetailSearchManager
    private lateinit var fastScrollHelper: FastScrollHelper

    private val replyActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d("DetailActivity", "Reply successful, attempting to refresh.")
            showToastOnUiThread("投稿を反映して再読み込みしました。", Toast.LENGTH_SHORT)
            reloadDetails()
        }
    }

    companion object {
        const val EXTRA_URL = "extra_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        scrollPositionStore = ScrollPositionStore(this)
        detailSearchManager = DetailSearchManager(binding, this)

        val url = intent.getStringExtra(EXTRA_URL)
        val title = intent.getStringExtra("EXTRA_TITLE")

        currentUrl = url
        binding.toolbarTitle.text = title

        if (currentUrl == null) {
            Toast.makeText(this, "Error: URL not provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.swipeRefreshLayout.setOnRefreshListener {
            reloadDetails()
        }

        setupCustomToolbarElements()
        setupRecyclerView()

        // FastScrollHelperの初期化
        fastScrollHelper = FastScrollHelper(
            binding.detailRecyclerView,
            binding.fastScrollTrack,
            binding.fastScrollThumb,
            layoutManager
        )

        observeViewModel()
        viewModel.fetchDetails(currentUrl!!)
        detailSearchManager.setupSearchNavigation()

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (detailSearchManager.handleOnBackPressed()) {
                    return
                }
                if (scrollHistory.isNotEmpty()) {
                    val (position, offset) = scrollHistory.removeLast()
                    if (position >= 0 && position < detailAdapter.itemCount) {
                        binding.detailRecyclerView.post {
                            layoutManager.scrollToPositionWithOffset(position, offset)
                        }
                    } else {
                        Toast.makeText(this@DetailActivity, "戻る先の位置が無効です。", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private fun setupCustomToolbarElements() {
        binding.backButton.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.detail_menu, menu)
        detailSearchManager.setupSearch(menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_reply -> {
                currentUrl?.let { url ->
                    val threadId = url.substringAfterLast("/").substringBefore(".htm")
                    val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/"
                    val boardPostUrl = boardBasePath + "futaba.php"

                    val intent = Intent(this, ReplyActivity::class.java).apply {
                        putExtra(ReplyActivity.EXTRA_THREAD_ID, threadId)
                        putExtra(ReplyActivity.EXTRA_THREAD_TITLE, binding.toolbarTitle.text.toString())
                        putExtra(ReplyActivity.EXTRA_BOARD_URL, boardPostUrl)
                    }
                    replyActivityResultLauncher.launch(intent)
                }
                true
            }
            R.id.action_reload -> {
                binding.swipeRefreshLayout.isRefreshing = true
                reloadDetails()
                true
            }
            R.id.action_scroll_back -> {
                if (scrollHistory.isNotEmpty()) {
                    val (position, offset) = scrollHistory.removeLast()
                    if (position >= 0 && position < detailAdapter.itemCount) {
                        binding.detailRecyclerView.post {
                            layoutManager.scrollToPositionWithOffset(position, offset)
                        }
                    } else {
                        showToastOnUiThread("戻る先の位置が無効です。", Toast.LENGTH_SHORT)
                    }
                } else {
                    showToastOnUiThread("戻る履歴がありません。", Toast.LENGTH_SHORT)
                }
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

    private fun reloadDetails() {
        currentUrl?.let { urlToRefresh ->
            saveCurrentScrollStateIfApplicable()
            detailSearchManager.clearSearch()
            scrollHistory.clear()
            viewModel.fetchDetails(urlToRefresh, forceRefresh = true)
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentScrollStateIfApplicable()
    }

    private fun saveCurrentScrollStateIfApplicable() {
        if (!detailSearchManager.isSearchActive() && currentUrl != null) {
            saveCurrentScrollState(currentUrl!!)
        }
    }

    private fun setupRecyclerView() {
        detailAdapter = DetailAdapter()
        layoutManager = LinearLayoutManager(this@DetailActivity)

        // そうだねの状態を取得する関数をアダプターに設定
        detailAdapter.getSodaNeState = { resNum ->
            viewModel.getSodaNeState(resNum)
        }

        detailAdapter.onQuoteClickListener = clickListenerLambda@{ quotedText ->
            val mediaExt = setOf("jpg","jpeg","png","gif","webp","webm","mp4","mov","avi","flv","mkv")
            val isMediaFile = quotedText.substringAfterLast('.', "").lowercase() in mediaExt

            if (isMediaFile) {
                // メディアファイルの場合は返信画面を開く
                currentUrl?.let { url ->
                    val threadId = url.substringAfterLast("/").substringBefore(".htm")
                    val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/"
                    val boardPostUrl = boardBasePath + "futaba.php"
                    val intent = Intent(this, ReplyActivity::class.java).apply {
                        putExtra(ReplyActivity.EXTRA_THREAD_ID, threadId)
                        putExtra(ReplyActivity.EXTRA_THREAD_TITLE, binding.toolbarTitle.text.toString())
                        putExtra(ReplyActivity.EXTRA_BOARD_URL, boardPostUrl)
                        putExtra(ReplyActivity.EXTRA_QUOTE_TEXT, ">$quotedText")
                    }
                    replyActivityResultLauncher.launch(intent)
                }
            } else {
                // それ以外の場合はスクロール処理
                handleQuoteScroll(quotedText)
            }
        }

        detailAdapter.onSodaNeClickListener = { resNum -> viewModel.postSodaNe(resNum) }

        detailAdapter.onThreadEndTimeClickListener = {
            binding.swipeRefreshLayout.isRefreshing = true
            reloadDetails()
        }

        detailAdapter.onResNumClickListener = { resNum, resBody ->
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
            layoutManager = this@DetailActivity.layoutManager
            adapter = detailAdapter
            itemAnimator = null
            setItemViewCacheSize(100)
        }
    }

    private fun handleQuoteScroll(quotedText: String) {
        val contentList = viewModel.detailContent.value ?: return

        // 現在のスクロール位置を履歴に保存
        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        if (firstVisiblePosition != RecyclerView.NO_POSITION) {
            val firstVisibleView = layoutManager.findViewByPosition(firstVisiblePosition)
            val offset = firstVisibleView?.top ?: 0

            // 履歴は最大2件まで
            if (scrollHistory.size >= 2) {
                scrollHistory.removeFirst()
            }
            scrollHistory.addLast(Pair(firstVisiblePosition, offset))
        }

        // 引用先を探す
        var targetPosition = -1

        // パターン1: レス番号 (No.123 形式)
        val resNumPattern = """No\.(\d+)""".toRegex()
        val resNumMatch = resNumPattern.find(quotedText)
        if (resNumMatch != null) {
            val targetResNum = resNumMatch.groupValues[1]
            targetPosition = findPositionByResNum(contentList, targetResNum)
        }

        // パターン2: ファイル名
        if (targetPosition == -1) {
            targetPosition = findPositionByFileName(contentList, quotedText)
        }

        // パターン3: コメント本文の一部
        if (targetPosition == -1) {
            targetPosition = findPositionByContent(contentList, quotedText)
        }

        // スクロール実行
        if (targetPosition >= 0) {
            binding.detailRecyclerView.post {
                layoutManager.scrollToPositionWithOffset(targetPosition, 20)
            }
        } else {
            Toast.makeText(this, "引用先が見つかりませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun findPositionByResNum(contentList: List<DetailContent>, resNum: String): Int {
        contentList.forEachIndexed { index, content ->
            if (content is DetailContent.Text) {
                // HTMLからテキストを抽出してNo.XXXのパターンを探す
                val plainText = Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                if (plainText.contains("No.$resNum")) {
                    return index
                }
            }
        }
        return -1
    }

    private fun findPositionByFileName(contentList: List<DetailContent>, fileName: String): Int {
        contentList.forEachIndexed { index, content ->
            when (content) {
                is DetailContent.Image -> {
                    if (content.fileName == fileName || content.imageUrl.endsWith(fileName)) {
                        return index
                    }
                }
                is DetailContent.Video -> {
                    if (content.fileName == fileName || content.videoUrl.endsWith(fileName)) {
                        return index
                    }
                }
                is DetailContent.Text -> {
                    // テキスト内にファイル名が含まれているかチェック
                    val plainText = Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                    if (plainText.contains(fileName)) {
                        return index
                    }
                }
                else -> {}
            }
        }
        return -1
    }

    private fun findPositionByContent(contentList: List<DetailContent>, searchText: String): Int {
        // 検索テキストを正規化（空白や改行を除去）
        val normalizedSearch = searchText.trim().replace(Regex("\\s+"), " ")

        contentList.forEachIndexed { index, content ->
            if (content is DetailContent.Text) {
                val plainText = Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                val normalizedContent = plainText.trim().replace(Regex("\\s+"), " ")

                // 部分一致で検索
                if (normalizedContent.contains(normalizedSearch, ignoreCase = true)) {
                    return index
                }
            }
        }
        return -1
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            if (!binding.swipeRefreshLayout.isRefreshing) {
                binding.progressBar.isVisible = isLoading
            }
            if (!isLoading) {
                binding.swipeRefreshLayout.isRefreshing = false
            }
            binding.detailRecyclerView.isVisible = (!isLoading || binding.swipeRefreshLayout.isRefreshing) && !detailSearchManager.isSearchActive()
        }

        viewModel.detailContent.observe(this) { contentList ->
            val hadPreviousContent = detailAdapter.itemCount > 0
            detailAdapter.submitList(contentList) {
                if (contentList.isNotEmpty()) {
                    // FastScrollの位置を更新
                    fastScrollHelper.updateScrollPosition()

                    currentUrl?.let { url ->
                        if (detailSearchManager.getCurrentSearchQuery() != null && detailSearchManager.isSearchViewExpanded()) {
                            detailSearchManager.getCurrentSearchQuery()?.let { query ->
                                detailSearchManager.performSearch(query)
                            }
                        } else {
                            if (hadPreviousContent || scrollPositionStore.getScrollState(url).first != 0 || scrollPositionStore.getScrollState(url).second != 0) {
                                val (position, offset) = scrollPositionStore.getScrollState(url)
                                if (position >= 0 && position < contentList.size) {
                                    binding.detailRecyclerView.post {
                                        layoutManager.scrollToPositionWithOffset(position, offset)
                                        // スクロール後にFastScrollの位置も更新
                                        fastScrollHelper.updateScrollPosition()
                                    }
                                }
                            }
                        }
                    }

                    // アイテム数に応じてFastScrollの表示/非表示を切り替え
                    fastScrollHelper.setFastScrollEnabled(contentList.size > 20)
                } else {
                    detailSearchManager.clearSearch()
                    fastScrollHelper.setFastScrollEnabled(false)
                }
            }
        }

        // そうだねの部分更新を監視
        viewModel.sodaNeUpdate.observe(this) { (resNum, isClicked) ->
            // 該当するレス番号のビューを部分更新
            updateSodaNeInView(resNum, isClicked)
        }

        viewModel.error.observe(this) { errorMessage ->
            if (!errorMessage.isNullOrEmpty()) {
                showToastOnUiThread(errorMessage, Toast.LENGTH_LONG)
            }
        }
    }

    /**
     * そうだねの状態を部分的に更新する
     */
    private fun updateSodaNeInView(resNum: String, isClicked: Boolean) {
        // 現在表示されているビューの中から該当するレス番号を探して更新
        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()

        for (i in firstVisiblePosition..lastVisiblePosition) {
            val viewHolder = binding.detailRecyclerView.findViewHolderForAdapterPosition(i)
            if (viewHolder is DetailAdapter.TextViewHolder) {
                // TextViewHolderのテキストを部分更新
                updateTextViewSodaNe(viewHolder, resNum, isClicked)
            }
        }
    }

    /**
     * TextViewHolder内のそうだね部分を更新
     */
    private fun updateTextViewSodaNe(viewHolder: DetailAdapter.TextViewHolder, resNum: String, isClicked: Boolean) {
        val textView = viewHolder.itemView.findViewById<TextView>(R.id.detailTextView)
        val spannableText = textView.text as? SpannableStringBuilder ?: return

        // 既存のSodaNeClickableSpanを探して更新
        val spans = spannableText.getSpans(0, spannableText.length, DetailAdapter.SodaNeClickableSpan::class.java)

        spans.forEach { span ->
            if (span.resNum == resNum) {
                val start = spannableText.getSpanStart(span)
                val end = spannableText.getSpanEnd(span)
                val flags = spannableText.getSpanFlags(span)

                // 古いスパンを削除
                spannableText.removeSpan(span)

                // 新しいスパンを追加（状態を更新）
                val newSpan = DetailAdapter.SodaNeClickableSpan(
                    resNum = resNum,
                    listener = detailAdapter.onSodaNeClickListener,
                    isClicked = isClicked
                )
                spannableText.setSpan(newSpan, start, end, flags)
            }
        }

        // TextViewを再描画
        textView.text = spannableText
    }

    private fun saveCurrentScrollState(url: String) {
        val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
        if (firstVisibleItemPosition != RecyclerView.NO_POSITION) {
            val firstVisibleItemView = layoutManager.findViewByPosition(firstVisibleItemPosition)
            val offset = firstVisibleItemView?.top ?: 0
            scrollPositionStore.saveScrollState(url, firstVisibleItemPosition, offset)
        }
    }

    private fun showToastOnUiThread(message: String, duration: Int) {
        runOnUiThread {
            Toast.makeText(this, message, if (duration == 1) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    override fun getDetailContent(): List<DetailContent>? { return viewModel.detailContent.value }
    override fun getDetailAdapter(): DetailAdapter { return detailAdapter }
    override fun getLayoutManager(): LinearLayoutManager { return layoutManager }
    override fun showToast(message: String, duration: Int) { Toast.makeText(this, message, duration).show() }
    override fun getStringResource(resId: Int): String { return getString(resId) }
    override fun getStringResource(resId: Int, vararg formatArgs: Any): String { return getString(resId, *formatArgs) }
    override fun onSearchCleared() { if (isBindingInitialized() && viewModel.isLoading.value == false ) { binding.detailRecyclerView.isVisible = true } }
    override fun isBindingInitialized(): Boolean { return ::binding.isInitialized }
}