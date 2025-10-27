package com.valoser.futaburakari

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * 画像プロンプト取得に関する設定をまとめたヘルパー。
 * - 設定画面では `PREF_KEY_FETCH_ENABLED` を保存
 * - 各画面は `isPromptFetchEnabled` で現在の有効/無効を参照
 */
object PromptSettings {
    const val PREF_KEY_FETCH_ENABLED = "pref_key_prompt_fetch_enabled"

    /** 現在の画像プロンプト取得設定（既定: 有効）を返す。 */
    fun isPromptFetchEnabled(context: Context): Boolean {
        if (AppPreferences.isLowBandwidthModeEnabled(context)) {
            return false
        }
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(PREF_KEY_FETCH_ENABLED, false)
    }
}
