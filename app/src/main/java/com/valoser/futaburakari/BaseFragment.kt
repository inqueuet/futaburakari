package com.valoser.futaburakari

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager

open class BaseFragment : Fragment() {
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val original = super.onGetLayoutInflater(savedInstanceState)
        val base = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(base)
        val scale = prefs.getString("pref_key_font_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        val cfg = Configuration(base.resources.configuration).apply { fontScale = scale }
        val configCtx: Context = base.createConfigurationContext(cfg)
        val themedCtx = ContextThemeWrapper(configCtx, R.style.Theme_Futaburakari)
        return original.cloneInContext(themedCtx)
    }
}
