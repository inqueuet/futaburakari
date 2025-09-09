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
 * ブックマークを管理するアクティビティ（Jetpack Compose ベース）。
 * 追加/更新/削除/選択の操作を提供し、`BookmarkManager` を通じて永続化する。
 */
class BookmarkActivity : BaseActivity() {

    /**
     * テーマ適用済みの Compose コンテンツをセットし、各操作を永続化ロジックに接続する。
     * 各操作時には下部スナックバーでフィードバックを表示する。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            // アプリのテーマ（表現的なカラースキーム）を適用
            FutaburakariTheme(expressive = true) {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                // BookmarkManager のストレージを元にしたUI用のメモリ状態
                var bookmarks by remember { mutableStateOf(BookmarkManager.getBookmarks(this@BookmarkActivity)) }

                Box {
                    BookmarkScreen(
                        title = getString(R.string.bookmark_management_title),
                        bookmarks = bookmarks,
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        onAddBookmark = { name, url ->
                            // 入力検証後に永続化し、UI状態を再読込
                            if (name.isBlank() || url.isBlank()) {
                                scope.launch { snackbarHostState.showSnackbar("名前とURLを入力してください") }
                            } else {
                                BookmarkManager.addBookmark(this@BookmarkActivity, Bookmark(name, url))
                                scope.launch { snackbarHostState.showSnackbar("ブックマークを追加しました") }
                                bookmarks = BookmarkManager.getBookmarks(this@BookmarkActivity)
                            }
                        },
                        onUpdateBookmark = { oldUrl, name, url ->
                            // ストレージ更新。編集中の項目が選択中だった場合は選択URLも更新
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
                            // ストレージから削除。削除対象が選択中なら選択状態をクリア
                            BookmarkManager.deleteBookmark(this@BookmarkActivity, bookmark)
                            if (BookmarkManager.getSelectedBookmarkUrl(this@BookmarkActivity) == bookmark.url) {
                                BookmarkManager.saveSelectedBookmarkUrl(this@BookmarkActivity, null)
                            }
                            scope.launch { snackbarHostState.showSnackbar("「${bookmark.name}」を削除しました") }
                            bookmarks = BookmarkManager.getBookmarks(this@BookmarkActivity)
                        },
                        onSelectBookmark = { bookmark ->
                            // 選択したブックマークを保存して画面を閉じる
                            BookmarkManager.saveSelectedBookmarkUrl(this@BookmarkActivity, bookmark.url)
                            scope.launch { snackbarHostState.showSnackbar("「${bookmark.name}」を選択しました") }
                            finish()
                        }
                    )
                    // 画面下部に固定したスナックバー表示領域
                    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}
