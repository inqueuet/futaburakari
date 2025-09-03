package com.valoser.futaburakari.ui.detail

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Divider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
import com.valoser.futaburakari.DetailAdapter
import com.valoser.futaburakari.DetailContent
import com.valoser.futaburakari.R
import android.text.Html
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.clickable
import android.util.Patterns
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column

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
    onIdClick: ((String) -> Unit)? = null,
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
    // Compose側で検索Prev/Nextナビを提供するためのコールバック
    onProvideSearchNavigator: (((() -> Unit), (() -> Unit)) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Reuse adapter utilities by creating a lightweight adapter instance configured with callbacks
    val adapter = remember {
        DetailAdapter().apply {
            onQuoteClickListener = onQuoteClick
            onSodaNeClickListener = onSodaneClick
            onThreadEndTimeClickListener = onThreadEndTimeClick
            onResNumClickListener = onResNumClick
            onResNumConfirmClickListener = onResNumConfirmClick
            onResNumDelClickListener = onResNumDelClick
            onIdClickListener = onIdClick
            onBodyClickListener = onBodyClick
            onAddNgFromBodyListener = onAddNgFromBody
            getSodaNeState = getSodaneState
            this.onImageLoaded = onImageLoaded
        }
    }

    // Dialog state for No. actions
    var resNumForDialog by remember { mutableStateOf<String?>(null) }
    // Optimistic "そうだね" override counts
    val sodaneOverrides = remember { androidx.compose.runtime.mutableStateMapOf<String, Int>() }

    val safeIndex = if (initialScrollIndex >= 0) initialScrollIndex else 0
    val safeOffset = if (initialScrollOffset >= 0) initialScrollOffset else 0
    val internalState = listState ?: rememberLazyListState(
        initialFirstVisibleItemIndex = safeIndex,
        initialFirstVisibleItemScrollOffset = safeOffset
    )

    // Compose内で検索ヒット位置を計算してナビゲーションを提供
    var hitPositions by remember(items, searchQuery) { mutableStateOf<List<Int>>(emptyList()) }
    var currentHit by remember(items, searchQuery) { mutableStateOf(0) }
    LaunchedEffect(items, searchQuery) {
        val q = searchQuery?.trim().orEmpty()
        if (q.isBlank()) {
            hitPositions = emptyList()
            currentHit = 0
        } else {
            val list = mutableListOf<Int>()
            items.forEachIndexed { idx, content ->
                val textToSearch: String? = when (content) {
                    is DetailContent.Text -> Html.fromHtml(content.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                    is DetailContent.Image -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.imageUrl.substringAfterLast('/')}"
                    is DetailContent.Video -> "${content.prompt ?: ""} ${content.fileName ?: ""} ${content.videoUrl.substringAfterLast('/')}"
                    is DetailContent.ThreadEndTime -> null
                }
                if (textToSearch?.contains(q, ignoreCase = true) == true) list += idx
            }
            hitPositions = list
            currentHit = if (list.isNotEmpty()) 0 else 0
        }
    }
    val density = LocalDensity.current
    LaunchedEffect(hitPositions) {
        // 上位にハンドラを提供（Prev/Next）
        onProvideSearchNavigator?.invoke(
            {
                if (hitPositions.isEmpty()) return@invoke
                currentHit = if (currentHit - 1 < 0) hitPositions.lastIndex else currentHit - 1
                val target = hitPositions[currentHit]
                val offsetPx = with(density) { 20.dp.toPx().toInt() }
                scope.launch { internalState.animateScrollToItem(target, offsetPx) }
            },
            {
                if (hitPositions.isEmpty()) return@invoke
                currentHit = if (currentHit + 1 > hitPositions.lastIndex) 0 else currentHit + 1
                val target = hitPositions[currentHit]
                val offsetPx = with(density) { 20.dp.toPx().toInt() }
                scope.launch { internalState.animateScrollToItem(target, offsetPx) }
            }
        )
    }

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
                is DetailContent.Text -> {
                    val plain = Html.fromHtml(item.htmlContent, Html.FROM_HTML_MODE_COMPACT).toString()
                    val displayText = remember(plain, sodaneOverrides) { applySodaneDisplay(padTokensForSpacing(plain), sodaneOverrides) }
                    val annotated = remember(displayText, searchQuery) { buildAnnotatedFromText(displayText, searchQuery) }
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    val quoted = plain.lines().joinToString("\n") { ">" + it }
                                    onBodyClick?.invoke(quoted)
                                }
                            )
                    ) {
                        ClickableText(text = annotated, onClick = { offset ->
                            val tags = annotated.getStringAnnotations(start = offset, end = offset)
                            val res = tags.firstOrNull { it.tag == "res" }?.item
                            val quote = tags.firstOrNull { it.tag == "quote" }?.item
                            val id = tags.firstOrNull { it.tag == "id" }?.item
                            val url = tags.firstOrNull { it.tag == "url" }?.item
                            val sodane = tags.firstOrNull { it.tag == "sodane" }?.item
                            when {
                                res != null -> resNumForDialog = res
                                quote != null -> onQuoteClick?.invoke(quote)
                                id != null -> onIdClick?.invoke(id)
                                url != null -> try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
                                sodane != null -> {
                                    // 推定対象 No. を同一行から取得
                                    val lineStart = displayText.lastIndexOf('\n', startIndex = offset.coerceAtMost(displayText.length), ignoreCase = false).let { if (it < 0) 0 else it + 1 }
                                    val lineEnd = displayText.indexOf('\n', startIndex = lineStart).let { if (it < 0) displayText.length else it }
                                    val lineText = displayText.substring(lineStart, lineEnd)
                                    val m = Regex("No\\.(\\d+)").find(lineText)
                                    val rn = m?.groupValues?.getOrNull(1)
                                    if (!rn.isNullOrBlank()) {
                                        // 既に押していれば無視
                                        val disabled = getSodaneState?.invoke(rn) ?: false
                                        if (!disabled) {
                                            // 楽観的に +1 表示
                                            sodaneOverrides[rn] = (sodaneOverrides[rn] ?: 0) + 1
                                            // コールバック（サーバ送信）
                                            onSodaneClick?.invoke(rn)
                                        }
                                    }
                                }
                            }
                        })
                    }
                }

                is DetailContent.Image -> {
                    val ctx = LocalContext.current
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AsyncImage(
                            model = item.imageUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val i = android.content.Intent(ctx, com.valoser.futaburakari.MediaViewActivity::class.java).apply {
                                        putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_TYPE, com.valoser.futaburakari.MediaViewActivity.TYPE_IMAGE)
                                        putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_URL, item.imageUrl)
                                        putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_TEXT, item.prompt)
                                    }
                                    ctx.startActivity(i)
                                },
                            contentScale = ContentScale.Fit,
                            onSuccess = { adapter.onImageLoaded?.invoke() }
                        )
                        // プロンプトはHTML→プレーン化。リンク検出は行わずプレーン表示。
                        val promptPlain = run {
                            val raw = item.prompt
                            val plain = if (!raw.isNullOrBlank()) Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString().trim() else null
                            when {
                                !plain.isNullOrBlank() -> plain
                                !item.fileName.isNullOrBlank() -> item.fileName
                                else -> null
                            }
                        }
                        if (!promptPlain.isNullOrBlank()) {
                            var expanded by remember(item.id) { mutableStateOf(false) }
                            androidx.compose.material3.Text(
                                text = promptPlain,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) Int.MAX_VALUE else 3,
                                overflow = if (expanded) androidx.compose.ui.text.style.TextOverflow.Clip else androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clickable { expanded = !expanded }
                            )
                        }
                    }
                }

                is DetailContent.Video -> {
                    val ctx = LocalContext.current
                    Column(modifier = Modifier.fillMaxWidth()) {
                        AsyncImage(
                            model = item.videoUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val i = android.content.Intent(ctx, com.valoser.futaburakari.MediaViewActivity::class.java).apply {
                                        putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_TYPE, com.valoser.futaburakari.MediaViewActivity.TYPE_VIDEO)
                                        putExtra(com.valoser.futaburakari.MediaViewActivity.EXTRA_URL, item.videoUrl)
                                    }
                                    ctx.startActivity(i)
                                },
                            contentScale = ContentScale.Fit,
                            onSuccess = { adapter.onImageLoaded?.invoke() }
                        )
                        val promptPlain = run {
                            val raw = item.prompt
                            val plain = if (!raw.isNullOrBlank()) Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString().trim() else null
                            when {
                                !plain.isNullOrBlank() -> plain
                                !item.fileName.isNullOrBlank() -> item.fileName
                                else -> null
                            }
                        }
                        if (!promptPlain.isNullOrBlank()) {
                            var expanded by remember(item.id) { mutableStateOf(false) }
                            androidx.compose.material3.Text(
                                text = promptPlain,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expanded) Int.MAX_VALUE else 3,
                                overflow = if (expanded) androidx.compose.ui.text.style.TextOverflow.Clip else androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                                    .clickable { expanded = !expanded }
                            )
                        }
                    }
                }

                is DetailContent.ThreadEndTime -> {
                    androidx.compose.material3.Text(
                        text = item.endTime,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)
                            .clickable { adapter.onThreadEndTimeClickListener?.invoke() },
                    )
                }
            }

            // Divider: ブロック末尾でのみ描画（次要素がText/EndTime or 末尾）
            if (isEndOfBlock(items, index)) {
                // 視認性のための余白を上下に付与し、コンテンツと線が密着しないようにする
                androidx.compose.material3.Divider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    thickness = 1.dp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }
    }

    // No. アクションダイアログ（返信/削除/確認/del）
    val resDialog = resNumForDialog
    if (resDialog != null) {
        AlertDialog(
            onDismissRequest = { resNumForDialog = null },
            title = { androidx.compose.material3.Text("No.$resDialog") },
            text = { androidx.compose.material3.Text("操作を選択してください") },
            confirmButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = {
                        onResNumClick?.invoke(resDialog, ">No.$resDialog")
                        resNumForDialog = null
                    }) { androidx.compose.material3.Text("返信") }
                    TextButton(onClick = {
                        onResNumClick?.invoke(resDialog, "")
                        resNumForDialog = null
                    }) { androidx.compose.material3.Text("削除") }
                    TextButton(onClick = {
                        onResNumConfirmClick?.invoke(resDialog)
                        resNumForDialog = null
                    }) { androidx.compose.material3.Text("確認") }
                    TextButton(onClick = {
                        onResNumDelClick?.invoke(resDialog)
                        resNumForDialog = null
                    }) { androidx.compose.material3.Text("del(通報)") }
                }
            },
            dismissButton = {
                TextButton(onClick = { resNumForDialog = null }) {
                    androidx.compose.material3.Text("キャンセル")
                }
            }
        )
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

// Build AnnotatedString with clickable No.xxxx and quote lines (> or >>)
private fun buildAnnotatedFromText(text: String, highlight: String?): AnnotatedString = buildAnnotatedString {
    append(text)
    // No.1234 pattern
    val resRegex = Regex("""No\.(\d+)""")
    resRegex.findAll(text).forEach { m ->
        val num = m.groupValues[1]
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
        addStringAnnotation(tag = "res", annotation = num, start = m.range.first, end = m.range.last + 1)
    }
    // Quote lines starting with >+
    val lineRegex = Regex("^>+[^\n]*", RegexOption.MULTILINE)
    lineRegex.findAll(text).forEach { m ->
        val token = m.value
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
        addStringAnnotation(tag = "quote", annotation = token, start = m.range.first, end = m.range.last + 1)
    }
    // ID:xxxx pattern
    val idRegex = Regex("""ID([:：])([\u0021-\u007E\u00A0-\u00FF\w./+]+)""")
    idRegex.findAll(text).forEach { m ->
        val id = m.groupValues.getOrNull(2) ?: return@forEach
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
        addStringAnnotation(tag = "id", annotation = id, start = m.range.first, end = m.range.last + 1)
    }
    // search highlight
    if (!highlight.isNullOrBlank()) {
        val pat = Regex(Regex.escape(highlight), RegexOption.IGNORE_CASE)
        pat.findAll(text).forEach { f ->
            addStyle(SpanStyle(background = Color.Yellow), f.range.first, f.range.last + 1)
        }
    }
    // URL clickable
    val urlRegex = Patterns.WEB_URL.toRegex()
    urlRegex.findAll(text).forEach { m ->
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
        addStringAnnotation("url", m.value, m.range.first, m.range.last + 1)
    }
    // そうだねトークン（+ / ＋ / そうだね / そうだねxN）
    val sodaneRegex = Regex("""(?:そうだねx\d+|そうだね|[+＋])""")
    sodaneRegex.findAll(text).forEach { m ->
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
        addStringAnnotation("sodane", "1", m.range.first, m.range.last + 1)
    }
}

// Insert visible spaces between tokens that tend to stick together in the plain text (ID / No / + / そうだね)
private fun padTokensForSpacing(src: String): String {
    var t = src.replace("\u200B", "")
    // Ensure a space between ID:xxxx and No.xxxx regardless of order
    t = Regex("(ID[:：][\\w./+]+)\\s*(?=No\\.)").replace(t, "$1 ")
    t = Regex("(No\\.\\d+)\\s*(?=ID[:：])").replace(t, "$1 ")
    // Space between No.xxxx and plus/そうだね tokens
    t = Regex("(No\\.\\d+)(?=(?:[+＋]|そうだね))").replace(t, "$1 ")
    // Normalize multiple spaces
    t = Regex("[ ]{2,}").replace(t, " ")
    // Ensure a そうだね token exists at end of No. line if absent
    val sb = StringBuilder()
    var start = 0
    while (start < t.length) {
        val nl = t.indexOf('\n', start)
        val end = if (nl < 0) t.length else nl
        val line = t.substring(start, end)
        if (Regex("No\\.\\d+").containsMatchIn(line)) {
            if (!Regex("(?:[+＋]|そうだね(?:x\\d+)?)").containsMatchIn(line)) {
                sb.append(line).append(" そうだね")
            } else sb.append(line)
        } else sb.append(line)
        if (nl >= 0) sb.append('\n')
        start = if (nl < 0) t.length else nl + 1
    }
    return sb.toString()
}

// Apply optimistic overrides: replace token with そうだねxN on No.行
private fun applySodaneDisplay(text: String, overrides: Map<String, Int>): String {
    if (overrides.isEmpty()) return text
    val sb = StringBuilder()
    var start = 0
    while (start < text.length) {
        val nl = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl
        var line = text.substring(start, end)
        val m = Regex("No\\.(\\d+)").find(line)
        val rn = m?.groupValues?.getOrNull(1)
        val cnt = rn?.let { overrides[it] }
        if (cnt != null && cnt > 0) {
            line = line.replace(Regex("(?:そうだねx\\d+|そうだね|[+＋])"), "そうだねx$cnt")
        }
        sb.append(line)
        if (nl >= 0) sb.append('\n')
        start = if (nl < 0) text.length else nl + 1
    }
    return sb.toString()
}

private fun isEndOfBlock(items: List<DetailContent>, index: Int): Boolean {
    if (index !in items.indices) return false
    val next = items.getOrNull(index + 1)
    // ブロック構造: Text + (Image|Video)* の塊。次がText/EndTime/なし なら区切り。
    // 末尾(null)では線を描かないように変更（最終行の下に線は不要）
    return when (next) {
        null -> false
        is DetailContent.Text, is DetailContent.ThreadEndTime -> true
        is DetailContent.Image, is DetailContent.Video -> false
    }
}
