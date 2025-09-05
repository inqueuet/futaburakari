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
 * - テーマ: ライト/ダーク/システムのモードを起動前に適用（色テーマは固定）。
 * - 変更検知: バックグラウンド中にフォント/テーマが変わった場合は復帰時に再生成。
 * - 戻る操作: ActionBar の Up 操作を一貫して「戻る」動作として扱う（アイコン自体の表示/非表示は各画面側で管理）。
 */
open class BaseActivity : AppCompatActivity() {
    /** 最後に適用したフォント倍率。 */
    private var lastAppliedScale: Float? = null
    /** 復帰時の変更検知に使用する、最後に適用したテーマモード。 */
    private var lastAppliedThemeMode: String? = null
    // メモ: カラーモードの個別追跡は不要（現状はテーマに準拠）
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
     * 共有設定に基づいてテーマモードを適用する。
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

        lastAppliedThemeMode = themeMode
    }

    /**
     * バックグラウンド中にフォント倍率/テーマモードが変更された場合、
     * 復帰時に `recreate()` して最新設定を反映する。
     */
    override fun onResume() {
        super.onResume()
        // Material3 のナビゲーションアイコン/色はテーマに準拠させる（カスタム上書きは行わない）
        // バックグラウンド中に設定が変更されていた場合は再生成して反映する
        val targetScale = PreferenceManager.getDefaultSharedPreferences(this)
            .getString("pref_key_font_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        val current = resources.configuration.fontScale
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val themeModeNow = prefs.getString("pref_key_theme_mode", "system")

        val themeChanged = lastAppliedThemeMode != null && lastAppliedThemeMode != themeModeNow
        if (current != targetScale || themeChanged) {
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
