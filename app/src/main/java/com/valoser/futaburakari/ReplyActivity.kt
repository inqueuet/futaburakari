package com.valoser.futaburakari

import android.content.Intent
import android.net.Uri
import java.nio.charset.Charset
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.preference.PreferenceManager
import dagger.hilt.android.AndroidEntryPoint
import com.valoser.futaburakari.ui.compose.ReplyScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState

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

        // 不可視 WebView ワーカーをアタッチして TokenProvider をセット
        val tag = "reply_token_worker"
        val worker = supportFragmentManager.findFragmentByTag(tag) as? ReplyTokenWorkerFragment
            ?: ReplyTokenWorkerFragment().also {
                supportFragmentManager
                    .beginTransaction()
                    .add(android.R.id.content, it, tag)
                    .commitNow()
            }
        viewModel.tokenProvider = worker

        // ---- Intent パラメータ（DetailActivity から渡される）----
        val threadId = intent.getStringExtra(EXTRA_THREAD_ID) ?: ""
        val threadTitle = intent.getStringExtra(EXTRA_THREAD_TITLE) ?: ""
        val boardUrl = intent.getStringExtra(EXTRA_BOARD_URL) ?: "" // .../futaba.php
        val quote = intent.getStringExtra(EXTRA_QUOTE_TEXT).orEmpty()

        // 設定画面で保存している削除パスワードを取得
        val savedPwd = AppPreferences.getPwd(this)


        setContent {
            FutaburakariTheme(expressive = true) {
                val uiState by viewModel.uiState.observeAsState(ReplyViewModel.UiState.Idle)
                ReplyScreen(
                    title = threadTitle,
                    initialQuote = quote,
                    initialPassword = savedPwd,
                    uiState = uiState,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
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

        // Success/Error ハンドリングはActivity側で継続
        viewModel.uiState.observe(this) { st ->
            when (st) {
                is ReplyViewModel.UiState.Success -> {
                    Toast.makeText(this, "投稿に成功しました", Toast.LENGTH_SHORT).show()
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
     * - Shift_JIS にエンコードできない文字を '?' に置換
     */
    private fun sanitizeComment(text: String): String {
        // 1. 既存の正規化処理を先に実行
        val normalizedText = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("[\\u2028\\u2029]"), "\n")
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), "")

        // 2. Shift_JISでエンコードできない文字をチェックして '?' に置き換える
        val sjisCharset = Charset.forName("Shift_JIS")
        val encoder = sjisCharset.newEncoder()
        val builder = StringBuilder(normalizedText.length)

        for (char in normalizedText) {
            if (encoder.canEncode(char)) {
                // エンコード可能な文字はそのまま追加
                builder.append(char)
            } else {
                // エンコード不能な文字は '?' に置き換える（または除去も可能）
                builder.append('?')
            }
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
