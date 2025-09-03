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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import com.valoser.futaburakari.edit.EditingEngine
import kotlin.math.max
import kotlin.math.min

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

    // Tick to trigger recomposition for engine-driven drawing
    var overlayTick by remember { mutableStateOf(0) }

    // Keep engine mosaic alpha in sync
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

    fun imageRectInView(): android.graphics.RectF {
        val rect = android.graphics.RectF(0f, 0f, imgW, imgH)
        buildMatrix().mapRect(rect)
        return rect
    }

    fun clampOffset() {
        if (viewSize.width == 0 || viewSize.height == 0) return
        val vw = viewSize.width.toFloat()
        val vh = viewSize.height.toFloat()
        val rect = imageRectInView()
        var dx = 0f
        var dy = 0f
        if (rect.width() <= vw) {
            dx = vw * 0.5f - (rect.left + rect.right) * 0.5f
        } else {
            if (rect.left > 0) dx = -rect.left
            if (rect.right < vw) dx = vw - rect.right
        }
        if (rect.height() <= vh) {
            dy = vh * 0.5f - (rect.top + rect.bottom) * 0.5f
        } else {
            if (rect.top > 0) dy = -rect.top
            if (rect.bottom < vh) dy = vh - rect.bottom
        }
        if (dx != 0f || dy != 0f) {
            offset = Offset(offset.x + dx, offset.y + dy)
        }
    }

    // Pinch/drag transform centered at gesture centroid
    val transformGestureModifier = if (!locked) {
        Modifier.pointerInput(locked, zoom, offset, minZoom, maxZoom) {
            detectTransformGestures { centroid, pan, gestureZoom, _ ->
                var target = (zoom * gestureZoom).coerceIn(minZoom, maxZoom)
                val factor = if (zoom != 0f) target / zoom else 1f
                // Zoom around gesture centroid (view space)
                offset = (offset - centroid) * factor + centroid
                zoom = target
                // Apply pan after zoom
                offset += pan
                clampOffset()
                overlayTick++
            }
        }
    } else Modifier

    // Double-tap to toggle zoom at tap position
    val doubleTapModifier = Modifier.pointerInput(locked, zoom, minZoom) {
        detectTapGestures(
            onDoubleTap = { pos ->
                if (!locked) {
                    val target = if (zoom < minZoom * 1.9f) min(minZoom * 2f, maxZoom) else minZoom
                    val factor = target / zoom
                    // Zoom around tap position
                    offset = (offset - pos) * factor + pos
                    zoom = target
                    clampOffset()
                    overlayTick++
                }
            }
        )
    }

    // Drawing gestures when locked and tool active
    val drawGestureModifier = if (locked && (toolName == "MOSAIC" || toolName == "ERASER")) {
        Modifier.pointerInput(locked, toolName, brushSizePx, zoom, offset) {
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

    // Convert bitmap once per change
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }

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
        // Draw base image using Compose primitives for reliability
        withTransform({
            scale(zoom, zoom)
            translate(offset.x, offset.y)
        }) {
            drawImage(imageBitmap)
        }

        // Draw mosaic overlay through Android Canvas (engine API)
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.concat(buildMatrix())
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
