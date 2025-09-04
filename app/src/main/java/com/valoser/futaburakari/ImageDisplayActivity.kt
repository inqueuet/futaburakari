package com.valoser.futaburakari

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.ui.compose.ImageDisplayScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme

/**
 * 単一画像をComposeで表示するアクティビティ。
 *
 * - 呼び出し元から受け取った画像URIと補助テキスト（プロンプト情報）を表示
 * - テーマカラーはユーザー設定（`pref_key_color_mode`）を反映
 */
class ImageDisplayActivity : BaseActivity() {

    companion object {
        // 表示する画像のURI（String）
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        // 画像に付随する説明/プロンプトなどの文字列（任意）
        const val EXTRA_PROMPT_INFO = "extra_prompt_info"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // インテントから画像URIと補助テキストを取得
        val imageUriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        val promptInfo = intent.getStringExtra(EXTRA_PROMPT_INFO)

        // 配色モード（テーマカラー）設定を取得
        val colorModePref = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_color_mode", "green")

        setContent {
            FutaburakariTheme(colorMode = colorModePref) {
                // 画像表示用のComposeスクリーンを構築
                ImageDisplayScreen(
                    imageUri = imageUriString,
                    prompt = promptInfo,
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}
