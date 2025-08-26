package com.valoser.futaburakari

import android.os.Bundle
// import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings) // ★ 次のステップで作成

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        // アクションバーに戻るボタンを表示
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    // 戻るボタンが押されたときの処理
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
