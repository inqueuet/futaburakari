package com.valoser.futaburakari

/**
 * ブックマーク管理画面の Activity（Compose ベース）。
 * Hilt 経由で取得した BookmarkViewModel を通じて永続化層へアクセスする。
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
 * 追加/更新/削除/選択の操作を `BookmarkViewModel` に委譲し、その先で `BookmarkManager` へ反映する。
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
                val selectedBookmarkUrl by viewModel.selectedBookmarkUrl.collectAsStateWithLifecycle()

                Box {
                    BookmarkScreen(
                        title = getString(R.string.bookmark_management_title),
                        bookmarks = bookmarks,
                        selectedBookmarkUrl = selectedBookmarkUrl,
                        onBack = { onBackPressedDispatcher.onBackPressed() },
                        onAddBookmark = { name, url ->
                            // 永続化を ViewModel に依頼してから成功メッセージを表示（入力検証はダイアログ側で実施済み）
                            viewModel.addBookmark(Bookmark(name, url))
                            scope.launch { snackbarHostState.showSnackbar("ブックマークを追加しました") }
                        },
                        onUpdateBookmark = { oldUrl, name, url ->
                            // 更新と選択状態の調整を ViewModel へ委譲した後で成功メッセージを表示（入力検証はダイアログ側で実施済み）
                            viewModel.updateBookmark(oldUrl, Bookmark(name, url))
                            scope.launch { snackbarHostState.showSnackbar("ブックマークを更新しました") }
                        },
                        onDeleteBookmark = { bookmark ->
                            // ViewModel に削除と必要な選択解除を任せ、結果をメッセージで通知
                            viewModel.deleteBookmark(bookmark)
                            scope.launch { snackbarHostState.showSnackbar("「${bookmark.name}」を削除しました") }
                        },
                        onSelectBookmark = { bookmark ->
                            // 選択URLを保存し、フィードバック表示を依頼（画面は閉じずにユーザーが戻るボタンで戻れるようにする）
                            viewModel.saveSelectedBookmarkUrl(bookmark.url)
                            scope.launch { snackbarHostState.showSnackbar("「${bookmark.name}」を選択しました") }
                        }
                    )
                    // 画面下部に固定したスナックバー表示領域（Compose の SnackbarHost）
                    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}
