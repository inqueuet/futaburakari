package com.valoser.futaburakari

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.ListPreference
import coil.imageLoader
import com.valoser.futaburakari.cache.DetailCacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        // パスワード設定の処理
        val passwordPreference: EditTextPreference? = findPreference("pref_key_password")
        passwordPreference?.setOnPreferenceChangeListener { _, newValue ->
            val newPassword = newValue as String
            // AppPreferencesに新しいパスワードを保存
            AppPreferences.savePwd(requireContext(), newPassword)
            Toast.makeText(requireContext(), "パスワードを保存しました", Toast.LENGTH_SHORT).show()
            true // trueを返すと設定が永続化される
        }

        // キャッシュ削除の処理
        val clearCachePreference: Preference? = findPreference("pref_key_clear_cache")
        clearCachePreference?.setOnPreferenceClickListener {
            // 確認ダイアログなどをここに入れるとより親切

            // Coilの画像キャッシュを削除
            val imageLoader = requireContext().imageLoader
            imageLoader.memoryCache?.clear()
            // ディスクキャッシュの削除はバックグラウンドで行う
            lifecycleScope.launch(Dispatchers.IO) {
                imageLoader.diskCache?.clear()

                // DetailCacheManagerのスレッドキャッシュを削除
                val detailCacheManager = DetailCacheManager(requireContext())
                detailCacheManager.clearAllCache()

                // UIスレッドでToastを表示
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "すべてのキャッシュを削除しました", Toast.LENGTH_SHORT).show()
                }
            }
            true
        }

        // バックグラウンド監視のトグル
        val bgPref: androidx.preference.SwitchPreferenceCompat? = findPreference("pref_key_bg_monitor_enabled")
        bgPref?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = (newValue as? Boolean) == true
            // 保存（Worker側はSharedPreferencesで参照）
            requireContext().getSharedPreferences(
                com.valoser.futaburakari.worker.ThreadMonitorWorker.PREFS_BG,
                android.content.Context.MODE_PRIVATE
            ).edit().putBoolean(
                com.valoser.futaburakari.worker.ThreadMonitorWorker.KEY_BG_ENABLED,
                enabled
            ).apply()

            if (!enabled) {
                com.valoser.futaburakari.worker.ThreadMonitorWorker.cancelAll(requireContext())
            } else {
                // 有効化時：履歴にある全スレッドをスケジュール
                val all = HistoryManager.getAll(requireContext())
                all.forEach { entry ->
                    com.valoser.futaburakari.worker.ThreadMonitorWorker.schedule(requireContext(), entry.url)
                }
            }
            true
        }

        // フォントサイズ変更時は即時に画面へ反映（この画面のみ再生成）
        val fontPref: ListPreference? = findPreference("pref_key_font_scale")
        fontPref?.setOnPreferenceChangeListener { _, _ ->
            // 再生成して新しいfontScaleを適用
            requireActivity().recreate()
            true
        }

        // テーマモード（ライト/ダーク/システム）変更
        val themePref: ListPreference? = findPreference("pref_key_theme_mode")
        themePref?.setOnPreferenceChangeListener { _, _ ->
            // BaseActivity.onCreateで反映されるため再生成
            requireActivity().recreate()
            true
        }

        // カラーモード（配色）変更
        val colorPref: ListPreference? = findPreference("pref_key_color_mode")
        colorPref?.setOnPreferenceChangeListener { _, _ ->
            // その場で反映するため再生成
            requireActivity().recreate()
            true
        }
    }
}
