package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.collection.LruCache
import android.util.Log

/**
 * メタデータ抽出結果の永続キャッシュ（uriOrUrl -> prompt）。
 *
 * - SharedPreferences を利用し JSON 形式で key -> Entry(value, ts) を管理
 * - 最大エントリ数を超過時はタイムスタンプベースの LRU 削除を実行
 * - 頻繁な SharedPreferences アクセスを避けるためインメモリ LRU キャッシュを併用
 */
class MetadataCache(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("metadata_extractor_cache", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "entries_json"
    private val maxEntries = 512

    // インメモリキャッシュ（頻繁なアクセスを最適化）
    private val memoryCache = LruCache<String, Entry>(128)
    private var isDirty = false
    private var lastSaveTime = 0L
    private val saveDelayMs = 5000L // 5秒間の遅延書き込み

    private data class Entry(val value: String, val ts: Long)

    private fun load(): MutableMap<String, Entry> {
        val json = prefs.getString(key, null) ?: return mutableMapOf()
        return runCatching {
            val type = object : TypeToken<MutableMap<String, Entry>>() {}.type
            gson.fromJson<MutableMap<String, Entry>>(json, type) ?: mutableMapOf()
        }.getOrElse {
            Log.w("MetadataCache", "Failed to parse cache JSON, starting fresh")
            mutableMapOf()
        }
    }

    private fun saveDelayed(map: MutableMap<String, Entry>) {
        isDirty = true
        val now = System.currentTimeMillis()

        // 遅延書き込み：連続するput()をバッチ処理
        if (now - lastSaveTime > saveDelayMs) {
            save(map)
            isDirty = false
            lastSaveTime = now
        }
    }

    private fun save(map: MutableMap<String, Entry>) {
        runCatching {
            prefs.edit().putString(key, gson.toJson(map)).apply()
            Log.d("MetadataCache", "Saved ${map.size} entries to persistent storage")
        }.onFailure {
            Log.e("MetadataCache", "Failed to save cache", it)
        }
    }

    /**
     * 強制的に保留中の変更をディスクに保存する
     */
    fun flush() {
        if (isDirty) {
            val map = load()
            // メモリキャッシュの内容をディスクマップに反映
            map.putAll(memoryCache.snapshot())
            save(map)
            isDirty = false
        }
    }

    /**
     * 指定したキーに対応するメタデータ文字列を取得する。
     *
     * - まずインメモリキャッシュから検索し、見つからない場合のみディスクから読み込む。
     * - ヒットしない場合は null を返す。
     *
     * @param id URI/URL 等の識別子
     * @return 保存済みの値。存在しない場合は null
     */
    fun get(id: String): String? {
        // まずメモリキャッシュを確認
        memoryCache[id]?.let { entry ->
            return entry.value
        }

        // メモリキャッシュにない場合のみディスクアクセス
        val diskMap = load()
        val entry = diskMap[id] ?: return null

        // メモリキャッシュに追加（将来のアクセス高速化）
        memoryCache.put(id, entry)

        return entry.value
    }

    /**
     * 指定したキーに対応するメタデータ文字列を保存する。
     *
     * - 空白のみの値は無視。
     * - インメモリキャッシュに即座に保存し、ディスクへは遅延書き込み。
     * - 登録後、上限件数を超える場合は最終アクセス時刻（ts）の古い順に削除。
     *
     * @param id URI/URL 等の識別子
     * @param value 保存する値（空白のみは保存しない）
     */
    fun put(id: String, value: String) {
        if (value.isBlank()) return

        val entry = Entry(value = value, ts = System.currentTimeMillis())

        // メモリキャッシュに即座に保存
        memoryCache.put(id, entry)

        // ディスクへは遅延書き込み
        val diskMap = load()
        diskMap[id] = entry

        // 上限チェックとLRU削除
        if (diskMap.size > maxEntries) {
            val toRemove = diskMap.entries
                .sortedBy { it.value.ts }
                .take(diskMap.size - maxEntries)
                .map { it.key }
            toRemove.forEach { key ->
                diskMap.remove(key)
                memoryCache.remove(key) // メモリキャッシュからも削除
            }
        }

        saveDelayed(diskMap)
    }
}

