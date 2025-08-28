package com.valoser.futaburakari

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
// import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat // Added import
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.os.Build
import coil.load
import com.valoser.futaburakari.databinding.ActivityMediaViewBinding // ViewBindingを生成後にインポート
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaViewActivity : BaseActivity() {

    private lateinit var binding: ActivityMediaViewBinding
    private var exoPlayer: ExoPlayer? = null

    private var currentType: String? = null
    private var currentUrl: String? = null
    private var currentText: String? = null // プロンプトやテキスト用
    private var currentPrompt: String? = null // 念のためプロンプトも保持

    private val networkClient: NetworkClient by lazy {
        EntryPointAccessors.fromApplication(applicationContext, NetworkEntryPoint::class.java).networkClient()
    }

    companion object {
        const val EXTRA_TYPE = "EXTRA_TYPE"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_TEXT = "EXTRA_TEXT" // プロンプトや一般的なテキスト表示用
        const val EXTRA_PROMPT = "EXTRA_PROMPT" // DetailAdapterからのプロンプト専用 (オプション)
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        const val TYPE_TEXT = "text" // プロンプト表示用
    }

    // ActivityResultLauncher for creating a text file
    private val createTextFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri?.let {
            // Handle the URI for the created file (e.g., write text to it)
            val textToSave = currentText ?: currentPrompt ?: ""
            if (textToSave.isNotEmpty()) {
                try {
                    contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(textToSave.toByteArray())
                        Toast.makeText(this, "テキストを保存しました", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "テキストの保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "保存するテキストがありません", Toast.LENGTH_SHORT).show()
            }
        }
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
                binding.scrollContainer.isVisible = false
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
                supportActionBar?.title = "画像ビューア"
                currentText?.let { supportActionBar?.subtitle = it }
            }
            TYPE_VIDEO -> {
                binding.scrollContainer.isVisible = false
                binding.imageView.isVisible = false
                binding.playerView.isVisible = true
                binding.textView.isVisible = false
                currentUrl?.let { initializePlayer(it) }
                supportActionBar?.title = "動画ビューア"
                currentText?.let { supportActionBar?.subtitle = it }
            }
            TYPE_TEXT -> {
                binding.scrollContainer.isVisible = true
                binding.imageView.isVisible = false
                binding.playerView.isVisible = false
                binding.textView.isVisible = true
                binding.textView.text = currentText ?: currentPrompt ?: "テキストがありません"
                supportActionBar?.title = "テキストビューア"
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
        menuInflater.inflate(R.menu.media_view_menu, menu) // Ensure this is media_view_menu.xml
        when (currentType) {
            TYPE_TEXT -> {
                menu.findItem(R.id.action_copy_text)?.isVisible = true
                menu.findItem(R.id.action_save_text)?.isVisible = true
                menu.findItem(R.id.action_save_media)?.isVisible = false
            }
            TYPE_IMAGE, TYPE_VIDEO -> {
                // copy_text can be visible if there's a prompt/text associated with image/video
                val hasTextContent = !(currentText.isNullOrEmpty() && currentPrompt.isNullOrEmpty())
                menu.findItem(R.id.action_copy_text)?.isVisible = hasTextContent
                menu.findItem(R.id.action_save_text)?.isVisible = false // Text save only for TYPE_TEXT
                menu.findItem(R.id.action_save_media)?.isVisible = true
            }
            else -> { // Should not happen if type is validated in onCreate
                menu.findItem(R.id.action_copy_text)?.isVisible = false
                menu.findItem(R.id.action_save_text)?.isVisible = false
                menu.findItem(R.id.action_save_media)?.isVisible = false
            }
        }
        return true
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_copy_text -> {
                copyTextToClipboard()
                true
            }
            R.id.action_save_text -> {
                val defaultName = buildDefaultFileName()
                // Ensure currentText or currentPrompt is not null before launching
                if (currentText != null || currentPrompt != null) {
                    createTextFileLauncher.launch("$defaultName.txt")
                } else {
                    Toast.makeText(this, "保存するテキストがありません", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_save_media -> {
                when (currentType) {
                    TYPE_IMAGE -> saveImage()
                    TYPE_VIDEO -> saveVideo()
                    else -> Toast.makeText(this, "このタイプのメディアは保存できません", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun copyTextToClipboard() {
        val textToCopy = currentText ?: currentPrompt
        if (!textToCopy.isNullOrEmpty()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Copied Content", textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "テキストをコピーしました", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "コピーするテキストがありません", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImage() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    2001
                )
                return
            }
        }
        currentUrl?.let { url ->
            lifecycleScope.launch {
                MediaSaver.saveImage(this@MediaViewActivity, url, networkClient)
            }
        } ?: Toast.makeText(this, "画像URLがありません", Toast.LENGTH_SHORT).show()
    }

    private fun saveVideo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    2001
                )
                return
            }
        }
        currentUrl?.let { url ->
            lifecycleScope.launch {
                MediaSaver.saveVideo(this@MediaViewActivity, url, networkClient)
            }
        } ?: Toast.makeText(this, "動画URLがありません", Toast.LENGTH_SHORT).show()
    }


    private fun buildDefaultFileName(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        // You can use a portion of the text content if available, sanitized for filename
        val textHint = (currentText ?: currentPrompt)?.take(15)?.replace(Regex("[^a-zA-Z0-9_]"), "_") ?: "text"
        return "${textHint}_$timestamp"
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
