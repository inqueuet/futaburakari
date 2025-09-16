package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persistent cache for metadata extraction results: (uriOrUrl -> prompt).
 * - Backed by SharedPreferences as a JSON map of key -> Entry(value, ts).
 * - Simple LRU-ish eviction by timestamp when exceeding max entries.
 */
class MetadataCache(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("metadata_extractor_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "entries_json"
    private val maxEntries = 512

    private data class Entry(val value: String, val ts: Long)

    private fun load(): MutableMap<String, Entry> {
        val json = prefs.getString(key, null) ?: return mutableMapOf()
        return runCatching {
            val type = object : TypeToken<MutableMap<String, Entry>>() {}.type
            gson.fromJson<MutableMap<String, Entry>>(json, type) ?: mutableMapOf()
        }.getOrElse { mutableMapOf() }
    }

    private fun save(map: MutableMap<String, Entry>) {
        prefs.edit().putString(key, gson.toJson(map)).apply()
    }

    /**
     * 指定したキーに対応するメタデータ文字列を取得する。
     *
     * - 永続キャッシュ（SharedPreferences に保存された JSON マップ）から読み出す。
     * - ヒットしない場合は null を返す。
     *
     * @param id URI/URL 等の識別子
     * @return 保存済みの値。存在しない場合は null
     */
    fun get(id: String): String? {
        val m = load()
        val e = m[id] ?: return null
        // touch (update ts) lazily to avoid constant writes
        return e.value
    }

    /**
     * 指定したキーに対応するメタデータ文字列を保存する。
     *
     * - 空白のみの値は無視。
     * - 登録後、上限件数を超える場合は最終アクセス時刻（ts）の古い順に削除。
     *
     * @param id URI/URL 等の識別子
     * @param value 保存する値（空白のみは保存しない）
     */
    fun put(id: String, value: String) {
        if (value.isBlank()) return
        val m = load()
        m[id] = Entry(value = value, ts = System.currentTimeMillis())
        if (m.size > maxEntries) {
            // Evict oldest by ts
            val toRemove = m.entries
                .sortedBy { it.value.ts }
                .take(m.size - maxEntries)
                .map { it.key }
            toRemove.forEach { m.remove(it) }
        }
        save(m)
    }
}

