package com.valoser.futaburakari.videoeditor.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * キャッシュ管理クラス
 * サムネイルと波形データをメモリ・ディスクキャッシュで管理
 */
@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // 大型サムネイル用に増量
        private const val MAX_THUMBNAIL_CACHE_SIZE = 80 // 最大80枚
        private const val MAX_WAVEFORM_CACHE_SIZE = 30 // 最大30個
        private const val WEBP_QUALITY = 85
    }

    // メモリキャッシュ
    private val thumbnailCache = LruCache<String, Bitmap>(MAX_THUMBNAIL_CACHE_SIZE)
    private val waveformCache = LruCache<String, FloatArray>(MAX_WAVEFORM_CACHE_SIZE)

    /**
     * サムネイルを取得（メモリ → ディスク の順で探す）
     */
    fun getThumbnail(clipId: String, index: Int): Bitmap? {
        val key = "$clipId-$index"

        // メモリキャッシュを確認
        thumbnailCache.get(key)?.let { return it }

        // ディスクキャッシュを確認
        val file = getThumbnailFile(clipId, index)
        if (file.exists()) {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            thumbnailCache.put(key, bitmap)
            return bitmap
        }

        return null
    }

    /**
     * サムネイルをキャッシュ
     */
    fun cacheThumbnail(clipId: String, index: Int, bitmap: Bitmap) {
        val key = "$clipId-$index"

        // メモリキャッシュに保存
        thumbnailCache.put(key, bitmap)

        // ディスクに保存
        val file = getThumbnailFile(clipId, index)
        file.parentFile?.mkdirs()

        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, WEBP_QUALITY, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 波形データを取得（メモリ → ディスク の順で探す）
     */
    fun getWaveform(clipId: String): FloatArray? {
        // メモリキャッシュを確認
        waveformCache.get(clipId)?.let { return it }

        // ディスクキャッシュを確認
        val file = getWaveformFile(clipId)
        if (file.exists()) {
            try {
                val bytes = file.readBytes()
                val floatArray = FloatArray(bytes.size / 4)
                for (i in floatArray.indices) {
                    val offset = i * 4
                    val bits = ((bytes[offset].toInt() and 0xFF) shl 24) or
                            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                            (bytes[offset + 3].toInt() and 0xFF)
                    floatArray[i] = Float.fromBits(bits)
                }
                waveformCache.put(clipId, floatArray)
                return floatArray
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return null
    }

    /**
     * 波形データをキャッシュ
     */
    fun cacheWaveform(clipId: String, waveform: FloatArray) {
        // メモリキャッシュに保存
        waveformCache.put(clipId, waveform)

        // ディスクに保存
        val file = getWaveformFile(clipId)
        file.parentFile?.mkdirs()

        try {
            val bytes = ByteArray(waveform.size * 4)
            for (i in waveform.indices) {
                val bits = waveform[i].toBits()
                val offset = i * 4
                bytes[offset] = ((bits shr 24) and 0xFF).toByte()
                bytes[offset + 1] = ((bits shr 16) and 0xFF).toByte()
                bytes[offset + 2] = ((bits shr 8) and 0xFF).toByte()
                bytes[offset + 3] = (bits and 0xFF).toByte()
            }
            file.writeBytes(bytes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * サムネイルファイルのパスを取得
     */
    private fun getThumbnailFile(clipId: String, index: Int): File {
        val dir = File(context.cacheDir, "thumbnails")
        return File(dir, "${clipId}_${String.format("%03d", index)}.webp")
    }

    /**
     * 波形ファイルのパスを取得
     */
    private fun getWaveformFile(clipId: String): File {
        val dir = File(context.cacheDir, "waveforms")
        return File(dir, "${clipId}.bin")
    }

    /**
     * 全キャッシュをクリア
     */
    fun clearCache() {
        // メモリキャッシュをクリア
        thumbnailCache.evictAll()
        waveformCache.evictAll()

        // ディスクキャッシュをクリア
        context.cacheDir.deleteRecursively()
    }

    /**
     * 特定のクリップのキャッシュをクリア
     */
    fun clearClipCache(clipId: String) {
        // サムネイルをクリア
        val thumbnailDir = File(context.cacheDir, "thumbnails")
        thumbnailDir.listFiles()?.filter { it.name.startsWith(clipId) }?.forEach { it.delete() }

        // 波形をクリア
        val waveformFile = getWaveformFile(clipId)
        waveformFile.delete()

        // メモリキャッシュからも削除
        waveformCache.remove(clipId)
    }

    /**
     * アプリ終了時に呼び出す
     */
    fun clearOnExit() {
        clearCache()
    }

    /**
     * キャッシュサイズを取得
     */
    fun getCacheSize(): Long {
        var size = 0L
        val cacheDir = context.cacheDir
        cacheDir.walk().forEach { file ->
            if (file.isFile) {
                size += file.length()
            }
        }
        return size
    }
}
