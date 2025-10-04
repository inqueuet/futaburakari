package com.valoser.futaburakari

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NG ルール管理画面のための ViewModel。
 *
 * - 初期表示や再読込時に NG ルールのクリーンアップと取得を行い、結果に応じて state を更新。
 * - 追加/更新/削除などの操作も IO で実行し、完了後に一覧を再読込。
 * - エラーは UI 側で一度だけ表示できるよう state に保持し、`consumeError()` で消費する。
 */
@HiltViewModel
class NgManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class UiState(
        val rules: List<NgRule> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
    )

    private val store by lazy { NgStore(context) }

    private val _uiState = MutableStateFlow(UiState(isLoading = true))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null

    init {
        refreshRules()
    }

    fun refreshRules() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = runCatching { store.cleanupAndGetRules() }
            result.fold(
                onSuccess = { rules ->
                    _uiState.update { it.copy(rules = rules, isLoading = false) }
                },
                onFailure = { throwable ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = throwable.localizedMessage
                                ?: "NGルールの読み込みに失敗しました",
                        )
                    }
                },
            )
        }
    }

    fun addRule(type: RuleType, pattern: String, match: MatchType?) {
        launchMutation(
            block = { store.addRule(type, pattern, match) },
            failureMessage = "NGルールの追加に失敗しました",
        )
    }

    fun updateRule(ruleId: String, pattern: String, match: MatchType?) {
        launchMutation(
            block = { store.updateRule(ruleId, pattern, match) },
            failureMessage = "NGルールの更新に失敗しました",
        )
    }

    fun deleteRule(ruleId: String) {
        launchMutation(
            block = { store.removeRule(ruleId) },
            failureMessage = "NGルールの削除に失敗しました",
        )
    }

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
                refreshRules()
            } else {
                val message = result.exceptionOrNull()?.localizedMessage ?: failureMessage
                _uiState.update { it.copy(errorMessage = message) }
            }
        }
    }
}
