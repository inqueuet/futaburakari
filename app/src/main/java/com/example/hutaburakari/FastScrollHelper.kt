package com.example.hutaburakari

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

class FastScrollHelper(
    private val recyclerView: RecyclerView,
    private val fastScrollTrack: View,
    private val fastScrollThumb: View,
    private val layoutManager: LinearLayoutManager,
    private val onDragStateChanged: ((Boolean) -> Unit)? = null // 追加
) {

    private var isDragging = false
    private var thumbHeight = 0f
    private var trackHeight = 0f

    init {
        setupFastScroll()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFastScroll() {
        // RecyclerViewのスクロールリスナーでつまみの位置を更新
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                updateThumbPosition()
            }
        })

        // つまみのタッチイベント
        fastScrollThumb.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    onDragStateChanged?.invoke(true) // 追加
                    trackHeight = fastScrollTrack.height.toFloat()
                    thumbHeight = fastScrollThumb.height.toFloat()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) {
                        handleThumbDrag(event.rawY)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    onDragStateChanged?.invoke(false) // 追加
                    true
                }
                else -> false
            }
        }

        // トラック部分のタッチイベント（タップでジャンプ）
        fastScrollTrack.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                trackHeight = fastScrollTrack.height.toFloat()
                thumbHeight = fastScrollThumb.height.toFloat()
                handleTrackTap(event.y)
                true
            } else {
                false
            }
        }

        // 初期位置設定
        fastScrollThumb.post {
            updateThumbPosition()
        }
    }

    private fun handleThumbDrag(rawY: Float) {
        // スクリーン座標からトラック内の相対位置を計算
        val trackLocation = IntArray(2)
        fastScrollTrack.getLocationOnScreen(trackLocation)
        val trackTop = trackLocation[1]

        val relativeY = rawY - trackTop
        val clampedY = max(0f, min(relativeY - thumbHeight / 2, trackHeight - thumbHeight))

        // つまみの位置を更新
        fastScrollThumb.translationY = clampedY

        // RecyclerViewの位置を計算してスクロール
        val scrollRatio = clampedY / (trackHeight - thumbHeight)
        scrollToPosition(scrollRatio)
    }

    private fun handleTrackTap(y: Float) {
        val clampedY = max(0f, min(y - thumbHeight / 2, trackHeight - thumbHeight))

        // つまみの位置を更新
        fastScrollThumb.translationY = clampedY

        // RecyclerViewの位置を計算してスクロール
        val scrollRatio = clampedY / (trackHeight - thumbHeight)
        scrollToPosition(scrollRatio)
    }

    private fun scrollToPosition(ratio: Float) {
        val adapter = recyclerView.adapter ?: return
        val itemCount = adapter.itemCount
        if (itemCount <= 0) return

        val targetPosition = (ratio * (itemCount - 1)).toInt()
        layoutManager.scrollToPositionWithOffset(targetPosition, 0)
    }

    private fun updateThumbPosition() {
        val adapter = recyclerView.adapter ?: return
        val itemCount = adapter.itemCount
        if (itemCount <= 0) return

        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        if (firstVisiblePosition == RecyclerView.NO_POSITION) return

        val visibleRange = layoutManager.findLastVisibleItemPosition() - firstVisiblePosition + 1
        val scrollRange = itemCount - visibleRange

        if (scrollRange <= 0) {
            fastScrollThumb.translationY = 0f
            return
        }

        trackHeight = fastScrollTrack.height.toFloat()
        thumbHeight = fastScrollThumb.height.toFloat()

        val scrollRatio = firstVisiblePosition.toFloat() / scrollRange
        val thumbPosition = scrollRatio * (trackHeight - thumbHeight)

        fastScrollThumb.translationY = max(0f, min(thumbPosition, trackHeight - thumbHeight))
    }

    // 外部からスクロール状態を更新するためのメソッド
    fun updateScrollPosition() {
        updateThumbPosition()
    }

    // ファストスクロールの表示/非表示を制御
    fun setFastScrollEnabled(enabled: Boolean) {
        fastScrollTrack.visibility = if (enabled) View.VISIBLE else View.GONE
        fastScrollThumb.visibility = if (enabled) View.VISIBLE else View.GONE
    }
}