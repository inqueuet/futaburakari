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

/**
 * Activity for managing user bookmarks using a Jetpack Compose UI.
 * Handles add/update/delete/select operations and persists changes via `BookmarkManager`.
 */
class BookmarkActivity : BaseActivity() {

    /**
     * Sets up themed Compose content and wires bookmark actions to persistence.
     * Shows feedback with a bottom snackbar for each operation.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val colorModePref = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")

        setContent {
            // Apply app theme based on the selected color mode
            FutaburakariTheme(colorMode = colorModePref) {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                // In-memory state backed by BookmarkManager storage
                var bookmarks by remember { mutableStateOf(BookmarkManager.getBookmarks(this@BookmarkActivity)) }

                Box {
                    BookmarkScreen(
                        title = getString(R.string.bookmark_management_title),
                        bookmarks = bookmarks,
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        onAddBookmark = { name, url ->
                            // Validate inputs, then persist and refresh local state
                            if (name.isBlank() || url.isBlank()) {
                                scope.launch { snackbarHostState.showSnackbar("名前とURLを入力してください") }
                            } else {
                                BookmarkManager.addBookmark(this@BookmarkActivity, Bookmark(name, url))
                                scope.launch { snackbarHostState.showSnackbar("ブックマークを追加しました") }
                                bookmarks = BookmarkManager.getBookmarks(this@BookmarkActivity)
                            }
                        },
                        onUpdateBookmark = { oldUrl, name, url ->
                            // Update storage and selected URL if it was the edited one
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
                            // Remove from storage and clear selection if it was deleted
                            BookmarkManager.deleteBookmark(this@BookmarkActivity, bookmark)
                            if (BookmarkManager.getSelectedBookmarkUrl(this@BookmarkActivity) == bookmark.url) {
                                BookmarkManager.saveSelectedBookmarkUrl(this@BookmarkActivity, null)
                            }
                            scope.launch { snackbarHostState.showSnackbar("「${bookmark.name}」を削除しました") }
                            bookmarks = BookmarkManager.getBookmarks(this@BookmarkActivity)
                        },
                        onSelectBookmark = { bookmark ->
                            // Persist selected bookmark and close the screen
                            BookmarkManager.saveSelectedBookmarkUrl(this@BookmarkActivity, bookmark.url)
                            scope.launch { snackbarHostState.showSnackbar("「${bookmark.name}」を選択しました") }
                            finish()
                        }
                    )
                    // Global snackbar host anchored to the bottom of the screen
                    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}
