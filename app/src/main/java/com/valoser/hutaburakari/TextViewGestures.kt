package com.valoser.hutaburakari

import android.text.Spannable
import android.text.SpannableString   // ← 追加
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView

/**
 * TextView用：シングル/ダブルタップの両対応。
 * ・ClickableSpan（リンク）がタップされたときは、リンクを優先して処理する
 * ・シングルタップとダブルタップのコールバックを指定できる
 */
fun TextView.setSingleAndDoubleClickListener(
    onSingleClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    // 既存のリンク（ClickableSpan）も活かしたい場合は LinkMovementMethod を設定
    if (movementMethod == null) {
        movementMethod = LinkMovementMethod.getInstance()
    }

    val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // まずリンクがある位置かどうか調べ、あればリンク処理を優先
            if (handleClickableSpanTouch(this@setSingleAndDoubleClickListener, e)) {
                return true
            }
            onSingleClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // ダブルタップ時も、リンク位置ならリンク優先（ただし通常はシングルで発火）
            if (handleClickableSpanTouch(this@setSingleAndDoubleClickListener, e)) {
                return true
            }
            onDoubleClick()
            return true
        }
    })

    setOnTouchListener { _, event ->
        // まずリンクにイベントを渡して、消費されたら終わり
        val text = this.text
        if (movementMethod is LinkMovementMethod) {
            // ★ 必ず Spannable にする（Spanned のまま渡すと型不一致）
            val spannable: Spannable = if (text is Spannable) text else SpannableString.valueOf(text)
            val handledByLink = (movementMethod as LinkMovementMethod)
                .onTouchEvent(this, spannable, event)   // ← ここが安全に通る
            if (handledByLink) return@setOnTouchListener true
        }

        // リンクで消費されなければ、GestureDetector に渡す
        gestureDetector.onTouchEvent(event)
        true
    }
}

/**
 * タッチ座標下に ClickableSpan がある場合、その onClick を呼び出して true を返す。
 * なければ false。
 */
private fun handleClickableSpanTouch(tv: TextView, e: MotionEvent): Boolean {
    val text = tv.text
    if (tv.movementMethod is LinkMovementMethod) {
        // ★ ここも Spannable に統一して扱う
        val spannable: Spannable = if (text is Spannable) text else SpannableString.valueOf(text)

        val x = e.x.toInt() - tv.totalPaddingLeft + tv.scrollX
        val y = e.y.toInt() - tv.totalPaddingTop + tv.scrollY
        val layout = tv.layout ?: return false

        val line = layout.getLineForVertical(y)
        val off = try { layout.getOffsetForHorizontal(line, x.toFloat()) } catch (_: Throwable) { return false }

        val links = spannable.getSpans(off, off, ClickableSpan::class.java)
        if (links.isNotEmpty()) {
            if (e.action == MotionEvent.ACTION_UP) {
                links[0].onClick(tv)
            }
            return true
        }
    }
    return false
}