package com.example.hutaburakari

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import coil.imageLoader
import coil.request.ImageRequest
import com.example.hutaburakari.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MainActivity : AppCompatActivity(), SearchView.OnQueryTextListener {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var imageAdapter: ImageAdapter
    private var currentSelectedUrl: String? = null
    private var allItems: List<ImageItem> = emptyList()

    private val bookmarkActivityResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        loadAndFetchInitialData()
    }

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                Log.d("MainActivity", "Selected image URI: $uri")
                val promptInfo = MetadataExtractor.extract(this@MainActivity, uri.toString())
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        observeViewModel()
        setupClickListener()

        // ★ スワイプ更新のリスナーを設定
        binding.swipeRefreshLayout.setOnRefreshListener {
            fetchDataForCurrentUrl()
        }

        loadAndFetchInitialData()
    }

    private fun loadAndFetchInitialData() {
        currentSelectedUrl = BookmarkManager.getSelectedBookmarkUrl(this)
        binding.toolbar.subtitle = getCurrentBookmarkName()
        fetchDataForCurrentUrl()
    }

    private fun fetchDataForCurrentUrl() {
        currentSelectedUrl?.let { url ->
            lifecycleScope.launch {
                val boardBaseUrl = url.substringBefore("futaba.php")
                if (boardBaseUrl.isNotEmpty() && url.contains("futaba.php")) {
                    applyCatalogSettings(boardBaseUrl)
                } else {
                    Log.e("MainActivity", "Cannot derive boardBaseUrl from: $url. Skipping applyCatalogSettings.")
                }
                viewModel.fetchImagesFromUrl(url)
            }
        } ?: run {
            showBookmarkSelectionDialog()
        }
    }

    private suspend fun applyCatalogSettings(boardBaseUrl: String) {
        val settings = mapOf(
            "mode" to "catset",
            "cx" to "20",
            "cy" to "10",
            "cl" to "10"
        )
        try {
            // ★ 修正: NetworkClientからcontext引数を削除
            NetworkClient.applySettings(boardBaseUrl, settings)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "設定の適用に失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
            e.printStackTrace()
        }
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
                // ★ 修正: スワイプ更新用のインジケータも表示する
                binding.swipeRefreshLayout.isRefreshing = true
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
            // ★★★ このcaseを追加 ★★★
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
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
        filterImages(newText)
        return true
    }

    private fun filterImages(query: String?) {
        val filteredList = if (query.isNullOrEmpty()) {
            allItems
        } else {
            allItems.filter { it.title.contains(query, ignoreCase = true) }
        }
        imageAdapter.submitList(filteredList)
    }


    private fun getCurrentBookmarkName(): String {
        val bookmarks = BookmarkManager.getBookmarks(this)
        return bookmarks.find { it.url == currentSelectedUrl }?.name ?: "ブックマーク未選択"
    }

    private fun showBookmarkSelectionDialog() {
        val bookmarks = BookmarkManager.getBookmarks(this)
        val bookmarkNames = bookmarks.map { it.name }.toTypedArray()

        if (bookmarkNames.isEmpty()) {
            Toast.makeText(this, "ブックマークがありません。まずはブックマークを登録してください。", Toast.LENGTH_LONG).show()
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
                binding.toolbar.subtitle = selectedBookmark.name
                BookmarkManager.saveSelectedBookmarkUrl(this, selectedBookmark.url)
                fetchDataForCurrentUrl()
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun setupRecyclerView() {
        imageAdapter = ImageAdapter()
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            adapter = imageAdapter
            setHasFixedSize(true)
        }
    }

    private fun setupClickListener() {
        imageAdapter.onItemClick = { item ->
            val baseUrlString = currentSelectedUrl
            val imageUrlString = item.imageUrl

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
                    Log.e("MainActivity_Prefetch", "Prefetch failed for base:'$baseUrlString', image:'$imageUrlString'", e)
                }
            }

            val intent = Intent(this, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_URL, item.detailUrl)
                putExtra("EXTRA_TITLE", item.title)
            }
            startActivity(intent)
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
            // 読み込み中でもスワイプ更新中ならリストは表示したままにする
            binding.recyclerView.isVisible = !isLoading || binding.swipeRefreshLayout.isRefreshing
        }

        viewModel.images.observe(this) { items ->
            allItems = items
            filterImages(null)
        }

        viewModel.error.observe(this) { errorMessage ->
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }
}