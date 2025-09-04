package com.valoser.futaburakari

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.ui.compose.SettingsScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme

/**
 * アプリの設定画面を表示する `Activity`。
 * 保存済みの配色モード（デフォルトは "green"）を読み取り、
 * Compose のテーマに適用した上で設定画面を描画します。
 */
class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SharedPreferences から配色モードを取得（未設定時は "green"）。
        val colorModePref = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")

        setContent {
            // 取得した配色モードをテーマに適用して設定画面を表示。
            FutaburakariTheme(colorMode = colorModePref) {
                // 戻る操作は onBackPressedDispatcher に委譲。
                SettingsScreen(onBack = { onBackPressedDispatcher.onBackPressed() })
            }
        }
    }
}
