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
                var bookmarks by remember { mutableStateOf(BookmarkManager.getBookmarks(this@BookmarkActivity)) }

                BookmarkScreen(
                    title = getString(R.string.bookmark_management_title),
                    bookmarks = bookmarks,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onAddBookmark = { name, url ->
                        if (name.isBlank() || url.isBlank()) {
                            Toast.makeText(this@BookmarkActivity, "名前とURLを入力してください", Toast.LENGTH_SHORT).show()
                        } else {
                            BookmarkManager.addBookmark(this@BookmarkActivity, Bookmark(name, url))
                            Toast.makeText(this@BookmarkActivity, "ブックマークを追加しました", Toast.LENGTH_SHORT).show()
                            bookmarks = BookmarkManager.getBookmarks(this@BookmarkActivity)
                        }
                    },
                    onUpdateBookmark = { oldUrl, name, url ->
                        if (name.isBlank() || url.isBlank()) {
                            Toast.makeText(this@BookmarkActivity, "名前とURLを入力してください", Toast.LENGTH_SHORT).show()
                        } else {
                            BookmarkManager.updateBookmark(this@BookmarkActivity, oldUrl, Bookmark(name, url))
                            if (BookmarkManager.getSelectedBookmarkUrl(this@BookmarkActivity) == oldUrl) {
                                BookmarkManager.saveSelectedBookmarkUrl(this@BookmarkActivity, url)
                            }
                            Toast.makeText(this@BookmarkActivity, "ブックマークを更新しました", Toast.LENGTH_SHORT).show()
                            bookmarks = BookmarkManager.getBookmarks(this@BookmarkActivity)
                        }
                    },
                    onDeleteBookmark = { bookmark ->
                        BookmarkManager.deleteBookmark(this@BookmarkActivity, bookmark)
                        if (BookmarkManager.getSelectedBookmarkUrl(this@BookmarkActivity) == bookmark.url) {
                            BookmarkManager.saveSelectedBookmarkUrl(this@BookmarkActivity, null)
                        }
                        Toast.makeText(this@BookmarkActivity, "「${bookmark.name}」を削除しました", Toast.LENGTH_SHORT).show()
                        bookmarks = BookmarkManager.getBookmarks(this@BookmarkActivity)
                    },
                    onSelectBookmark = { bookmark ->
                        BookmarkManager.saveSelectedBookmarkUrl(this@BookmarkActivity, bookmark.url)
                        Toast.makeText(this@BookmarkActivity, "「${bookmark.name}」を選択しました", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                )
            }
        }
    }
}
