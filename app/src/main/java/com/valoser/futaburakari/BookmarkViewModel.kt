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

@HiltViewModel
class BookmarkViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    init {
        loadBookmarks()
    }

    private fun loadBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            _bookmarks.value = BookmarkManager.getBookmarks(context)
        }
    }

    fun addBookmark(bookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            BookmarkManager.addBookmark(context, bookmark)
            loadBookmarks()
        }
    }

    fun updateBookmark(oldUrl: String, newBookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            BookmarkManager.updateBookmark(context, oldUrl, newBookmark)
            if (BookmarkManager.getSelectedBookmarkUrl(context) == oldUrl) {
                BookmarkManager.saveSelectedBookmarkUrl(context, newBookmark.url)
            }
            loadBookmarks()
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            BookmarkManager.deleteBookmark(context, bookmark)
            if (BookmarkManager.getSelectedBookmarkUrl(context) == bookmark.url) {
                BookmarkManager.saveSelectedBookmarkUrl(context, null)
            }
            loadBookmarks()
        }
    }

    fun saveSelectedBookmarkUrl(url: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            BookmarkManager.saveSelectedBookmarkUrl(context, url)
        }
    }
}
