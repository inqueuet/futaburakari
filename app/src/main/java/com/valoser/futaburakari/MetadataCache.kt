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

    fun get(id: String): String? {
        val m = load()
        val e = m[id] ?: return null
        // touch (update ts) lazily to avoid constant writes
        return e.value
    }

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

