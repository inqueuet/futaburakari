package com.valoser.futaburakari

/**
 * ブックマーク管理画面の Activity（Compose ベース）。
 * 追加・更新・削除・選択の操作を提供し、BookmarkManager を通じて永続化する。
 */

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valoser.futaburakari.ui.compose.BookmarkScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * ブックマークを管理するアクティビティ（Jetpack Compose ベース）。
 * 追加/更新/削除/選択の操作を提供し、`BookmarkManager` を通じて永続化する。
 */
@AndroidEntryPoint
class BookmarkActivity : BaseActivity() {

    private val viewModel: BookmarkViewModel by viewModels()

    /**
     * テーマ適用済みの Compose コンテンツを設定し、各操作を永続化ロジックに接続する。
     * 各操作時にはスナックバーでフィードバックを表示する。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // アプリのテーマ（表現的カラースキーム）を適用
            FutaburakariTheme(expressive = true) {
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()

                Box {
                    BookmarkScreen(
                        title = getString(R.string.bookmark_management_title),
                        bookmarks = bookmarks,
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        onAddBookmark = { name, url ->
                            // 入力検証後に永続化し、UI 状態を再読込
                            if (name.isBlank() || url.isBlank()) {
                                scope.launch { snackbarHostState.showSnackbar("名前とURLを入力してください") }
                            } else {
                                viewModel.addBookmark(Bookmark(name, url))
                                scope.launch { snackbarHostState.showSnackbar("ブックマークを追加しました") }
                            }
                        },
                        onUpdateBookmark = { oldUrl, name, url ->
                            // ストレージ更新。編集中の項目が選択中だった場合は選択 URL も更新
                            if (name.isBlank() || url.isBlank()) {
                                scope.launch { snackbarHostState.showSnackbar("名前とURLを入力してください") }
                            } else {
                                viewModel.updateBookmark(oldUrl, Bookmark(name, url))
                                scope.launch { snackbarHostState.showSnackbar("ブックマークを更新しました") }
                            }
                        },
                        onDeleteBookmark = { bookmark ->
                            // ストレージから削除。削除対象が選択中なら選択状態をクリア
                            viewModel.deleteBookmark(bookmark)
                            scope.launch { snackbarHostState.showSnackbar("「${bookmark.name}」を削除しました") }
                        },
                        onSelectBookmark = { bookmark ->
                            // 選択したブックマークを保存して画面を閉じる
                            viewModel.saveSelectedBookmarkUrl(bookmark.url)
                            scope.launch { snackbarHostState.showSnackbar("「${bookmark.name}」を選択しました") }
                            finish()
                        }
                    )
                    // 画面下部に固定したスナックバー表示領域（Compose の SnackbarHost）
                    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}
