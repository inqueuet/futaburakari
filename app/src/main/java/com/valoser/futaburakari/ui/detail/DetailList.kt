package com.valoser.futaburakari.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Divider
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.valoser.futaburakari.DetailAdapter
import com.valoser.futaburakari.DetailContent
import com.valoser.futaburakari.R

@Composable
fun DetailListCompose(
    items: List<DetailContent>,
    searchQuery: String?,
    // Callbacks — mirror DetailAdapter listeners
    onQuoteClick: ((String) -> Unit)? = null,
    onSodaneClick: ((String) -> Unit)? = null,
    onThreadEndTimeClick: (() -> Unit)? = null,
    onResNumClick: ((resNum: String, resBody: String) -> Unit)? = null,
    onResNumConfirmClick: ((String) -> Unit)? = null,
    onResNumDelClick: ((String) -> Unit)? = null,
    onBodyClick: ((String) -> Unit)? = null,
    onAddNgFromBody: ((String) -> Unit)? = null,
    getSodaneState: ((String) -> Boolean)? = null,
    onImageLoaded: (() -> Unit)? = null,
    onVisibleMaxOrdinal: ((Int) -> Unit)? = null,
    listState: LazyListState? = null,
    initialScrollIndex: Int = 0,
    initialScrollOffset: Int = 0,
    onSaveScroll: ((Int, Int) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val context = LocalContext.current
    // Reuse adapter utilities by creating a lightweight adapter instance configured with callbacks
    val adapter = remember {
        DetailAdapter().apply {
            onQuoteClickListener = onQuoteClick
            onSodaNeClickListener = onSodaneClick
            onThreadEndTimeClickListener = onThreadEndTimeClick
            onResNumClickListener = onResNumClick
            onResNumConfirmClickListener = onResNumConfirmClick
            onResNumDelClickListener = onResNumDelClick
            onBodyClickListener = onBodyClick
            onAddNgFromBodyListener = onAddNgFromBody
            getSodaNeState = getSodaneState
            this.onImageLoaded = onImageLoaded
        }
    }

    val safeIndex = if (initialScrollIndex >= 0) initialScrollIndex else 0
    val safeOffset = if (initialScrollOffset >= 0) initialScrollOffset else 0
    val internalState = listState ?: rememberLazyListState(
        initialFirstVisibleItemIndex = safeIndex,
        initialFirstVisibleItemScrollOffset = safeOffset
    )

    // Report max visible ordinal (50%以上見えているText単位の最大序数)
    LaunchedEffect(items, internalState) {
        snapshotFlow { internalState.layoutInfo }
            .map { info ->
                val vpStart = info.viewportStartOffset
                val vpEnd = info.viewportEndOffset
                val visible = info.visibleItemsInfo
                var maxOrdinal = 0
                for (vi in visible) {
                    val top = vi.offset
                    val bottom = vi.offset + vi.size
                    val visibleTop = maxOf(vpStart, top)
                    val visibleBottom = minOf(vpEnd, bottom)
                    val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(0)
                    val ratio = if (vi.size > 0) visibleHeight.toFloat() / vi.size.toFloat() else 0f
                    if (ratio >= 0.5f) {
                        val ordinal = ordinalForIndex(items, vi.index)
                        if (ordinal > maxOrdinal) maxOrdinal = ordinal
                    }
                }
                // 補正：最終要素がほぼ見えていれば末尾まで既読にしやすく
                if (visible.isNotEmpty()) {
                    val lastIdx = items.lastIndex
                    val lastOrdinal = ordinalForIndex(items, lastIdx)
                    val lastVi = visible.last()
                    if (lastVi.index >= lastIdx - 1 && lastOrdinal > maxOrdinal) maxOrdinal = lastOrdinal
                }
                maxOrdinal
            }
            .distinctUntilChanged()
            .collectLatest { ord -> if (ord > 0) onVisibleMaxOrdinal?.invoke(ord) }
    }

    // Persist scroll position changes (index + offset)
    LaunchedEffect(internalState) {
        snapshotFlow { internalState.firstVisibleItemIndex to internalState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collectLatest { (pos, off) -> onSaveScroll?.invoke(pos, off) }
    }

    LazyColumn(state = internalState, modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
        itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
            when (item) {
                is DetailContent.Text ->
                    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { ctx ->
                        // Inflate the existing layout and bind via ViewHolder to reuse spans/clicks
                        val v = LayoutInflater.from(ctx).inflate(R.layout.detail_item_text, null, false)
                        // Store holder in tag to reuse
                        val holder = DetailAdapter.TextViewHolder(
                            v,
                            adapter,
                            adapter.onQuoteClickListener,
                            adapter.onSodaNeClickListener,
                            java.util.regex.Pattern.compile("\\b([a-zA-Z0-9_.-]+\\.(?:jpg|jpeg|png|gif|webp|mp4|webm|mov|avi|flv|mkv))\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                            java.util.regex.Pattern.compile("No\\.(\\d+)"),
                            java.util.regex.Pattern.compile("No\\.(\\d+)\\s*(?:\\u200B)?\\s*(?:[+＋]|そうだねx\\d+)"),
                            adapter.onResNumClickListener,
                            "\u200B"
                        )
                        v.tag = holder
                        v
                    }, update = { view ->
                        val holder = view.tag as DetailAdapter.TextViewHolder
                        holder.bind(item, searchQuery)
                    })

                is DetailContent.Image ->
                    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { ctx ->
                        val v = LayoutInflater.from(ctx).inflate(R.layout.detail_item_image, null, false)
                        val holder = DetailAdapter.ImageViewHolder(
                            v,
                            adapter.onQuoteClickListener,
                            java.util.regex.Pattern.compile("\\b([a-zA-Z0-9_.-]+\\.(?:jpg|jpeg|png|gif|webp|mp4|webm|mov|avi|flv|mkv))\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                            adapter.onImageLoaded
                        )
                        v.tag = holder
                        v
                    }, update = { view ->
                        val holder = view.tag as DetailAdapter.ImageViewHolder
                        holder.bind(item)
                    })

                is DetailContent.Video ->
                    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { ctx ->
                        val v = LayoutInflater.from(ctx).inflate(R.layout.detail_item_video, null, false)
                        val holder = DetailAdapter.VideoViewHolder(
                            v,
                            adapter.onQuoteClickListener,
                            java.util.regex.Pattern.compile("\\b([a-zA-Z0-9_.-]+\\.(?:jpg|jpeg|png|gif|webp|mp4|webm|mov|avi|flv|mkv))\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                            adapter.onImageLoaded
                        )
                        v.tag = holder
                        v
                    }, update = { view ->
                        val holder = view.tag as DetailAdapter.VideoViewHolder
                        holder.bind(item)
                    })

                is DetailContent.ThreadEndTime ->
                    AndroidView(modifier = Modifier.fillMaxWidth(), factory = { ctx ->
                        val v = LayoutInflater.from(ctx).inflate(R.layout.detail_item_thread_end_time, null, false)
                        val holder = DetailAdapter.ThreadEndTimeViewHolder(v, adapter.onThreadEndTimeClickListener)
                        v.tag = holder
                        v
                    }, update = { view ->
                        val holder = view.tag as DetailAdapter.ThreadEndTimeViewHolder
                        holder.bind(item)
                    })
            }

            // Divider: ブロック末尾でのみ描画（次要素がText/EndTime or 末尾）
            if (isEndOfBlock(items, index)) {
                Divider(thickness = 0.5.dp)
            }
        }
    }
}

private fun ordinalForIndex(all: List<DetailContent>, index: Int): Int {
    var ord = 0
    var i = 0
    while (i <= index && i < all.size) {
        if (all[i] is DetailContent.Text) ord++
        i++
    }
    return ord
}

private fun isEndOfBlock(items: List<DetailContent>, index: Int): Boolean {
    if (index !in items.indices) return false
    val next = items.getOrNull(index + 1)
    // ブロック構造: Text + (Image|Video)* の塊。次がText/EndTime/なし なら区切り。
    return when (next) {
        null -> true
        is DetailContent.Text, is DetailContent.ThreadEndTime -> true
        is DetailContent.Image, is DetailContent.Video -> false
    }
}
