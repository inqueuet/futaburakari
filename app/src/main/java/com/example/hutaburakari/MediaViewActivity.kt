package com.example.hutaburakari

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat // Added import
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.load
import com.example.hutaburakari.databinding.ActivityMediaViewBinding // ViewBindingを生成後にインポート
import kotlinx.coroutines.launch

class MediaViewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMediaViewBinding
    private var exoPlayer: ExoPlayer? = null

    private var currentType: String? = null
    private var currentUrl: String? = null
    private var currentText: String? = null // プロンプトやテキスト用
    private var currentPrompt: String? = null // 念のためプロンプトも保持

    companion object {
        const val EXTRA_TYPE = "EXTRA_TYPE"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_TEXT = "EXTRA_TEXT" // プロンプトや一般的なテキスト表示用
        const val EXTRA_PROMPT = "EXTRA_PROMPT" // DetailAdapterからのプロンプト専用 (オプション)
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        const val TYPE_TEXT = "text" // プロンプト表示用
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false) // Added for edge-to-edge
        binding = ActivityMediaViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        currentType = intent.getStringExtra(EXTRA_TYPE)
        currentUrl = intent.getStringExtra(EXTRA_URL)
        currentText = intent.getStringExtra(EXTRA_TEXT) // プロンプトはこちらで受け取ることを想定
        currentPrompt = intent.getStringExtra(EXTRA_PROMPT) // DetailAdapterからはこちらで渡される可能性も考慮

        when (currentType) {
            TYPE_IMAGE -> {
                binding.scrollContainer.isVisible = false // Added
                binding.imageView.isVisible = true
                binding.playerView.isVisible = false
                binding.textView.isVisible = false
                currentUrl?.let {
                    binding.imageView.load(it) {
                        crossfade(true)
                        placeholder(R.drawable.ic_launcher_background)
                        error(android.R.drawable.ic_dialog_alert)
                    }
                }
                // 画像の場合はプロンプト(currentText または currentPrompt)をToolbarのSubtitleなどに表示することも検討
                supportActionBar?.title = "画像ビューア"
                currentText?.let { supportActionBar?.subtitle = it }
            }
            TYPE_VIDEO -> {
                binding.scrollContainer.isVisible = false // Added
                binding.imageView.isVisible = false
                binding.playerView.isVisible = true
                binding.textView.isVisible = false
                currentUrl?.let { initializePlayer(it) }
                supportActionBar?.title = "動画ビューア"
                currentText?.let { supportActionBar?.subtitle = it }
            }
            TYPE_TEXT -> { // プロンプト表示用
                binding.scrollContainer.isVisible = true // Added
                binding.imageView.isVisible = false
                binding.playerView.isVisible = false
                binding.textView.isVisible = true
                binding.textView.text = currentText ?: currentPrompt ?: "テキストがありません"
                supportActionBar?.title = "テキストビューア"
                // テキストの場合はスクロール可能にするためにScrollViewで囲むことを検討 (レイアウト側で)
            }
            else -> {
                Toast.makeText(this, "不明なメディアタイプです", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun initializePlayer(videoUrl: String) {
        exoPlayer = ExoPlayer.Builder(this).build().also { player ->
            binding.playerView.player = player
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
            player.setMediaItem(mediaItem)
            player.playWhenReady = true // 自動再生
            player.prepare()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.media_view_menu, menu) // メニューファイルを作成する必要あり
        // タイプによってメニュー項目を制御する場合はここで
        menu.findItem(R.id.action_save_media)?.isVisible = (currentType == TYPE_IMAGE || currentType == TYPE_VIDEO)
        menu.findItem(R.id.action_copy_text)?.isVisible = (currentType == TYPE_TEXT || currentPrompt != null || currentText != null)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_copy_text -> {
                val textToCopy = currentText ?: currentPrompt
                if (!textToCopy.isNullOrEmpty()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Content", textToCopy)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "テキストをコピーしました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "コピーするテキストがありません", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_save_media -> {
                when (currentType) {
                    TYPE_IMAGE -> {
                        currentUrl?.let { url ->
                            lifecycleScope.launch {
                                MediaSaver.saveImage(this@MediaViewActivity, url)
                            }
                        }
                    }
                    TYPE_VIDEO -> {
                        currentUrl?.let { url ->
                            lifecycleScope.launch {
                                MediaSaver.saveVideo(this@MediaViewActivity, url)
                            }
                        }
                    }
                    else -> Toast.makeText(this, "このメディアは保存できません", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onPause() {
        super.onPause()
        if (currentType == TYPE_VIDEO) {
            exoPlayer?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (currentType == TYPE_VIDEO) {
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        exoPlayer?.let { player ->
            player.release()
            exoPlayer = null
            binding.playerView.player = null
        }
    }
}