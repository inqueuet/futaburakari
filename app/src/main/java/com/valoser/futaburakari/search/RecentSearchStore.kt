package com.valoser.futaburakari.search

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

class RecentSearchStore(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val key = "recent_searches_json"
    private val maxSize = 10

    private val _items = MutableStateFlow<List<String>>(load())
    val items: StateFlow<List<String>> = _items

    fun add(query: String) {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        val cur = _items.value.toMutableList()
        cur.remove(q)
        cur.add(0, q)
        if (cur.size > maxSize) cur.subList(maxSize, cur.size).clear()
        _items.value = cur
        save(cur)
    }

    private fun load(): List<String> {
        return runCatching {
            val json = prefs.getString(key, null) ?: return emptyList()
            val arr = JSONArray(json)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        }.getOrElse { emptyList() }
    }

    private fun save(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}

