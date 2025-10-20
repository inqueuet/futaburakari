package com.valoser.futaburakari.videoeditor.presentation.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import com.valoser.futaburakari.videoeditor.domain.model.VideoClip
import com.valoser.futaburakari.videoeditor.media.thumbnail.THUMBNAIL_BASE_INTERVAL_MS
import com.valoser.futaburakari.videoeditor.media.thumbnail.ThumbnailGenerator
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val thumbnailGenerator = remember { ThumbnailGenerator(context) }
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

    // サムネイル生成
    LaunchedEffect(clipSignature) {
        clipSignature.forEach { signature ->
            val clip = clips.firstOrNull { it.id == signature.id } ?: return@forEach
            val hasCache = thumbnailCache.keys.any { it.startsWith("${clip.id}_") }
            if (hasCache) return@forEach

            scope.launch {
                thumbnailGenerator.generate(clip).onSuccess { thumbnails ->
                    // 生成完了時点でクリップが残っているか再確認
                    if (!currentClipIdsState.value.contains(clip.id)) return@onSuccess
                    thumbnails.forEach { thumbnail ->
                        val file = File(thumbnail.path)
                        if (file.exists()) {
                            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                            thumbnailCache["${clip.id}_${thumbnail.time}"] = bitmap
                        }
                    }
                }
            }
        }
    }

    val thumbnailHeightPx = with(density) { 64.dp.toPx() }
    // 可変密度：画面上のタイル幅を一定に保つ（zoomに応じて時間間隔を自動計算）
    val targetTileWidthPx = with(density) { 96.dp.toPx() } // 好みで 64–128dp 程度
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
                val intervalMs = ((targetTileWidthPx / zoom).toLong())
                    .coerceAtLeast(100L)
                val baseIntervalMs = THUMBNAIL_BASE_INTERVAL_MS

                var currentDisplayMs = 0L

                while (currentDisplayMs < clipTimelineDuration) {
                    // 画面上の経過時間 -> 素材上のサンプリング時刻（速度補正）
                    val desiredSourceTime = clip.startTime + (currentDisplayMs / speed).toLong()

                    // 既存キャッシュ(0.1s刻み)から最も近いキーを選ぶ（当面の改善）
                    // 例: baseIntervalMs（現状は100ms）グリッドへ丸める。将来的には generateAt() で厳密生成へ。
                    val nearestTime = ((desiredSourceTime + baseIntervalMs / 2) / baseIntervalMs) * baseIntervalMs
                    val thumbnailKey = "${clip.id}_${nearestTime}"
                    val thumbnail = thumbnailCache[thumbnailKey]

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
