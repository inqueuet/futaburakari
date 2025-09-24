package com.valoser.futaburakari

import android.os.Bundle
import androidx.activity.compose.setContent
import com.valoser.futaburakari.ui.compose.SettingsScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme

/**
 * アプリの設定画面を表示する `Activity`。
 * `FutaburakariTheme(expressive = true)` 上で `SettingsScreen` を描画し、戻る操作は
 * `onBackPressedDispatcher` に委譲する。
 */
class SettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FutaburakariTheme(expressive = true) {
                // 戻る操作は onBackPressedDispatcher に委譲。
                SettingsScreen(onBack = { onBackPressedDispatcher.onBackPressed() })
            }
        }
    }
}
