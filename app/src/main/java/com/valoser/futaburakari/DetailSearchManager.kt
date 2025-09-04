package com.valoser.futaburakari

import android.text.Html
 
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
 

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

    // setupSearch(Menu/SearchView) はCompose移行により廃止

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
        // Compose側: クエリが空でなければ検索中とみなす
        return !currentSearchQuery.isNullOrEmpty()
    }
    
    fun isSearchViewExpanded(): Boolean {
        // Compose側ではSearchViewを使わない
        return false
    }

    fun getCurrentSearchQuery(): String? {
        return currentSearchQuery
    }

    // DetailActivityのonBackPressedDispatcher.addCallbackから呼び出される
    fun handleOnBackPressed(): Boolean {
        // Compose側では戻るキーでの閉じ処理はアクティビティで状態を下げる
        return false
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
