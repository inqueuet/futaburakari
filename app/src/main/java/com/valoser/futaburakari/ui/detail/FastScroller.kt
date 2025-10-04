package com.valoser.futaburakari.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.offset // For positioning the thumb by pixels
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.toSize
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * 高速スクロール用トラックのデフォルト幅。
 * リスト側で同等の end パディングを確保すると重なりを避けられます。
 */
val DefaultFastScrollerWidth: Dp = 20.dp

/**
 * 縦方向の軽量な高速スクローラー（長い `LazyColumn` 向け）。
 * - `itemsCount` が `visibleThreshold` を超える場合のみ描画。
 * - 画面上部に揃えた全高のトラック上にドラッグ可能なサムを表示。
 * - サム位置をインデックスに線形マップして `LazyListState.scrollToItem(...)` を呼ぶ（概算。各行の高さやオフセットは考慮しない）。
 * - ドラッグ中はトラックをうっすら着色し、`onDragActiveChange(true/false)` を通知。
 * - `bottomPadding` で（バナー等の）下部領域を避ける。
 *
 * パラメータ:
 * - `modifier`: 外側に適用する `Modifier`。
 * - `listState`: 対象の `LazyListState`。
 * - `itemsCount`: リスト項目数。
 * - `bottomPadding`: 下部の余白（バナー等で隠れないようにするため）。
 * - `visibleThreshold`: これ以下なら非表示にするしきい値。
 * - `onDragActiveChange`: ドラッグ開始/終了時の通知。
 */
@Composable
fun FastScroller(
    modifier: Modifier = Modifier,
    listState: LazyListState,
    itemsCount: Int,
    bottomPadding: Dp = 0.dp,
    visibleThreshold: Int = 30,
    onDragActiveChange: ((Boolean) -> Unit)? = null,
) {
    if (itemsCount <= visibleThreshold) return

    val density = LocalDensity.current
    val trackWidth = DefaultFastScrollerWidth
    val thumbWidth = 14.dp
    val thumbHeight = 80.dp
    val scope = rememberCoroutineScope()

    var trackHeightPx by remember { mutableStateOf(0f) }
    var trackTopPx by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(dragging) { onDragActiveChange?.invoke(dragging) }

    Box(
        modifier = modifier
            .padding(bottom = bottomPadding)
            .width(trackWidth)
            .fillMaxHeight(),
        contentAlignment = Alignment.TopCenter
    ) {
        // トラック
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                // ドラッグ中以外はほぼ透明に保つ
                .background(
                    if (dragging) MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f)
                    else Color.Transparent
                )
                .onGloballyPositioned { coords ->
                    trackHeightPx = coords.size.height.toFloat()
                    trackTopPx = 0f
                }
        )

        // サム
        var thumbOffsetYPx by remember { mutableStateOf(0f) }

        // リストスクロールに概算で追随（インデックス基準の近似）
        LaunchedEffect(listState.firstVisibleItemIndex, itemsCount) {
            if (!dragging && itemsCount > 0 && trackHeightPx > 0f) {
                val ratio = (listState.firstVisibleItemIndex.coerceAtLeast(0)).toFloat() / (itemsCount - 1).coerceAtLeast(1)
                val travel = trackHeightPx - with(density) { thumbHeight.toPx() }
                thumbOffsetYPx = (travel * ratio).coerceIn(0f, travel)
            }
        }

        Box(
            modifier = Modifier
                .width(thumbWidth)
                .height(thumbHeight)
                .align(Alignment.TopCenter)
                .offset { IntOffset(x = 0, y = thumbOffsetYPx.roundToInt()) }
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.66f))
                .pointerInput(itemsCount, trackHeightPx, thumbHeight) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dragAmount ->
                        // ジェスチャを消費し、サムの移動量をインデックスにマップ
                        change.consume()
                        if (trackHeightPx <= 0f || itemsCount <= 0) return@detectDragGestures
                        val travel = trackHeightPx - with(density) { thumbHeight.toPx() }
                        thumbOffsetYPx = (thumbOffsetYPx + dragAmount.y).coerceIn(0f, travel)

                        val ratio = if (travel > 0f) thumbOffsetYPx / travel else 0f
                        val targetIndex = (ratio * (itemsCount - 1)).toInt().coerceIn(0, (itemsCount - 1).coerceAtLeast(0))
                        scope.launch { listState.scrollToItem(targetIndex) }
                    }
                }
        )
    }
}
