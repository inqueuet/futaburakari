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
 * キャッシュしたスレ詳細と保存時刻を保持するコンテナ。
 * timestamp はミリ秒（epoch）。
 */
data class CachedDetails(
    val timestamp: Long,
    val details: List<DetailContent>
)

/**
 * スレッドの詳細内容および媒体アーカイブのオンディスクキャッシュを管理するクラス。
 * - 正規化済みスレURL（SHA-256）をキーに `DetailContent` のリストを JSON で保存/読込。
 * - 旧版との互換のためレガシーキー（ドメイン非含有）も読み込み・移行をサポート。
 * - スレ単位で媒体をアーカイブし、スナップショットが無い場合はファイルから最小限の詳細を再構成。
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
     * `DetailContent` の（逆）シリアライズに対応した Gson を生成する。
     */
    init {
        gson = GsonBuilder()
            .registerTypeAdapterFactory(DetailContentTypeAdapterFactory()) // Factory を登録
            // .serializeNulls() // 必要であれば null も JSON に出力する設定
            .create()
    }

    /**
     * 与えられたスレ `url` に対応するキャッシュファイルパスを返す（正規化 → SHA-256 でファイル名化）。
     */
    private fun getCacheFile(url: String): File {
        val key = UrlNormalizer.threadKey(url)   // 正規化キーに変換
        val fileName = key.sha256()
        Log.d("DetailCacheManager", "Key: $key -> FileName: $fileName")
        return File(cacheDir, fileName)
    }

    // レガシーキー（ドメイン非含有）のキャッシュファイル（旧版互換）。
    private fun getLegacyCacheFile(url: String): File {
        val key = UrlNormalizer.legacyThreadKey(url)
        val fileName = key.sha256()
        return File(cacheDir, fileName)
    }

    /**
     * スレごとの媒体アーカイブ用ディレクトリを返す（必要なら作成）。
     */
    fun getArchiveDirForUrl(url: String): File {
        val key = UrlNormalizer.threadKey(url)
        val dirName = key.sha256()
        return File(archiveRoot, dirName).apply { mkdirs() }
    }

    /**
     * アーカイブディレクトリ直下のスナップショット JSON ファイルパスを返す。
     */
    private fun getArchiveSnapshotFile(url: String): File {
        val dir = getArchiveDirForUrl(url)
        return File(dir, "snapshot.json")
    }

    /**
     * スレ詳細をキャッシュファイルへ保存し、レガシー名のファイルがあれば削除する。
     * 既存内容と同一（details が変化なし）の場合は書き換えをスキップする。
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
     * スレ詳細キャッシュを読み込む。無ければレガシー名をフォールバックし、読み込めた場合は移行する。
     * 破損している場合は当該キャッシュを削除して null を返す。
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
     * 指定 URL に対応するキャッシュファイル（存在すればレガシー名も）を削除する。
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
     * 指定 URL のアーカイブディレクトリ配下を再帰的に削除する（存在する場合）。
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

    /**
     * アーカイブスナップショット（本文含む）を保存する。
     * 既存スナップショットと同一内容なら書き換えをスキップする。
     */
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

    /**
     * アーカイブスナップショットを読み込む。ファイルが無い、または解析に失敗した場合は null。
     */
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

    /**
     * キャッシュと媒体アーカイブが占有する総バイト数を返す。
     */
    fun totalBytes(): Long {
        fun dirSize(d: File): Long {
            if (!d.exists()) return 0L
            return d.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        }
        return dirSize(cacheDir) + dirSize(archiveRoot)
    }

    /**
     * 総サイズが `limitBytes` を超える場合、履歴順にスレのアーカイブ/キャッシュを削除し、
     * 全体サイズがおおよそ 90% 未満になるまで間引く。未読があるスレは後回しにする。
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
    /** すべてのスレッド内容キャッシュおよび媒体アーカイブを削除する。 */
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

    /** 文字列の SHA-256 ハッシュ（小文字16進）を返す。 */
    private fun String.sha256(): String {
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }
}
