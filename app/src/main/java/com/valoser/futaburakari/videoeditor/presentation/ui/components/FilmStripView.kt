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
import com.valoser.futaburakari.videoeditor.media.thumbnail.ThumbnailGenerator
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

/**
 * フィルムストリップビュー
 * 0.5秒間隔の大型サムネイル表示（64dp高さ）
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

    // サムネイル生成
    LaunchedEffect(clips) {
        thumbnailCache.clear()
        clips.forEach { clip ->
            scope.launch {
                thumbnailGenerator.generate(clip).onSuccess { thumbnails ->
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

    val thumbnailWidthPx = with(density) { 128.dp.toPx() }
    val thumbnailHeightPx = with(density) { 64.dp.toPx() }
    val contentDuration = max(
        timelineDuration,
        clips.maxOfOrNull { it.position + it.duration } ?: 0L
    ).coerceAtLeast(1L)
    val contentWidthPx = max(contentDuration * zoom, thumbnailWidthPx)
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
                val thumbnailIntervalMs = 500L
                var currentTimeInClipMs = 0L

                while (currentTimeInClipMs < clip.duration) {
                    val thumbnailTime = clip.startTime + currentTimeInClipMs
                    val thumbnailKey = "${clip.id}_${thumbnailTime}"
                    val thumbnail = thumbnailCache[thumbnailKey]

                    val thumbnailStartX = (clip.position + currentTimeInClipMs) * zoom
                    val thumbnailDisplayWidth = (thumbnailIntervalMs * zoom)

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

                    currentTimeInClipMs += thumbnailIntervalMs
                }
            }
        }
    }
}

private fun DrawScope.drawBitmap(
    image: androidx.compose.ui.graphics.ImageBitmap,
    topLeft: Offset,
    dstSize: IntSize
) {
    drawImage(
        image = image,
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = dstSize
    )
}
