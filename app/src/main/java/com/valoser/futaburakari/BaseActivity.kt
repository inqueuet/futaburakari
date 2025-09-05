package com.valoser.futaburakari

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager

/**
 * ベースとなる Activity。
 *
 * 機能概要:
 * - フォントサイズ: 共有設定の `pref_key_font_scale` を参照して `attachBaseContext` で反映。
 * - テーマ: ライト/ダーク/システムのモードと、色テーマを起動前に適用。
 * - 変更検知: バックグラウンド中にフォント/テーマ/カラーが変わった場合は復帰時に再生成。
 * - 戻る操作: ActionBar の Up 操作を一貫して「戻る」動作として扱う（アイコン自体の表示/非表示は各画面側で管理）。
 */
open class BaseActivity : AppCompatActivity() {
    /** Last applied font scale. */
    private var lastAppliedScale: Float? = null
    /** Last applied theme mode used to detect changes on resume. */
    private var lastAppliedThemeMode: String? = null
    /** Last applied color mode used to detect changes on resume. */
    private var lastAppliedColorMode: String? = null
    /**
     * 共有設定のフォント倍率を反映した `Configuration` でベースコンテキストをラップする。
     * `pref_key_font_scale`（既定値 "1.0"）を読み取り、`Configuration.fontScale` を設定してからアタッチする。
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

    /** 生成前にテーマ関連の設定を適用し、Edge-to-Edge 表示を有効化する。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemePreferences()
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge with backward-compatible behavior
        enableEdgeToEdge()
    }

    /**
     * 共有設定に基づいてテーマモードと色テーマを適用する。
     * 復帰時の変更検知のため、適用した値を保持する。
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
     * バックグラウンド中にフォント倍率/テーマモード/カラーモードが変更された場合、
     * 復帰時に `recreate()` して最新設定を反映する。
     */
    override fun onResume() {
        super.onResume()
        // Material3 のナビゲーションアイコン/色はテーマに準拠させる（カスタム上書きは行わない）
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

    /** ActionBar の Up ナビゲーションをバックディスパッチャに委譲して戻る動作とする。 */
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    /** ツールバーのホームボタン押下を「戻る」動作として扱う。 */
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            true
        } else super.onOptionsItemSelected(item)
    }
}
