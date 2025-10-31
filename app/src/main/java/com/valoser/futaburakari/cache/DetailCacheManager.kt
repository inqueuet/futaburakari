package com.valoser.futaburakari.cache

import android.content.Context
import android.util.Log
import com.valoser.futaburakari.DetailContent
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.valoser.futaburakari.UrlNormalizer

/**
 * キャッシュしたスレ詳細と保存時刻を保持するコンテナ。
 * timestamp はミリ秒（epoch）。
 */
data class CachedDetails(
    val timestamp: Long,
    val details: List<DetailContent>,
    val checksum: String? = null
)

/**
 * スレッドの詳細内容および媒体アーカイブのオンディスクキャッシュを管理するクラス。
 * - 正規化済みスレURL（SHA-256）をキーに `DetailContent` を保存し、チェックサムで差分検知して不要な書き換えを抑止。
 * - 旧版との互換のためレガシーキー（ドメイン非含有）も読み込み・移行しつつ、存在すれば削除して整理する。
 * - 大量データは `JsonWriter` によるストリーミング書き込みを用いてメモリ消費を抑制。
 * - 媒体アーカイブ/スナップショットを扱い、必要に応じて再構成フォールバックや総容量の上限制御を行う。
 */
@Singleton
class DetailCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapterFactory(DetailContentTypeAdapterFactory())
        .create()
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DetailCacheManager-Writer").apply {
            // 通常スレッドとして設定（isDaemon=falseがデフォルト）
            // データ整合性は cleanup() メソッドで writeExecutor.shutdown() と awaitTermination() を
            // 呼び出すことで保証する（書き込み完了を待機してから終了）
            // また、Applicationクラスで確実にcleanup()を呼び出すことで、
            // アプリ終了時のデータ損失を防止
            priority = Thread.NORM_PRIORITY - 1 // 優先度を下げてメインスレッドへの影響を最小化
        }
    }
    private val cacheDir: File by lazy {
        File(context.cacheDir, "details_cache").apply { mkdirs() }
    }
    private val archiveRoot: File by lazy {
        File(context.filesDir, "archive_media").apply { mkdirs() }
    }

    /**
     * 詳細リストのチェックサムを計算する（差分更新用）
     */
    private fun calculateChecksum(details: List<DetailContent>): String {
        // より効率的なハッシュ計算（メモリ使用量とGC負荷を削減）
        val digest = MessageDigest.getInstance("MD5")

        // StringBuilder使用でメモリ確保回数を削減
        val content = StringBuilder()
        details.forEachIndexed { index, detail ->
            if (index > 0) content.append("|")
            content.append(detail.id).append(":").append(detail.hashCode())
        }

        return digest.digest(content.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * DetailContent リストの推定JSONサイズを計算する（ストリーミング判定用）
     */
    private fun estimateJsonSize(details: List<DetailContent>): Int {
        if (details.isEmpty()) return 100 // 基本構造分

        // 最初の数件をサンプリングして平均サイズを推定
        val sampleSize = minOf(10, details.size)
        var totalSampleSize = 0

        for (i in 0 until sampleSize) {
            val detail = details[i]
            // 概算：ID(50) + 基本フィールド(200) + テキスト/URL長
            totalSampleSize += 250 + when (detail) {
                is DetailContent.Text -> detail.htmlContent.length
                is DetailContent.Image -> (detail.imageUrl?.length ?: 0) + (detail.prompt?.length ?: 0)
                is DetailContent.Video -> (detail.videoUrl?.length ?: 0) + (detail.prompt?.length ?: 0)
                else -> 100
            }
        }

        val avgItemSize = totalSampleSize / sampleSize
        return avgItemSize * details.size + 1000 // 構造オーバーヘッド
    }

    /**
     * 大量データ向けのメモリ効率的なJSON書き込み処理。
     * - JsonWriter を使った逐次書き込みでリスト全体の巨大な中間文字列生成を回避。
     * - 各要素を Gson の型アダプタでシリアライズしつつ 50 件ごとに flush してメモリ保持時間を短縮。
     * - 失敗時は標準の文字列出力方式にフォールバックして確実に永続化する。
     */
    private fun writeJsonStreamOptimized(cacheFile: File, cachedData: CachedDetails) {
        try {
            cacheFile.bufferedWriter().use { bufferedWriter ->
                com.google.gson.stream.JsonWriter(bufferedWriter).use { jsonWriter ->
                    jsonWriter.beginObject()

                    // timestamp
                    jsonWriter.name("timestamp").value(cachedData.timestamp)

                    // checksum
                    jsonWriter.name("checksum")
                    if (cachedData.checksum != null) {
                        jsonWriter.value(cachedData.checksum)
                    } else {
                        jsonWriter.nullValue()
                    }

                    // details array - 真のストリーミング処理
                    jsonWriter.name("details").beginArray()

                    // 各要素を個別にGsonで処理（既存の複雑な型変換を維持）
                    cachedData.details.forEachIndexed { index, detail ->
                        // Gsonの型アダプタを活用してDetailContentを正しくシリアライズ
                        val jsonElement = gson.toJsonTree(detail)
                        gson.getAdapter(com.google.gson.JsonElement::class.java).write(jsonWriter, jsonElement)

                        // より頻繁にフラッシュ（メモリ逼迫対策）
                        if (index % 50 == 0) {
                            jsonWriter.flush()
                        }
                    }

                    jsonWriter.endArray()
                    jsonWriter.endObject()
                    jsonWriter.flush()
                }
            }
            Log.d("DetailCacheManager", "Successfully saved large dataset (${cachedData.details.size} items) using true streaming")
        } catch (e: Exception) {
            Log.e("DetailCacheManager", "Error in streaming JSON write", e)
            // フォールバック: 通常の方法で保存を試行
            try {
                val jsonString = gson.toJson(cachedData)
                cacheFile.writeText(jsonString)
                Log.d("DetailCacheManager", "Fallback to standard JSON write succeeded")
            } catch (fallbackException: Exception) {
                Log.e("DetailCacheManager", "Both streaming and fallback failed", fallbackException)
                throw fallbackException
            }
        }
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

    /**
     * レガシーキー（ドメイン非含有）を使った旧フォーマットのキャッシュファイル。
     */
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
     * スレ詳細をキャッシュファイルへ非同期保存し、レガシー名のファイルがあれば削除する。
     * 既存内容と同一（details が変化なし）の場合は書き換えをスキップする。
     */
    fun saveDetailsAsync(url: String, details: List<DetailContent>) {
        writeExecutor.execute {
            saveDetailsInternal(url, details)
        }
    }

    /**
     * スレ詳細をキャッシュファイルへ同期保存し、レガシー名のファイルがあれば削除する。
     * 既存内容と同一（details が変化なし）の場合は書き換えをスキップする。
     */
    fun saveDetails(url: String, details: List<DetailContent>) {
        saveDetailsInternal(url, details)
    }

    /**
     * チェックサムで差分を確認しつつスレ詳細を保存する内部処理。
     * 変更が無ければ書き込みを省略し、閾値を超えるデータはストリーミング書き込みを選択する。
     * 保存後はレガシーキャッシュを整理する。
     */
    private fun saveDetailsInternal(url: String, details: List<DetailContent>) {
        val cacheFile = getCacheFile(url)
        val legacyFile = getLegacyCacheFile(url)
        val newChecksum = calculateChecksum(details)

        try {
            // 既存内容と差分がなければスキップ（チェックサムで高速比較）
            if (cacheFile.exists()) {
                val existingChecksum = readExistingChecksum(cacheFile)
                if (existingChecksum != null && existingChecksum == newChecksum) {
                    Log.d("DetailCacheManager", "Cache unchanged for $url (checksum match); skipping write.")
                    // レガシー名の掃除だけは行う
                    runCatching { if (legacyFile.exists()) legacyFile.delete() }
                    return
                } else if (existingChecksum == null) {
                    Log.w("DetailCacheManager", "Failed to read checksum for $url; proceeding with rewrite.")
                }
            }

            Log.d("DetailCacheManager", "Saving to cache file: ${cacheFile.absolutePath}")
            val cachedData = CachedDetails(System.currentTimeMillis(), details, newChecksum)

            // デバイス性能に応じた動的閾値設定
            val runtime = Runtime.getRuntime()
            val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            val memoryMB = availableMemory / 1024 / 1024

            // デバイス性能に応じて閾値を調整
            val (sizeThreshold, memoryThreshold) = when {
                memoryMB < 256 -> Pair(200, 256 * 1024) // 低メモリ端末
                memoryMB < 512 -> Pair(350, 384 * 1024) // 中程度メモリ端末
                memoryMB < 1024 -> Pair(500, 512 * 1024) // 標準メモリ端末
                else -> Pair(750, 768 * 1024) // 高メモリ端末
            }

            val shouldUseStreaming = details.size > sizeThreshold || estimateJsonSize(details) > memoryThreshold

            if (shouldUseStreaming) {
                Log.d("DetailCacheManager", "Large dataset detected (${details.size} items), using streaming processing")
                writeJsonStreamOptimized(cacheFile, cachedData)
            } else {
                val jsonString = gson.toJson(cachedData)

                // JSON文字列長の監視（警告のみ、ストリーミングは上で判定済み）
                val jsonLength = jsonString.length
                Log.d("DetailCacheManager", "Standard JSON write: ${jsonLength / 1024}KB")

                cacheFile.writeText(jsonString)
            }
            // 旧ファイルが残っていれば削除（容量節約）
            runCatching { if (legacyFile.exists()) legacyFile.delete() }
            Log.d("DetailCacheManager", "Successfully saved cache for $url")
        } catch (e: Exception) {
            Log.e("DetailCacheManager", "Error saving cache for $url", e)
        }
    }

    /**
     * 既存キャッシュファイルから checksum フィールドのみをストリーミングで取得する。
     * 大量の details 配列を読み込まずに比較できるようにする。
     */
    private fun readExistingChecksum(file: File): String? {
        return try {
            file.inputStream().buffered().use { input ->
                JsonReader(InputStreamReader(input)).use { reader ->
                    reader.beginObject()
                    var checksum: String? = null
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "checksum" -> {
                                checksum = if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
                                    reader.nextNull()
                                    null
                                } else {
                                    reader.nextString()
                                }
                            }
                            "details" -> reader.skipValue()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    checksum
                }
            }
        } catch (e: Exception) {
            Log.w("DetailCacheManager", "Failed to stream checksum from cache ${file.name}", e)
            null
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

            // 基本的なJSONフォーマットチェック
            if (jsonString.isBlank() || !jsonString.trim().startsWith("{")) {
                Log.w("DetailCacheManager", "Invalid JSON format in cache file for $url")
                cacheFile.delete()
                return null
            }

            val cachedData: CachedDetails = gson.fromJson(jsonString, object : TypeToken<CachedDetails>() {}.type)

            // データ整合性チェック
            if (cachedData.details.isEmpty()) {
                Log.w("DetailCacheManager", "Empty details in cache for $url")
                return null
            }

            // チェックサムがあれば検証
            cachedData.checksum?.let { savedChecksum ->
                val currentChecksum = calculateChecksum(cachedData.details)
                if (savedChecksum != currentChecksum) {
                    Log.w("DetailCacheManager", "Checksum mismatch for cached data $url. Data may be corrupted.")
                    cacheFile.delete()
                    return null
                }
            }

            Log.d("DetailCacheManager", "Successfully validated cache for $url")
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
        fun isImageName(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
                lower.endsWith(".png") || lower.endsWith(".gif") ||
                lower.endsWith(".webp") || lower.endsWith(".avif")
        }
        val list = mutableListOf<DetailContent>()
        for ((index, f) in files.withIndex()) {
            val uri = f.toURI().toString()
            val name = f.name
            if (name.equals("snapshot.json", ignoreCase = true)) continue
            if (name.endsWith("_thumb.jpg", ignoreCase = true) || name.endsWith("_thumb.jpeg", ignoreCase = true)) {
                continue
            }
            if (isVideoName(name)) {
                val thumbFile = File(f.parentFile, f.nameWithoutExtension + "_thumb.jpg")
                val thumbUri = if (thumbFile.exists() && thumbFile.length() > 0) thumbFile.toURI().toString() else null
                list += DetailContent.Video(
                    id = "video_${uri.hashCode().toUInt().toString(16)}",
                    videoUrl = uri,
                    prompt = null,
                    fileName = name,
                    thumbnailUrl = thumbUri
                )
            } else if (isImageName(name)) {
                list += DetailContent.Image(id = "image_${uri.hashCode().toUInt().toString(16)}", imageUrl = uri, prompt = null, fileName = name)
            } else {
                Log.d("DetailCacheManager", "Skipping non-media file in archive: $name")
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
     * 総サイズが `limitBytes` を超える場合、未読が無いスレから閲覧・更新時刻が古い順にアーカイブとキャッシュを削除し、
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
    /** すべてのスレッド内容キャッシュおよび媒体アーカイブを削除し、空のディレクトリを再初期化する。 */
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

    /**
     * リソースのクリーンアップ（アプリ終了時などに呼び出す）
     */
    fun cleanup() {
        if (!writeExecutor.isShutdown) {
            writeExecutor.shutdown()
            try {
                if (!writeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    Log.w("DetailCacheManager", "Executor did not terminate gracefully, forcing shutdown")
                    writeExecutor.shutdownNow()
                    // 再度終了を待機
                    if (!writeExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                        Log.e("DetailCacheManager", "Executor did not terminate after forced shutdown")
                    }
                }
            } catch (e: InterruptedException) {
                Log.w("DetailCacheManager", "Interrupted while waiting for executor termination")
                Thread.currentThread().interrupt()
                writeExecutor.shutdownNow()
            }
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
