package com.example.hutaburakari.edit

import android.graphics.*

class EditingEngine(
    private val original: Bitmap
) {
    val width = original.width
    val height = original.height

    // 全面モザイク画像
    private val mosaicFull: Bitmap = makePixelated(original, block = 16)

    // モザイク表示領域マスク（A8相当。ここでは ARGB_8888 を使う）
    private val maskBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val maskCanvas = Canvas(maskBitmap)
    private val paintMaskAdd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE          // 255=見せる
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) // そのまま書く
    }
    private val paintMaskErase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) // 透明に戻す=消す
    }

    private var mosaicAlpha: Int = 255 // ★ 追加

    /** モザイクのアルファ値を設定 */
    fun setMosaicAlpha(alpha: Int) { // ★ 追加
        this.mosaicAlpha = alpha.coerceIn(0, 255)
    }

    /** モザイクをこの中心と直径で“見せる”（マスクに白円） */
    fun applyMosaic(cxImage: Float, cyImage: Float, diameterPx: Float) {
        val r = diameterPx / 2f
        maskCanvas.drawCircle(cxImage, cyImage, r, paintMaskAdd)
    }

    /** “モザイクを消す”＝マスクを透明に戻す */
    fun eraseMosaic(cxImage: Float, cyImage: Float, diameterPx: Float) {
        val r = diameterPx / 2f
        maskCanvas.drawCircle(cxImage, cyImage, r, paintMaskErase)
    }

    /** 保存用に最終合成を返す（元画像 + マスク適用モザイク） */
    fun composeFinal(): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(original, 0f, 0f, null)
        // モザイクにマスクを掛けてから載せる
        c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        c.drawBitmap(mosaicFull, 0f, 0f, null)
        val pMask = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            alpha = mosaicAlpha // ★ 追加
        }
        c.drawBitmap(maskBitmap, 0f, 0f, pMask)
        c.restore()
        return out
    }

    /** プレビュー用：モザイク部分だけを描く（マトリクス適用は呼び出し側で） */
    fun drawMosaicWithMask(canvas: Canvas) {
        canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawBitmap(mosaicFull, 0f, 0f, null)
        val pMask = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            alpha = mosaicAlpha // ★ 追加
        }
        canvas.drawBitmap(maskBitmap, 0f, 0f, pMask)
        canvas.restore()
    }

    // --- 簡易モザイク生成（縮小→拡大、フィルタ無し） ---
    private fun makePixelated(src: Bitmap, block: Int): Bitmap {
        val w = (src.width / block).coerceAtLeast(1)
        val h = (src.height / block).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, false)
        return Bitmap.createScaledBitmap(small, src.width, src.height, false)
    }
}
