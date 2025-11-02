package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * 覗き見防止に関する設定アクセスをまとめたユーティリティ。
 *
 * - 設定キーは Compose からも Activity からも同じ値を使用して同期する。
 * - 値の取得は `SharedPreferences`（デフォルト設定領域）経由で行う。
 */
object PrivacyScreenSettings {
    /** 覗き見防止設定のプリファレンスキー。 */
    const val PREF_KEY_PRIVACY_SCREEN = "pref_key_privacy_screen"
    /** 覗き見防止フィルタの色を保持するキー。 */
    const val PREF_KEY_PRIVACY_SCREEN_COLOR = "pref_key_privacy_screen_color"
    /** 覗き見防止フィルタの模様を保持するキー。 */
    const val PREF_KEY_PRIVACY_SCREEN_PATTERN = "pref_key_privacy_screen_pattern"
    /** 覗き見防止フィルタの濃さを保持するキー。 */
    const val PREF_KEY_PRIVACY_SCREEN_INTENSITY = "pref_key_privacy_screen_intensity"

    // 旧実装との互換用キー。移行後は自動的に削除する。
    const val PREF_KEY_PRIVACY_SCREEN_STYLE = "pref_key_privacy_screen_style"

    /** 覗き見防止が有効かどうかを返す。 */
    fun isPrivacyScreenEnabled(context: Context): Boolean {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_KEY_PRIVACY_SCREEN, false)
    }

    /** 覗き見防止フィルタの色を返す（旧設定からの移行を含む）。 */
    fun getPrivacyScreenColor(context: Context): PrivacyScreenColor {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        migrateLegacyStyleIfNeeded(prefs)
        val value = prefs.getString(PREF_KEY_PRIVACY_SCREEN_COLOR, PrivacyScreenColor.Dark.storageValue)
        return PrivacyScreenColor.fromPreferenceValue(value)
    }

    /** 覗き見防止フィルタの模様を返す（旧設定からの移行を含む）。 */
    fun getPrivacyScreenPattern(context: Context): PrivacyScreenPattern {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        migrateLegacyStyleIfNeeded(prefs)
        val value = prefs.getString(PREF_KEY_PRIVACY_SCREEN_PATTERN, PrivacyScreenPattern.Plain.storageValue)
        return PrivacyScreenPattern.fromPreferenceValue(value)
    }

    /** 覗き見防止フィルタの濃さを返す（旧設定からの移行を含む）。 */
    fun getPrivacyScreenIntensity(context: Context): PrivacyScreenIntensity {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        migrateLegacyStyleIfNeeded(prefs)
        val value = prefs.getString(PREF_KEY_PRIVACY_SCREEN_INTENSITY, PrivacyScreenIntensity.Medium.storageValue)
        return PrivacyScreenIntensity.fromPreferenceValue(value)
    }

    /** 色・模様・濃さの組み合わせを返す。 */
    fun getPrivacyScreenStyle(context: Context): PrivacyScreenStyle {
        return PrivacyScreenStyle(
            color = getPrivacyScreenColor(context),
            pattern = getPrivacyScreenPattern(context),
            intensity = getPrivacyScreenIntensity(context)
        )
    }

    private fun migrateLegacyStyleIfNeeded(prefs: SharedPreferences) {
        var needsApply = false
        val editor = prefs.edit()

        if (!prefs.contains(PREF_KEY_PRIVACY_SCREEN_COLOR) || !prefs.contains(PREF_KEY_PRIVACY_SCREEN_PATTERN)) {
            val legacy = prefs.getString(PREF_KEY_PRIVACY_SCREEN_STYLE, null)
            val (color, pattern) = if (legacy != null) {
                val legacyStyle = LegacyPrivacyScreenStyle.fromPreferenceValue(legacy)
                when (legacyStyle) {
                    LegacyPrivacyScreenStyle.Dark -> PrivacyScreenColor.Dark to PrivacyScreenPattern.Plain
                    LegacyPrivacyScreenStyle.Light -> PrivacyScreenColor.Light to PrivacyScreenPattern.Plain
                    LegacyPrivacyScreenStyle.Pattern -> PrivacyScreenColor.Dark to PrivacyScreenPattern.Pattern
                }
            } else {
                PrivacyScreenColor.Dark to PrivacyScreenPattern.Plain
            }
            editor.putString(PREF_KEY_PRIVACY_SCREEN_COLOR, color.storageValue)
            editor.putString(PREF_KEY_PRIVACY_SCREEN_PATTERN, pattern.storageValue)
            needsApply = true
        }

        if (!prefs.contains(PREF_KEY_PRIVACY_SCREEN_INTENSITY)) {
            editor.putString(PREF_KEY_PRIVACY_SCREEN_INTENSITY, PrivacyScreenIntensity.Medium.storageValue)
            needsApply = true
        }

        if (prefs.contains(PREF_KEY_PRIVACY_SCREEN_STYLE)) {
            editor.remove(PREF_KEY_PRIVACY_SCREEN_STYLE)
            needsApply = true
        }

        if (needsApply) editor.apply()
    }
}

/** 覗き見防止フィルタの色バリエーション。 */
enum class PrivacyScreenColor(val storageValue: String) {
    Dark("dark"),
    Light("light");

    companion object {
        fun fromPreferenceValue(value: String?): PrivacyScreenColor {
            return values().firstOrNull { it.storageValue == value } ?: Dark
        }
    }
}

/** 覗き見防止フィルタの模様バリエーション。 */
enum class PrivacyScreenPattern(val storageValue: String) {
    Plain("plain"),
    Pattern("pattern");

    companion object {
        fun fromPreferenceValue(value: String?): PrivacyScreenPattern {
            return values().firstOrNull { it.storageValue == value } ?: Plain
        }
    }
}

/** 覗き見防止フィルタの組み合わせ。 */
data class PrivacyScreenStyle(
    val color: PrivacyScreenColor,
    val pattern: PrivacyScreenPattern,
    val intensity: PrivacyScreenIntensity
)

/** 覗き見防止フィルタの濃さ（不透明度）バリエーション。 */
enum class PrivacyScreenIntensity(val storageValue: String, val alphaMultiplier: Float) {
    Light("light", 0.75f),
    Medium("medium", 1.0f),
    Strong("strong", 1.25f);

    companion object {
        fun fromPreferenceValue(value: String?): PrivacyScreenIntensity {
            return values().firstOrNull { it.storageValue == value } ?: Medium
        }
    }
}

// 旧実装のスタイル（互換移行専用）
private enum class LegacyPrivacyScreenStyle(val storageValue: String) {
    Dark("dark"),
    Light("light"),
    Pattern("pattern");

    companion object {
        fun fromPreferenceValue(value: String?): LegacyPrivacyScreenStyle {
            return values().firstOrNull { it.storageValue == value } ?: Dark
        }
    }
}
