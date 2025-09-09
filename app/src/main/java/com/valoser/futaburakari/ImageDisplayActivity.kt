package com.valoser.futaburakari

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.preference.PreferenceManager
import com.valoser.futaburakari.ui.compose.ImageDisplayScreen
import com.valoser.futaburakari.ui.theme.FutaburakariTheme

/**
 * 単一画像を Compose で表示するアクティビティ。
 *
 * - 画像URI（`EXTRA_IMAGE_URI`）と補助テキスト（`EXTRA_PROMPT_INFO`）を受け取り表示
 * - 上部UIは Compose 側で提供。戻る操作は `onBackPressedDispatcher` に委譲
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

        // テーマ適用済みのコンテンツを構築（表現的なカラースキーム）

        setContent {
            FutaburakariTheme(expressive = true) {
                // 画像表示用の Compose スクリーンを構築
                ImageDisplayScreen(
                    imageUri = imageUriString,
                    prompt = promptInfo,
                    onBack = { onBackPressedDispatcher.onBackPressed() }
                )
            }
        }
    }
}
