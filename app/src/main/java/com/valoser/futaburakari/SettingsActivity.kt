package com.valoser.futaburakari

import android.os.Bundle
import com.valoser.futaburakari.databinding.ActivitySettingsBinding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Pad toolbar for status bar insets (edge-to-edge)
        run {
            val tb = binding.toolbar
            val origTop = tb.paddingTop
            ViewCompat.setOnApplyWindowInsetsListener(tb) { v, insets ->
                val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                v.setPadding(v.paddingLeft, origTop + top, v.paddingRight, v.paddingBottom)
                WindowInsetsCompat.CONSUMED
            }
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }

        // Pad container for bottom system bars
        val container = binding.settingsContainer
        val orig = container.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, orig + sys.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }
}
