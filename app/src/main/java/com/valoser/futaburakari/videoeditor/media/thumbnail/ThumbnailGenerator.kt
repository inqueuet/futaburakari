package com.valoser.futaburakari.videoeditor.media.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import com.valoser.futaburakari.videoeditor.domain.model.Thumbnail
import com.valoser.futaburakari.videoeditor.domain.model.VideoClip
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashMap
import java.util.Collections
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.concurrent.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * サムネイル生成クラス
 * 大型フィルムストリップ用の高解像度サムネイルを生成
 */
@Singleton
class ThumbnailGenerator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // 大型フィルムストリップ用の高解像度サムネイル
        private const val THUMBNAIL_TARGET_HEIGHT = 96 // 実描画サイズに合わせて圧縮
        private const val THUMBNAIL_MAX_WIDTH = 384      // 横長動画の上限幅
        private const val DEFAULT_INTERVAL = THUMBNAIL_BASE_INTERVAL_MS // 0.1秒間隔
        private const val WEBP_QUALITY = 70
        private const val MAX_THUMBNAILS_PER_CLIP = 100
        private const val MAX_RETRIEVER_POOL_SIZE = 4
        private const val BITMAP_CACHE_SIZE_BYTES = 8 * 1024 * 1024 // 8MB
    }

    private val generationSemaphore = Semaphore(determineMaxConcurrentGeneration())
    private val retrieverPoolLock = ReentrantLock()
    private val retrieverPool = object : LinkedHashMap<String, MediaMetadataRetriever>(MAX_RETRIEVER_POOL_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadataRetriever>?): Boolean {
            val shouldRemove = size > MAX_RETRIEVER_POOL_SIZE
            if (shouldRemove) {
                eldest?.value?.release()
            }
            return shouldRemove
        }
    }
    private val bitmapCacheLock = ReentrantLock()
    private val bitmapCache = object : LruCache<String, Bitmap>(BITMAP_CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val compressionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val compressionRegistryLock = ReentrantLock()
    private val compressionInFlight = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * サムネイルを生成
     */
    suspend fun generate(
        clip: VideoClip,
        interval: Long = DEFAULT_INTERVAL
    ): Result<List<Thumbnail>> = generateRange(
        clip = clip,
        rangeStartMs = clip.startTime,
        rangeEndMs = clip.endTime,
        interval = interval
    )

    suspend fun generateRange(
        clip: VideoClip,
        rangeStartMs: Long,
        rangeEndMs: Long,
        interval: Long = DEFAULT_INTERVAL,
        persistToDisk: Boolean = true
    ): Result<List<Thumbnail>> = generationSemaphore.withPermit {
        withContext(Dispatchers.IO) {
            try {
                val effectiveStart = rangeStartMs
                    .coerceIn(clip.startTime, clip.endTime)
                val effectiveEnd = rangeEndMs
                    .coerceIn(clip.startTime, clip.endTime)

                if (effectiveEnd <= effectiveStart) {
                    return@withContext Result.success(emptyList())
                }

                val step = interval.coerceAtLeast(1L)
                val rangeDuration = (effectiveEnd - effectiveStart).coerceAtLeast(step)
                val adaptiveStep = calculateAdaptiveStep(step, rangeDuration)

                var retriever: MediaMetadataRetriever? = null
                try {
                    retriever = acquireRetriever(clip)

                    val (targetWidth, targetHeight) = resolveTargetDimensions(retriever)
                    val thumbnails = mutableListOf<Thumbnail>()

                    var currentTime = effectiveStart
                    while (currentTime < effectiveEnd) {
                        val sampleTime = normalizeThumbnailTime(currentTime)
                            .coerceIn(clip.startTime, clip.endTime)
                        if (thumbnails.lastOrNull()?.time == sampleTime) {
                            currentTime += adaptiveStep
                            continue
                        }

                        val cacheKey = buildCacheKey(clip.id, sampleTime)
                        val file = if (persistToDisk) getThumbnailFile(clip.id, sampleTime) else null
                        val cachedBitmap = getCachedBitmap(cacheKey)
                        val thumbnail = when {
                            cachedBitmap != null -> Thumbnail(
                                time = sampleTime,
                                path = file?.absolutePath.orEmpty(),
                                bitmap = cachedBitmap
                            )
                            persistToDisk && file != null && file.exists() -> Thumbnail(
                                time = sampleTime,
                                path = file.absolutePath
                            )
                            else -> {
                                val bitmap = extractFrame(
                                    retriever,
                                    sampleTime,
                                    targetWidth,
                                    targetHeight
                                )
                                
                                bitmap?.let { scaled ->
                                    putCachedBitmap(cacheKey, scaled)
                                    if (persistToDisk && file != null) {
                                        scheduleCompression(cacheKey, file, scaled)
                                    }

                                    Thumbnail(
                                        time = sampleTime,
                                        path = file?.absolutePath.orEmpty(),
                                        bitmap = scaled
                                    )
                                }
                            }
                        }

                        thumbnail?.let { thumbnails.add(it) }
                        currentTime += adaptiveStep
                    }

                    val finalSampleTime = normalizeThumbnailTime(effectiveEnd - 1)
                        .coerceIn(clip.startTime, clip.endTime)
                    val hasFinalThumbnail = thumbnails.any { it.time == finalSampleTime }
                    if (!hasFinalThumbnail) {
                        val finalCacheKey = buildCacheKey(clip.id, finalSampleTime)
                        val cachedBitmap = getCachedBitmap(finalCacheKey)
                        val file = if (persistToDisk) getThumbnailFile(clip.id, finalSampleTime) else null
                        val thumbnail = when {
                            cachedBitmap != null -> Thumbnail(
                                time = finalSampleTime,
                                path = file?.absolutePath.orEmpty(),
                                bitmap = cachedBitmap
                            )
                            persistToDisk && file != null && file.exists() -> Thumbnail(
                                time = finalSampleTime,
                                path = file.absolutePath
                            )
                            else -> {
                                val bitmap = extractFrame(
                                    retriever,
                                    finalSampleTime,
                                    targetWidth,
                                    targetHeight
                                )

                                bitmap?.let { finalBitmap ->
                                    putCachedBitmap(finalCacheKey, finalBitmap)
                                    if (persistToDisk && file != null) {
                                        scheduleCompression(finalCacheKey, file, finalBitmap)
                                    }

                                    Thumbnail(
                                        time = finalSampleTime,
                                        path = file?.absolutePath.orEmpty(),
                                        bitmap = finalBitmap
                                    )
                                }
                            }
                        }

                        thumbnail?.let { thumbnails.add(it) }
                    }

                    thumbnails.sortBy { it.time }
                    Result.success(thumbnails)
                } finally {
                    retriever?.let { releaseRetriever(clip, it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun normalizeThumbnailTime(timeMs: Long): Long {
        val grid = THUMBNAIL_BASE_INTERVAL_MS.coerceAtLeast(1)
        return ((timeMs + grid / 2) / grid) * grid
    }

    /**
     * サムネイルファイルのパスを取得
     */
    private fun getThumbnailFile(clipId: String, timeMs: Long): File {
        val dir = File(context.cacheDir, "thumbnails")
        return File(dir, "${clipId}_${timeMs}.webp")
    }

    private fun buildCacheKey(clipId: String, timeMs: Long): String = "${clipId}_${timeMs}"

    private fun getCachedBitmap(key: String): Bitmap? =
        bitmapCacheLock.withLock { bitmapCache.get(key) }

    private fun putCachedBitmap(key: String, bitmap: Bitmap) {
        bitmapCacheLock.withLock { bitmapCache.put(key, bitmap) }
    }

    private fun removeCachedBitmap(key: String) {
        bitmapCacheLock.withLock { bitmapCache.remove(key) }
    }

    private fun clearBitmapCache() {
        bitmapCacheLock.withLock { bitmapCache.evictAll() }
    }

    private fun snapshotBitmapKeys(): Set<String> =
        bitmapCacheLock.withLock { bitmapCache.snapshot().keys.toSet() }

    /**
     * サムネイルキャッシュをクリア
     */
    fun clearCache() {
        val dir = File(context.cacheDir, "thumbnails")
        dir.deleteRecursively()
        clearBitmapCache()
        compressionScope.coroutineContext.cancelChildren()
        compressionRegistryLock.withLock { compressionInFlight.clear() }
        retrieverPoolLock.withLock {
            retrieverPool.values.forEach { it.release() }
            retrieverPool.clear()
        }
    }

    /**
     * 特定のクリップのサムネイルをクリア
     */
    fun clearClipCache(clipId: String) {
        val dir = File(context.cacheDir, "thumbnails")
        dir.listFiles()?.filter { it.name.startsWith(clipId) }?.forEach { it.delete() }
        val snapshotKeys = snapshotBitmapKeys()
        snapshotKeys.filter { it.startsWith("${clipId}_") }.forEach { removeCachedBitmap(it) }
    }

    private fun calculateAdaptiveStep(baseStep: Long, clipDuration: Long): Long {
        val maxFrames = MAX_THUMBNAILS_PER_CLIP.coerceAtLeast(1)
        val adaptive = ceil(clipDuration.toDouble() / maxFrames).toLong().coerceAtLeast(baseStep)
        return adaptive.coerceAtLeast(baseStep)
    }

    private fun resolveTargetDimensions(retriever: MediaMetadataRetriever): Pair<Int, Int> {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull()
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull()

        val (displayWidth, displayHeight) = when {
            width == null || height == null -> THUMBNAIL_TARGET_HEIGHT to THUMBNAIL_TARGET_HEIGHT
            rotation != null && rotation % 180 != 0 -> height to width
            else -> width to height
        }

        val aspectRatio = if (displayHeight != 0) {
            displayWidth.toFloat() / displayHeight.toFloat()
        } else {
            1f
        }

        val targetHeight = THUMBNAIL_TARGET_HEIGHT
        val targetWidth = (targetHeight * aspectRatio)
            .roundToInt()
            .coerceAtLeast(1)
            .coerceAtMost(THUMBNAIL_MAX_WIDTH)

        return targetWidth to targetHeight
    }

    private fun extractFrame(
        retriever: MediaMetadataRetriever,
        timeMs: Long,
        width: Int,
        height: Int
    ): Bitmap? {
        val timeUs = timeMs * 1000
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                width,
                height
            )
        } else {
            retriever.getFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST
            )?.let { frame ->
                val scaled = Bitmap.createScaledBitmap(frame, width, height, true)
                frame.recycle()
                scaled
            }
        }
    }

    private fun determineMaxConcurrentGeneration(): Int {
        val available = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return max(2, available - 1)
    }

    private fun acquireRetriever(clip: VideoClip): MediaMetadataRetriever {
        val key = clip.source.toString()
        retrieverPoolLock.withLock {
            retrieverPool.remove(key)?.let { return it }
        }

        val retriever = MediaMetadataRetriever()
        context.contentResolver.openFileDescriptor(clip.source, "r")?.use { pfd ->
            retriever.setDataSource(pfd.fileDescriptor)
        } ?: throw IllegalArgumentException("Cannot open file descriptor for ${clip.source}")

        return retriever
    }

    private fun releaseRetriever(clip: VideoClip, retriever: MediaMetadataRetriever) {
        val key = clip.source.toString()
        retrieverPoolLock.withLock {
            val previous = retrieverPool.put(key, retriever)
            if (previous != null && previous !== retriever) {
                previous.release()
            }
        }
    }

    private fun scheduleCompression(cacheKey: String, file: File, bitmap: Bitmap) {
        if (!registerCompression(cacheKey)) return

        compressionScope.launch {
            try {
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { out ->
                        bitmap.compress(
                            Bitmap.CompressFormat.WEBP_LOSSY,
                            WEBP_QUALITY,
                            out
                        )
                    }
                }
            } catch (_: Exception) {
                // 失敗してもメモリキャッシュから再生成できるため握り潰す
            } finally {
                unregisterCompression(cacheKey)
            }
        }
    }

    private fun registerCompression(cacheKey: String): Boolean =
        compressionRegistryLock.withLock {
            if (compressionInFlight.contains(cacheKey)) return false
            compressionInFlight.add(cacheKey)
            true
        }

    private fun unregisterCompression(cacheKey: String) {
        compressionRegistryLock.withLock {
            compressionInFlight.remove(cacheKey)
        }
    }
}
