package com.valoser.futaburakari.edit

import android.graphics.*

/**
 * 画像に対して「全面モザイク + マスク」の合成で部分モザイクを実現する簡易エンジン。
 *
 * 構成:
 * - `mosaicFull`: 元画像をブロック状に縮小→拡大した全面モザイク。
 * - `maskBitmap`: モザイクを表示する領域を白(不透明)で描くマスク。透明部分は非表示。
 * - 合成: `DST_IN` で [mosaicFull ∩ mask] を作り、`mosaicAlpha` に合わせた不透明度で元画像の上に重ねる。
 *
 * 使い方:
 * - `applyMosaic(...)` でマスクに白円を描く＝その領域にモザイクが「出る」。
 * - `eraseMosaic(...)` でマスクをクリア＝その領域のモザイクを「消す」。
 * - `setMosaicAlpha(a)` でモザイク合成時の不透明度(0..255)を調整。
 * - `composeFinal()` で保存用の合成結果を取得。`drawMosaicWithMask()` はプレビュー描画用。
 */
class EditingEngine(
    private val original: Bitmap
) {
    val width = original.width
    val height = original.height

    // 全面モザイク画像
    private val mosaicFull: Bitmap = makePixelated(original, block = 16)

    // モザイク表示領域マスク（A8相当の使い方。ここでは ARGB_8888 を使用）
    private val maskBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val maskCanvas = Canvas(maskBitmap)
    private val paintMaskAdd = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE          // 白=不透明 → マスクで「見せる」領域
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) // そのまま上書き
    }
    private val paintMaskErase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) // 透明に戻す＝消す
    }
    private val paintDstIn = Paint().apply {
        // マスク適用用。alpha は都度 mosaicAlpha に差し替える。
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    // モザイク重ねの不透明度（0..255）。
    private var mosaicAlpha: Int = 255

    /** モザイクの不透明度（0..255）を設定。範囲外は丸め込み。 */
    fun setMosaicAlpha(alpha: Int) {
        this.mosaicAlpha = alpha.coerceIn(0, 255)
    }

    /** モザイクをこの中心と直径で「見せる」（マスクに白円を描く）。単位は画像座標(px)。 */
    fun applyMosaic(cxImage: Float, cyImage: Float, diameterPx: Float) {
        val r = diameterPx / 2f
        maskCanvas.drawCircle(cxImage, cyImage, r, paintMaskAdd)
    }

    /** 「モザイクを消す」＝マスクを透明に戻す。単位は画像座標(px)。 */
    fun eraseMosaic(cxImage: Float, cyImage: Float, diameterPx: Float) {
        val r = diameterPx / 2f
        maskCanvas.drawCircle(cxImage, cyImage, r, paintMaskErase)
    }

    /** 保存用の最終合成を返す（元画像 + [モザイク ∩ マスク] を mosaicAlpha で合成）。 */
    fun composeFinal(): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(original, 0f, 0f, null)
        // モザイクにマスクを掛けてから載せる（DST_INで交差領域のみ残す）
        c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        c.drawBitmap(mosaicFull, 0f, 0f, null)
        paintDstIn.alpha = mosaicAlpha
        c.drawBitmap(maskBitmap, 0f, 0f, paintDstIn)
        c.restore()
        return out
    }

    /** プレビュー用：モザイク部分だけを mosaicAlpha 反映で描く（行列変換は呼び出し側で適用）。 */
    fun drawMosaicWithMask(canvas: Canvas) {
        canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawBitmap(mosaicFull, 0f, 0f, null)
        paintDstIn.alpha = mosaicAlpha
        canvas.drawBitmap(maskBitmap, 0f, 0f, paintDstIn)
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
