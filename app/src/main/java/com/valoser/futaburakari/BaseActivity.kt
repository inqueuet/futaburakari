package com.valoser.futaburakari

/**
 * 画面共通の挙動（フォント倍率・テーマ適用・Edge-to-Edge・戻る動作・システムバー外観）をまとめた基底 Activity。
 * 各画面は本クラスを継承して共通機能を得る。
 */

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.PreferenceManager
import androidx.core.view.WindowCompat

/**
 * ベースとなる Activity。
 *
 * 機能概要:
 * - フォントサイズ: 共有設定の `pref_key_font_scale` を参照して `attachBaseContext` で反映。
 *   - 既定値は 1.0（設定UIの「標準」と一致）。ユーザー設定で変更可能。
 *   - 復帰時（onResume）に設定と現在の `Configuration.fontScale` を比較し、差があれば `recreate()` で即時反映。
 * - テーマ: ライト/ダーク/システムのモードを起動前に適用（色テーマは固定）。
 *   - 最後に適用したテーマモードを保持し、復帰時の変更を検知して再生成。
 * - Edge-to-Edge: `enableEdgeToEdge()` でシステムバー領域まで描画を拡張。
 * - システムバー外観: テーマに応じてステータスバー/ナビゲーションバーのアイコン色を更新。
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
        // 既定のフォント倍率は設定UIの既定（標準=1.0）に合わせる
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

    /** 生成前にテーマモードを適用し、生成後に Edge-to-Edge とシステムバー外観を整える。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemePreferences()
        super.onCreate(savedInstanceState)
        // Enable edge-to-edge with backward-compatible behavior
        enableEdgeToEdge()
        updateSystemBarsAppearance()
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
        updateSystemBarsAppearance()
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

    /**
     * ダーク/ライトのテーマ状態に応じてステータスバー/ナビゲーションバーのアイコン色を調整する。
     * ライトテーマ時はライトバー（暗色アイコン）を有効化し、ダークテーマ時は無効化する。
     */
    private fun updateSystemBarsAppearance() {
        val nightMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isNight = nightMask == Configuration.UI_MODE_NIGHT_YES
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight
    }
}
