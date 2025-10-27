package com.valoser.futaburakari

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import com.valoser.futaburakari.ui.compose.ReplyScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import dagger.hilt.android.AndroidEntryPoint
import java.nio.charset.Charset

/**
 * Compose ベースの返信画面。
 * 不可視の ReplyTokenWorkerFragment を添付して ViewModel の tokenProvider として紐付ける。
 * 設定画面で保存した削除キー（パスワード）を初期値に適用し、入力が空なら送信時のフォールバックにも使う。
 */
@AndroidEntryPoint
class ReplyActivity : BaseActivity() {

    companion object {
        // DetailActivity から渡されるキー（DetailActivity 側の参照に合わせる）
        const val EXTRA_THREAD_ID = "extra_thread_id"       // スレ番号（resto）
        const val EXTRA_THREAD_TITLE = "extra_thread_title" // 画面表示用タイトル
        const val EXTRA_BOARD_URL = "extra_board_url"       // 例: https://may.2chan.net/27/futaba.php
        const val EXTRA_QUOTE_TEXT = "extra_quote_text"     // 引用本文（必要なら本文に差し込むなど）
    }

    private val viewModel: ReplyViewModel by viewModels()
    private var pickedUri: Uri? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // ---- Intent パラメータ（DetailActivity から渡される）----
        val threadId = intent.getStringExtra(EXTRA_THREAD_ID) ?: ""
        val threadTitle = intent.getStringExtra(EXTRA_THREAD_TITLE) ?: ""
        val boardUrl = intent.getStringExtra(EXTRA_BOARD_URL) ?: "" // .../futaba.php
        val quote = intent.getStringExtra(EXTRA_QUOTE_TEXT).orEmpty()

        // 設定画面で保存している削除パスワードを取得
        val savedPwd = AppPreferences.getPwd(this)

        val initialDraft = AppPreferences.getReplyDraft(this, boardUrl, threadId)
        val initialComment = initialDraft?.takeUnless { it.isBlank() } ?: quote


        setContent {
            FutaburakariTheme(expressive = true) {
                val uiState by viewModel.uiState.observeAsState(ReplyViewModel.UiState.Idle)
                ReplyScreen(
                    title = threadTitle,
                    initialQuote = initialComment,
                    initialPassword = savedPwd,
                    uiState = uiState,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onCommentChange = { comment ->
                        AppPreferences.saveReplyDraft(this, boardUrl, threadId, comment)
                    },
                    onSubmit = { name, email, sub, com, pwd, upfile, textOnly ->
                        val comment = sanitizeComment(com)
                        if (boardUrl.isBlank() || threadId.isBlank()) {
                            Toast.makeText(this, "投稿先URLが不正です", Toast.LENGTH_SHORT).show()
                            return@ReplyScreen
                        }
                        if (comment.isBlank() && (textOnly || upfile == null)) {
                            Toast.makeText(this, "本文が空です", Toast.LENGTH_SHORT).show()
                            return@ReplyScreen
                        }
                        pickedUri = upfile
                        val postPageUrl = "$boardUrl?mode=post&res=$threadId"
                        viewModel.submit(
                            context = this,
                            boardUrl = boardUrl,
                            resto = threadId,
                            name = name,
                            email = email,
                            sub = sub,
                            com = comment,
                            inputPwd = pwd ?: AppPreferences.getPwd(this),
                            upfileUri = if (textOnly) null else upfile,
                            textOnly = textOnly,
                            postPageUrlForToken = postPageUrl
                        )
                    }
                )
            }
        }

        // 不可視 WebView ワーカーを Compose 設置後にアタッチして TokenProvider をセット
        val tag = "reply_token_worker"
        val worker = supportFragmentManager.findFragmentByTag(tag) as? ReplyTokenWorkerFragment
            ?: ReplyTokenWorkerFragment().also {
                supportFragmentManager
                    .beginTransaction()
                    .add(android.R.id.content, it, tag)
                    .commitNow()
            }
        viewModel.tokenProvider = worker

        // Success/Error ハンドリングはActivity側で継続
        viewModel.uiState.observe(this) { st ->
            when (st) {
                is ReplyViewModel.UiState.Success -> {
                    Toast.makeText(this, "投稿に成功しました", Toast.LENGTH_SHORT).show()
                    AppPreferences.clearReplyDraft(this, boardUrl, threadId)
                    // レス番号を抽出して返す（例: "送信完了 No.12345"）
                    val resNumber = Regex("""No\.(\d+)""").find(st.html)?.groupValues?.getOrNull(1)
                    val resultIntent = Intent().apply {
                        if (!resNumber.isNullOrBlank()) {
                            putExtra("RES_NUMBER", resNumber)
                        }
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
                is ReplyViewModel.UiState.Error -> {
                    Toast.makeText(this, st.message, Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
        }
    }

    /**
     * 入力本文を送信用に正規化する。
     * - 改行コード（CR/LF）や不可視文字を統一/除去
     * - Shift_JIS にエンコードできない文字を HTML 数値文字参照に変換
     */
    private fun sanitizeComment(text: String): String {
        // 1. 既存の正規化処理を先に実行
        val normalizedText = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("[\\u2028\\u2029]"), "\n")
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

        // 2. Shift_JISでエンコードできない文字は数値文字参照へフォールバック
        val encoder = Charset.forName("Shift_JIS").newEncoder()
        val builder = StringBuilder(normalizedText.length)
        var index = 0

        while (index < normalizedText.length) {
            val codePoint = normalizedText.codePointAt(index)
            val chars = Character.toChars(codePoint)
            val encodable = try {
                encoder.canEncode(String(chars))
            } finally {
                encoder.reset()
            }

            if (encodable) {
                builder.append(chars)
            } else {
                // Shift_JISに無い文字（例: 絵文字）は数値文字参照にして送信
                builder.append("&#").append(codePoint).append(';')
            }

            index += chars.size
        }
        return builder.toString()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
