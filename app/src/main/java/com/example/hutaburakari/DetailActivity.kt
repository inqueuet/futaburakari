package com.example.hutaburakari

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.Menu
import android.view.MenuItem
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

    // ★ 非推奨APIの代替となるActivityResultLauncherを定義
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

        // ★ スワイプ更新のリスナーを設定
        binding.swipeRefreshLayout.setOnRefreshListener {
            reloadDetails()
        }

        setupCustomToolbarElements()
        setupRecyclerView()
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
                    // ★ 修正: 新しいLauncherを呼び出す
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

    // ★ onActivityResultは完全に不要になったため削除

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

        detailAdapter.onQuoteClickListener = clickListenerLambda@{ quotedText ->
            val mediaExt = setOf("jpg","jpeg","png","gif","webp","webm","mp4","mov","avi","flv","mkv")
            val isMediaFile = quotedText.substringAfterLast('.', "").lowercase() in mediaExt

            if (isMediaFile) {
                currentUrl?.let { url ->
                    val intent = Intent(this, ReplyActivity::class.java).apply {
                        // ... putExtra
                    }
                    // ★ 修正: 新しいLauncherを呼び出す
                    replyActivityResultLauncher.launch(intent)
                }
            } else {
                // ... (既存のスクロールロジック)
            }
        }
        detailAdapter.onSodaNeClickListener = { resNum -> viewModel.postSodaNe(resNum) }
        detailAdapter.onThreadEndTimeClickListener = {
            binding.swipeRefreshLayout.isRefreshing = true
            reloadDetails()
        }
        detailAdapter.onResNumClickListener = { resNum, resBody ->
            currentUrl?.let { url ->
                val intent = Intent(this, ReplyActivity::class.java).apply {
                    // ... putExtra
                }
                // ★ 修正: 新しいLauncherを呼び出す
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

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            // ★ 修正: ProgressBarとSwipeRefreshLayoutの両方の状態を更新
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
                                    }
                                }
                            }
                        }
                    }
                } else {
                    detailSearchManager.clearSearch()
                }
            }
        }
        viewModel.error.observe(this) { errorMessage ->
            if (!errorMessage.isNullOrEmpty()) {
                showToastOnUiThread(errorMessage, Toast.LENGTH_LONG)
            }
        }
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

    // (SearchManagerCallbackの実装は変更なし)
    override fun getDetailContent(): List<DetailContent>? { return viewModel.detailContent.value }
    override fun getDetailAdapter(): DetailAdapter { return detailAdapter }
    override fun getLayoutManager(): LinearLayoutManager { return layoutManager }
    override fun showToast(message: String, duration: Int) { Toast.makeText(this, message, duration).show() }
    override fun getStringResource(resId: Int): String { return getString(resId) }
    override fun getStringResource(resId: Int, vararg formatArgs: Any): String { return getString(resId, *formatArgs) }
    override fun onSearchCleared() { if (isBindingInitialized() && viewModel.isLoading.value == false ) { binding.detailRecyclerView.isVisible = true } }
    override fun isBindingInitialized(): Boolean { return ::binding.isInitialized }
}