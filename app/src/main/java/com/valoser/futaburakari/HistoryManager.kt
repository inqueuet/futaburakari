package com.valoser.futaburakari

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object HistoryManager {

    private const val PREFS_NAME = "com.valoser.futaburakari.history"
    private const val KEY_HISTORY = "history_list"
    const val ACTION_HISTORY_CHANGED = "com.valoser.futaburakari.ACTION_HISTORY_CHANGED"

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
        // 変更通知（同一プロセス内向けの簡易ブロードキャスト）
        context.sendBroadcast(Intent(ACTION_HISTORY_CHANGED))
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
                list[idx] = e.copy(
                    key = key,
                    url = url,
                    title = title,
                    lastViewedAt = now,
                    thumbnailUrl = thumbnailUrl ?: e.thumbnailUrl
                )
            }
        }
        if (idx >= 0) {
            val e = list[idx]
            list[idx] = e.copy(
                title = title,
                lastViewedAt = now,
                thumbnailUrl = thumbnailUrl ?: e.thumbnailUrl
            )
        } else {
            list.add(
                HistoryEntry(
                    key = key,
                    url = url,
                    title = title,
                    lastViewedAt = now,
                    thumbnailUrl = thumbnailUrl
                )
            )
        }
        // 並び順は getAll() 側のルールに委ねる
        save(context, list)
    }

    fun getAll(context: Context): List<HistoryEntry> {
        val list = load(context)
        // 未読ありを優先 → 未読あり同士は lastUpdatedAt 降順 → 未読なしは lastViewedAt 降順
        return list.sortedWith(compareByDescending<HistoryEntry> { it.unreadCount > 0 }
            .thenByDescending { if (it.unreadCount > 0) it.lastUpdatedAt else it.lastViewedAt }
            .thenByDescending { it.lastViewedAt })
    }

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

    // サムネイルをクリア（自動クリーンアップで媒体削除したときなど）
    fun clearThumbnail(context: Context, url: String) {
        val key = UrlNormalizer.threadKey(url)
        val list = load(context)
        val idx = list.indexOfFirst { it.key == key }
        if (idx >= 0) {
            val e = list[idx]
            if (e.thumbnailUrl != null) {
                list[idx] = e.copy(thumbnailUrl = null)
                save(context, list)
            }
        }
    }

    // 取得結果の反映（バックグラウンド更新などから呼び出し）
    fun applyFetchResult(context: Context, url: String, latestReplyNo: Int) {
        val key = UrlNormalizer.threadKey(url)
        val list = load(context)
        val now = System.currentTimeMillis()
        val idx = list.indexOfFirst { it.key == key }
        if (idx >= 0) {
            val e = list[idx]
            if (latestReplyNo > e.lastKnownReplyNo) {
                val unread = (latestReplyNo - maxOf(e.lastViewedReplyNo, 0)).coerceAtLeast(0)
                list[idx] = e.copy(
                    lastUpdatedAt = now,
                    lastKnownReplyNo = latestReplyNo,
                    unreadCount = unread
                )
                save(context, list)
            } else if (latestReplyNo > 0 && latestReplyNo != e.lastKnownReplyNo) {
                // 変化はないが最新番号を追従（未読は据え置き）
                list[idx] = e.copy(lastKnownReplyNo = latestReplyNo)
                save(context, list)
            }
        }
    }

    // ユーザ閲覧の反映（詳細画面を見たとき等）
    fun markViewed(context: Context, url: String, lastViewedReplyNo: Int) {
        val key = UrlNormalizer.threadKey(url)
        val list = load(context)
        val now = System.currentTimeMillis()
        val idx = list.indexOfFirst { it.key == key }
        if (idx >= 0) {
            val e = list[idx]
            val unread = (e.lastKnownReplyNo - lastViewedReplyNo).coerceAtLeast(0)
            list[idx] = e.copy(
                lastViewedAt = now,
                lastViewedReplyNo = lastViewedReplyNo,
                unreadCount = unread
            )
            save(context, list)
        }
    }

    // dat落ち等を検知した際にアーカイブとしてマーク
    fun markArchived(context: Context, url: String) {
        val key = UrlNormalizer.threadKey(url)
        val list = load(context)
        val idx = list.indexOfFirst { it.key == key }
        if (idx >= 0) {
            val e = list[idx]
            if (!e.isArchived) {
                list[idx] = e.copy(isArchived = true, archivedAt = System.currentTimeMillis())
                save(context, list)
            }
        }
    }
}
