package com.valoser.hutaburakari

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

open class BaseActivity : AppCompatActivity() {
    private var lastAppliedScale: Float? = null
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

    override fun onResume() {
        super.onResume()
        // If preference changed while this activity was in background, recreate to apply.
        val targetScale = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_font_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        val current = resources.configuration.fontScale
        if (current != targetScale) {
            recreate()
        }
    }
}
