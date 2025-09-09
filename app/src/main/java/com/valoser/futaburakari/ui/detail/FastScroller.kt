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
 * Default width of the fast scroller track, so callers can reserve matching
 * end padding on the list content to avoid overlap.
 */
val DefaultFastScrollerWidth: Dp = 20.dp

/**
 * Lightweight vertical fast scroller for long `LazyColumn`s.
 * - Renders only when `itemsCount` exceeds `visibleThreshold`.
 * - Positions a draggable thumb within a full-height track aligned to the top.
 * - Maps thumb position to `LazyListState.scrollToItem(targetIndex)` linearly by index
 *   (approximate: ignores per-item heights and scroll offset).
 * - While dragging, slightly tints the track and invokes `onDragActiveChange(true/false)`.
 * - `bottomPadding` reserves space (e.g., for a banner) so the track does not overlap it.
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
        // Track
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                // Keep track nearly transparent unless dragging
                .background(
                    if (dragging) MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f)
                    else Color.Transparent
                )
                .onGloballyPositioned { coords ->
                    trackHeightPx = coords.size.height.toFloat()
                    trackTopPx = 0f
                }
        )

        // Thumb
        var thumbOffsetYPx by remember { mutableStateOf(0f) }

        // Keep thumb roughly in sync with list scroll (index-based approximation)
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
                        // Consume the gesture and map thumb delta to list index
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
