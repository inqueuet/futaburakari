package com.valoser.futaburakari

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * スレ監視キーワード管理画面のための ViewModel。
 *
 * - 共有設定に保存された監視キーワードを読み込み、追加・更新・削除を提供する。
 * - 操作は IO ディスパッチャで実行し、結果に応じて UI state を更新する。
 */
@HiltViewModel
class ThreadWatchManagerViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val entries: List<ThreadWatchEntry> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    )

    private val store by lazy { ThreadWatchStore(context) }

    private val _uiState = MutableStateFlow(UiState(isLoading = true))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refreshEntries()
    }

    /** 監視キーワード一覧を再読込する。 */
    fun refreshEntries() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = runCatching { withContext(Dispatchers.IO) { store.getEntries() } }
            result.fold(
                onSuccess = { entries ->
                    _uiState.update { it.copy(entries = entries, isLoading = false) }
                },
                onFailure = { throwable ->
                    val message = throwable.localizedMessage ?: "キーワードの読み込みに失敗しました"
                    _uiState.update { it.copy(isLoading = false, errorMessage = message) }
                }
            )
        }
    }

    /** 新しいキーワードを追加する。 */
    fun addEntry(keyword: String) {
        launchMutation(
            block = { store.addEntry(keyword) },
            failureMessage = "キーワードの追加に失敗しました",
        )
    }

    /** 既存キーワードを更新する。 */
    fun updateEntry(id: String, keyword: String) {
        launchMutation(
            block = { store.updateEntry(id, keyword) },
            failureMessage = "キーワードの更新に失敗しました",
        )
    }

    /** 指定 ID のキーワードを削除する。 */
    fun deleteEntry(id: String) {
        launchMutation(
            block = { store.removeEntry(id) },
            failureMessage = "キーワードの削除に失敗しました",
        )
    }

    /** 表示済みのエラーを消費する。 */
    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun launchMutation(
        block: suspend () -> Unit,
        failureMessage: String,
    ) {
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            if (result.isSuccess) {
                refreshEntries()
            } else {
                val message = result.exceptionOrNull()?.localizedMessage ?: failureMessage
                _uiState.update { it.copy(errorMessage = message) }
            }
        }
    }
}
