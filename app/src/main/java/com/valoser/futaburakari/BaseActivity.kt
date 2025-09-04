package com.valoser.futaburakari

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/**
 * Base activity that applies user UI preferences:
 * - Respects font scale from shared preferences via a wrapped base context.
 * - Applies theme mode (light/dark/system) and color theme per user setting.
 * - Recreates on resume if font scale, theme, or color settings changed.
 * Also standardizes the ActionBar "Up" indicator across screens.
 */
open class BaseActivity : AppCompatActivity() {
    /** Last applied font scale. */
    private var lastAppliedScale: Float? = null
    /** Last applied theme mode used to detect changes on resume. */
    private var lastAppliedThemeMode: String? = null
    /** Last applied color mode used to detect changes on resume. */
    private var lastAppliedColorMode: String? = null
    /**
     * Wraps the base context with a configuration reflecting the stored font scale.
     * Reads `pref_key_font_scale` (default "1.0") and sets `Configuration.fontScale` before attaching.
     */
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

    /** Applies theme preferences before standard creation and enables edge-to-edge. */
    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemePreferences()
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge with backward-compatible behavior
        enableEdgeToEdge()
    }

    /**
     * Applies theme mode and color theme based on shared preferences.
     * Persists the "last applied" values for change detection on resume.
     */
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

    /**
     * Standardizes the Up indicator and recreates the activity if preferences changed
     * while in background (font scale, theme mode, or color mode).
     */
    override fun onResume() {
        super.onResume()
        // Unify Up indicator icon across activities that show it
        runCatching { supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_arrow_back) }
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

    /** Handles ActionBar Up navigation by delegating to the back dispatcher. */
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    /** Handles toolbar/home button presses; treats Home as back navigation. */
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            true
        } else super.onOptionsItemSelected(item)
    }
}
