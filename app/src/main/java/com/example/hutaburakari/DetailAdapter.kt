package com.example.hutaburakari

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
import coil.size.ViewSizeResolver
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
    // 追加: IDクリック
    var onIdClickListener: ((id: String) -> Unit)? = null
    // ★ 追加: 本文タップ時のコールバック
    var onBodyClickListener: ((quotedBody: String) -> Unit)? = null
    private var currentSearchQuery: String? = null

    // “そうだね”状態問い合わせ（外部提供）
    var getSodaNeState: ((String) -> Boolean)? = null

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
                fileNamePattern
            )
            VIEW_TYPE_VIDEO -> VideoViewHolder(
                inf.inflate(R.layout.detail_item_video, parent, false),
                onQuoteClickListener,
                fileNamePattern
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

        // 正: detail_item_text.xml の ID
        private val textView: TextView = view.findViewById(R.id.detailTextView)

        fun bind(item: DetailContent.Text, searchQuery: String?) {
            val htmlWithZwsp = insertZwspForPadding(item.htmlContent)
            val textFromHtmlWithZwsp = Html.fromHtml(htmlWithZwsp, Html.FROM_HTML_MODE_COMPACT)
            //val spannableBuilder = SpannableStringBuilder(adapter.buildDisplayOnlyPaddedText(textView, textFromHtmlWithZwsp))
            val spannableBuilder = SpannableStringBuilder(textFromHtmlWithZwsp)
            val contentString = spannableBuilder.toString()

            var mainResNum: String? = null

            // ★ 変更点 1: No.xxx をクリック → メニュー表示
            run {
                val m = resNumPatternForClickableSpan.matcher(contentString)
                if (m.find()) {
                    mainResNum = m.group(1)
                    val s = m.start()
                    val e = m.end()
                    if (s >= 0 && e <= spannableBuilder.length) {
                        val span = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                val resNum = mainResNum ?: return
                                val menuItems = arrayOf("返信", "削除") // 削除は未実装
                                AlertDialog.Builder(widget.context)
                                    .setItems(menuItems) { _: DialogInterface, which: Int ->
                                        when (which) {
                                            0 -> { // 返信
                                                val q = ">No.$resNum"
                                                onResNumClickListener?.invoke(resNum, q)
                                            }
                                            1 -> { // 削除
                                                // ★ ここをActivityへ委譲（resBodyは要らないので空文字で）
                                                onResNumClickListener?.invoke(resNum, "")
                                            }
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
                val m = quotePattern.matcher(contentString)
                while (m.find()) {
                    val marks = m.group(1) ?: continue
                    val body = m.group(2)?.trim() ?: continue
                    val s = m.start()
                    val e = m.end()
                    if (s >= 0 && e <= spannableBuilder.length) {
                        val span = object : ClickableSpan() {
                            override fun onClick(widget: View) { onQuoteClickListener?.invoke("$marks$body") }
                            override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
                        }
                        spannableBuilder.setSpan(span, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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

            run {
                val m = sodaNePatternForClick.matcher(contentString)
                while (m.find()) {
                    val resNum = m.group(1) ?: continue
                    val matchStart = m.start()
                    val matchEnd = m.end()

                    // マッチ範囲の中で、クリック対象となるトークン（+ または そうだねxN）の位置だけに span を貼る
                    val matchedText = contentString.substring(matchStart, matchEnd)

                    // “+ / ＋ / そうだねx数字” を探す
                    val tokenRegex = Regex("(?:[+＋]|そうだねx\\d+)")
                    val tokenMatch = tokenRegex.find(matchedText) ?: continue

                    val tokenAbsStart = matchStart + tokenMatch.range.first
                    val tokenAbsEnd   = matchStart + tokenMatch.range.last + 1

                    if (tokenAbsStart >= 0 && tokenAbsEnd <= spannableBuilder.length) {
                        val span = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                onSodaNeClickListener?.invoke(resNum) // ← No の数字を渡す
                            }
                            override fun updateDrawState(ds: TextPaint) {
                                ds.isUnderlineText = true // 見た目はお好みで
                            }
                        }
                        spannableBuilder.setSpan(
                            span, tokenAbsStart, tokenAbsEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
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
                            override fun onClick(widget: View) { onQuoteClickListener?.invoke(file) }
                            override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = true }
                        }
                        spannableBuilder.setSpan(span, s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            // URLSpan を外部ブラウザ起動に差し替え
            run {
                val urlSpans = spannableBuilder.getSpans(0, spannableBuilder.length, URLSpan::class.java)
                for (old in urlSpans) {
                    val s = spannableBuilder.getSpanStart(old)
                    val e = spannableBuilder.getSpanEnd(old)
                    spannableBuilder.removeSpan(old)
                    val span = object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            try {
                                val ctx = widget.context
                                val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(old.url))
                                ctx.startActivity(i)
                            } catch (ex: Exception) {
                                Log.e("DetailAdapter", "Failed to open url: ${old.url}", ex)
                            }
                        }
                    }
                    if (s >= 0 && e <= spannableBuilder.length) {
                        spannableBuilder.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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

            // ★ 変更点 2: 本文タップで引用付き返信
            textView.setOnClickListener {
                val bodyOnly = extractPlainBody(item.htmlContent)
                if (bodyOnly.isNotBlank()) {
                    // 全行の先頭に ">" を付けて引用
                    val quotedBody = bodyOnly.lines().joinToString("\n") { ">$it" }
                    adapter.onBodyClickListener?.invoke(quotedBody)
                }
            }

            textView.text = spannableBuilder
            textView.movementMethod = LinkMovementMethod.getInstance()

            // 長押しコピー
            textView.setOnLongClickListener {
                val ctx = it.context
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val bodyOnly = extractPlainBody(item.htmlContent)
                cm.setPrimaryClip(ClipData.newPlainText("text", bodyOnly))
                true
            }
        }

        private fun insertZwspForPadding(html: String): String = adapter.insertZwspForPadding(html)

        private fun extractPlainBody(html: String): String {
            val plain = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString()

            // 例: 08/21/25(水)23:11:22 のような投稿メタ時刻
            val dateRegex = Regex("""\d{2}/\d{2}/\d{2}\([^)]+\)\d{2}:\d{2}:\d{2}""")

            // 画像/動画/ファイル拡張子（表示説明に混ざるのを検出する用）
            val fileExtRegex = Regex(
                """\.(?:jpg|jpeg|png|gif|webp|bmp|svg|webm|mp4|mov|mkv|avi|wmv|flv)\b""",
                RegexOption.IGNORE_CASE
            )

            // -(123B), -(1.2MB) などサイズ表記（全角/半角/各種ダッシュを許容）
            val sizeSuffixRegex = Regex(
                """[ \t]*[\\-ー−―–—]?\s*\(\s*\d+(?:\.\d+)?\s*(?:[kKmMgGtT]?[bB])\s*\)"""
            )

            // ✅ 行頭ラベル（「画像」「動画」「ファイル名」「添付」「サムネ」など＋任意の「ファイル名」語）
            //   例:
            //   - 画像: test.jpg -(123B)
            //   - 動画ファイル名：sample.mp4 ー(12.3MB)
            //   - ファイル名: pic.webp
            //   - サムネ: xxx.jpg
            val headLabelRegex = Regex(
                """^(?:画像|動画|ファイル名|ファイル|添付|サムネ|サムネイル)(?:\s*ファイル名)?\s*[:：]""",
                RegexOption.IGNORE_CASE
            )

            // ✅ 「画像ファイル名：ー(123B)」のように“ファイル名が空”でもサイズだけ付く行も除外
            //   （行頭ラベル + 何かしらのサイズ表記があれば弾く）
            fun isLabeledSizeOnlyLine(t: String): Boolean {
                return headLabelRegex.containsMatchIn(t) && sizeSuffixRegex.containsMatchIn(t)
            }

            return plain
                .lineSequence()
                .map { it.trimEnd() }
                .filterNot { line ->
                    val t = line.trim()

                    // 既存のメタ行を除外
                    t.startsWith("ID:") ||
                            t.startsWith("No.") ||
                            dateRegex.containsMatchIn(t) ||
                            t.contains("Name")
                }
                .filterNot { line ->
                    val t = line.trim()

                    // 1) 行頭が 画像/動画/ファイル名/添付/サムネ などのラベルなら除外
                    headLabelRegex.containsMatchIn(t) ||

                            // 2) 「xxx.jpg -(123B)」のような 拡張子+サイズ併記も除外
                            (fileExtRegex.containsMatchIn(t) && sizeSuffixRegex.containsMatchIn(t)) ||

                            // 3) 「画像ファイル名：ー(123B)」のように“名前が空”でもサイズだけの行も除外
                            isLabeledSizeOnlyLine(t) ||

                            // 4) サムネ表記が混入した説明行
                            (fileExtRegex.containsMatchIn(t) && t.contains("サムネ"))
                }
                .joinToString("\n")
                .trimEnd()
        }


    }

    class ImageViewHolder(
        view: View,
        private val onQuoteClickListener: ((quotedToken: String) -> Unit)?,
        private val fileNamePattern: Pattern
    ) : RecyclerView.ViewHolder(view) {

        // 正: detail_item_image.xml の ID
        private val imageView: ImageView = view.findViewById(R.id.detailImageView)
        private val promptView: TextView? = view.findViewById(R.id.promptTextView)

        fun bind(item: DetailContent.Image) {
            imageView.load(item.imageUrl) {
                crossfade(true)
                size(ViewSizeResolver(imageView))
            }

            // ★ 画像タップで MediaViewActivity 起動
            imageView.setOnClickListener {
                val ctx = it.context
                val i = Intent(ctx, MediaViewActivity::class.java).apply {
                    putExtra(MediaViewActivity.EXTRA_TYPE, MediaViewActivity.TYPE_IMAGE)
                    putExtra(MediaViewActivity.EXTRA_URL, item.imageUrl)
                    putExtra(MediaViewActivity.EXTRA_TEXT, item.prompt) // サブタイトル等に表示
                }
                ctx.startActivity(i)
            }

            promptView?.let { tv ->
                val prompt = item.prompt.orEmpty()
                if (prompt.isNotEmpty()) {
                    val sp = SpannableString(prompt)
                    val m = fileNamePattern.matcher(prompt)
                    while (m.find()) {
                        val file = m.group(1) ?: continue
                        val s = m.start()
                        val e = m.end()
                        if (s >= 0 && e <= sp.length) {
                            val span = object : ClickableSpan() {
                                override fun onClick(widget: View) { onQuoteClickListener?.invoke(file) }
                            }
                            sp.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    tv.text = sp
                    tv.movementMethod = LinkMovementMethod.getInstance()
                    tv.visibility = View.VISIBLE

                    // ★ プロンプト全体タップでテキストビューア起動
                    tv.setOnClickListener { v ->
                        val ctx = v.context
                        val i = Intent(ctx, MediaViewActivity::class.java).apply {
                            putExtra(MediaViewActivity.EXTRA_TYPE, MediaViewActivity.TYPE_TEXT)
                            putExtra(MediaViewActivity.EXTRA_TEXT, prompt)
                        }
                        ctx.startActivity(i)
                    }
                } else {
                    tv.text = ""
                    tv.visibility = View.GONE
                }
            }
        }
    }

    class VideoViewHolder(
        view: View,
        private val onQuoteClickListener: ((quotedToken: String) -> Unit)?,
        private val fileNamePattern: Pattern
    ) : RecyclerView.ViewHolder(view) {

        // Viewの参照（変更なし）
        private val playerView: androidx.media3.ui.PlayerView = view.findViewById(R.id.playerView)
        private val promptView: TextView? = view.findViewById(R.id.promptTextView)
        private val thumb: ImageView = view.findViewById(R.id.videoThumbView)

        // ★★★ 修正点 1: ハードウェアアクセラレーション対策 ★★★
        // initブロックを追加して、サムネイル用ImageViewの描画設定を行う
        init {
            thumb.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        fun bind(item: DetailContent.Video) {
            // ★★★ 修正点 2: レイアウトの競合対策 ★★★
            // PlayerViewを非表示にして、サムネイルが隠れないようにする
            playerView.visibility = View.GONE
            thumb.visibility = View.VISIBLE

            // Coilによるサムネイル読み込み（変更なし）
            thumb.load(item.videoUrl) {
                crossfade(true)
                placeholder(R.drawable.ic_play_circle)
                setParameter("video_frame_millis", 10000L) // 0ms位置のフレーム
                // (オプション) もし問題が続くならエラー時の画像も指定すると原因究明に役立ちます
                // error(R.drawable.ic_error)
            }

            // クリックリスナー（変更なし）
            val clickListener = View.OnClickListener {
                val ctx = it.context
                val i = Intent(ctx, MediaViewActivity::class.java).apply {
                    putExtra(MediaViewActivity.EXTRA_TYPE, MediaViewActivity.TYPE_VIDEO)
                    putExtra(MediaViewActivity.EXTRA_URL, item.videoUrl)
                    putExtra(MediaViewActivity.EXTRA_TEXT, item.prompt)
                }
                ctx.startActivity(i)
            }
            thumb.setOnClickListener(clickListener)

            // プロンプト表示処理（変更なし）
            promptView?.let { tv ->
                val prompt = item.prompt.orEmpty()
                if (prompt.isNotEmpty()) {
                    val sp = SpannableString(prompt)
                    val m = fileNamePattern.matcher(prompt)
                    while (m.find()) {
                        val file = m.group(1) ?: continue
                        val s = m.start()
                        val e = m.end()
                        if (s >= 0 && e <= sp.length) {
                            val span = object : ClickableSpan() {
                                override fun onClick(widget: View) { onQuoteClickListener?.invoke(file) }
                            }
                            sp.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    tv.text = sp
                    tv.movementMethod = LinkMovementMethod.getInstance()
                    tv.visibility = View.VISIBLE

                    tv.setOnClickListener { v ->
                        val ctx = v.context
                        val i = Intent(ctx, MediaViewActivity::class.java).apply {
                            putExtra(MediaViewActivity.EXTRA_TYPE, MediaViewActivity.TYPE_TEXT)
                            putExtra(MediaViewActivity.EXTRA_TEXT, prompt)
                        }
                        ctx.startActivity(i)
                    }
                } else {
                    tv.text = ""
                    tv.visibility = View.GONE
                }
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
            return oldItem.id == newItem.id && oldItem::class == newItem::class
        }
        override fun areContentsTheSame(oldItem: DetailContent, newItem: DetailContent): Boolean = oldItem == newItem
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