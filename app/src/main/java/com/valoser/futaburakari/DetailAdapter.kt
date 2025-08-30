package com.valoser.futaburakari

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.URLSpan
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import java.util.regex.Pattern
import android.content.DialogInterface
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

class DetailAdapter : ListAdapter<DetailContent, RecyclerView.ViewHolder>(DetailDiffCallback()) {

    // 既存互換: ">" / ">>" を含めたトークンを1引数で渡す
    var onQuoteClickListener: ((quotedToken: String) -> Unit)? = null
    var onSodaNeClickListener: ((resNum: String) -> Unit)? = null
    var onThreadEndTimeClickListener: (() -> Unit)? = null
    var onResNumClickListener: ((resNum: String, resBody: String) -> Unit)? = null
    // 追加: del メニュー用（del.php を叩く）
    var onResNumDelClickListener: ((resNum: String) -> Unit)? = null
    // 追加: IDクリック
    var onIdClickListener: ((id: String) -> Unit)? = null
    // ★ 追加: 本文タップ時のコールバック
    var onBodyClickListener: ((quotedBody: String) -> Unit)? = null
    // ★ 追加: 本文からNG追加
    var onAddNgFromBodyListener: ((bodyText: String) -> Unit)? = null
    var onImageLoaded: (() -> Unit)? = null
    private var currentSearchQuery: String? = null

    // “そうだね”状態問い合わせ（外部提供）
    var getSodaNeState: ((String) -> Boolean)? = null

    // ★ 追加: レス番号→現在のそうだね数（サーバ返り値）を覚えておく
    private val sodaneOverrides = mutableMapOf<String, Int>()

    // 追加: 確認ボタン用のコールバック
    var onResNumConfirmClickListener: ((resNum: String) -> Unit)? = null

    // パターン類
    private val fileNamePattern = Pattern.compile("\\b([a-zA-Z0-9_.-]+\\.(?:jpg|jpeg|png|gif|webp|mp4|webm|mov|avi|flv|mkv))\\b", Pattern.CASE_INSENSITIVE)
    private val resNumPatternOriginal = Pattern.compile("No\\.(\\d+)")
    private val sodaNePattern = Pattern.compile("No\\.(\\d+)\\s*(?:\\u200B)?\\s*(?:[+＋]|そうだねx\\d+)")
    @Suppress("unused")
    private val urlPattern = Patterns.WEB_URL

    private val VIEW_TYPE_TEXT = 0
    private val VIEW_TYPE_IMAGE = 1
    private val VIEW_TYPE_VIDEO = 2
    private val VIEW_TYPE_THREAD_END = 3

    // 視覚余白用のゼロ幅スペース
    private val zwsp = "\u200B"


    fun updateSodane(resNum: String, count: Int) {
        sodaneOverrides[resNum] = count
        val idx = findPositionByResNum(resNum)
        if (idx >= 0) notifyItemChanged(idx, "SODANE_CHANGED") else notifyDataSetChanged()
    }

    // 楽観的更新: タップ直後にローカルで +1 して即時反映
    fun bumpSodaneOptimistic(resNum: String) {
        val next = (sodaneOverrides[resNum] ?: 0) + 1
        sodaneOverrides[resNum] = next
        val idx = findPositionByResNum(resNum)
        if (idx >= 0) notifyItemChanged(idx, "SODANE_CHANGED") else notifyDataSetChanged()
    }

    private fun findPositionByResNum(resNum: String): Int {
        val list = currentList
        for (i in list.indices) {
            val c = list[i]
            if (c is DetailContent.Text) {
                val html = c.htmlContent
                val byHtml = extractResNo(html)
                if (byHtml == resNum) return i
                // フォールバック：プレーンテキストから判定
                val plain = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()
                if (plain.contains("No.$resNum")) return i
            }
        }
        return -1
    }

    private fun extractResNo(html: String): String? {
        val m = resNumPatternOriginal.matcher(html)
        return if (m.find()) m.group(1) else null
    }

    // injectSodaneCount は廃止（HTML改変を避けるため）

    fun setSearchQuery(query: String?) {
        currentSearchQuery = query
        notifyDataSetChanged()
    }

    // dp -> px
    private fun TextView.dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // ZWSP を差し込み（ID/No/日時の直後など）
    private fun insertZwspForPadding(html: String): String {
        var t = html

        // ★ まずは ID と No が隣接している場合に “見える半角スペース” を入れる（両順序対応）
        // 例) "ID:abcdNo.123" -> "ID:abcd No.123"
        t = Regex("(ID:[\\w./+]+)\\s*(?=No\\.)").replace(t, "$1 ")
        // 例) "No.123ID:abcd" -> "No.123 ID:abcd"
        t = Regex("(No\\.\\d+)\\s*(?=ID:)").replace(t, "$1 ")

        // ★ そのうえで ZWSP による余白（見た目の行間）を従来通り付与
        val repl = listOf(
            Regex("(Name)") to "$1$zwsp",
            Regex("(としあき)") to "$1$zwsp",
            Regex("(\\d{2}/\\d{2}/\\d{2}\\([^)]+\\)\\d{2}:\\d{2}:\\d{2})") to "$1$zwsp",
            Regex("(ID:[\\w./+]+)") to "$1 $zwsp",
            Regex("(No\\.\\d+)") to "$1 $zwsp"
        )
        for ((rx, rep) in repl) t = rx.replace(t, rep)

        return t
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is DetailContent.Text -> VIEW_TYPE_TEXT
        is DetailContent.Image -> VIEW_TYPE_IMAGE
        is DetailContent.Video -> VIEW_TYPE_VIDEO
        is DetailContent.ThreadEndTime -> VIEW_TYPE_THREAD_END
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_TEXT -> TextViewHolder(
                inf.inflate(R.layout.detail_item_text, parent, false),
                this,
                onQuoteClickListener,
                onSodaNeClickListener,
                fileNamePattern,
                resNumPatternOriginal,
                sodaNePattern,
                onResNumClickListener,
                zwsp
            )
            VIEW_TYPE_IMAGE -> ImageViewHolder(
                inf.inflate(R.layout.detail_item_image, parent, false),
                onQuoteClickListener,
                fileNamePattern,
                onImageLoaded // ★ ViewHolderにコールバックを渡す
            )
            VIEW_TYPE_VIDEO -> VideoViewHolder(
                inf.inflate(R.layout.detail_item_video, parent, false),
                onQuoteClickListener,
                fileNamePattern,
                onImageLoaded // ★ この行を追加
            )
            VIEW_TYPE_THREAD_END -> ThreadEndTimeViewHolder(
                inf.inflate(R.layout.detail_item_thread_end_time, parent, false),
                onThreadEndTimeClickListener
            )
            else -> error("Unsupported view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DetailContent.Text -> (holder as TextViewHolder).bind(item, currentSearchQuery)
            is DetailContent.Image -> (holder as ImageViewHolder).bind(item)
            is DetailContent.Video -> (holder as VideoViewHolder).bind(item)
            is DetailContent.ThreadEndTime -> (holder as ThreadEndTimeViewHolder).bind(item)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.any { it == "SODANE_CHANGED" }) {
            val item = getItem(position)
            if (item is DetailContent.Text && holder is TextViewHolder) {
                holder.bind(item, currentSearchQuery)
                return
            }
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    // ---------- ViewHolders ----------

    class TextViewHolder(
        view: View,
        private val adapter: DetailAdapter,
        private val onQuoteClickListener: ((quotedToken: String) -> Unit)?,
        private val onSodaNeClickListener: ((resNum: String) -> Unit)?,
        private val fileNamePattern: Pattern,
        private val resNumPatternForClickableSpan: Pattern,
        private val sodaNePatternForClick: Pattern,
        private val onResNumClickListener: ((resNum: String, resBody: String) -> Unit)?,
        private val zwsp: String
    ) : RecyclerView.ViewHolder(view) {

        private val textView: TextView = view.findViewById(R.id.detailTextView)

        fun bind(item: DetailContent.Text, searchQuery: String?) {
            val htmlWithZwsp = insertZwspForPadding(item.htmlContent)
            val textFromHtmlWithZwsp = Html.fromHtml(htmlWithZwsp, Html.FROM_HTML_MODE_COMPACT)
            val spannableBuilder = SpannableStringBuilder(textFromHtmlWithZwsp)
            val contentString = spannableBuilder.toString()
            // No. が html に無いケースに備え、プレーンテキストからも取得を試みる
            val mainResNum = adapter.extractResNo(item.htmlContent)
                ?: run {
                    val m = resNumPatternForClickableSpan.matcher(contentString)
                    if (m.find()) m.group(1) else null
                }

            // --- No.xxx クリック（返信/削除メニュー）の設定 ---
            run {
                val m = resNumPatternForClickableSpan.matcher(contentString)
                var matchedResNum: String? = null
                if (m.find()) {
                    matchedResNum = m.group(1)
                    val s = m.start()
                    val e = m.end()
                    if (s >= 0 && e <= spannableBuilder.length) {
                        val span = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                val resNum = matchedResNum ?: mainResNum ?: return
                                val menuItems = arrayOf("返信", "削除", "確認", "del")
                                AlertDialog.Builder(widget.context)
                                    .setItems(menuItems) { _: DialogInterface, which: Int ->
                                        when (which) {
                                            0 -> onResNumClickListener?.invoke(resNum, ">No.$resNum")
                                            1 -> onResNumClickListener?.invoke(resNum, "")
                                            2 -> adapter.onResNumConfirmClickListener?.invoke(resNum)
                                            3 -> adapter.onResNumDelClickListener?.invoke(resNum)
                                        }
                                    }
                                    .show()
                            }
                            override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }
                        }
                        spannableBuilder.setSpan(span, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            // 引用（> / >>）
            run {
                val quotePattern = Pattern.compile("^(>+)(.+)$", Pattern.MULTILINE)
                val quoteMatcher = quotePattern.matcher(contentString)

                while (quoteMatcher.find()) {
                    val fullQuoteStart = quoteMatcher.start()
                    val fullQuoteEnd = quoteMatcher.end()
                    val marks = quoteMatcher.group(1) ?: continue
                    val body = quoteMatcher.group(2) ?: continue

                    // ★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★
                    // ★ 修正点：ゼロ幅スペース(zwsp)を除去してから判定する
                    // ★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★★
                    val cleanedBody = body.replace(zwsp, "").trim()

                    val idPattern = Pattern.compile("^ID:([\\w./+]+)")
                    val idMatcher = idPattern.matcher(cleanedBody) // 綺麗にした文字列で判定

                    if (idMatcher.find()) {
                        val id = idMatcher.group(1) ?: continue
                        val span = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                adapter.onIdClickListener?.invoke(id)
                            }
                            override fun updateDrawState(ds: TextPaint) {
                                ds.isUnderlineText = true
                            }
                        }
                        spannableBuilder.setSpan(span, fullQuoteStart, fullQuoteEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        continue
                    }

                    // --- IDでなかった場合にのみ、以下の通常の引用処理が実行される ---

                    val trimmedBody = body.trim() // 通常のtrimも念のため残す
                    val resNumMatcher = resNumPatternForClickableSpan.matcher(trimmedBody)
                    if (resNumMatcher.find()) {
                        val resNum = resNumMatcher.group(1) ?: continue
                        val bodyStartOffset = quoteMatcher.start(2)
                        val resNumStart = bodyStartOffset + resNumMatcher.start()
                        val resNumEnd = bodyStartOffset + resNumMatcher.end()

                        if (resNumStart >= 0 && resNumEnd <= spannableBuilder.length) {
                            val resNumSpan = object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    val menuItems = arrayOf("返信", "削除", "確認")
                                    AlertDialog.Builder(widget.context)
                                        .setItems(menuItems) { _, which ->
                                            when (which) {
                                                0 -> onResNumClickListener?.invoke(resNum, ">No.$resNum")
                                                1 -> onResNumClickListener?.invoke(resNum, "")
                                                2 -> adapter.onResNumConfirmClickListener?.invoke(resNum)
                                            }
                                        }
                                        .show()
                                }
                                override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }
                            }
                            spannableBuilder.setSpan(resNumSpan, resNumStart, resNumEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }

                        val generalQuoteSpan = object : ClickableSpan() {
                            override fun onClick(widget: View) { onQuoteClickListener?.invoke("$marks${trimmedBody}") }
                            override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
                        }

                        if (fullQuoteStart < resNumStart) {
                            spannableBuilder.setSpan(generalQuoteSpan, fullQuoteStart, resNumStart, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        if (resNumEnd < fullQuoteEnd) {
                            val generalQuoteSpanAfter = object : ClickableSpan() {
                                override fun onClick(widget: View) { onQuoteClickListener?.invoke("$marks${trimmedBody}") }
                                override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
                            }
                            spannableBuilder.setSpan(generalQuoteSpanAfter, resNumEnd, fullQuoteEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }

                    } else {
                        if (fullQuoteStart >= 0 && fullQuoteEnd <= spannableBuilder.length) {
                            val span = object : ClickableSpan() {
                                override fun onClick(widget: View) { onQuoteClickListener?.invoke("$marks${trimmedBody}") }
                                override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
                            }
                            spannableBuilder.setSpan(span, fullQuoteStart, fullQuoteEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }

            // ID:xxxx
            run {
                val idPattern = Pattern.compile("ID:([\\w./+]+)")
                val im = idPattern.matcher(contentString)
                while (im.find()) {
                    val id = im.group(1) ?: continue
                    val s = im.start(0)
                    val e = im.end(0)
                    if (s >= 0 && e <= spannableBuilder.length) {
                        val span = object : ClickableSpan() {
                            override fun onClick(widget: View) { adapter.onIdClickListener?.invoke(id) }
                            override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }
                        }
                        spannableBuilder.setSpan(span, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            // そうだね
            run {
                val m = sodaNePatternForClick.matcher(contentString)
                while (m.find()) {
                    val resNum = m.group(1) ?: continue
                    val matchStart = m.start()
                    val matchEnd = m.end()
                    val matchedText = contentString.substring(matchStart, matchEnd)
                    val tokenRegex = Regex("(?:[+＋]|そうだねx\\d+)")
                    val tokenMatch = tokenRegex.find(matchedText) ?: continue
                    var tokenAbsStart = matchStart + tokenMatch.range.first
                    var tokenAbsEnd   = matchStart + tokenMatch.range.last + 1

                    if (tokenAbsStart >= 0 && tokenAbsEnd <= spannableBuilder.length) {
                        // 楽観的更新やサーバ返り値がある場合は、+ を そうだねxN に置き換える
                        val override = adapter.sodaneOverrides[resNum]
                        if (override != null && override >= 0) {
                            val replacement = "そうだねx$override"
                            spannableBuilder.replace(tokenAbsStart, tokenAbsEnd, replacement)
                            // 置換により長さが変わるため、終端を再計算
                            tokenAbsEnd = tokenAbsStart + replacement.length
                        }

                        val span = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                // 1) 楽観的にローカル更新（TextViewの現在テキストを書き換え）
                                val tv = widget as TextView
                                val sp = SpannableStringBuilder(tv.text)
                                val start = sp.getSpanStart(this)
                                val end = sp.getSpanEnd(this)
                                val next = (adapter.sodaneOverrides[resNum] ?: 0) + 1
                                adapter.sodaneOverrides[resNum] = next
                                val replacement = "そうだねx$next"
                                val newEnd = if (start >= 0 && end >= 0 && end <= sp.length) {
                                    sp.replace(start, end, replacement)
                                    start + replacement.length
                                } else {
                                    // 位置が特定できない場合は末尾に追記
                                    sp.append(" $replacement"); sp.length
                                }
                                // 既存のクリックを除去して再付与
                                val exist = sp.getSpans(0, sp.length, ClickableSpan::class.java)
                                exist.forEach { sp.removeSpan(it) }
                                sp.setSpan(this, (newEnd - replacement.length), newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                tv.text = sp

                                // 2) 通知（保険で再バインド）
                                adapter.notifyDataSetChanged()

                                // 3) ネット送信
                                onSodaNeClickListener?.invoke(resNum)
                            }
                            override fun updateDrawState(ds: TextPaint) {
                                ds.isUnderlineText = true
                            }
                        }
                        spannableBuilder.setSpan(span, tokenAbsStart, tokenAbsEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            // そうだね（安全な後付け表示）
            // HTMLは改変せず、No.xxx の行末に " そうだねx{count}" もしくは " そうだね" を付与しクリック可能にする
            run {
                val resNum = mainResNum
                val count = resNum?.let { adapter.sodaneOverrides[it] }
                if (resNum != null) {
                    val noMatch = Regex("""No\.$resNum""").find(contentString)
                    val insertPos = if (noMatch != null) {
                        val start = noMatch.range.first
                        val after = contentString.substring(start)
                        val brIdx = after.indexOf('\n')
                        if (brIdx >= 0) start + brIdx else spannableBuilder.length
                    } else {
                        spannableBuilder.length
                    }
                    val lineText = if (noMatch != null) {
                        val start = noMatch.range.first
                        val end = insertPos
                        contentString.substring(start, end)
                    } else ""
                    // 既にトークン（+ / ＋ / そうだねxN / そうだね）が見えているなら追加しない
                    val alreadyHas = lineText.contains(Regex("(?:[+＋]|そうだねx\\d+|そうだね)(?![\\w])"))
                    if (!alreadyHas) {
                        val prefix = if (insertPos > 0 && spannableBuilder[insertPos - 1].isWhitespace()) "" else " "
                        val suffixCore = if (count != null && count >= 0) "そうだねx${count}" else "そうだね"
                        val suffix = "${prefix}${suffixCore}"
                        val start = insertPos
                        spannableBuilder.insert(start, suffix)
                        val end = start + suffix.length
                        val clickableStart = end - suffixCore.length
                        if (clickableStart >= 0 && end <= spannableBuilder.length) {
                            val span = object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    val tv = widget as TextView
                                    val sp = SpannableStringBuilder(tv.text)
                                    val start = sp.getSpanStart(this)
                                    val endPos = sp.getSpanEnd(this)
                                    val next = (adapter.sodaneOverrides[resNum] ?: 0) + 1
                                    adapter.sodaneOverrides[resNum] = next
                                    val replacement = "そうだねx$next"
                                    val newEnd = if (start >= 0 && endPos >= 0 && endPos <= sp.length) {
                                        sp.replace(start, endPos, replacement)
                                        start + replacement.length
                                    } else {
                                        sp.append(" $replacement"); sp.length
                                    }
                                    val exist = sp.getSpans(0, sp.length, ClickableSpan::class.java)
                                    exist.forEach { sp.removeSpan(it) }
                                    sp.setSpan(this, (newEnd - replacement.length), newEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    tv.text = sp

                                    adapter.notifyDataSetChanged()

                                    onSodaNeClickListener?.invoke(resNum)
                                }
                                override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }
                            }
                            spannableBuilder.setSpan(span, clickableStart, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }

            // 本文に含まれるファイル名
            run {
                val fm = fileNamePattern.matcher(contentString)
                while (fm.find()) {
                    val file = fm.group(1) ?: continue
                    val s = fm.start()
                    val e = fm.end()
                    if (s >= 0 && e <= spannableBuilder.length) {
                        val span = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                val items = arrayOf("返信", "確認")
                                AlertDialog.Builder(widget.context)
                                    .setItems(items) { _, which ->
                                        when (which) {
                                            0 -> adapter.onBodyClickListener?.invoke(">$file")
                                            1 -> onQuoteClickListener?.invoke(file)
                                        }
                                    }
                                    .show()
                            }
                            override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }
                        }
                        spannableBuilder.setSpan(span, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            // URLSpan を外部ブラウザ起動に差し替え（javascript: は無視して除去）
            run {
                val urlSpans = spannableBuilder.getSpans(0, spannableBuilder.length, URLSpan::class.java)
                for (old in urlSpans) {
                    val s = spannableBuilder.getSpanStart(old)
                    val e = spannableBuilder.getSpanEnd(old)
                    spannableBuilder.removeSpan(old)
                    val url = old.url ?: ""
                    if (url.startsWith("javascript:", ignoreCase = true)) {
                        // そうだねなどの JS アンカーはURLSpanを再付与しない（独自処理に委ねる）
                        continue
                    } else {
                        val span = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                try {
                                    val ctx = widget.context
                                    val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    ctx.startActivity(i)
                                } catch (ex: Exception) {
                                    Log.e("DetailAdapter", "Failed to open url: $url", ex)
                                }
                            }
                        }
                        if (s >= 0 && e <= spannableBuilder.length) {
                            spannableBuilder.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }

            // 検索ハイライト
            if (!searchQuery.isNullOrEmpty()) {
                val nq = Regex.escape(searchQuery)
                val pat = Regex("(?i)$nq")
                var idx = 0
                while (idx < spannableBuilder.length) {
                    val f = pat.find(spannableBuilder, idx) ?: break
                    spannableBuilder.setSpan(
                        BackgroundColorSpan(Color.YELLOW),
                        f.range.first,
                        f.range.last + 1,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    idx = f.range.last + 1
                }
            }

            textView.text = spannableBuilder
            textView.movementMethod = LinkMovementMethod.getInstance()

            // 本文長押しでメニュー
            textView.setOnLongClickListener { v ->
                val ctx = v.context
                val bodyOnly = extractPlainBody(item.htmlContent)
                if (bodyOnly.isBlank()) return@setOnLongClickListener true

                val items = arrayOf("引用して返信", "本文をコピー", "この本文をNGに追加…")
                AlertDialog.Builder(ctx)
                    .setItems(items) { _, which ->
                        when (which) {
                            0 -> {
                                val quotedBody = bodyOnly.lines().joinToString("\n") { ">$it" }
                                adapter.onBodyClickListener?.invoke(quotedBody)
                            }
                            1 -> {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("text", bodyOnly))
                                Toast.makeText(ctx, "コピーしました", Toast.LENGTH_SHORT).show()
                            }
                            2 -> {
                                adapter.onAddNgFromBodyListener?.invoke(bodyOnly)
                            }
                        }
                    }
                    .show()
                true
            }
        }

        private fun insertZwspForPadding(html: String): String = adapter.insertZwspForPadding(html)

        private fun extractPlainBody(html: String): String {
            val plain = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()
            val dateRegex = Regex("""\d{2}/\d{2}/\d{2}\([^)]+\)\d{2}:\d{2}:\d{2}""")
            val fileExtRegex = Regex("""\.(?:jpg|jpeg|png|gif|webp|bmp|svg|webm|mp4|mov|mkv|avi|wmv|flv)\b""", RegexOption.IGNORE_CASE)
            val sizeSuffixRegex = Regex("""[ \t]*[\\-ー−―–—]?\s*\(\s*\d+(?:\.\d+)?\s*(?:[kKmMgGtT]?[bB])\s*\)""")
            val headLabelRegex = Regex("""^(?:画像|動画|ファイル名|ファイル|添付|サムネ|サムネイル)(?:\s*ファイル名)?\s*[:：]""", RegexOption.IGNORE_CASE)

            fun isLabeledSizeOnlyLine(t: String): Boolean {
                return headLabelRegex.containsMatchIn(t) && sizeSuffixRegex.containsMatchIn(t)
            }

            return plain
                .lineSequence()
                .map { it.trimEnd() }
                .filterNot { line ->
                    val t = line.trim()
                    t.startsWith("ID:") || t.startsWith("No.") || dateRegex.containsMatchIn(t) || t.contains("Name")
                }
                .filterNot { line ->
                    val t = line.trim()
                    headLabelRegex.containsMatchIn(t) ||
                            (fileExtRegex.containsMatchIn(t) && sizeSuffixRegex.containsMatchIn(t)) ||
                            isLabeledSizeOnlyLine(t) ||
                            (fileExtRegex.containsMatchIn(t) && t.contains("サムネ"))
                }
                .joinToString("\n")
                .trimEnd()
        }
    }

    // DetailAdapter.ImageViewHolder （マージ後）
    class ImageViewHolder(
        view: View,
        private val onQuoteClickListener: ((quotedToken: String) -> Unit)?,
        private val fileNamePattern: Pattern,
        private val onImageLoaded: (() -> Unit)?
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {

        private val imageView: ImageView = view.findViewById(R.id.detailImageView)
        private val promptView: TextView? = view.findViewById(R.id.promptTextView)

        private val image: ImageView = view.findViewById(R.id.detailImageView)
        private val spinner: View? = view.findViewById(R.id.loadingSpinner)

        fun bind(item: DetailContent.Image) {
            // --- 画像ローディング ---
            spinner?.visibility = View.VISIBLE
            image.visibility = View.INVISIBLE

            image.layoutParams = image.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = (image.resources.displayMetrics.density * 100).toInt() // 100dp
            }
            image.scaleType = ImageView.ScaleType.CENTER_CROP
            image.adjustViewBounds = false

            image.load(item.imageUrl) {
                crossfade(true)
                listener(
                    onStart = {
                        spinner?.visibility = View.VISIBLE
                        image.visibility = View.INVISIBLE
                    },
                    onSuccess = { _, _ ->
                        image.layoutParams = image.layoutParams.apply {
                            width = ViewGroup.LayoutParams.MATCH_PARENT
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        image.adjustViewBounds = true
                        image.scaleType = ImageView.ScaleType.FIT_CENTER

                        spinner?.visibility = View.GONE
                        image.visibility = View.VISIBLE
                        onImageLoaded?.invoke()
                    },
                    onError = { _, _ ->
                        spinner?.visibility = View.GONE
                        image.visibility = View.VISIBLE
                    }
                )
            }

            // 画像タップでビューア
            imageView.setOnClickListener {
                val ctx = it.context
                val i = Intent(ctx, MediaViewActivity::class.java).apply {
                    putExtra(MediaViewActivity.EXTRA_TYPE, MediaViewActivity.TYPE_IMAGE)
                    putExtra(MediaViewActivity.EXTRA_URL, item.imageUrl)
                    putExtra(MediaViewActivity.EXTRA_TEXT, item.prompt)
                }
                ctx.startActivity(i)
            }

            // --- プロンプト表示 ---
            promptView?.let { tv ->
                val raw = item.prompt.orEmpty()
                if (raw.isNotEmpty()) {
                    val promptRaw = raw
                    val pd = PromptFormatter.parse(raw)
                    if (pd != null) {
                        val pretty = PromptFormatter.toSpannable(pd)
                        setPromptWithToggle(tv, pretty, maxLines = 8)
                    } else {
                        // フォールバック：生テキストにファイル名スパンを付与
                        val sp = SpannableString(raw)
                        val m = fileNamePattern.matcher(raw)
                        while (m.find()) {
                            val file = m.group(1) ?: continue
                            val s = m.start()
                            val e = m.end()
                            if (s >= 0 && e <= sp.length) {
                                val span = object : ClickableSpan() {
                                    override fun onClick(widget: View) {
                                        onQuoteClickListener?.invoke(file)
                                    }
                                }
                                sp.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                        tv.movementMethod = LinkMovementMethod.getInstance()
                        setPromptWithToggle(tv, sp, maxLines = 8)
                    }

                    tv.visibility = View.VISIBLE

                    // シングル＝折りたたみ/展開、ダブル＝全文ビューア
                    tv.setSingleAndDoubleClickListener(
                        onSingleClick = {
                            val collapsed = tv.maxLines != Int.MAX_VALUE
                            tv.maxLines = if (collapsed) Int.MAX_VALUE else 8
                            // 再設定（Spannable が切れないように）
                            val again = pd?.let { PromptFormatter.toSpannable(it) } ?: tv.text
                            tv.text = again
                        },
                        onDoubleClick = {
                            val ctx = tv.context
                            val i = Intent(ctx, MediaViewActivity::class.java).apply {
                                putExtra(MediaViewActivity.EXTRA_TYPE, MediaViewActivity.TYPE_TEXT)
                                putExtra(MediaViewActivity.EXTRA_TEXT, promptRaw) // ★ raw を固定で渡す
                            }
                            ctx.startActivity(i)
                        }
                    )
                } else {
                    tv.text = ""
                    tv.visibility = View.GONE
                }
            }
        }

        // 折りたたみ/展開の簡易ヘルパ（TextView内完結）
        private fun setPromptWithToggle(tv: TextView, content: CharSequence, maxLines: Int = 8) {
            tv.text = content
            tv.visibility = View.VISIBLE
            tv.post {
                val needsFold = tv.lineCount > maxLines
                if (!needsFold) return@post
                tv.maxLines = maxLines
            }
        }
    }


    class VideoViewHolder(
        view: View,
        private val onQuoteClickListener: ((quotedToken: String) -> Unit)?,
        private val fileNamePattern: Pattern,
        private val onImageLoaded: (() -> Unit)?
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {

        private val thumb: ImageView = view.findViewById(R.id.videoThumbView)
        private val spinner: View?   = view.findViewById(R.id.videoLoadingSpinner)
        private val promptView: TextView? = view.findViewById(R.id.promptTextView)

        fun bind(item: DetailContent.Video) {
            // --- サムネローディング ---
            spinner?.visibility = View.VISIBLE
            thumb.visibility = View.INVISIBLE

            thumb.layoutParams = thumb.layoutParams.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = (thumb.resources.displayMetrics.density * 100).toInt() // 100dp
            }
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.adjustViewBounds = false

            thumb.load(item.videoUrl) {
                crossfade(true)
                listener(
                    onStart = {
                        spinner?.visibility = View.VISIBLE
                        thumb.visibility = View.INVISIBLE
                    },
                    onSuccess = { _, _ ->
                        thumb.layoutParams = thumb.layoutParams.apply {
                            width = ViewGroup.LayoutParams.MATCH_PARENT
                            height = ViewGroup.LayoutParams.WRAP_CONTENT
                        }
                        thumb.adjustViewBounds = true
                        thumb.scaleType = ImageView.ScaleType.FIT_CENTER

                        spinner?.visibility = View.GONE
                        thumb.visibility = View.VISIBLE
                        onImageLoaded?.invoke()
                    },
                    onError = { _, _ ->
                        spinner?.visibility = View.GONE
                        thumb.visibility = View.VISIBLE
                    }
                )
            }

            // サムネタップで動画ビューア
            thumb.setOnClickListener {
                val ctx = it.context
                val i = Intent(ctx, MediaViewActivity::class.java).apply {
                    putExtra(MediaViewActivity.EXTRA_TYPE, MediaViewActivity.TYPE_VIDEO)
                    putExtra(MediaViewActivity.EXTRA_URL, item.videoUrl)
                    putExtra(MediaViewActivity.EXTRA_TEXT, item.prompt)
                }
                ctx.startActivity(i)
            }

            // --- プロンプト表示 ---
            promptView?.let { tv ->
                val raw = item.prompt.orEmpty()
                if (raw.isNotEmpty()) {
                    val promptRaw = raw
                    val pd = PromptFormatter.parse(raw)
                    if (pd != null) {
                        val pretty = PromptFormatter.toSpannable(pd)
                        setPromptWithToggle(tv, pretty, maxLines = 8)
                    } else {
                        // フォールバック：生テキストにファイル名スパン
                        val sp = SpannableString(raw)
                        val m = fileNamePattern.matcher(raw)
                        while (m.find()) {
                            val file = m.group(1) ?: continue
                            val s = m.start()
                            val e = m.end()
                            if (s >= 0 && e <= sp.length) {
                                val span = object : ClickableSpan() {
                                    override fun onClick(widget: View) {
                                        onQuoteClickListener?.invoke(file)
                                    }
                                }
                                sp.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                        tv.movementMethod = LinkMovementMethod.getInstance()
                        setPromptWithToggle(tv, sp, maxLines = 8)
                    }

                    tv.visibility = View.VISIBLE

                    // シングル＝折りたたみ/展開、ダブル＝全文ビューア
                    tv.setSingleAndDoubleClickListener(
                        onSingleClick = {
                            val collapsed = tv.maxLines != Int.MAX_VALUE
                            tv.maxLines = if (collapsed) Int.MAX_VALUE else 8
                            val again = pd?.let { PromptFormatter.toSpannable(it) } ?: tv.text
                            tv.text = again
                        },
                        onDoubleClick = {
                            val ctx = tv.context
                            val i = Intent(ctx, MediaViewActivity::class.java).apply {
                                putExtra(MediaViewActivity.EXTRA_TYPE, MediaViewActivity.TYPE_TEXT)
                                putExtra(MediaViewActivity.EXTRA_TEXT, promptRaw) // ★ raw を固定で渡す
                            }
                            ctx.startActivity(i)
                        }
                    )
                } else {
                    tv.text = ""
                    tv.visibility = View.GONE
                }
            }
        }

        private fun setPromptWithToggle(tv: TextView, content: CharSequence, maxLines: Int = 8) {
            tv.text = content
            tv.visibility = View.VISIBLE
            tv.post {
                val needsFold = tv.lineCount > maxLines
                if (!needsFold) return@post
                tv.maxLines = maxLines
            }
        }
    }

    class ThreadEndTimeViewHolder(
        view: View,
        private val onClick: (() -> Unit)?
    ) : RecyclerView.ViewHolder(view) {
        // 正: detail_item_thread_end_time.xml の ID
        private val endView: TextView = view.findViewById(R.id.endTimeTextView)
        fun bind(item: DetailContent.ThreadEndTime) {
            endView.text = item.endTime
            endView.setOnClickListener { onClick?.invoke() }
        }
    }

    // ---------- DiffUtil ----------

    class DetailDiffCallback : DiffUtil.ItemCallback<DetailContent>() {
        override fun areItemsTheSame(oldItem: DetailContent, newItem: DetailContent): Boolean {
            return when {
                // ★ ThreadEndTime は「常に同一アイテム」とみなす
                oldItem is DetailContent.ThreadEndTime && newItem is DetailContent.ThreadEndTime -> true
                else -> oldItem.id == newItem.id && oldItem::class == newItem::class
            }
        }

        override fun areContentsTheSame(oldItem: DetailContent, newItem: DetailContent): Boolean {
            return when {
                // ★ 中身（endTime）が同じなら変更なし、違えば更新
                oldItem is DetailContent.ThreadEndTime && newItem is DetailContent.ThreadEndTime ->
                    oldItem.endTime == newItem.endTime
                else -> oldItem == newItem
            }
        }

        // あるとよりスムーズ（任意）
        override fun getChangePayload(oldItem: DetailContent, newItem: DetailContent): Any? {
            return when {
                oldItem is DetailContent.ThreadEndTime && newItem is DetailContent.ThreadEndTime ->
                    if (oldItem.endTime != newItem.endTime) "THREAD_END_TIME_CHANGED" else null
                else -> null
            }
        }
    }

    // ---------- 補助Span ----------

    class PaddingAfterSpan(private val paddingPx: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence?, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
            fm.bottom += paddingPx; fm.descent += paddingPx
        }
    }

    class ZeroWidthCleanerSpan : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean): Int = 0
        override fun drawLeadingMargin(
            c: Canvas?, p: Paint?, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence?, start: Int, end: Int, first: Boolean, layout: android.text.Layout?
        ) {}
    }
}
