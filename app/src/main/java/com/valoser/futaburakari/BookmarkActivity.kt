package com.valoser.futaburakari

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.ui.compose.BookmarkScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import kotlinx.coroutines.launch

class BookmarkActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val colorModePref = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")

        setContent {
            FutaburakariTheme(colorMode = colorModePref) {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                var bookmarks by remember { mutableStateOf(BookmarkManager.getBookmarks(this@BookmarkActivity)) }

                Box {
                    BookmarkScreen(
                        title = getString(R.string.bookmark_management_title),
                        bookmarks = bookmarks,
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        onAddBookmark = { name, url ->
                            if (name.isBlank() || url.isBlank()) {
                                scope.launch { snackbarHostState.showSnackbar("名前とURLを入力してください") }
                            } else {
                                BookmarkManager.addBookmark(this@BookmarkActivity, Bookmark(name, url))
                                scope.launch { snackbarHostState.showSnackbar("ブックマークを追加しました") }
                                bookmarks = BookmarkManager.getBookmarks(this@BookmarkActivity)
                            }
                        },
                        onUpdateBookmark = { oldUrl, name, url ->
                            if (name.isBlank() || url.isBlank()) {
                                scope.launch { snackbarHostState.showSnackbar("名前とURLを入力してください") }
                            } else {
                                BookmarkManager.updateBookmark(this@BookmarkActivity, oldUrl, Bookmark(name, url))
                                if (BookmarkManager.getSelectedBookmarkUrl(this@BookmarkActivity) == oldUrl) {
                                    BookmarkManager.saveSelectedBookmarkUrl(this@BookmarkActivity, url)
                                }
                                scope.launch { snackbarHostState.showSnackbar("ブックマークを更新しました") }
                                bookmarks = BookmarkManager.getBookmarks(this@BookmarkActivity)
                            }
                        },
                        onDeleteBookmark = { bookmark ->
                            BookmarkManager.deleteBookmark(this@BookmarkActivity, bookmark)
                            if (BookmarkManager.getSelectedBookmarkUrl(this@BookmarkActivity) == bookmark.url) {
                                BookmarkManager.saveSelectedBookmarkUrl(this@BookmarkActivity, null)
                            }
                            scope.launch { snackbarHostState.showSnackbar("「${bookmark.name}」を削除しました") }
                            bookmarks = BookmarkManager.getBookmarks(this@BookmarkActivity)
                        },
                        onSelectBookmark = { bookmark ->
                            BookmarkManager.saveSelectedBookmarkUrl(this@BookmarkActivity, bookmark.url)
                            scope.launch { snackbarHostState.showSnackbar("「${bookmark.name}」を選択しました") }
                            finish()
                        }
                    )
                    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}
