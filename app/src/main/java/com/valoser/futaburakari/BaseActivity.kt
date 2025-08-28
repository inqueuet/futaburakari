package com.valoser.futaburakari

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

open class BaseActivity : AppCompatActivity() {
    private var lastAppliedScale: Float? = null
    private var lastAppliedThemeMode: String? = null
    private var lastAppliedColorMode: String? = null
    override fun attachBaseContext(newBase: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(newBase)
        val scaleStr = prefs.getString("pref_key_font_scale", "1.0") ?: "1.0"
        val scale = scaleStr.toFloatOrNull() ?: 1.0f

        val current = newBase.resources.configuration
        val config = Configuration(current)
        if (config.fontScale != scale) {
            config.fontScale = scale
        }
        val ctx = newBase.createConfigurationContext(config)
        lastAppliedScale = scale
        super.attachBaseContext(ctx)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemePreferences()
        super.onCreate(savedInstanceState)
    }

    private fun applyThemePreferences() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val themeMode = prefs.getString("pref_key_theme_mode", "system")
        when (themeMode) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        val colorMode = prefs.getString("pref_key_color_mode", "green")
        when (colorMode) {
            "purple" -> setTheme(R.style.Theme_Futaburakari_Purple)
            "blue" -> setTheme(R.style.Theme_Futaburakari_Blue)
            "orange" -> setTheme(R.style.Theme_Futaburakari_Orange)
            else -> setTheme(R.style.Theme_Futaburakari_Green)
        }

        lastAppliedThemeMode = themeMode
        lastAppliedColorMode = colorMode
    }

    override fun onResume() {
        super.onResume()
        // If preference changed while this activity was in background, recreate to apply.
        val targetScale = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_font_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        val current = resources.configuration.fontScale
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val themeModeNow = prefs.getString("pref_key_theme_mode", "system")
        val colorModeNow = prefs.getString("pref_key_color_mode", "green")

        val themeChanged = lastAppliedThemeMode != null && lastAppliedThemeMode != themeModeNow
        val colorChanged = lastAppliedColorMode != null && lastAppliedColorMode != colorModeNow

        if (current != targetScale || themeChanged || colorChanged) {
            recreate()
        }
    }
}
