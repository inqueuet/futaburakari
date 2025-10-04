package com.valoser.futaburakari.ui.compose

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.valoser.futaburakari.edit.EditingEngine
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas that renders the editable bitmap and forwards gestures to the mosaic engine.
 *
 * @param bitmap Source image to display
 * @param engine Engine that applies mosaic strokes; null renders the bitmap only
 * @param toolName Active tool name ("MOSAIC" | "ERASER" | "NONE")
 * @param locked Disables pan/zoom while allowing drawing when true
 * @param brushSizePx Brush diameter in image pixels
 * @param mosaicAlpha Alpha value used by the mosaic engine
 * @param modifier Modifier provided by the caller
 */
@Composable
fun ImageEditorCanvas(
    bitmap: Bitmap,
    engine: EditingEngine?,
    toolName: String, // "MOSAIC" | "ERASER" | "NONE"
    locked: Boolean,
    brushSizePx: Int,
    mosaicAlpha: Int,
    modifier: Modifier = Modifier
) {
    // Viewport size
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    // Image intrinsic size
    val imgW = bitmap.width.toFloat()
    val imgH = bitmap.height.toFloat()

    // Transform state (scale & translation in view pixels)
    var zoom by remember(bitmap) { mutableStateOf(1f) }
    var minZoom by remember(bitmap) { mutableStateOf(1f) }
    var maxZoom by remember(bitmap) { mutableStateOf(8f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    // Brush preview state (view space)
    var showBrush by remember { mutableStateOf(false) }
    var brushCenter by remember { mutableStateOf(Offset.Zero) }
    var brushRadius by remember { mutableStateOf(0f) }

    // Tick used to drive recomposition for engine overlays and brush preview
    var overlayTick by remember { mutableStateOf(0) }

    // Keep engine mosaic alpha in sync and force an overlay refresh
    LaunchedEffect(engine, mosaicAlpha) {
        engine?.setMosaicAlpha(mosaicAlpha)
        overlayTick++
    }

    // Fit-center on size or bitmap change
    fun computeFit() {
        if (viewSize.width == 0 || viewSize.height == 0) return
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        val s = min(vw / imgW, vh / imgH)
        minZoom = s
        maxZoom = max(4f * s, 8f)
        zoom = s
        val dx = (vw - imgW * s) * 0.5f
        val dy = (vh - imgH * s) * 0.5f
        offset = Offset(dx, dy)
    }

    LaunchedEffect(viewSize, bitmap) {
        computeFit()
    }

    // Build current matrix for image->view transform
    fun buildMatrix(): Matrix {
        val m = Matrix()
        m.postScale(zoom, zoom)
        m.postTranslate(offset.x, offset.y)
        return m
    }

    fun invertToImage(pointView: Offset): Offset? {
        val inv = Matrix()
        val ok = buildMatrix().invert(inv)
        if (!ok) return null
        val pts = floatArrayOf(pointView.x, pointView.y)
        inv.mapPoints(pts)
        return Offset(pts[0], pts[1])
    }

    fun imageLengthToView(lengthInImagePx: Float): Float {
        // Since transform is scale + translate only, length scales linearly
        return lengthInImagePx * zoom
    }

    fun clampOffset() {
        if (viewSize.width == 0 || viewSize.height == 0) return
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        val left = offset.x
        val top = offset.y
        val width = imgW * zoom
        val height = imgH * zoom
        val right = left + width
        val bottom = top + height
        var dx = 0f
        var dy = 0f
        if (width <= vw) {
            dx = vw * 0.5f - (left + right) * 0.5f
        } else {
            if (left > 0) dx = -left
            if (right < vw) dx = vw - right
        }
        if (height <= vh) {
            dy = vh * 0.5f - (top + bottom) * 0.5f
        } else {
            if (top > 0) dy = -top
            if (bottom < vh) dy = vh - bottom
        }
        if (dx != 0f || dy != 0f) {
            offset = Offset(offset.x + dx, offset.y + dy)
        }
    }

    // When unlocked, pinch/drag keeps the gesture centroid anchored while scaling
    val transformGestureModifier = if (!locked) {
        Modifier.pointerInput(locked) {
            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                var target = (zoom * gestureZoom).coerceIn(minZoom, maxZoom)
                val factor = if (zoom != 0f) target / zoom else 1f
                // Zoom around gesture centroid (view space)
                offset = (offset - centroid) * factor + centroid
                zoom = target
                // Apply pan after zoom
                offset += pan
                // Defer heavy work; clamping is cheap now but still avoid extra invalidations
                clampOffset()
            }
        }
    } else Modifier

    // Double-tap (unlocked) toggles between fit and zoomed state around the tap
    val doubleTapModifier = Modifier.pointerInput(locked) {
        detectTapGestures(
            onDoubleTap = { pos ->
                if (!locked) {
                    val target = if (zoom < minZoom * 1.9f) min(minZoom * 2f, maxZoom) else minZoom
                    val factor = target / zoom
                    // Zoom around tap position
                    offset = (offset - pos) * factor + pos
                    zoom = target
                    clampOffset()
                }
            }
        )
    }

    // When locked and a drawing tool is active, drags apply mosaic/eraser strokes
    val drawGestureModifier = if (locked && (toolName == "MOSAIC" || toolName == "ERASER")) {
        Modifier.pointerInput(locked, toolName, brushSizePx) {
            detectDragGestures(
                onDragStart = { pos ->
                    val pImg = invertToImage(pos)
                    if (pImg != null) {
                        val diameterImagePx = brushSizePx.toFloat()
                        when (toolName) {
                            "MOSAIC" -> engine?.applyMosaic(pImg.x, pImg.y, diameterImagePx)
                            "ERASER" -> engine?.eraseMosaic(pImg.x, pImg.y, diameterImagePx)
                        }
                        overlayTick++
                        showBrush = true
                        brushCenter = pos
                        brushRadius = imageLengthToView(diameterImagePx) / 2f
                    }
                },
                onDrag = { change, _ ->
                    val pos = change.position
                    val pImg = invertToImage(pos)
                    if (pImg != null) {
                        val diameterImagePx = brushSizePx.toFloat()
                        when (toolName) {
                            "MOSAIC" -> engine?.applyMosaic(pImg.x, pImg.y, diameterImagePx)
                            "ERASER" -> engine?.eraseMosaic(pImg.x, pImg.y, diameterImagePx)
                        }
                        overlayTick++
                        showBrush = true
                        brushCenter = pos
                        brushRadius = imageLengthToView(diameterImagePx) / 2f
                    }
                },
                onDragEnd = { showBrush = false },
                onDragCancel = { showBrush = false }
            )
        }
    } else Modifier

    // nativeCanvas keeps the bitmap and engine overlay perfectly in sync

    Canvas(
        modifier = modifier
            .onSizeChanged { newSize -> viewSize = newSize }
            .then(doubleTapModifier)
            .then(transformGestureModifier)
            .then(drawGestureModifier)
    ) {
        // read tick to recompose when overlay or matrix changes
        val tick = overlayTick
        if (tick < 0) { /* read state no-op */ }
        // Draw base image and overlay using the exact same matrix
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.concat(buildMatrix())
            native.drawBitmap(bitmap, 0f, 0f, null)
            engine?.drawMosaicWithMask(native)
            native.restore()
        }

        if (showBrush) {
            drawCircle(
                color = Color.White.copy(alpha = 0.6f),
                radius = brushRadius,
                center = brushCenter,
                style = Stroke(width = 2f)
            )
        }
    }
}
