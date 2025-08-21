package com.example.hutaburakari

import android.content.Context
import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * 返信画面の ViewModel。
 * 1回目は OkHttp 単独で送信 → 失敗し、JS必須っぽい場合のみ TokenProvider で hidden/token を取得して再送する。
 */
class ReplyViewModel(
    private val repository: ReplyRepository = ReplyRepository()
) : ViewModel() {

    // Activity/Fragment 側でセットする（不可視WebViewワーカー）
    var tokenProvider: TokenProvider? = null

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    sealed interface UiState {
        data object Idle : UiState
        data object Loading : UiState
        data class Success(val html: String) : UiState
        data class Error(val message: String) : UiState
    }

    fun submit(
        context: Context,
        boardUrl: String,
        resto: String,
        name: String?,
        email: String?,
        sub: String?,
        com: String,
        inputPwd: String?,
        upfileUri: Uri?,
        textOnly: Boolean,
        postPageUrlForToken: String? // 例: https://may.2chan.net/27/futaba.php?mode=post&res=323716
    ) {
        viewModelScope.launch {
            _uiState.postValue(UiState.Loading)

            // 1回目：extra なしで投稿
            val first = repository.postReply(
                boardUrl = boardUrl,
                resto = resto,
                name = name,
                email = email,
                sub = sub,
                com = com,
                inputPwd = inputPwd,
                upfileUri = upfileUri,
                textOnly = textOnly,
                context = context,
                extra = emptyMap()
            )

            if (first.isSuccess) {
                _uiState.postValue(UiState.Success(first.getOrThrow()))
                return@launch
            }

            val firstMsg = first.exceptionOrNull()?.message.orEmpty()
            if (!looksLikeJsRequired(firstMsg) || postPageUrlForToken.isNullOrBlank()) {
                _uiState.postValue(UiState.Error(firstMsg.ifBlank { "投稿に失敗しました" }))
                return@launch
            }

            // 2回目：トークンを取得して再送
            val tokens = tokenProvider?.fetchTokens(postPageUrlForToken)?.getOrNull()
            if (tokens.isNullOrEmpty()) {
                _uiState.postValue(UiState.Error(firstMsg.ifBlank { "トークン取得に失敗しました" }))
                return@launch
            }

            val second = repository.postReply(
                boardUrl = boardUrl,
                resto = resto,
                name = name,
                email = email,
                sub = sub,
                com = com,
                inputPwd = inputPwd,
                upfileUri = upfileUri,
                textOnly = textOnly,
                context = context,
                extra = tokens
            )

            if (second.isSuccess) {
                _uiState.postValue(UiState.Success(second.getOrThrow()))
            } else {
                _uiState.postValue(
                    UiState.Error(
                        second.exceptionOrNull()?.message.orEmpty().ifBlank { "投稿に失敗しました(再送)" }
                    )
                )
            }
        }
    }

    private fun looksLikeJsRequired(message: String): Boolean {
        val lowered = message.lowercase()
        return listOf("hash", "js", "token", "不正", "拒否", "連投", "本文なし").any { lowered.contains(it) }
    }
}