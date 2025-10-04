package com.valoser.futaburakari

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ブックマーク管理画面のViewModel（Hilt DI 対応）。
 * BookmarkManager を介した CRUD 操作と選択状態の永続化を `Dispatchers.IO` 上で実行し、結果を StateFlow として公開する。
 */
@HiltViewModel
class BookmarkViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    /** UI向けStateFlowの内部ストレージとして機能する MutableStateFlow。 */
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    /** UIから購読されるブックマークリスト。 */
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    init {
        loadBookmarks()
    }

    /** ストレージからブックマーク一覧を読み込み、UI状態を更新する。 */
    private fun loadBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            _bookmarks.value = BookmarkManager.getBookmarks(context)
        }
    }

    /** 新しいブックマークをストレージに追加し、UI状態を再読み込みする。 */
    fun addBookmark(bookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            BookmarkManager.addBookmark(context, bookmark)
            loadBookmarks()
        }
    }

    /**
     * 既存ブックマークを更新し、UI状態を再読み込みする。
     * 編集対象が現在選択中のブックマークの場合は、選択URLも同時に更新する。
     */
    fun updateBookmark(oldUrl: String, newBookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            BookmarkManager.updateBookmark(context, oldUrl, newBookmark)
            if (BookmarkManager.getSelectedBookmarkUrl(context) == oldUrl) {
                BookmarkManager.saveSelectedBookmarkUrl(context, newBookmark.url)
            }
            loadBookmarks()
        }
    }

    /**
     * ブックマークをストレージから削除し、UI状態を再読み込みする。
     * 削除対象が現在選択中のブックマークの場合は、選択状態もクリアする。
     */
    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            BookmarkManager.deleteBookmark(context, bookmark)
            if (BookmarkManager.getSelectedBookmarkUrl(context) == bookmark.url) {
                BookmarkManager.saveSelectedBookmarkUrl(context, null)
            }
            loadBookmarks()
        }
    }

    /** 現在選択中のブックマークURLを保存する（null で選択状態を解除）。 */
    fun saveSelectedBookmarkUrl(url: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            BookmarkManager.saveSelectedBookmarkUrl(context, url)
        }
    }
}
