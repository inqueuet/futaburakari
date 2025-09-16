package com.valoser.futaburakari.cache

import android.content.Context
import android.util.Log
import com.valoser.futaburakari.DetailContent
import com.google.gson.Gson
import com.google.gson.GsonBuilder // GsonBuilder import
import com.google.gson.reflect.TypeToken
// DetailContentTypeAdapterFactory が同一パッケージ or 適切にインポートされていることを前提に使用します。
import java.io.File
import java.util.concurrent.TimeUnit
import com.valoser.futaburakari.UrlNormalizer

/**
 * Container for cached thread details with a timestamp for when they were saved.
 */
data class CachedDetails(
    val timestamp: Long,
    val details: List<DetailContent>
)

/**
 * Manages on-disk caching for thread detail content and archived media.
 * - Caches JSON-serialized `DetailContent` lists keyed by normalized thread URL (SHA-256).
 * - Supports legacy cache keys for backward compatibility.
 * - Archives media per-thread and can reconstruct minimal details from files.
 */
class DetailCacheManager(private val context: Context) {

    private val gson: Gson // ★ 初期化を init ブロックに移動
    private val cacheDir: File by lazy {
        File(context.cacheDir, "details_cache").apply { mkdirs() }
    }
    private val archiveRoot: File by lazy {
        File(context.filesDir, "archive_media").apply { mkdirs() }
    }

    /**
     * Builds a Gson instance that knows how to (de)serialize `DetailContent` via a registered factory.
     */
    init {
        gson = GsonBuilder()
            .registerTypeAdapterFactory(DetailContentTypeAdapterFactory()) // Factory を登録
            // .serializeNulls() // 必要であれば null も JSON に出力する設定
            .create()
    }

    /**
     * Returns the cache file for a given thread `url` using a normalized key hashed with SHA-256.
     */
    private fun getCacheFile(url: String): File {
        val key = UrlNormalizer.threadKey(url)   // 正規化キーに変換
        val fileName = key.sha256()
        Log.d("DetailCacheManager", "Key: $key -> FileName: $fileName")
        return File(cacheDir, fileName)
    }

    // レガシーキー（ドメイン非含有）のキャッシュファイル（旧版互換）
    private fun getLegacyCacheFile(url: String): File {
        val key = UrlNormalizer.legacyThreadKey(url)
        val fileName = key.sha256()
        return File(cacheDir, fileName)
    }

    /**
     * Returns or creates the per-thread archive directory under app files.
     */
    fun getArchiveDirForUrl(url: String): File {
        val key = UrlNormalizer.threadKey(url)
        val dirName = key.sha256()
        return File(archiveRoot, dirName).apply { mkdirs() }
    }

    /**
     * Returns the snapshot file path (JSON) inside the archive directory.
     */
    private fun getArchiveSnapshotFile(url: String): File {
        val dir = getArchiveDirForUrl(url)
        return File(dir, "snapshot.json")
    }

    /**
     * Saves details for a thread to the cache file and removes any legacy-named file.
     */
    fun saveDetails(url: String, details: List<DetailContent>) {
        val cacheFile = getCacheFile(url)
        val legacyFile = getLegacyCacheFile(url)

        try {
            // 既存内容と差分がなければスキップ（タイムスタンプの差だけで書き換えない）
            if (cacheFile.exists()) {
                runCatching {
                    val existing = gson.fromJson(cacheFile.readText(), object : TypeToken<CachedDetails>() {}.type) as CachedDetails
                    if (existing.details == details) {
                        Log.d("DetailCacheManager", "Cache unchanged for $url; skipping write.")
                        // レガシー名の掃除だけは行う
                        runCatching { if (legacyFile.exists()) legacyFile.delete() }
                        return
                    }
                }
            }

            Log.d("DetailCacheManager", "Saving to cache file: ${cacheFile.absolutePath}")
            val cachedData = CachedDetails(System.currentTimeMillis(), details)
            val jsonString = gson.toJson(cachedData)
            Log.d("DetailCacheManager", "JSON string length: ${jsonString.length}")
            cacheFile.writeText(jsonString)
            // 旧ファイルが残っていれば削除（容量節約）
            runCatching { if (legacyFile.exists()) legacyFile.delete() }
            Log.d("DetailCacheManager", "Successfully saved cache for $url")
        } catch (e: Exception) {
            Log.e("DetailCacheManager", "Error saving cache for $url", e)
        }
    }

    /**
     * Loads cached details for the thread; falls back to legacy file and migrates if present.
     * If parsing fails, deletes the corrupt cache and returns null.
     */
    fun loadDetails(url: String): List<DetailContent>? {
        val cacheFile = getCacheFile(url)
        val legacyFile = getLegacyCacheFile(url)
        Log.d("DetailCacheManager", "Loading from cache file: ${cacheFile.absolutePath}")

        if (!cacheFile.exists()) {
            // レガシー名をフォールバックで試す
            if (legacyFile.exists()) {
                Log.d("DetailCacheManager", "Primary cache missing; trying legacy: ${legacyFile.name}")
                return try {
                    val jsonString = legacyFile.readText()
                    val cachedData: CachedDetails = gson.fromJson(jsonString, object : TypeToken<CachedDetails>() {}.type)
                    // 新名へ移行（コピー）。失敗しても読み出しは返す。
                    runCatching { legacyFile.copyTo(cacheFile, overwrite = true) }
                    cachedData.details
                } catch (e: Exception) {
                    Log.e("DetailCacheManager", "Error reading legacy cache file.", e)
                    null
                }
            }
            Log.d("DetailCacheManager", "Cache file not found: ${cacheFile.name}")
            return null
        }

        return try {
            val jsonString = cacheFile.readText()
            Log.d("DetailCacheManager", "JSON string loaded, length: ${jsonString.length}")

            val cachedData: CachedDetails = gson.fromJson(jsonString, object : TypeToken<CachedDetails>() {}.type)
            Log.d("DetailCacheManager", "Successfully parsed JSON for $url")

            // 期限切れによる削除は行わない（アーカイブ閲覧を優先）
            Log.d("DetailCacheManager", "Cache hit for $url. Returning ${cachedData.details.size} items.")
            cachedData.details
        } catch (e: Exception) {
            Log.e("DetailCacheManager", "Error loading or parsing cache for $url. Deleting cache file.", e)
            cacheFile.delete()
            null
        }
    }

    /**
     * Deletes the cache file for the given URL (and legacy file if present).
     */
    fun invalidateCache(url: String) {
        val cacheFile = getCacheFile(url)
        val legacyFile = getLegacyCacheFile(url)
        Log.d("DetailCacheManager", "Invalidating cache for $url. Deleting file: ${cacheFile.name}")
        if (cacheFile.exists()) {
            if (cacheFile.delete()) {
                Log.d("DetailCacheManager", "Successfully deleted cache file: ${cacheFile.name}")
            } else {
                Log.w("DetailCacheManager", "Failed to delete cache file: ${cacheFile.name}")
            }
        } else {
            Log.d("DetailCacheManager", "Cache file to invalidate not found: ${cacheFile.name}")
        }
        // レガシー側も削除
        if (legacyFile.exists()) {
            runCatching { legacyFile.delete() }
        }
    }

    /**
     * Deletes the entire archive directory tree for the given URL if it exists.
     */
    fun clearArchiveForUrl(url: String) {
        val dir = getArchiveDirForUrl(url)
        if (dir.exists()) {
            if (!dir.deleteRecursively()) {
                Log.w("DetailCacheManager", "Failed to delete archive dir: ${dir.absolutePath}")
            }
        }
    }

    /**
     * アーカイブディレクトリに残っている媒体から、最低限の詳細リストを再構成する。
     * ネットワーク/キャッシュが使えない場合の最後のフォールバック用。
     * 取得できるのは媒体のみ（テキストは復元不可）。
     */
    fun reconstructFromArchive(url: String): List<DetailContent>? {
        // スナップショットがあればまず採用（本文も含む）
        loadArchiveSnapshot(url)?.let { return it }
        val dir = getArchiveDirForUrl(url)
        if (!dir.exists()) return null
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.lastModified() }
            ?: return null
        if (files.isEmpty()) return null
        fun isVideoName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".mp4") || lower.endsWith(".webm")
        }
        val list = mutableListOf<DetailContent>()
        for (f in files) {
            val uri = f.toURI().toString()
            val name = f.name
            if (isVideoName(name)) {
                list += DetailContent.Video(id = uri, videoUrl = uri, prompt = null, fileName = name)
            } else {
                list += DetailContent.Image(id = uri, imageUrl = uri, prompt = null, fileName = name)
            }
        }
        return list
    }

    // アーカイブスナップショット（本文含む）
    fun saveArchiveSnapshot(url: String, details: List<DetailContent>) {
        runCatching {
            val f = getArchiveSnapshotFile(url)

            // 既存スナップショットと内容が同一なら書き換えをスキップ
            if (f.exists()) {
                runCatching {
                    val existing: CachedDetails = gson.fromJson(f.readText(), object : TypeToken<CachedDetails>() {}.type)
                    if (existing.details == details) {
                        Log.d("DetailCacheManager", "Archive snapshot unchanged for $url; skipping write.")
                        return
                    }
                }
            }

            val json = gson.toJson(CachedDetails(System.currentTimeMillis(), details))
            f.writeText(json)
            Log.d("DetailCacheManager", "Saved archive snapshot: ${f.absolutePath}")
        }.onFailure {
            Log.w("DetailCacheManager", "Failed to save archive snapshot", it)
        }
    }

    /** Loads an archive snapshot if present; returns null on absence or parse failure. */
    fun loadArchiveSnapshot(url: String): List<DetailContent>? {
        val f = getArchiveSnapshotFile(url)
        if (!f.exists()) return null
        return try {
            val json = f.readText()
            val data: CachedDetails = gson.fromJson(json, object : TypeToken<CachedDetails>() {}.type)
            data.details
        } catch (e: Exception) {
            Log.w("DetailCacheManager", "Failed to load archive snapshot", e)
            null
        }
    }

    /** Returns total bytes occupied by cache and archived media. */
    fun totalBytes(): Long {
        fun dirSize(d: File): Long {
            if (!d.exists()) return 0L
            return d.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }
        return dirSize(cacheDir) + dirSize(archiveRoot)
    }

    /**
     * limitBytes を超えている場合、履歴の古い順に各スレのアーカイブ/キャッシュを削除して
     * 全体サイズが (limitBytes * 0.9) を下回るまで間引く。
     * ユーザが未読のスレは最後に回す（未読0を優先的に削除）。
     */
    /**
     * If total bytes exceed `limitBytes`, delete per-thread archives and caches in history order
     * until the size drops below 90% of the limit. Unread threads are deprioritized for deletion.
     */
    fun enforceLimit(limitBytes: Long, history: List<com.valoser.futaburakari.HistoryEntry>, onEntryCleaned: (com.valoser.futaburakari.HistoryEntry) -> Unit = {}) {
        if (limitBytes <= 0) return
        var total = totalBytes()
        if (total <= limitBytes) return

        // 削減目標: 少し余裕を持たせる
        val target = (limitBytes * 0.9).toLong()

        val ordered = history.sortedWith(
            compareBy<com.valoser.futaburakari.HistoryEntry> { it.unreadCount > 0 } // 未読0が先
                .thenBy { if (it.lastViewedAt > 0) it.lastViewedAt else Long.MAX_VALUE }
                .thenBy { if (it.lastUpdatedAt > 0) it.lastUpdatedAt else Long.MAX_VALUE }
        )

        fun fileSize(f: File?): Long = if (f != null && f.exists()) f.length() else 0L
        fun dirSizeQuick(d: File): Long {
            if (!d.exists()) return 0L
            var sum = 0L
            d.walkTopDown().forEach { if (it.isFile) sum += it.length() }
            return sum
        }

        for (e in ordered) {
            // 事前にこのスレ分のサイズを見積もる
            val archiveDir = getArchiveDirForUrl(e.url)
            val archiveBytes = dirSizeQuick(archiveDir)
            val cacheFile = getCacheFile(e.url)
            val cacheBytes = fileSize(cacheFile)

            // スレごとに媒体と詳細キャッシュを削除
            clearArchiveForUrl(e.url)
            invalidateCache(e.url)
            onEntryCleaned(e)

            // 全体サイズから差分で減算（全走査の繰り返しを避ける）
            total -= (archiveBytes + cacheBytes)
            if (total <= target) break
        }
    }

    // 追加ユーティリティ群
    /**
     * すべてのスレッド内容キャッシュを削除します。
     */
    fun clearAllCache() {
        if (cacheDir.exists()) {
            // cacheDirの中身をすべて削除する
            if (cacheDir.deleteRecursively()) {
                Log.d("DetailCacheManager", "Successfully cleared all cache.")
            } else {
                Log.w("DetailCacheManager", "Failed to clear all cache.")
            }
            // ディレクトリ自体は再作成しておく
            cacheDir.mkdirs()
        }

        if (archiveRoot.exists()) {
            if (archiveRoot.deleteRecursively()) {
                Log.d("DetailCacheManager", "Successfully cleared all archived media.")
            } else {
                Log.w("DetailCacheManager", "Failed to clear archived media.")
            }
            archiveRoot.mkdirs()
        }
    }

    /** SHA-256 hash for strings, returned as a lowercase hex digest. */
    private fun String.sha256(): String {
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }
}
