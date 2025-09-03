package com.valoser.futaburakari

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.valoser.futaburakari.ui.compose.MediaViewScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

class MediaViewActivity : BaseActivity() {

    private var currentType: String? = null
    private var currentUrl: String? = null
    private var currentText: String? = null // テキスト用（画像メタ/明示テキスト）

    private val networkClient: NetworkClient by lazy {
        EntryPointAccessors.fromApplication(applicationContext, NetworkEntryPoint::class.java).networkClient()
    }

    companion object {
        const val EXTRA_TYPE = "EXTRA_TYPE"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_TEXT = "EXTRA_TEXT" // テキスト表示用
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        const val TYPE_TEXT = "text" // プロンプト表示用
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentType = intent.getStringExtra(EXTRA_TYPE)
        currentUrl = intent.getStringExtra(EXTRA_URL)
        currentText = intent.getStringExtra(EXTRA_TEXT)

        val colorModePref = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")

        setContent {
            FutaburakariTheme(colorMode = colorModePref) {
                val title = when (currentType) {
                    TYPE_IMAGE -> "画像ビューア"
                    TYPE_VIDEO -> "動画ビューア"
                    TYPE_TEXT -> "テキスト"
                    else -> getString(R.string.app_name)
                }
                MediaViewScreen(
                    title = title,
                    type = currentType ?: TYPE_TEXT,
                    url = currentUrl,
                    initialText = currentText,
                    networkClient = networkClient,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onSaveImage = if (currentType == TYPE_IMAGE) ({ saveImage() }) else null,
                    onSaveVideo = if (currentType == TYPE_VIDEO) ({ saveVideo() }) else null
                )
            }
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
        }
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
        }
    }
}
