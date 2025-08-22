package com.example.hutaburakari

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class BlockDividerDecoration(
    private val adapter: DetailAdapter,
    context: Context,
    private val paddingStartDp: Int = 0,
    private val paddingEndDp: Int = 0,
) : RecyclerView.ItemDecoration() {

    private val divider: Drawable by lazy {
        val a = context.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
        val d = a.getDrawable(0)!!
        a.recycle()
        d
    }

    private val paddingStartPx = dpToPx(context, paddingStartDp)
    private val paddingEndPx = dpToPx(context, paddingEndDp)

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val left = parent.paddingLeft + paddingStartPx
        val right = parent.width - parent.paddingRight - paddingEndPx

        val list = adapter.currentList
        val childCount = parent.childCount
        for (i in 0 until childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue

            if (shouldDrawDividerAfter(pos, list)) {
                val params = child.layoutParams as RecyclerView.LayoutParams
                val top = child.bottom + params.bottomMargin
                val bottom = top + (divider.intrinsicHeight.takeIf { it > 0 } ?: 1)
                divider.setBounds(left, top, right, bottom)
                divider.draw(c)
            }
        }
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val pos = parent.getChildAdapterPosition(view)
        if (pos != RecyclerView.NO_POSITION && shouldDrawDividerAfter(pos, adapter.currentList)) {
            outRect.bottom = divider.intrinsicHeight.takeIf { it > 0 } ?: 1
        } else {
            outRect.bottom = 0
        }
    }

    private fun shouldDrawDividerAfter(pos: Int, list: List<DetailContent>): Boolean {
        if (pos !in list.indices) return false
        val curr = list[pos]
        val next = list.getOrNull(pos + 1)

        return when (curr) {
            is DetailContent.Image, is DetailContent.Video -> {
                // 画像/動画の直後に Text/ThreadEndTime が来る or 末尾 → “塊の末尾”
                next is DetailContent.Text || next is DetailContent.ThreadEndTime || next == null
            }
            is DetailContent.Text -> {
                // 直後が Text/ThreadEndTime/末尾（= 後続メディアが無い） → “塊が本文だけ”のケース
                next is DetailContent.Text || next is DetailContent.ThreadEndTime || next == null
            }
            else -> false
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics
        ).toInt()
    }
}