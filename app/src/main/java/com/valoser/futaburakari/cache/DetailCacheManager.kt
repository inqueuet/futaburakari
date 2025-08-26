package com.valoser.futaburakari.cache

import android.content.Context
import android.util.Log
import com.valoser.futaburakari.DetailContent
import com.google.gson.Gson
import com.google.gson.GsonBuilder // ★ GsonBuilder をインポート
import com.google.gson.reflect.TypeToken
// DetailContentTypeAdapterFactory をインポート (パッケージ名は適宜修正)
// import com.valoser.futaburakari.util.DetailContentTypeAdapterFactory
import java.io.File
import java.util.concurrent.TimeUnit
import com.valoser.futaburakari.UrlNormalizer

data class CachedDetails(
    val timestamp: Long,
    val details: List<DetailContent>
)

class DetailCacheManager(private val context: Context) {

    private val gson: Gson // ★ 初期化を init ブロックに移動
    private val cacheDir: File by lazy {
        File(context.cacheDir, "details_cache").apply { mkdirs() }
    }
    private val archiveRoot: File by lazy {
        File(context.filesDir, "archive_media").apply { mkdirs() }
    }

    init { // ★ init ブロックを修正
        gson = GsonBuilder()
            .registerTypeAdapterFactory(DetailContentTypeAdapterFactory()) // ★ 作成したFactoryを登録
            // .serializeNulls() // 必要であればnullもJSONに出力する設定
            .create()
    }

    private fun getCacheFile(url: String): File {
        val key = UrlNormalizer.threadKey(url)   // ★ 正規化キーに変換
        val fileName = key.sha256()
        Log.d("DetailCacheManager", "Key: $key -> FileName: $fileName")
        return File(cacheDir, fileName)
    }

    fun getArchiveDirForUrl(url: String): File {
        val key = UrlNormalizer.threadKey(url)
        val dirName = key.sha256()
        return File(archiveRoot, dirName).apply { mkdirs() }
    }

    fun saveDetails(url: String, details: List<DetailContent>) {
        val cacheFile = getCacheFile(url)
        Log.d("DetailCacheManager", "Saving to cache file: ${cacheFile.absolutePath}")
        try {
            val cachedData = CachedDetails(System.currentTimeMillis(), details)
            val jsonString = gson.toJson(cachedData)
            Log.d("DetailCacheManager", "JSON string length: ${jsonString.length}")
            cacheFile.writeText(jsonString)
            Log.d("DetailCacheManager", "Successfully saved cache for $url")
        } catch (e: Exception) {
            Log.e("DetailCacheManager", "Error saving cache for $url", e)
        }
    }

    fun loadDetails(url: String): List<DetailContent>? {
        val cacheFile = getCacheFile(url)
        Log.d("DetailCacheManager", "Loading from cache file: ${cacheFile.absolutePath}")

        if (!cacheFile.exists()) {
            Log.d("DetailCacheManager", "Cache file not found: ${cacheFile.name}")
            return null
        }

        return try {
            val jsonString = cacheFile.readText()
            Log.d("DetailCacheManager", "JSON string loaded, length: ${jsonString.length}")

            val cachedData: CachedDetails = gson.fromJson(jsonString, object : TypeToken<CachedDetails>() {}.type)
            Log.d("DetailCacheManager", "Successfully parsed JSON for $url")

            val oneDayInMillis = TimeUnit.DAYS.toMillis(1)
            if (System.currentTimeMillis() - cachedData.timestamp > oneDayInMillis) {
                Log.d("DetailCacheManager", "Cache expired for $url. Deleting.")
                cacheFile.delete()
                null
            } else {
                Log.d("DetailCacheManager", "Cache hit and valid for $url. Returning ${cachedData.details.size} items.")
                cachedData.details
            }
        } catch (e: Exception) {
            Log.e("DetailCacheManager", "Error loading or parsing cache for $url. Deleting cache file.", e)
            cacheFile.delete()
            null
        }
    }

    fun invalidateCache(url: String) {
        val cacheFile = getCacheFile(url)
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
    }

    fun clearArchiveForUrl(url: String) {
        val dir = getArchiveDirForUrl(url)
        if (dir.exists()) {
            if (!dir.deleteRecursively()) {
                Log.w("DetailCacheManager", "Failed to delete archive dir: ${dir.absolutePath}")
            }
        }
    }

    // ★★★ ここから追加 ★★★
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

    private fun String.sha256(): String {
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }
    }
}
