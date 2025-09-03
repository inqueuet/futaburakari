package com.valoser.futaburakari

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.ui.compose.ImageDisplayScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme

class ImageDisplayActivity : BaseActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_PROMPT_INFO = "extra_prompt_info"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imageUriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        val promptInfo = intent.getStringExtra(EXTRA_PROMPT_INFO)

        val colorModePref = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")

        setContent {
            FutaburakariTheme(colorMode = colorModePref) {
                ImageDisplayScreen(
                    imageUri = imageUriString,
                    prompt = promptInfo,
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}
