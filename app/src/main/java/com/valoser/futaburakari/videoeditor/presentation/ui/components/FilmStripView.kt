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
                val clipTrimmedDuration = (clip.endTime - clip.startTime).coerceAtLeast(0L)
                // 画面上のタイル幅 -> 実時間間隔（ms）に変換（下限/上限で暴れ防止）
                val intervalMs = ((targetTileWidthPx / zoom).toLong())
                    .coerceAtLeast(100L)
                
                var currentDisplayMs = 0L

                while (currentDisplayMs < clipTrimmedDuration) {
                    // 画面上の経過時間 -> 素材上のサンプリング時刻（速度補正）
                    val desiredSourceTime = clip.startTime + (currentDisplayMs / speed).toLong()

                    // 既存キャッシュ(0.5s刻み)から最も近いキーを選ぶ（当面の改善）
                    // 例: 500msグリッドへ丸める。将来的には generateAt() で厳密生成へ。
                    val nearestTime = (desiredSourceTime / 500L) * 500L
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
