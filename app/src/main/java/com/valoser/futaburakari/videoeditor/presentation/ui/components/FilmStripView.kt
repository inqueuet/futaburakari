package com.valoser.futaburakari.videoeditor.presentation.ui.components

import android.app.Application
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.valoser.futaburakari.videoeditor.core.di.ThumbnailGeneratorEntryPoint
import com.valoser.futaburakari.videoeditor.domain.model.VideoClip
import com.valoser.futaburakari.videoeditor.media.thumbnail.THUMBNAIL_BASE_INTERVAL_MS
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * フィルムストリップビュー
 * 0.1秒間隔の大型サムネイル表示（64dp高さ）
 */
@Composable
fun FilmStripView(
    clips: List<VideoClip>,
    playhead: Long,
    zoom: Float,
    timelineDuration: Long,
    requestedStartMs: Long,
    requestedEndMs: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val application = remember(context) { context.applicationContext as Application }
    val thumbnailGenerator = remember(application) {
        EntryPointAccessors.fromApplication(
            application,
            ThumbnailGeneratorEntryPoint::class.java
        ).thumbnailGenerator()
    }
    val scope = rememberCoroutineScope()

    // サムネイルキャッシュ
    val thumbnailCache = remember { mutableStateMapOf<String, android.graphics.Bitmap>() }
    // クリップID集合を常に最新化
    val clipSignature = remember(clips) {
        clips.map { clip ->
            FilmStripClipSignature(
                id = clip.id,
                startTime = clip.startTime,
                endTime = clip.endTime,
                position = clip.position
            )
        }
    }
    val currentClipIdsState = rememberUpdatedState(clipSignature.map { it.id }.toSet())

    LaunchedEffect(clipSignature) {
        // 削除されたクリップのキャッシュを即時除去
        val activeIds = currentClipIdsState.value
        val staleKeys = thumbnailCache.keys.filter { key ->
            val id = key.substringBefore('_')
            id !in activeIds
        }
        staleKeys.forEach { thumbnailCache.remove(it) }
    }

    val thumbnailHeightPx = with(density) { 64.dp.toPx() }
    val targetTileWidthPx = with(density) { 24.dp.toPx() }

    val requestedRanges = remember { mutableStateMapOf<String, Pair<Long, Long>>() }
    val clipLocks = remember { mutableMapOf<String, Mutex>() }
    val viewportSpanMs = (requestedEndMs - requestedStartMs).coerceAtLeast(0L)
    val preloadWindowMs = min(viewportSpanMs, 1_500L)
    val requestStart = (requestedStartMs - preloadWindowMs).coerceAtLeast(0L)
    val requestEnd = requestedEndMs + preloadWindowMs
    val triggerIntervalMs = max(THUMBNAIL_BASE_INTERVAL_MS * 5, THUMBNAIL_BASE_INTERVAL_MS)
    val quantizedStart = (requestStart / triggerIntervalMs) * triggerIntervalMs
    val quantizedEnd = ((requestEnd + triggerIntervalMs - 1) / triggerIntervalMs) * triggerIntervalMs

    fun mapTimelineToSource(clip: VideoClip, timelineTime: Long): Long {
        val speed = clip.speed.takeIf { it > 0f } ?: 1f
        val timelineOffset = (timelineTime - clip.position).coerceAtLeast(0L)
        val sourceOffset = (timelineOffset.toDouble() * speed.toDouble()).toLong()
        val sourceTime = clip.startTime + sourceOffset
        return sourceTime.coerceIn(clip.startTime, clip.endTime)
    }

    fun normalizeTimeForCache(timeMs: Long): Long {
        val grid = THUMBNAIL_BASE_INTERVAL_MS.coerceAtLeast(1)
        return ((timeMs + grid / 2) / grid) * grid
    }

    fun mergeSegments(segments: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (segments.isEmpty()) return segments
        val sorted = segments.sortedBy { it.first }
        val result = mutableListOf<Pair<Long, Long>>()
        var current = sorted.first()
        for (i in 1 until sorted.size) {
            val next = sorted[i]
            if (next.first <= current.second) {
                current = current.first to max(current.second, next.second)
            } else {
                result.add(current)
                current = next
            }
        }
        result.add(current)
        return result
    }

    fun getThumbnailBitmap(clipId: String, targetTime: Long): android.graphics.Bitmap? {
        val directKey = "${clipId}_${targetTime}"
        thumbnailCache[directKey]?.let { return it }

        val prefix = "${clipId}_"
        var closestKey: String? = null
        var smallestDiff = Long.MAX_VALUE
        thumbnailCache.forEach { (key, _) ->
            if (!key.startsWith(prefix)) return@forEach
            val time = key.substringAfter('_').toLongOrNull() ?: return@forEach
            val diff = abs(time - targetTime)
            if (diff < smallestDiff) {
                smallestDiff = diff
                closestKey = key
            }
        }
        return closestKey?.let { thumbnailCache[it] }
    }

    fun sourceToTimeline(clip: VideoClip, sourceTime: Long): Long {
        val safeSpeed = clip.speed.takeIf { it.isFinite() && it > 0f } ?: 1f
        val clampedSource = sourceTime.coerceIn(clip.startTime, clip.endTime)
        val offsetSource = clampedSource - clip.startTime
        val timelineOffset = (offsetSource / safeSpeed).toLong()
        return clip.position + timelineOffset
    }

    fun pruneCaches() {
        val maxEntries = 600
        if (thumbnailCache.isEmpty() && requestedRanges.isEmpty()) return

        val retentionStartTimeline = (requestedStartMs - preloadWindowMs * 2).coerceAtLeast(0L)
        val retentionEndTimeline = requestedEndMs + preloadWindowMs * 2

        // Clip がなくなったら範囲情報も削除
        requestedRanges.keys.toList().forEach { clipId ->
            val clip = clips.firstOrNull { it.id == clipId }
            if (clip == null) {
                requestedRanges.remove(clipId)
            } else {
                val clipTimelineEnd = clip.position + clip.duration
                val allowedTimelineStart = retentionStartTimeline.coerceIn(clip.position, clipTimelineEnd)
                val allowedTimelineEnd = retentionEndTimeline.coerceIn(clip.position, clipTimelineEnd)
                val allowedStart = mapTimelineToSource(clip, allowedTimelineStart)
                val allowedEnd = mapTimelineToSource(clip, max(allowedTimelineStart, allowedTimelineEnd))
                val normalizedStart = normalizeTimeForCache(allowedStart)
                val normalizedEnd = normalizeTimeForCache(max(allowedStart, allowedEnd))
                val existing = requestedRanges[clipId]
                if (existing != null) {
                    val clampedStart = existing.first.coerceIn(normalizedStart, normalizedEnd)
                    val clampedEnd = existing.second.coerceIn(clampedStart, normalizedEnd)
                    requestedRanges[clipId] = clampedStart to clampedEnd
                } else {
                    requestedRanges[clipId] = normalizedStart to normalizedStart
                }
            }
        }

        if (thumbnailCache.size <= maxEntries) {
            return
        }

        val sortedKeys = thumbnailCache.keys.map { key ->
            val clipId = key.substringBefore('_')
            val clip = clips.firstOrNull { it.id == clipId }
            val sourceTime = key.substringAfter('_').toLongOrNull()
            val distance = if (clip == null || sourceTime == null) {
                Long.MAX_VALUE
            } else {
                val timeline = sourceToTimeline(clip, sourceTime)
                when {
                    timeline in retentionStartTimeline..retentionEndTimeline -> 0L
                    timeline < retentionStartTimeline -> retentionStartTimeline - timeline
                    else -> timeline - retentionEndTimeline
                }
            }
            key to distance
        }.sortedBy { it.second }

        val keysToKeep = sortedKeys.take(maxEntries).mapTo(mutableSetOf()) { it.first }
        thumbnailCache.keys.toList().forEach { key ->
            if (key !in keysToKeep) {
                thumbnailCache.remove(key)
            }
        }
    }

    // サムネイル生成（表示範囲に応じてオンデマンド実行）
    LaunchedEffect(clipSignature, quantizedStart, quantizedEnd, zoom) {
        val safeZoom = zoom.coerceAtLeast(0.01f)
        val zoomScaledMinInterval = when {
            safeZoom >= 8f -> THUMBNAIL_BASE_INTERVAL_MS * 4
            safeZoom >= 4f -> THUMBNAIL_BASE_INTERVAL_MS * 3
            else -> THUMBNAIL_BASE_INTERVAL_MS * 2
        }
        val baseIntervalMs = ((targetTileWidthPx / safeZoom).toLong())
            .coerceAtLeast(zoomScaledMinInterval)
        val activeIds = clipSignature.map { it.id }.toSet()
        val staleRangeKeys = requestedRanges.keys.filter { it !in activeIds }
        staleRangeKeys.forEach { requestedRanges.remove(it) }

        clipSignature.forEach { signature ->
            val clip = clips.firstOrNull { it.id == signature.id } ?: return@forEach
            val clipStart = clip.position
            val clipEnd = clip.position + clip.duration
            val overlapStart = max(clipStart, quantizedStart)
            val overlapEnd = min(clipEnd, quantizedEnd)

            if (overlapEnd <= overlapStart) return@forEach

            var sourceStart = mapTimelineToSource(clip, overlapStart)
            var sourceEnd = mapTimelineToSource(clip, overlapEnd)
            if (sourceEnd <= sourceStart) {
                sourceEnd = (sourceStart + THUMBNAIL_BASE_INTERVAL_MS).coerceAtMost(clip.endTime)
            }
            if (sourceEnd <= sourceStart) return@forEach

            var normalizedStart = normalizeTimeForCache(sourceStart)
            var normalizedEnd = normalizeTimeForCache(sourceEnd)
            if (normalizedEnd <= normalizedStart) {
                normalizedEnd = normalizedStart + baseIntervalMs
            }

            val existingRange = requestedRanges[clip.id]
            val missingSegments = mutableListOf<Pair<Long, Long>>()

            if (existingRange == null) {
                missingSegments += normalizedStart to normalizedEnd
            } else {
                if (normalizedStart < existingRange.first) {
                    val segmentEnd = min(existingRange.first, normalizedEnd)
                    if (segmentEnd > normalizedStart) {
                        missingSegments += normalizedStart to segmentEnd
                    }
                }
                if (normalizedEnd > existingRange.second) {
                    val segmentStart = max(normalizedStart, existingRange.second)
                    if (normalizedEnd > segmentStart) {
                        missingSegments += segmentStart to normalizedEnd
                    }
                }
            }

            if (missingSegments.isEmpty()) return@forEach

            val mergedSegments = mergeSegments(missingSegments)
            if (mergedSegments.isEmpty()) return@forEach

            val mergedStart = mergedSegments.minOf { it.first }
            val mergedEnd = mergedSegments.maxOf { it.second }
            val updatedStart = existingRange?.first?.let { min(it, mergedStart) } ?: mergedStart
            val updatedEnd = existingRange?.second?.let { max(it, mergedEnd) } ?: mergedEnd

            val retentionStartTimeline = (requestedStartMs - preloadWindowMs)
                .coerceAtLeast(clip.position)
            val retentionEndTimeline = (requestedEndMs + preloadWindowMs)
                .coerceAtMost(clip.position + clip.duration)
            val allowedSourceStart = normalizeTimeForCache(
                mapTimelineToSource(clip, retentionStartTimeline)
            )
            val allowedSourceEnd = normalizeTimeForCache(
                mapTimelineToSource(clip, max(retentionStartTimeline, retentionEndTimeline))
            )
            val clampedStart = updatedStart.coerceAtLeast(allowedSourceStart)
            val clampedEnd = updatedEnd.coerceAtMost(max(clampedStart, allowedSourceEnd))
            requestedRanges[clip.id] = clampedStart to max(clampedStart, clampedEnd)

            val mutex = clipLocks.getOrPut(clip.id) { Mutex() }

            scope.launch {
                mutex.withLock {
                    mergedSegments.forEach { (segmentStart, segmentEnd) ->
                        if (segmentEnd <= segmentStart) return@forEach
                        thumbnailGenerator.generateRange(
                            clip = clip,
                            rangeStartMs = segmentStart,
                            rangeEndMs = segmentEnd,
                            interval = baseIntervalMs,
                            persistToDisk = false
                        ).onSuccess { thumbnails ->
                            if (!currentClipIdsState.value.contains(clip.id)) return@onSuccess
                            thumbnails.forEach { thumbnail ->
                                val normalizedTime = normalizeTimeForCache(thumbnail.time)
                                val cacheKey = "${clip.id}_${normalizedTime}"
                                if (!thumbnailCache.containsKey(cacheKey)) {
                                    val cachedBitmap = thumbnail.bitmap
                                    if (cachedBitmap != null) {
                                        thumbnailCache[cacheKey] = cachedBitmap
                                    } else {
                                        val file = File(thumbnail.path)
                                        if (file.exists()) {
                                            val bitmap = withContext(Dispatchers.IO) {
                                                BitmapFactory.decodeFile(file.absolutePath)
                                            }
                                            if (bitmap != null) {
                                                thumbnailCache[cacheKey] = bitmap
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        pruneCaches()
    }

    // 可変密度：画面上のタイル幅を一定に保つ（zoomに応じて時間間隔を自動計算）
    val contentDuration = max(
        timelineDuration,
        clips.maxOfOrNull { it.position + it.duration } ?: 0L
    ).coerceAtLeast(1L)
    val contentWidthPx = max(contentDuration * zoom, with(density) { 1.dp.toPx() })
    val contentWidthDp = with(density) { contentWidthPx.toDp() }

    Box(
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier
                .width(contentWidthDp)
                .fillMaxHeight()
                .background(Color.Black)
        ) {
            clips.forEach { clip ->
                val speed = 1.0f // 将来: clip.playbackSpeed が入ったら置き換え
                val clipTimelineDuration = clip.duration.coerceAtLeast(0L)
                // 画面上のタイル幅 -> 実時間間隔（ms）に変換（下限/上限で暴れ防止）
                val zoomScaledMinInterval = when {
                    zoom >= 8f -> THUMBNAIL_BASE_INTERVAL_MS * 4
                    zoom >= 4f -> THUMBNAIL_BASE_INTERVAL_MS * 3
                    else -> THUMBNAIL_BASE_INTERVAL_MS * 2
                }
                val intervalMs = ((targetTileWidthPx / zoom).toLong())
                    .coerceAtLeast(zoomScaledMinInterval)
                val baseIntervalMs = THUMBNAIL_BASE_INTERVAL_MS

                var currentDisplayMs = 0L

                while (currentDisplayMs < clipTimelineDuration) {
                    // 画面上の経過時間 -> 素材上のサンプリング時刻（速度補正）
                    val desiredSourceTime = clip.startTime + (currentDisplayMs / speed).toLong()

                    // 既存キャッシュ(0.1s刻み)から最も近いキーを選ぶ（当面の改善）
                    // 例: baseIntervalMs（現状は100ms）グリッドへ丸める。将来的には generateAt() で厳密生成へ。
                    val nearestTime = ((desiredSourceTime + baseIntervalMs / 2) / baseIntervalMs) * baseIntervalMs
                    val thumbnail = getThumbnailBitmap(clip.id, nearestTime)

                    val thumbnailStartX = (clip.position + currentDisplayMs) * zoom
                    val thumbnailDisplayWidth = (intervalMs * zoom)

                    thumbnail?.let {
                        drawBitmap(
                            image = it.asImageBitmap(),
                            topLeft = Offset(thumbnailStartX, 0f),
                            dstSize = IntSize(
                                thumbnailDisplayWidth.toInt().coerceAtLeast(1),
                                thumbnailHeightPx.toInt()
                            )
                        )
                    } ?: run {
                        // サムネイルがない場合はグレーの矩形を描画
                        drawRect(
                            color = Color.DarkGray,
                            topLeft = Offset(thumbnailStartX, 0f),
                            size = Size(thumbnailDisplayWidth.coerceAtLeast(1f), thumbnailHeightPx)
                        )
                    }

                    currentDisplayMs += intervalMs
                }
            }
        }
    }
}

private data class FilmStripClipSignature(
    val id: String,
    val startTime: Long,
    val endTime: Long,
    val position: Long
)

private fun DrawScope.drawBitmap(
    image: androidx.compose.ui.graphics.ImageBitmap,
    topLeft: Offset,
    dstSize: IntSize
) {
    if (dstSize.width <= 0 || dstSize.height <= 0 || image.width <= 0 || image.height <= 0) return

    val dstAspect = dstSize.width.toFloat() / dstSize.height.toFloat()
    val srcAspect = image.width.toFloat() / image.height.toFloat()

    val (srcOffset, srcSize) = if (srcAspect > dstAspect) {
        // 横長 -> 横をトリミング
        val targetWidth = (image.height * dstAspect)
            .toInt()
            .coerceAtLeast(1)
            .coerceAtMost(image.width)
        val offsetX = ((image.width - targetWidth) / 2).coerceAtLeast(0)
        IntOffset(offsetX, 0) to IntSize(targetWidth, image.height)
    } else {
        // 縦長 -> 縦をトリミング
        val targetHeight = (image.width / dstAspect)
            .toInt()
            .coerceAtLeast(1)
            .coerceAtMost(image.height)
        val offsetY = ((image.height - targetHeight) / 2).coerceAtLeast(0)
        IntOffset(0, offsetY) to IntSize(image.width, targetHeight)
    }

    drawImage(
        image = image,
        srcOffset = srcOffset,
        srcSize = srcSize,
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
        dstSize = dstSize
    )
}
