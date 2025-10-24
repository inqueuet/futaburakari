package com.valoser.futaburakari

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.valoser.futaburakari.ui.compose.ThreadWatchManagerScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * スレ監視キーワードを管理する Activity。
 *
 * Compose の `ThreadWatchManagerScreen` をホストし、ViewModel 経由で永続化を操作する。
 */
@AndroidEntryPoint
class ThreadWatchManagerActivity : BaseActivity() {

    private val viewModel: ThreadWatchManagerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FutaburakariTheme(expressive = true) {
                val uiState by viewModel.uiState.collectAsState()
                val context = LocalContext.current

                LaunchedEffect(uiState.errorMessage) {
                    uiState.errorMessage?.let { message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        viewModel.consumeError()
                    }
                }

                ThreadWatchManagerScreen(
                    entries = uiState.entries,
                    isLoading = uiState.isLoading,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onAddKeyword = { keyword -> viewModel.addEntry(keyword) },
                    onUpdateKeyword = { id, keyword -> viewModel.updateEntry(id, keyword) },
                    onDeleteKeyword = { id -> viewModel.deleteEntry(id) },
                )
            }
        }
    }
}
