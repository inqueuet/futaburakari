package com.example.hutaburakari

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.hutaburakari.databinding.ActivityReplyNativeBinding

/**
 * 完全ネイティブUIの返信画面。
 * 不可視WebViewワーカー（ReplyTokenWorkerFragment）を起動し、ViewModel の tokenProvider として渡す。
 * 設定画面で保存した削除キー（パスワード）を起動時に自動セットし、送信時に使用する。
 */
class ReplyActivity : AppCompatActivity() {

    companion object {
        // DetailActivity から渡されるキー（DetailActivity 側の参照に合わせる）
        const val EXTRA_THREAD_ID = "extra_thread_id"       // スレ番号（resto）
        const val EXTRA_THREAD_TITLE = "extra_thread_title" // 画面表示用タイトル
        const val EXTRA_BOARD_URL = "extra_board_url"       // 例: https://may.2chan.net/27/futaba.php
        const val EXTRA_QUOTE_TEXT = "extra_quote_text"     // 引用本文（必要なら本文に差し込むなど）
    }

    private lateinit var binding: ActivityReplyNativeBinding
    private val viewModel: ReplyViewModel by viewModels()

    private var pickedUri: Uri? = null

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pickedUri = uri
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            binding.textPickedFile.text = uri.lastPathSegment ?: uri.toString()
            binding.checkTextOnly.isChecked = false
        } else {
            binding.textPickedFile.text = "ファイルが選択されていません"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReplyNativeBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        // Toolbar タイトルに表示（layout に textThreadTitle は無い設計）
        supportActionBar?.title = if (threadTitle.isNotBlank()) threadTitle else "レスを投稿"

        // コメント欄に引用を初期挿入（必要な場合）
        if (quote.isNotBlank()) {
            val current = binding.inputComment.text?.toString().orEmpty()
            val inserted = if (current.isBlank()) quote else current + "\n" + quote
            binding.inputComment.setText(inserted)
            binding.inputComment.setSelection(binding.inputComment.text?.length ?: 0)
        }

        // ▼ 設定で保存した削除キー（パスワード）を自動セット
        val savedPwd = AppPreferences.getPwd(this)
        if (!savedPwd.isNullOrBlank()) {
            binding.inputPassword.setText(savedPwd)
        }

        binding.buttonPickFile.setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }

        binding.buttonSubmit.setOnClickListener {
            val name = binding.inputName.text?.toString()
            val email = binding.inputEmail.text?.toString()
            val sub = binding.inputSub.text?.toString()
            val com = binding.inputComment.text?.toString().orEmpty()

            // 入力欄に値があればそれを優先、空なら保存済みを再適用
            val inputPwdField = binding.inputPassword.text?.toString()
            val effectivePwd = if (inputPwdField.isNullOrBlank()) {
                AppPreferences.getPwd(this)
            } else {
                inputPwdField
            }

            val textOnly = binding.checkTextOnly.isChecked

            if (boardUrl.isBlank() || threadId.isBlank()) {
                Toast.makeText(this, "投稿先URLが不正です", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (com.isBlank() && (textOnly || pickedUri == null)) {
                Toast.makeText(this, "本文が空です", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // トークン取得用の投稿ページURLを組み立て（.../futaba.php?mode=post&res=xxx）
            val postPageUrl = "$boardUrl?mode=post&res=$threadId"

            viewModel.submit(
                context = this,
                boardUrl = "$boardUrl?guid=on", // 送信先（boardの futaba.php に guid=on を付与）
                resto = threadId,
                name = name,
                email = email,
                sub = sub,
                com = com,
                inputPwd = effectivePwd,
                upfileUri = if (textOnly) null else pickedUri,
                textOnly = textOnly,
                postPageUrlForToken = postPageUrl
            )
        }

        viewModel.uiState.observe(this) { st ->
            when (st) {
                is ReplyViewModel.UiState.Idle -> {
                    binding.progressBar.visibility = android.view.View.GONE
                }
                is ReplyViewModel.UiState.Loading -> {
                    binding.progressBar.visibility = android.view.View.VISIBLE
                }
                is ReplyViewModel.UiState.Success -> {
                    binding.progressBar.visibility = android.view.View.GONE
                    Toast.makeText(this, "投稿に成功しました", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
                is ReplyViewModel.UiState.Error -> {
                    binding.progressBar.visibility = android.view.View.GONE
                    Toast.makeText(this, st.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}