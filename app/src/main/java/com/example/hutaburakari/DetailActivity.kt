package com.example.hutaburakari

// ... (他のimport文)
import android.app.Activity // Activity.RESULT_OK のために必要
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat // ★ Import for WindowCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hutaburakari.databinding.ActivityDetailBinding
import java.util.ArrayDeque
import android.text.Html // Html.fromHtml のために必要
import java.util.regex.Pattern
// kotlin.text.Regex と RegexOption は標準ライブラリのため、通常は明示的なimportは不要

class DetailActivity : AppCompatActivity(), SearchManagerCallback {

    private lateinit var binding: ActivityDetailBinding
    private val viewModel: DetailViewModel by viewModels()
    private lateinit var detailAdapter: DetailAdapter
    private lateinit var scrollPositionStore: ScrollPositionStore
    private var currentUrl: String? = null
    private lateinit var layoutManager: LinearLayoutManager

    private val scrollHistory = ArrayDeque<Pair<Int, Int>>(2)

    private lateinit var detailSearchManager: DetailSearchManager

    companion object {
        const val EXTRA_URL = "extra_url"
        private const val REQUEST_CODE_REPLY = 1 // リクエストコードを追加
        // FILENAME_PATTERN は新しい正規表現に置き換えられるため、ここではコメントアウトまたは削除してもよい
        // private val FILENAME_PATTERN = Pattern.compile("\\.(png|jpe?g|gif|webp)$", Pattern.CASE_INSENSITIVE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // ★ Add this line for edge-to-edge
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
                    // 正しい boardUrl の生成
                    val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/" // 例: https://may.2chan.net/b/
                    val boardPostUrl = boardBasePath + "futaba.php" // 例: https://may.2chan.net/b/futaba.php
                    
                    Log.d("DetailActivity", "Action Reply: Thread ID: $threadId, Original currentUrl: $url")
                    Log.d("DetailActivity", "Action Reply: Generated boardBasePath: $boardBasePath")
                    Log.d("DetailActivity", "Action Reply: Generated boardPostUrl for ReplyActivity: $boardPostUrl")
                    Log.d("DetailActivity", "Action Reply: Thread Title: ${binding.toolbarTitle.text}")

                    val intent = Intent(this, ReplyActivity::class.java).apply {
                        putExtra(ReplyActivity.EXTRA_THREAD_ID, threadId)
                        putExtra(ReplyActivity.EXTRA_THREAD_TITLE, binding.toolbarTitle.text.toString())
                        putExtra(ReplyActivity.EXTRA_BOARD_URL, boardPostUrl) // 修正：完全な投稿先URLを渡す
                    }
                    startActivityForResult(intent, REQUEST_CODE_REPLY) // 変更
                }
                true
            }
            R.id.action_reload -> {
                reloadDetails() // ★ 再読み込み処理をメソッドに抽出
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

    // ★ 再読み込み処理を共通化
    private fun reloadDetails() {
        currentUrl?.let { urlToRefresh ->
            saveCurrentScrollStateIfApplicable()
            detailSearchManager.clearSearch()
            scrollHistory.clear()
            viewModel.fetchDetails(urlToRefresh, forceRefresh = true)
            showToastOnUiThread("再読み込みしています...", Toast.LENGTH_SHORT)
        }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d("DetailActivity", "onActivityResult: requestCode=$requestCode, resultCode=$resultCode") // ログ追加
        if (requestCode == REQUEST_CODE_REPLY && resultCode == Activity.RESULT_OK) {
            Log.d("DetailActivity", "onActivityResult: Reply successful, attempting to refresh.") // ログ追加
            Toast.makeText(this, "Reply successful, refreshing...", Toast.LENGTH_SHORT).show() // 確認用トースト
            currentUrl?.let { urlToRefresh -> // ★ ここも共通メソッドを利用可能だが、トーストメッセージが異なるため個別実装のまま
                saveCurrentScrollStateIfApplicable()
                detailSearchManager.clearSearch()
                scrollHistory.clear()
                viewModel.fetchDetails(urlToRefresh, forceRefresh = true)
                showToastOnUiThread("投稿を反映して再読み込みしました。", Toast.LENGTH_SHORT) // こちらは既存
            }
        } else {
            Log.d("DetailActivity", "onActivityResult: Reply not successful or wrong request code.") // ログ追加
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentScrollStateIfApplicable()
    }

    override fun onDestroy() {
        super.onDestroy()
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
                    val threadId = url.substringAfterLast("/").substringBefore(".htm")
                    val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/"
                    val boardPostUrl = boardBasePath + "futaba.php"

                    Log.d("DetailActivity", "FileName Clicked (Extension Check): $quotedText")
                    val intent = Intent(this, ReplyActivity::class.java).apply {
                        putExtra(ReplyActivity.EXTRA_THREAD_ID, threadId)
                        putExtra(ReplyActivity.EXTRA_THREAD_TITLE, binding.toolbarTitle.text.toString())
                        putExtra(ReplyActivity.EXTRA_BOARD_URL, boardPostUrl)
                        putExtra(ReplyActivity.EXTRA_QUOTE_TEXT, ">$quotedText") // Prepend ">" to the filename
                    }
                    startActivityForResult(intent, REQUEST_CODE_REPLY)
                }
            } else {
                // それ以外は既存のスクロール検索に流す
                val contentList = viewModel.detailContent.value ?: return@clickListenerLambda
                val currentFirstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()
                if (currentFirstVisibleItemPosition != RecyclerView.NO_POSITION) {
                    val firstVisibleItemView = layoutManager.findViewByPosition(currentFirstVisibleItemPosition)
                    val offset = firstVisibleItemView?.top ?: 0
                    if (scrollHistory.size == 2) {
                        scrollHistory.removeFirst()
                    }
                    scrollHistory.addLast(Pair(currentFirstVisibleItemPosition, offset))
                }
                val targetPosition = contentList.indexOfFirst { content ->
                    when (content) {
                        is DetailContent.Text -> Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString().contains(quotedText, ignoreCase = true)
                        is DetailContent.Image -> {
                            (content.fileName?.equals(quotedText, ignoreCase = true) == true ||
                                    content.imageUrl.substringAfterLast('/').equals(quotedText, ignoreCase = true) ||
                                    content.prompt?.contains(quotedText, ignoreCase = true) == true)
                        }
                        is DetailContent.Video -> {
                            (content.fileName?.equals(quotedText, ignoreCase = true) == true ||
                                    content.videoUrl.substringAfterLast('/').equals(quotedText, ignoreCase = true) ||
                                    content.prompt?.contains(quotedText, ignoreCase = true) == true)
                        }
                        is DetailContent.ThreadEndTime -> false
                    }
                }
                if (targetPosition != -1) {
                    binding.detailRecyclerView.smoothScrollToPosition(targetPosition)
                } else {
                    showToastOnUiThread("引用元が見つかりません: $quotedText", Toast.LENGTH_SHORT)
                }
            }
        }
        detailAdapter.onSodaNeClickListener = {
            resNum -> viewModel.postSodaNe(resNum)
        }
        // ★ ここに onThreadEndTimeClickListener を設定
        detailAdapter.onThreadEndTimeClickListener = {
            Log.d("DetailActivity", "ThreadEndTime clicked, reloading...")
            reloadDetails() // ★ 再読み込み処理を呼び出し
        }
        // ★ ResNumクリックリスナーを設定
        detailAdapter.onResNumClickListener = { resNum, resBody ->
            currentUrl?.let { url ->
                val threadId = url.substringAfterLast("/").substringBefore(".htm")
                val boardBasePath = url.substringBeforeLast("/").substringBeforeLast("/") + "/"
                val boardPostUrl = boardBasePath + "futaba.php"

                Log.d("DetailActivity", "ResNum Clicked: No.$resNum, Body: \n$resBody")
                Log.d("DetailActivity", "Thread ID: $threadId, Board URL: $boardPostUrl, Thread Title: ${binding.toolbarTitle.text}")

                val intent = Intent(this, ReplyActivity::class.java).apply {
                    putExtra(ReplyActivity.EXTRA_THREAD_ID, threadId)
                    putExtra(ReplyActivity.EXTRA_THREAD_TITLE, binding.toolbarTitle.text.toString())
                    putExtra(ReplyActivity.EXTRA_BOARD_URL, boardPostUrl)
                    putExtra(ReplyActivity.EXTRA_QUOTE_TEXT, resBody) // ★ 引用テキストを渡す
                }
                startActivityForResult(intent, REQUEST_CODE_REPLY)
            }
        }

        binding.detailRecyclerView.apply {
            layoutManager = this@DetailActivity.layoutManager
            adapter = detailAdapter
            itemAnimator = null
            setItemViewCacheSize(100) // Add this line
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            binding.progressBar.isVisible = isLoading
            binding.detailRecyclerView.isVisible = !isLoading && !detailSearchManager.isSearchActive()
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

    // Implementation of SearchManagerCallback
    override fun getDetailContent(): List<DetailContent>? {
        return viewModel.detailContent.value
    }

    override fun getDetailAdapter(): DetailAdapter {
        return detailAdapter
    }

    override fun getLayoutManager(): LinearLayoutManager {
        return layoutManager
    }

    override fun showToast(message: String, duration: Int) {
        Toast.makeText(this, message, duration).show()
    }

    override fun getStringResource(resId: Int): String {
        return getString(resId)
    }

    override fun getStringResource(resId: Int, vararg formatArgs: Any): String {
        return getString(resId, *formatArgs)
    }

    override fun onSearchCleared() {
        if (isBindingInitialized() && viewModel.isLoading.value == false ) {
             binding.detailRecyclerView.isVisible = true
        }
    }

    override fun isBindingInitialized(): Boolean {
        return ::binding.isInitialized
    }
}
