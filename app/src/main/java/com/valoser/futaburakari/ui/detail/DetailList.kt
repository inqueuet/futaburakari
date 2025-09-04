package com.valoser.futaburakari.ui.detail

/**
 * スレ詳細のコンテンツを表示するCompose版リスト。
 *
 * 概要:
 * - 表示要素は `DetailContent` の列（Text / Image / Video / ThreadEndTime）。
 * - ブロック構造: 1つの Text に続く Image/Video を同一ブロックとして扱い、ブロック末尾のみ区切り線を描画。
 * - 検索: `searchQuery` にマッチする要素のインデックスを算出し、`onProvideSearchNavigator` で Prev/Next 関数を渡す。
 * - アノテーション/クリック: 本文(Text)内の `No.xxxx`、引用行(> or ＞)、`ID:xxxx`、URL、ファイル名（xxx.jpg 等）、そうだね(+/＋/そうだね/そうだねxN) を検出してクリック可能にする。
 *   - そうだねは引用行を除外し、同じ行に `No.` が含まれる場合のみ有効。
 *   - タイトル行が `threadTitle` に一致する場合は引用としても扱う（全角/半角/空白の差は正規化して比較）。
 * - 「そうだね」表示は親から渡されるカウントで楽観的に上書き表示する（`applySodaneDisplay`）。
 * - スクロール状態の保存/復元、最大既読序数の通知(`onVisibleMaxOrdinal`)に対応。
 * - 画像/動画の直下に表示するプロンプト文は選択コピー可能（SelectionContainer）。
 * - 画像/動画のタップでメディアビューへ遷移（拡大/動画再生、コピー/保存機能はメディア側で提供）。
 * - 画像/動画の直下にファイル名があれば表示し、タップでファイル名参照の集計シートを開く。
 * - プロンプト文はHTML→プレーン化して表示（リンク検出や装飾は行わない）。
 */
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
 
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Color
 
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
    // コールバック群 — 従来の DetailAdapter のリスナー相当をComposeで受け取る
    onQuoteClick: ((String) -> Unit)? = null,
    onSodaneClick: ((String) -> Unit)? = null,
    onThreadEndTimeClick: (() -> Unit)? = null,
    onResNumClick: ((resNum: String, resBody: String) -> Unit)? = null,
    onResNumConfirmClick: ((String) -> Unit)? = null,
    onResNumDelClick: ((String) -> Unit)? = null,
    onIdClick: ((String) -> Unit)? = null,
    onBodyClick: ((String) -> Unit)? = null,
    onAddNgFromBody: ((String) -> Unit)? = null,
    // 本文タップで引用元（このレスを引用している投稿）を表示
    onBodyShowBackRefs: ((DetailContent.Text) -> Unit)? = null,
    // ファイル名クリックで引用まとめ
    onFileNameClick: ((String) -> Unit)? = null,
    // "そうだね" 済みかの状態問い合わせ（重複押下の抑止用）
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
    // 上位で保持する "そうだね" 表示カウント（resNum -> count）
    sodaneCounts: Map<String, Int> = emptyMap(),
    onSetSodaneCount: ((String, Int) -> Unit)? = null,
    // スレタイトル（タイトル行を引用扱いにするためのヒント）
    threadTitle: String? = null,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // コールバックは直接受け渡し（レガシーなアダプタ依存は無し）

    // No. アクション用ダイアログの状態
    var resNumForDialog by remember { mutableStateOf<String?>(null) }
    // "そうだね" 表示カウントは親から受け取る

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
        // 上位にハンドラを提供（Prev/Next）。クリック時に該当箇所へアニメーションスクロール。
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

    // 最大既読序数の通知（50%以上見えている Text 単位の最大序数）
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

    // スクロール位置の変化を親へ保存通知（index + offset）
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
                    val selfResNum = remember(plain) {
                        Regex("""No\.(\d+)""").find(plain)?.groupValues?.getOrNull(1)
                    }
                    // 楽観表示の変更に追随するよう、エントリのスナップショットをキーに再計算
                    // 表示用にトークン周りの空白を補正し、そうだねの楽観カウントを適用
                    val displayText = remember(plain, sodaneCounts.toList()) {
                        applySodaneDisplay(padTokensForSpacing(plain), sodaneCounts)
                    }
                    // クリック可能領域（No./引用/ID/URL/ファイル名/そうだね/検索ハイライト）を付与
                    val annotated = remember(displayText, searchQuery, threadTitle) { buildAnnotatedFromText(displayText, searchQuery, threadTitle) }
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
                            val filename = tags.firstOrNull { it.tag == "filename" }?.item
                            val quote = tags.firstOrNull { it.tag == "quote" }?.item
                            val id = tags.firstOrNull { it.tag == "id" }?.item
                            val url = tags.firstOrNull { it.tag == "url" }?.item
                            val sodane = tags.firstOrNull { it.tag == "sodane" }?.item
                            when {
                                // No. タップで「引用元（このレスを引用している投稿）」の一覧シートを直接表示
                                res != null -> onResNumConfirmClick?.invoke(res)
                                // 引用行内にファイル名が存在する場合はファイル名集計を優先
                                filename != null -> onFileNameClick?.invoke(filename)
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
                                            // 楽観的に +1 表示（親に委譲）
                                            val next = (sodaneCounts[rn] ?: 0) + 1
                                            onSetSodaneCount?.invoke(rn, next)
                                            // コールバック（サーバ送信）
                                            onSodaneClick?.invoke(rn)
                                        }
                                    }
                                }
                            }
                            // 本文（いずれのタグにも該当しない領域）タップで、当該レスを引用している投稿一覧を表示
                            if (res == null && filename == null && quote == null && id == null && url == null && sodane == null) {
                                onBodyShowBackRefs?.invoke(item)
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
                            onSuccess = { onImageLoaded?.invoke() }
                        )
                        // プロンプトはHTML→プレーン化。リンク検出は行わずプレーン表示。長文はタップで展開/折りたたみ。
                        val promptPlain = run {
                            val raw = item.prompt
                            val plain = if (!raw.isNullOrBlank()) Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString().trim() else null
                            if (!plain.isNullOrBlank()) plain else null
                        }
                        if (!promptPlain.isNullOrBlank()) {
                            var expanded by remember(item.id) { mutableStateOf(false) }
                            SelectionContainer {
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
                        // ファイル名（表示してクリック可能に）
                        val fn = item.fileName
                        if (!fn.isNullOrBlank()) {
                            androidx.compose.material3.Text(
                                text = fn,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                                    .clickable { onFileNameClick?.invoke(fn) }
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
                            onSuccess = { onImageLoaded?.invoke() }
                        )
                        // ファイル名（表示してクリック可能に）
                        val vfn = item.fileName
                        if (!vfn.isNullOrBlank()) {
                            androidx.compose.material3.Text(
                                text = vfn,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.Underline),
                                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                                    .clickable { onFileNameClick?.invoke(vfn) }
                            )
                        }
                        // サムネイル下の説明テキスト（HTML→プレーン化）。長文はタップで展開/折りたたみ。
                        val promptPlain = run {
                            val raw = item.prompt
                            val plain = if (!raw.isNullOrBlank()) Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString().trim() else null
                            if (!plain.isNullOrBlank()) plain else null
                        }
                        if (!promptPlain.isNullOrBlank()) {
                            var expanded by remember(item.id) { mutableStateOf(false) }
                            SelectionContainer {
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
                }

                is DetailContent.ThreadEndTime -> {
                    androidx.compose.material3.Text(
                        text = item.endTime,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)
                            .clickable { onThreadEndTimeClick?.invoke() },
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

    // No. アクションダイアログ（返信/削除/確認/del）。`resNumForDialog` がセットされたときに表示。
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

// indexまでに現れたText要素の個数（=序数）を求める
private fun ordinalForIndex(all: List<DetailContent>, index: Int): Int {
    var ord = 0
    var i = 0
    while (i <= index && i < all.size) {
        if (all[i] is DetailContent.Text) ord++
        i++
    }
    return ord
}

// 本文用の AnnotatedString を構築。
// - クリック可能: No.xxxx, 引用行(> または 全角＞), スレタイ行, ID:xxxx, URL, ファイル名（xxx.jpg 等）, そうだねトークン。
// - 検索語は背景色でハイライト。
private fun buildAnnotatedFromText(text: String, highlight: String?, threadTitle: String?): AnnotatedString = buildAnnotatedString {
    append(text)
    // No.1234 pattern
    val resRegex = Regex("""No\.(\d+)""")
    resRegex.findAll(text).forEach { m ->
        val num = m.groupValues[1]
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
        addStringAnnotation(tag = "res", annotation = num, start = m.range.first, end = m.range.last + 1)
    }
    // 引用行: 行頭の空白や全角＞を許容し、タグには正規化したトークンを渡す
    val lineRegex = Regex("^(?:[\\t \\u3000])*[>＞]+[^\\n]*", RegexOption.MULTILINE)
    lineRegex.findAll(text).forEach { m ->
        val tokenRaw = m.value
        val token = tokenRaw.trimStart().replace('＞', '>')
        val start = m.range.first + (m.value.length - tokenRaw.trimStart().length)
        val end = m.range.last + 1
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
        addStringAnnotation(tag = "quote", annotation = token, start = start, end = end)
    }
    // タイトル行: スレタイと一致する行（空白/全角差を無視）は引用としてもクリック可能にする
    if (!threadTitle.isNullOrBlank()) {
        fun normalize(s: String): String = java.text.Normalizer.normalize(
            s.replace("\u200B", "").replace('　', ' ').replace('＞', '>').replace('≫', '>'),
            java.text.Normalizer.Form.NFKC
        ).replace(Regex("\\s+"), " ").trim()
        val needle = normalize(threadTitle)
        var idx = 0
        while (idx <= text.length) {
            val nl = text.indexOf('\n', idx)
            val end = if (nl < 0) text.length else nl
            val s = idx
            val e = end
            val line = text.substring(s, e)
            val trimmed = line.trim()
            // 既に '>' で始まる行は通常の引用検出に任せる
            if (!trimmed.startsWith('>')) {
                val norm = normalize(line)
                if (norm.isNotBlank() && norm == needle) {
                    val token = ">" + trimmed
                    addStyle(SpanStyle(textDecoration = TextDecoration.Underline), s, e)
                    addStringAnnotation(tag = "quote", annotation = token, start = s, end = e)
                }
            }
            if (nl < 0) break else idx = nl + 1
        }
    }
    // ID:xxxx pattern
    val idRegex = Regex("""ID([:：])([\u0021-\u007E\u00A0-\u00FF\w./+]+)""")
    idRegex.findAll(text).forEach { m ->
        val id = m.groupValues.getOrNull(2) ?: return@forEach
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
        addStringAnnotation(tag = "id", annotation = id, start = m.range.first, end = m.range.last + 1)
    }
    // 検索ハイライト
    if (!highlight.isNullOrBlank()) {
        val pat = Regex(Regex.escape(highlight), RegexOption.IGNORE_CASE)
        pat.findAll(text).forEach { f ->
            addStyle(SpanStyle(background = Color.Yellow), f.range.first, f.range.last + 1)
        }
    }
    // URL: クリック可能にする
    val urlRegex = Patterns.WEB_URL.toRegex()
    urlRegex.findAll(text).forEach { m ->
        addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
        addStringAnnotation("url", m.value, m.range.first, m.range.last + 1)
    }
    // ファイル名トークン（拡張子を含むものを検出しクリック可能に）
    run {
        val ext = "(?:jpg|jpeg|png|gif|webp|bmp|mp4|webm|avi|mov|mkv)"
        val pat = Regex("""(?i)([A-Za-z0-9._-]+\.$ext)""")
        pat.findAll(text).forEach { m ->
            addStyle(SpanStyle(textDecoration = TextDecoration.Underline), m.range.first, m.range.last + 1)
            addStringAnnotation("filename", m.groupValues[1], m.range.first, m.range.last + 1)
        }
    }
    // そうだねトークン（+ / ＋ / そうだね / そうだねxN）— 引用行(>)を除き、No.を含む行を対象にする
    val sodaneRegex = Regex("""(?:そうだねx\d+|そうだね|[+＋])""")
    var start = 0
    while (start <= text.length) {
        val nl = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl
        val lineStart = start
        val line = text.substring(lineStart, end)
        val trimmed = line.trimStart()
        val isQuote = trimmed.startsWith(">")
        val hasNo = Regex("\\bNo\\.\\d+\\b").containsMatchIn(line)
        if (!isQuote && hasNo) {
            sodaneRegex.findAll(line).forEach { m ->
                val s = lineStart + m.range.first
                val e = lineStart + m.range.last + 1
                addStyle(SpanStyle(textDecoration = TextDecoration.Underline), s, e)
                addStringAnnotation("sodane", "1", s, e)
            }
        }
        if (nl < 0) break else start = nl + 1
    }
}

// プレーンテキスト上で詰まりやすいトークン（ID / No / + / そうだね）の間に空白を補い、
// No. を含む非引用行の末尾に そうだね トークンが存在しない場合は付与する。
private fun padTokensForSpacing(src: String): String {
    var t = src.replace("\u200B", "")
    // ID:xxxx と No.xxxx の間に順不同で必ず空白を入れる
    t = Regex("(ID[:：][\\w./+]+)\\s*(?=No\\.)").replace(t, "$1 ")
    t = Regex("(No\\.\\d+)\\s*(?=ID[:：])").replace(t, "$1 ")
    // No.xxxx と +/そうだね トークンの間に空白を入れる
    t = Regex("(No\\.\\d+)(?=(?:[+＋]|そうだね))").replace(t, "$1 ")
    // 複数の空白を1つに正規化
    t = Regex("[ ]{2,}").replace(t, " ")
    // No. を含む非引用行の末尾に そうだね トークンが無ければ付与（本文や引用行は対象外）
    val sb = StringBuilder()
    var start = 0
    while (start < t.length) {
        val nl = t.indexOf('\n', start)
        val end = if (nl < 0) t.length else nl
        val line = t.substring(start, end)
        val trimmed = line.trimStart()
        val isQuote = trimmed.startsWith(">")
        val hasNo = Regex("\\bNo\\.\\d+\\b").containsMatchIn(line)
        if (!isQuote && hasNo) {
            if (!Regex("(?:[+＋]|そうだね(?:x\\d+)?)").containsMatchIn(line)) {
                sb.append(line).append(" そうだね")
            } else sb.append(line)
        } else sb.append(line)
        if (nl >= 0) sb.append('\n')
        start = if (nl < 0) t.length else nl + 1
    }
    return sb.toString()
}

// Apply optimistic overrides: そうだねトークンを そうだねxN に置換（No. 行のみ対象）
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
