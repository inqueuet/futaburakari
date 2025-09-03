package com.valoser.futaburakari

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.ui.compose.BookmarkScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme

class BookmarkActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val colorModePref = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")

        setContent {
            FutaburakariTheme(colorMode = colorModePref) {
                var bookmarks by remember { mutableStateOf(BookmarkManager.getBookmarks(this)) }

                BookmarkScreen(
                    title = getString(R.string.bookmark_management_title),
                    bookmarks = bookmarks,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onAddBookmark = { name, url ->
                        if (name.isBlank() || url.isBlank()) {
                            Toast.makeText(this, "名前とURLを入力してください", Toast.LENGTH_SHORT).show()
                        } else {
                            BookmarkManager.addBookmark(this, Bookmark(name, url))
                            Toast.makeText(this, "ブックマークを追加しました", Toast.LENGTH_SHORT).show()
                            bookmarks = BookmarkManager.getBookmarks(this)
                        }
                    },
                    onUpdateBookmark = { oldUrl, name, url ->
                        if (name.isBlank() || url.isBlank()) {
                            Toast.makeText(this, "名前とURLを入力してください", Toast.LENGTH_SHORT).show()
                        } else {
                            BookmarkManager.updateBookmark(this, oldUrl, Bookmark(name, url))
                            if (BookmarkManager.getSelectedBookmarkUrl(this) == oldUrl) {
                                BookmarkManager.saveSelectedBookmarkUrl(this, url)
                            }
                            Toast.makeText(this, "ブックマークを更新しました", Toast.LENGTH_SHORT).show()
                            bookmarks = BookmarkManager.getBookmarks(this)
                        }
                    },
                    onDeleteBookmark = { bookmark ->
                        BookmarkManager.deleteBookmark(this, bookmark)
                        if (BookmarkManager.getSelectedBookmarkUrl(this) == bookmark.url) {
                            BookmarkManager.saveSelectedBookmarkUrl(this, null)
                        }
                        Toast.makeText(this, "「${bookmark.name}」を削除しました", Toast.LENGTH_SHORT).show()
                        bookmarks = BookmarkManager.getBookmarks(this)
                    },
                    onSelectBookmark = { bookmark ->
                        BookmarkManager.saveSelectedBookmarkUrl(this, bookmark.url)
                        Toast.makeText(this, "「${bookmark.name}」を選択しました", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
        }
    }
}
