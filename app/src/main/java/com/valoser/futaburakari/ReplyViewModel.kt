package com.valoser.futaburakari

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.zip.CRC32

/**
 * 返信画面の ViewModel。
 *
 * - 最初は最低限のフィールドのみで送信し、失敗した場合に（URL が指定されていれば）
 *   TokenProvider から hidden/token を取得して再送を試みます。
 * - 「操作が早すぎます。あとN秒」を検出した場合は自動的に待機して、その段階内で 1 回だけ再試行します。
 *  （初回送信と再送のそれぞれで一度ずつ自動再試行の可能性があります。）
 * - 全体処理は 10 秒で打ち切ります。トークン取得自体には 5 秒の個別タイムアウトを設けています。
 */
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ReplyViewModel @Inject constructor(
    private val repository: ReplyRepository
) : ViewModel() {

    /**
     * 投稿再送時に必要となる hidden/token を取得するためのプロバイダ（任意）。
     * 指定された場合のみ 2 回目の送信で利用します。
     */
    var tokenProvider: TokenProvider? = null

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    /** 画面の状態を表すシールクラス。 */
    sealed interface UiState {
        data object Idle : UiState
        data object Loading : UiState
        /** 成功時にサーバーから受領したメッセージ（例: 送信完了 No.xxx）。 */
        data class Success(val html: String) : UiState
        /** 失敗時のメッセージ。 */
        data class Error(val message: String) : UiState
    }

    /**
     * 投稿フローを開始します。
     *
     * - まず最低限のフィールドのみで送信。失敗した場合、`postPageUrlForToken` が指定されていれば
     *   TokenProvider でトークンを取得し、不足値を補完して再送します。
     * - 初回・再送の各段階で「早すぎます」エラーを検出したら待機して 1 回だけ自動再試行します。
     * - 全体のタイムアウトは 10 秒。トークン取得は個別に 5 秒でタイムアウトします。
     * - 結果は `uiState` に `Success` または `Error` として反映されます。
     */
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
        postPageUrlForToken: String? // 再送用のトークン取得ページURL（例: https://may.2chan.net/b/futaba.php?mode=post&res=123456&guid=on）
    ) {
        viewModelScope.launch {
            _uiState.postValue(UiState.Loading)

            val result = runCatching {
                withTimeout(10_000L) {  // ✅ 全体タイムアウト 10秒
                    // --- 1回目：ブラウザ準拠の最低限フィールド付きで投稿 ---
                    val firstExtras = mutableMapOf(
                        "responsemode" to "ajax",
                        "baseform" to "",
                        "resto" to resto,
                        "js" to "on"
                    )
                    suspend fun tryFirst() = repository.postReply(
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
                        extra = firstExtras
                    )
                    val first = retryIfTooFast { tryFirst() }
                    if (first.isSuccess) return@withTimeout UiState.Success(first.getOrThrow())

                    // --- 2回目：トークン取得 → 再送 ---
                    if (postPageUrlForToken.isNullOrBlank()) {
                        return@withTimeout UiState.Error(
                            first.exceptionOrNull()?.message.orEmpty()
                                .ifBlank { "投稿に失敗しました" }
                        )
                    }

                    // トークン取得は個別に 5 秒で打ち切り
                    val tokens = withTimeoutOrNull(5_000L) {
                        tokenProvider?.fetchTokens(postPageUrlForToken)?.getOrNull()
                    }
                    if (tokens.isNullOrEmpty()) {
                        return@withTimeout UiState.Error(
                            first.exceptionOrNull()?.message.orEmpty()
                                .ifBlank { "トークン取得に失敗しました" }
                        )
                    }

                    // 欠落キーのログ出力
                    runCatching {
                        val must = listOf("ptua","scsz","hash","MAX_FILE_SIZE","js","chrenc","resto")
                        val missing = must.filter { !tokens.containsKey(it) }
                        Log.d("ReplyVM", "token missing: $missing")
                    }

                    // 不足フィールドを保険で補完
                    Log.d("ReplyVM", "token keys: ${tokens.keys}")
                    val patched = tokens.toMutableMap().apply {
                        put("responsemode", "ajax")
                        putIfAbsent("js", "on")
                        putIfAbsent("resto", resto)
                        putIfAbsent("baseform", "")

                        // scsz（画面サイズ）
                        put("scsz", "1920x1080x24") // ← PC成功ログに合わせて固定
                        //if (!containsKey("scsz")) {
                        //    val dm = android.content.res.Resources.getSystem().displayMetrics
                        //    put("scsz", "${dm.widthPixels}x${dm.heightPixels}x24")
                        //}
                        // ptua（UA の CRC32 で代用）
                        if (!containsKey("ptua")) {
                            put("ptua", crc32(Ua.STRING))
                        }
                        // MAX_FILE_SIZE（取得できない場合の既定）
                        putIfAbsent("MAX_FILE_SIZE", "8192000")
                    }

                    suspend fun trySecond() = repository.postReply(
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
                        extra = patched
                    )
                    val second = retryIfTooFast { trySecond() }
                    if (second.isSuccess) {
                        UiState.Success(second.getOrThrow())
                    } else {
                        UiState.Error(
                            second.exceptionOrNull()?.message.orEmpty()
                                .ifBlank { "投稿に失敗しました(再送)" }
                        )
                    }
                }
            }

            if (result.isSuccess) {
                _uiState.postValue(result.getOrThrow())
            } else {
                val msg = result.exceptionOrNull()?.message
                    ?: "タイムアウトまたは予期しないエラーが発生しました"
                _uiState.postValue(UiState.Error(msg))
            }
        }
    }

    /**
     * 「操作が早すぎます。あとN秒」を検出し、N秒(+1000ms)待って1回だけ再試行する。
     */
    private suspend fun retryIfTooFast(block: suspend () -> Result<String>): Result<String> {
        val first = runCatching { block() }.getOrElse { return Result.failure(it) }
        if (first.isSuccess) return first

        val msg = first.exceptionOrNull()?.message.orEmpty()
        val secRaw = parseRetryAfterSec(msg)
        val waitMs = if (secRaw == null) {
            0L
        } else {
            val s = secRaw.coerceAtLeast(1)
            (s * 1000L) + 1000L
        }
        if (waitMs <= 0L) return first

        delay(waitMs)
        val second = runCatching { block() }.getOrElse { return Result.failure(it) }
        return second
    }

    /** 「あとN秒」を抽出（例:「操作が早すぎます。あと1秒で再送できます」） */
    private fun parseRetryAfterSec(message: String): Int? {
        val re = Regex("""あと\s*(\d+)\s*秒""")
        val m = re.find(message) ?: return null
        return m.groupValues.getOrNull(1)?.toIntOrNull()
    }

    /** UA文字列からCRC32を計算（ptuaの代替） */
    private fun crc32(text: String): String {
        val c = CRC32()
        c.update(text.toByteArray(Charsets.UTF_8))
        return c.value.toString()
    }
}
