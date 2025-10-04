package com.valoser.futaburakari.search

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * 検索ワードの履歴を `SharedPreferences` に JSON 配列として保存・復元し、
 * `StateFlow` で購読可能にするためのストア。
 * - 追加時は前後空白をトリムし、空文字は無視します。
 * - 既存に同一クエリがあれば削除して、最新のものを先頭に移動します。
 * - 最大保持件数は 10 件で、超過分は末尾から削除します。
 */
class RecentSearchStore(context: Context) {
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    // 履歴リストを保存する JSON 文字列のキー
    private val key = "recent_searches_json"
    // 履歴の最大件数
    private val maxSize = 10

    private val _items = MutableStateFlow<List<String>>(load())
    /** 現在の履歴リストを公開する読み取り専用のフロー。 */
    val items: StateFlow<List<String>> = _items

    /**
     * 検索クエリを履歴に追加します。
     * 前後空白はトリムし、空文字は追加しません。
     * 既存に同一クエリがあれば削除して先頭に挿入し、
     * 件数が上限を超えた場合は末尾を削除します。
     */
    fun add(query: String) {
        val q = query.trim().takeIf { it.isNotEmpty() } ?: return
        val cur = _items.value.toMutableList()
        cur.remove(q)
        cur.add(0, q)
        if (cur.size > maxSize) cur.subList(maxSize, cur.size).clear()
        _items.value = cur
        save(cur)
    }

    // SharedPreferences から JSON を読み、履歴リストへ復元する。
    private fun load(): List<String> {
        return runCatching {
            val json = prefs.getString(key, null) ?: return emptyList()
            val arr = JSONArray(json)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        }.getOrElse { emptyList() }
    }

    // 履歴リストを JSON 配列として保存する。
    private fun save(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
