package com.valoser.futaburakari

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 履歴データの読み書き・更新・並び替えを担うマネージャ。
 *
 * - `SharedPreferences` に JSON として保存/復元
 * - サムネイルURLや閲覧/更新情報、未読数の更新を提供
 * - 互換用の旧キーから現行キーへのマイグレーションを内包
 * - 変更時にアプリ内通知ブロードキャスト（`ACTION_HISTORY_CHANGED`）を送出
 */
object HistoryManager {

    private const val PREFS_NAME = "com.valoser.futaburakari.history"
    private const val KEY_HISTORY = "history_list"
    const val ACTION_HISTORY_CHANGED = "com.valoser.futaburakari.ACTION_HISTORY_CHANGED"

    private val historyMutex = Mutex()

    // 履歴保存先の SharedPreferences を取得
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // JSON から履歴リストを復元。失敗時は空リストを返す。
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

    // すでにロック済みの状態で呼び出す内部用保存処理。
    private suspend fun saveLocked(context: Context, list: List<HistoryEntry>) {
        try {
            withContext(Dispatchers.IO) {
                prefs(context).edit().putString(KEY_HISTORY, Gson().toJson(list)).apply()
            }
            // 変更通知（自アプリ内限定ブロードキャスト）
            val intent = Intent(ACTION_HISTORY_CHANGED).apply {
                setPackage(context.packageName)
                addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            // 保存失敗時のログ出力（サイレント失敗を防ぐ）
            android.util.Log.e("HistoryManager", "Failed to save history", e)
        }
    }

    /**
     * 履歴を追加または更新。
     *
     * - 旧キーが存在する場合は現行キーへ差し替えてマイグレーション
     * - タイトル/最終閲覧時刻/サムネイルURL（指定時のみ上書き）を更新
     * - 並び順は getAll() 側のルールに委譲
     */
    suspend fun addOrUpdate(context: Context, url: String, title: String, thumbnailUrl: String? = null) {
        historyMutex.withLock {
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
                        thumbnailUrl = thumbnailUrl ?: e.thumbnailUrl,
                        threadUrl = e.threadUrl ?: url
                    )
                }
            }
            if (idx >= 0) {
                val e = list[idx]
                list[idx] = e.copy(
                    title = title,
                    lastViewedAt = now,
                    thumbnailUrl = thumbnailUrl ?: e.thumbnailUrl,
                    threadUrl = e.threadUrl ?: url
                )
            } else {
                list.add(
                    HistoryEntry(
                        key = key,
                        url = url,
                        title = title,
                        lastViewedAt = now,
                        thumbnailUrl = thumbnailUrl,
                        threadUrl = url
                    )
                )
            }
            // 並び順は getAll() 側のルールに委ねる
            saveLocked(context, list)
        }
    }

    /**
     * すべての履歴を取得。
     *
     * - 未読がある項目（`unreadCount > 0`）を優先
     * - 未読あり同士: `lastUpdatedAt` の降順で並べ、同値は `lastViewedAt` で安定化
     * - 未読なし同士: `lastViewedAt` の降順
     * - いずれの場合も最終的に `lastViewedAt` を比較して既知の閲覧順を維持
     */
    fun getAll(context: Context): List<HistoryEntry> {
        val list = load(context)
        // 未読ありを優先 → 未読あり同士は lastUpdatedAt 降順 → 未読なしは lastViewedAt 降順
        return list.sortedWith(compareByDescending<HistoryEntry> { it.unreadCount > 0 }
            .thenByDescending { if (it.unreadCount > 0) it.lastUpdatedAt else it.lastViewedAt }
            .thenByDescending { it.lastViewedAt })
    }

    /** 指定キーの履歴を削除し、変更を保存。 */
    suspend fun delete(context: Context, key: String) {
        historyMutex.withLock {
            val list = load(context)
            val newList = list.filterNot { it.key == key }
            saveLocked(context, newList)
        }
    }

    /** 全履歴をクリア。 */
    suspend fun clear(context: Context) {
        historyMutex.withLock {
            saveLocked(context, emptyList())
        }
    }

    /**
     * サムネイルURLを更新。
     *
     * - 旧キーが見つかった場合は現行キーへ差し替え
     * - 値が変化したときのみ保存/通知
     */
    suspend fun updateThumbnail(context: Context, url: String, thumbnailUrl: String) {
        withContext(Dispatchers.IO) {
            historyMutex.withLock {
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
                        saveLocked(context, list)
                    }
                }
            }
        }
    }

    // サムネイルをクリア（自動クリーンアップで媒体削除したときなど）
    suspend fun clearThumbnail(context: Context, url: String) {
        withContext(Dispatchers.IO) {
            historyMutex.withLock {
                val key = UrlNormalizer.threadKey(url)
                val list = load(context)
                val idx = list.indexOfFirst { it.key == key }
                if (idx >= 0) {
                    val e = list[idx]
                    if (e.thumbnailUrl != null) {
                        list[idx] = e.copy(thumbnailUrl = null)
                        saveLocked(context, list)
                    }
                }
            }
        }
    }

    // 取得結果の反映（バックグラウンド更新などから呼び出し）
    // 最新レス番号が増えている場合に更新時刻/未読数/既知番号を反映。
    // 変化がなくても最新番号のみ更新する場合がある。
    suspend fun applyFetchResult(context: Context, url: String, latestReplyNo: Int) {
        withContext(Dispatchers.IO) {
            historyMutex.withLock {
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
                        saveLocked(context, list)
                    } else if (latestReplyNo > 0 && latestReplyNo != e.lastKnownReplyNo) {
                        // 変化はないが最新番号を追従（未読は据え置き）
                        list[idx] = e.copy(lastKnownReplyNo = latestReplyNo)
                        saveLocked(context, list)
                    }
                }
            }
        }
    }

    // ユーザ閲覧の反映（詳細画面を見たとき等）。
    // 閲覧時刻/最終閲覧レス番号/未読数を更新する。
    suspend fun markViewed(context: Context, url: String, lastViewedReplyNo: Int) {
        historyMutex.withLock {
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
                saveLocked(context, list)
            }
        }
    }

    // dat落ち等を検知した際にアーカイブとしてマーク（エントリ自体は保持）。
    @Suppress("UNUSED_PARAMETER")
    suspend fun markArchived(context: Context, url: String, autoExpireIfStale: Boolean = false) {
        historyMutex.withLock {
            val key = UrlNormalizer.threadKey(url)
            val list = load(context)
            val idx = list.indexOfFirst { it.key == key }
            if (idx >= 0) {
                val entry = list[idx]
                // 以前は dat 落ち後に一定期間閲覧されていない履歴を自動削除していたが、
                // ユーザー操作を優先し、アーカイブ状態でもエントリを保持するよう変更。
                if (!entry.isArchived) {
                    list[idx] = entry.copy(isArchived = true, archivedAt = System.currentTimeMillis())
                    saveLocked(context, list)
                }
            }
        }
    }
}
