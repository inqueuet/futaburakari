package com.valoser.hutaburakari

import android.text.Html
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import com.valoser.hutaburakari.databinding.ActivityDetailBinding // Bindingクラス名は実際のプロジェクトに合わせてください

// DetailActivityが実装するコールバックインターフェース
interface SearchManagerCallback {
    fun getDetailContent(): List<DetailContent>?
    fun getDetailAdapter(): DetailAdapter // DetailAdapterにsetSearchQueryとitemCountがある前提
    fun getLayoutManager(): LinearLayoutManager
    fun showToast(message: String, duration: Int)
    fun getStringResource(resId: Int): String
    fun getStringResource(resId: Int, vararg formatArgs: Any): String
    fun onSearchCleared() // 検索がクリアされたときにDetailActivityに通知
    fun isBindingInitialized(): Boolean // bindingが初期化済みか確認
}

class DetailSearchManager(
    // private val activity: DetailActivity, // Activityへの直接参照の代わりにCallbackを使用
    private val binding: ActivityDetailBinding,
    private val callback: SearchManagerCallback
) {

    private var searchView: SearchView? = null
    private var currentSearchQuery: String? = null
    private val searchResultPositions = mutableListOf<Int>()
    private var currentSearchHitIndex = -1

    fun setupSearch(menu: Menu) {
        val searchItem = menu.findItem(R.id.action_search)
        searchView = searchItem?.actionView as? SearchView
        searchView?.queryHint = callback.getStringResource(R.string.search_hint) // R.string.search_hint を使用
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { performSearch(it) }
                searchView?.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // ユーザーがテキストをクリアした場合、即座に検索をクリアする
                if (newText.isNullOrEmpty() && !currentSearchQuery.isNullOrEmpty()) {
                    clearSearch()
                }
                return true
            }
        })
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                if (callback.isBindingInitialized()) {
                    binding.searchNavigationControls.visibility = View.VISIBLE
                }
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                clearSearch() // SearchViewが閉じられたら検索状態をクリア
                return true
            }
        })
    }

    fun performSearch(query: String) {
        if (!callback.isBindingInitialized()) return

        currentSearchQuery = query
        // DetailAdapterに検索クエリを渡してハイライトなどを処理させることを想定
        callback.getDetailAdapter().setSearchQuery(query)
        searchResultPositions.clear()
        currentSearchHitIndex = -1

        val contentList = callback.getDetailContent() ?: return
        contentList.forEachIndexed { index, content ->
            val textToSearch: String? = when (content) {
                is DetailContent.Text -> Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                is DetailContent.Image -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.imageUrl.substringAfterLast('/')}"
                is DetailContent.Video -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.videoUrl.substringAfterLast('/')}"
                is DetailContent.ThreadEndTime -> null // ThreadEndTime は検索対象外とする
            }
            if (textToSearch?.contains(query, ignoreCase = true) == true) {
                searchResultPositions.add(index)
            }
        }

        if (searchResultPositions.isNotEmpty()) {
            currentSearchHitIndex = 0
            navigateToCurrentHit()
            binding.searchNavigationControls.visibility = View.VISIBLE
        } else {
            callback.showToast(callback.getStringResource(R.string.no_results_found), Toast.LENGTH_SHORT) // R.string.no_results_found を使用
            binding.searchNavigationControls.visibility = View.GONE
        }
        updateSearchResultsCount()
    }

    fun clearSearch() {
        if (!callback.isBindingInitialized()) return

        val searchWasActive = currentSearchQuery != null
        currentSearchQuery = null
        callback.getDetailAdapter().setSearchQuery(null) // Adapterの検索状態もクリア
        searchResultPositions.clear()
        currentSearchHitIndex = -1
        
        if (callback.isBindingInitialized()) {
            binding.searchNavigationControls.visibility = View.GONE
            updateSearchResultsCount() // UI更新
        }

        // SearchViewの状態もリセット（これが onMenuItemActionCollapse をトリガーしないように注意）
        if (searchView?.isIconified == false && searchView?.query?.isNotEmpty() == true) {
            searchView?.setQuery("", false) // クエリをクリア
        }
        if (searchView?.isIconified == false) {
             // searchView?.isIconified = true // これが onMenuItemActionCollapse をトリガーする可能性
        }


        if (searchWasActive) {
            callback.onSearchCleared() // DetailActivityに通知して必要なUI更新を行わせる
        }
    }

    private fun navigateToCurrentHit() {
        if (!callback.isBindingInitialized()) return
        if (searchResultPositions.isEmpty() || currentSearchHitIndex !in searchResultPositions.indices) return

        val position = searchResultPositions[currentSearchHitIndex]
        if (position >= 0 && position < callback.getDetailAdapter().itemCount) {
            // RecyclerViewの描画準備ができてからスクロールする
            binding.detailRecyclerView.post {
                // 連続操作のブレを抑える
                binding.detailRecyclerView.stopScroll()
                callback.getLayoutManager().scrollToPositionWithOffset(position, 20)
            }
        }
        updateSearchResultsCount()
    }

    // レイアウト更新・画像読み込み後などに、現在のヒット位置へ再吸着させるための補助
    fun realignToCurrentHitIfActive() {
        if (!callback.isBindingInitialized()) return
        if (searchResultPositions.isEmpty() || currentSearchHitIndex !in searchResultPositions.indices) return
        val position = searchResultPositions[currentSearchHitIndex]
        if (position < 0 || position >= callback.getDetailAdapter().itemCount) return

        binding.detailRecyclerView.post {
            binding.detailRecyclerView.stopScroll()
            callback.getLayoutManager().scrollToPositionWithOffset(position, 20)
        }
    }

    fun setupSearchNavigation() {
        if (!callback.isBindingInitialized()) return

        binding.searchUpButton.setOnClickListener {
            if (searchResultPositions.isNotEmpty()) {
                currentSearchHitIndex--
                if (currentSearchHitIndex < 0) {
                    currentSearchHitIndex = searchResultPositions.size - 1
                }
                navigateToCurrentHit()
            }
        }
        binding.searchDownButton.setOnClickListener {
            if (searchResultPositions.isNotEmpty()) {
                currentSearchHitIndex++
                if (currentSearchHitIndex >= searchResultPositions.size) {
                    currentSearchHitIndex = 0
                }
                navigateToCurrentHit()
            }
        }
    }

    private fun updateSearchResultsCount() {
        if (!callback.isBindingInitialized()) return

        val countText = if (searchResultPositions.isNotEmpty() && currentSearchHitIndex != -1) {
            callback.getStringResource(R.string.search_results_format, currentSearchHitIndex + 1, searchResultPositions.size) // R.string.search_results_format を使用
        } else if (currentSearchQuery != null && searchResultPositions.isEmpty()) {
            callback.getStringResource(R.string.no_results_found) // R.string.no_results_found を使用
        } else {
            ""
        }
        binding.searchResultsCountText.text = countText
    }

    fun isSearchActive(): Boolean {
        // SearchViewが展開されていて、かつ検索クエリがある場合を「検索中」とみなす
        return searchView?.isIconified == false && !currentSearchQuery.isNullOrEmpty()
    }
    
    fun isSearchViewExpanded(): Boolean {
        return searchView?.isIconified == false
    }

    fun getCurrentSearchQuery(): String? {
        return currentSearchQuery
    }

    // DetailActivityのonBackPressedDispatcher.addCallbackから呼び出される
    fun handleOnBackPressed(): Boolean {
        if (isSearchViewExpanded()) { // まずSearchViewが開いているか確認
            searchView?.isIconified = true // SearchViewを閉じる (これによりonMenuItemActionCollapseが呼ばれ、clearSearchが実行される)
            return true // バックキーイベントを消費したことを示す
        }
        return false // SearchViewが閉じていれば、通常のバックキー処理
    }
}
