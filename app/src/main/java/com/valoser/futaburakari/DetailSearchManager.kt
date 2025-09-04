package com.valoser.futaburakari

import android.text.Html
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.appcompat.widget.SearchView

// DetailActivityが実装するコールバックインターフェース
interface SearchManagerCallback {
    fun getDetailContent(): List<DetailContent>?
    fun showToast(message: String, duration: Int)
    fun getStringResource(resId: Int): String
    fun getStringResource(resId: Int, vararg formatArgs: Any): String
    fun onSearchCleared() // 検索がクリアされたときにDetailActivityに通知
}

class DetailSearchManager(
    private val callback: SearchManagerCallback
) {

    private var searchView: SearchView? = null
    private var currentSearchQuery: String? = null
    private val _currentQueryFlow = MutableStateFlow<String?>(null)
    val currentQueryFlow: StateFlow<String?> = _currentQueryFlow
    private val searchResultPositions = mutableListOf<Int>()
    private var currentSearchHitIndex = -1

    data class SearchState(
        val active: Boolean,
        val currentIndexDisplay: Int, // 1-based, 0 if none
        val total: Int
    )

    private val _searchState = MutableStateFlow(SearchState(active = false, currentIndexDisplay = 0, total = 0))
    val searchState: StateFlow<SearchState> = _searchState

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
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                clearSearch() // SearchViewが閉じられたら検索状態をクリア
                return true
            }
        })
    }

    fun performSearch(query: String) {
        currentSearchQuery = query
        _currentQueryFlow.value = query
        // Compose側でハイライトするため、Adapterへの伝播は不要
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
        } else {
            callback.showToast(callback.getStringResource(R.string.no_results_found), Toast.LENGTH_SHORT) // R.string.no_results_found を使用
        }
        updateSearchResultsCount()
    }

    fun clearSearch() {
        val searchWasActive = currentSearchQuery != null
        currentSearchQuery = null
        _currentQueryFlow.value = null
        // Compose側のハイライトはFlowで制御するため、特別なクリアは不要
        searchResultPositions.clear()
        currentSearchHitIndex = -1
        
        updateSearchResultsCount() // UI更新（Composeに反映）

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
        // Compose側にスクロールを委譲するため、ここでは件数・インデックスの更新のみ行う
        if (searchResultPositions.isEmpty() || currentSearchHitIndex !in searchResultPositions.indices) return
        updateSearchResultsCount()
    }

    // レイアウト更新・画像読み込み後などに、現在のヒット位置へ再吸着させるための補助
    fun realignToCurrentHitIfActive() {
        // Compose側で再吸着を行うため、ここでは何もしない
    }

    // 旧Viewベースの検索ナビは廃止（Compose化）

    private fun updateSearchResultsCount() {
        // 画面のテキストUIはCompose側で表示

        // Flow にも反映（Compose用）
        val active = (currentSearchQuery != null) && (searchResultPositions.isNotEmpty())
        val currentDisp = if (active && currentSearchHitIndex in searchResultPositions.indices) currentSearchHitIndex + 1 else 0
        val total = searchResultPositions.size
        _searchState.value = SearchState(active = active, currentIndexDisplay = currentDisp, total = total)
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

    // ---- Compose用ナビゲーションAPI ----
    fun navigateToPrevHit() {
        if (searchResultPositions.isEmpty()) return
        currentSearchHitIndex--
        if (currentSearchHitIndex < 0) currentSearchHitIndex = searchResultPositions.size - 1
        navigateToCurrentHit()
    }

    fun navigateToNextHit() {
        if (searchResultPositions.isEmpty()) return
        currentSearchHitIndex++
        if (currentSearchHitIndex >= searchResultPositions.size) currentSearchHitIndex = 0
        navigateToCurrentHit()
    }
}
