package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object HistoryManager {

    private const val PREFS_NAME = "com.valoser.futaburakari.history"
    private const val KEY_HISTORY = "history_list"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun load(context: Context): MutableList<HistoryEntry> {
        val json = prefs(context).getString(KEY_HISTORY, null)
        if (json.isNullOrBlank()) return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<HistoryEntry>>() {}.type
            Gson().fromJson<MutableList<HistoryEntry>>(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(context: Context, list: List<HistoryEntry>) {
        prefs(context).edit().putString(KEY_HISTORY, Gson().toJson(list)).apply()
    }

    fun addOrUpdate(context: Context, url: String, title: String, thumbnailUrl: String? = null) {
        val key = UrlNormalizer.threadKey(url)
        val legacyKey = UrlNormalizer.legacyThreadKey(url)
        val list = load(context)
        val now = System.currentTimeMillis()
        var idx = list.indexOfFirst { it.key == key }
        if (idx < 0 && legacyKey != key) {
            // 旧キーでの既存項目をマイグレーション（キー差し替え）
            idx = list.indexOfFirst { it.key == legacyKey }
            if (idx >= 0) {
                val e = list[idx]
                list[idx] = e.copy(key = key, url = url, title = title, lastViewedAt = now, thumbnailUrl = thumbnailUrl ?: e.thumbnailUrl)
            }
        }
        if (idx >= 0) {
            val e = list[idx]
            list[idx] = e.copy(title = title, lastViewedAt = now, thumbnailUrl = thumbnailUrl ?: e.thumbnailUrl)
        } else {
            list.add(HistoryEntry(key = key, url = url, title = title, lastViewedAt = now, thumbnailUrl = thumbnailUrl))
        }
        // newest first
        val sorted = list.sortedByDescending { it.lastViewedAt }
        save(context, sorted)
    }

    fun getAll(context: Context): List<HistoryEntry> = load(context)

    fun delete(context: Context, key: String) {
        val list = load(context)
        val newList = list.filterNot { it.key == key }
        save(context, newList)
    }

    fun clear(context: Context) {
        save(context, emptyList())
    }

    fun updateThumbnail(context: Context, url: String, thumbnailUrl: String) {
        val key = UrlNormalizer.threadKey(url)
        val legacyKey = UrlNormalizer.legacyThreadKey(url)
        val list = load(context)
        var idx = list.indexOfFirst { it.key == key }
        if (idx < 0 && legacyKey != key) {
            idx = list.indexOfFirst { it.key == legacyKey }
            if (idx >= 0) {
                // 旧キーであれば最新キーへ差し替える
                val e = list[idx]
                list[idx] = e.copy(key = key)
            }
        }
        if (idx >= 0) {
            val e = list[idx]
            if (e.thumbnailUrl != thumbnailUrl) {
                list[idx] = e.copy(thumbnailUrl = thumbnailUrl)
                save(context, list)
            }
        }
    }
}
