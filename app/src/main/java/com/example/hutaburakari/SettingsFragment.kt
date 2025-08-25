package com.example.hutaburakari

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import coil.imageLoader
import com.example.hutaburakari.cache.DetailCacheManager
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
                com.example.hutaburakari.worker.ThreadMonitorWorker.PREFS_BG,
                android.content.Context.MODE_PRIVATE
            ).edit().putBoolean(
                com.example.hutaburakari.worker.ThreadMonitorWorker.KEY_BG_ENABLED,
                enabled
            ).apply()

            if (!enabled) {
                com.example.hutaburakari.worker.ThreadMonitorWorker.cancelAll(requireContext())
            } else {
                // 有効化時：履歴にある全スレッドをスケジュール
                val all = HistoryManager.getAll(requireContext())
                all.forEach { entry ->
                    com.example.hutaburakari.worker.ThreadMonitorWorker.schedule(requireContext(), entry.url)
                }
            }
            true
        }
    }
}
