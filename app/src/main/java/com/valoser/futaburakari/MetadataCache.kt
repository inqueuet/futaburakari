package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import androidx.collection.LruCache
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.jvm.Volatile

/**
 * メタデータ抽出結果の永続キャッシュ（uriOrUrl -> メタデータ文字列）。
 *
 * - SharedPreferences を利用し JSON 形式で key -> Entry(value, ts) を管理
 * - 最大エントリ数を超過した場合は保存時刻が古い順に削除（タイムスタンプを LRU 代替として利用）
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var pendingMap: MutableMap<String, Entry>? = null
    @Volatile
    private var saveJob: Job? = null

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

    @Synchronized
    private fun saveDelayed() {
        if (pendingMap == null) return

        isDirty = true
        val now = System.currentTimeMillis()

        if (now - lastSaveTime >= saveDelayMs) {
            savePendingNow()
        } else {
            scheduleSave()
        }
    }

    @Synchronized
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(saveDelayMs)
            savePendingNow()
        }
    }

    @Synchronized
    private fun savePendingNow() {
        val map = pendingMap ?: return
        save(map)
        pendingMap = null
        saveJob = null
        isDirty = false
        lastSaveTime = System.currentTimeMillis()
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
     * 強制的に保留中の変更をディスクに保存する。
     *
     * @return 非同期フラッシュ処理の `Job`（呼び出し側で必要に応じて待機可能）
     */
    fun flush(): Job {
        return scope.launch {
            flushInternal()
        }
    }

    @Synchronized
    private fun flushInternal() {
        saveJob?.cancel()
        savePendingNow()
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
     * - 登録後、上限件数を超える場合は保存時刻（ts）の古い順に削除。
     *
     * @param id URI/URL 等の識別子
     * @param value 保存する値（空白のみは保存しない）
     */
    @Synchronized
    fun put(id: String, value: String) {
        if (value.isBlank()) return

        val entry = Entry(value = value, ts = System.currentTimeMillis())

        // メモリキャッシュに即座に保存
        memoryCache.put(id, entry)

        // 遅延ディスク書き込み用のマップを取得（未ロードならロードして保持）
        val workingMap = pendingMap ?: load().also { pendingMap = it }
        workingMap[id] = entry

        // 上限チェックとLRU削除
        if (workingMap.size > maxEntries) {
            val toRemove = workingMap.entries
                .sortedBy { it.value.ts }
                .take(workingMap.size - maxEntries)
                .map { it.key }
            toRemove.forEach { key ->
                workingMap.remove(key)
                memoryCache.remove(key) // メモリキャッシュからも削除
            }
        }

        saveDelayed()
    }

    /**
     * キャッシュ保存用のコルーチンスコープを停止し、保留中の書き込みを直ちに反映する。
     * アプリ終了時など明示的なクリーンアップ用。
     */
    fun close() {
        synchronized(this) {
            saveJob?.cancel()
            savePendingNow()
            scope.cancel()
        }
    }
}
