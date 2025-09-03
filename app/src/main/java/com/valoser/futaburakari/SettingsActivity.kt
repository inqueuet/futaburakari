package com.valoser.futaburakari

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.ui.compose.SettingsScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme

class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val colorModePref = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")

        setContent {
            FutaburakariTheme(colorMode = colorModePref) {
                SettingsScreen(onBack = { onBackPressedDispatcher.onBackPressed() })
            }
        }
    }
}
