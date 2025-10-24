package com.valoser.futaburakari

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * スレ監視キーワードを永続化・取得するためのシンプルなストア。
 *
 * 共有設定（`SharedPreferences`）に JSON として保存し、必要に応じて追加・更新・削除を提供する。
 */
class ThreadWatchStore(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private val gson = Gson()
    private val key = "thread_watch_entries"

    /** 登録済みのキーワード一覧を取得（作成日時の降順で返却）。 */
    fun getEntries(): List<ThreadWatchEntry> {
        return loadEntries().sortedByDescending { it.createdAt }
    }

    /** 新しいキーワードを追加する。重複（大文字小文字無視）がある場合は例外を投げる。 */
    fun addEntry(keyword: String): ThreadWatchEntry {
        val normalized = keyword.trim()
        require(normalized.isNotEmpty()) { "キーワードを入力してください" }
        val entries = loadEntries()
        if (entries.any { it.keyword.equals(normalized, ignoreCase = true) }) {
            throw IllegalStateException("同じキーワードが既に登録されています")
        }
        val entry = ThreadWatchEntry(
            id = UUID.randomUUID().toString(),
            keyword = normalized,
            createdAt = System.currentTimeMillis(),
        )
        save(entries + entry)
        return entry
    }

    /** 既存キーワードを更新する。存在しない場合や重複がある場合は例外を投げる。 */
    fun updateEntry(id: String, keyword: String) {
        val normalized = keyword.trim()
        require(normalized.isNotEmpty()) { "キーワードを入力してください" }
        val entries = loadEntries()
        val exists = entries.any { it.id == id }
        if (!exists) throw IllegalArgumentException("対象のキーワードが見つかりません")
        if (entries.any { it.id != id && it.keyword.equals(normalized, ignoreCase = true) }) {
            throw IllegalStateException("同じキーワードが既に登録されています")
        }
        val updated = entries.map { entry ->
            if (entry.id == id) entry.copy(keyword = normalized) else entry
        }
        save(updated)
    }

    /** 指定 ID のキーワードを削除する。存在しない場合は何もしない。 */
    fun removeEntry(id: String) {
        val entries = loadEntries()
        val updated = entries.filterNot { it.id == id }
        if (updated.size != entries.size) {
            save(updated)
        }
    }

    /** JSON からキーワード一覧を読み出す内部ヘルパー。 */
    private fun loadEntries(): List<ThreadWatchEntry> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ThreadWatchEntry>>() {}.type
            gson.fromJson<List<ThreadWatchEntry>>(json, type)
        }.getOrElse { emptyList() }
    }

    /** キーワード一覧を JSON として保存する。 */
    private fun save(entries: List<ThreadWatchEntry>) {
        prefs.edit().putString(key, gson.toJson(entries)).apply()
    }
}
