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
import android.text.style.ReplacementSpan
import android.text.style.URLSpan
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.CircularProgressDrawable
import coil.load
import coil.size.ViewSizeResolver
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class DetailAdapter : ListAdapter<DetailContent, RecyclerView.ViewHolder>(DetailDiffCallback()) {

    var onQuoteClickListener: ((quotedText: String) -> Unit)? = null
    var onSodaNeClickListener: ((resNum: String) -> Unit)? = null
    var onThreadEndTimeClickListener: (() -> Unit)? = null
    var onResNumClickListener: ((resNum: String, resBody: String) -> Unit)? = null
    private var currentSearchQuery: String? = null

    // ★ ViewHolderからアクセスできるよう、ファイル拡張子のリストを定義
    val mediaExt = setOf("jpg","jpeg","png","gif","webp","webm","mp4","mov","avi","flv","mkv")

    private val fileNamePattern = Pattern.compile("\\b([a-zA-Z0-9_.-]+\\.(?:jpg|jpeg|png|gif|mp4|webm|mov|avi|flv|mkv))\\b", Pattern.CASE_INSENSITIVE)
    private val resNumPatternOriginal = Pattern.compile("No\\.(\\d+)")
    private val sodaNePattern = Pattern.compile("(そうだね\\d*)|(No\\.\\d+\\s*([+＋]))")
    private val zwsp = "\u200B"

    fun setSearchQuery(query: String?) {
        val oldQuery = currentSearchQuery
        currentSearchQuery = query
        if (oldQuery != query) {
            notifyDataSetChanged()
        }
    }

    private fun View.dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private class PaddingAfterSpan(private val paddingPx: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            return paddingPx
        }

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
    }

    private fun buildDisplayOnlyPaddedText(tv: TextView, raw: CharSequence): CharSequence {
        if (raw !is Spannable) {
            val spannableString = SpannableString(raw)
            applyZwspPadding(tv, spannableString)
            return spannableString
        }

        val spannableToProcess: Spannable = if (raw is SpannableString || raw is SpannableStringBuilder) {
            raw as Spannable
        } else {
            SpannableString(raw)
        }

        applyZwspPadding(tv, spannableToProcess)
        return spannableToProcess
    }

    private fun applyZwspPadding(tv: TextView, s: Spannable) {
        val medium = tv.dpToPx(4) // 余白を4dpに調整
        var index = 0
        while (index < s.length) {
            index = s.toString().indexOf(zwsp, index)
            if (index == -1) break
            s.setSpan(PaddingAfterSpan(medium), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            index += zwsp.length
        }
    }


    companion object {
        const val VIEW_TYPE_TEXT = 1
        const val VIEW_TYPE_IMAGE = 2
        const val VIEW_TYPE_VIDEO = 3
        const val VIEW_TYPE_THREAD_END_TIME = 4

        const val EXTRA_TYPE = "EXTRA_TYPE"
        const val EXTRA_URL = "EXTRA_URL"
        const val EXTRA_TEXT = "EXTRA_TEXT"
        const val TYPE_IMAGE = "image"
        const val TYPE_VIDEO = "video"
        const val TYPE_TEXT = "text"
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is VideoViewHolder) {
            holder.releasePlayer()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is DetailContent.Text -> VIEW_TYPE_TEXT
            is DetailContent.Image -> VIEW_TYPE_IMAGE
            is DetailContent.Video -> VIEW_TYPE_VIDEO
            is DetailContent.ThreadEndTime -> VIEW_TYPE_THREAD_END_TIME
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_TEXT -> TextViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_text, parent, false),
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
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_image, parent, false),
                onQuoteClickListener,
                fileNamePattern
            )
            VIEW_TYPE_VIDEO -> VideoViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_video, parent, false),
                onQuoteClickListener,
                fileNamePattern
            )
            VIEW_TYPE_THREAD_END_TIME -> ThreadEndTimeViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.detail_item_thread_end_time, parent, false),
                onThreadEndTimeClickListener
            )
            else -> throw IllegalArgumentException("Invalid view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is DetailContent.Text -> (holder as TextViewHolder).bind(item, currentSearchQuery)
            is DetailContent.Image -> (holder as ImageViewHolder).bind(item, currentSearchQuery)
            is DetailContent.Video -> (holder as VideoViewHolder).bind(item, currentSearchQuery)
            is DetailContent.ThreadEndTime -> (holder as ThreadEndTimeViewHolder).bind(item)
        }
    }

    class TextViewHolder(
        view: View,
        private val adapter: DetailAdapter,
        private val onQuoteClickListener: ((quotedText: String) -> Unit)?,
        private val onSodaNeClickListener: ((resNum: String) -> Unit)?,
        private val fileNamePattern: Pattern,
        private val resNumPatternForClickableSpan: Pattern,
        private val sodaNePatternForClick: Pattern,
        private val onResNumClickListener: ((resNum: String, resBody: String) -> Unit)?,
        private val zwsp: String
    ) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.detailTextView)

        private val patternsForZwspInsertion = listOf(
            Regex("(\\d+)</strong>") to "$1$zwsp",
            Regex("(無念)") to "$1$zwsp",
            Regex("(Name)") to "$1$zwsp",
            Regex("(としあき)") to "$1$zwsp",
            Regex("(\\d{2}/\\d{2}/\\d{2}\\([^)]+\\)\\d{2}:\\d{2}:\\d{2})") to "$1$zwsp",
            Regex("(ID:[\\w./+]+)") to "$1$zwsp",
            Regex("(No\\.\\d+)") to "$1$zwsp"
        )

        private fun insertZwspForPadding(htmlContent: String): String {
            var processedHtml = htmlContent
            patternsForZwspInsertion.forEach { (pattern, replacement) ->
                processedHtml = pattern.replace(processedHtml, replacement)
            }
            processedHtml = processedHtml.replaceFirst(Regex("^(\\d+)(?=\\s)"), "$1$zwsp")
            return processedHtml
        }


        fun bind(item: DetailContent.Text, searchQuery: String?) {
            val htmlWithZwsp = insertZwspForPadding(item.htmlContent)
            val textFromHtmlWithZwsp = Html.fromHtml(htmlWithZwsp, Html.FROM_HTML_MODE_COMPACT)
            val textWithVisualPadding = adapter.buildDisplayOnlyPaddedText(textView, textFromHtmlWithZwsp)
            val spannableBuilder = SpannableStringBuilder(textWithVisualPadding)
            val contentString = spannableBuilder.toString()

            var mainResNum: String? = null

            val resNumMatcher = resNumPatternForClickableSpan.matcher(contentString)
            if (resNumMatcher.find()) {
                mainResNum = resNumMatcher.group(1)
                val start = resNumMatcher.start()
                val end = resNumMatcher.end()

                val resNumClickableSpan = object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val quotedResNum = ">No.${mainResNum}"
                        onResNumClickListener?.invoke(mainResNum!!, quotedResNum)
                    }
                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = true
                    }
                }
                if (start < end && end <= spannableBuilder.length) {
                    spannableBuilder.setSpan(resNumClickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }

            val quotePattern = Pattern.compile("^>(.+)$", Pattern.MULTILINE)
            val quoteMatcher = quotePattern.matcher(contentString)
            while (quoteMatcher.find()) {
                val quoteText = quoteMatcher.group(1)?.trim()
                if (quoteText != null) {
                    val start = quoteMatcher.start()
                    val end = quoteMatcher.end()
                    val clickableSpan = object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            onQuoteClickListener?.invoke(quoteText)
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = false
                        }
                    }
                    if (start >= 0 && end <= spannableBuilder.length) {
                        spannableBuilder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            val fileMatcher = fileNamePattern.matcher(contentString)
            while (fileMatcher.find()) {
                val fileName = fileMatcher.group(1)
                if (fileName != null) {
                    val start = fileMatcher.start()
                    val end = fileMatcher.end()
                    val clickableSpan = object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            onQuoteClickListener?.invoke(fileName)
                        }
                        override fun updateDrawState(ds: TextPaint) {
                            super.updateDrawState(ds)
                            ds.isUnderlineText = true
                        }
                    }
                    if (start >= 0 && end <= spannableBuilder.length) {
                        spannableBuilder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            }

            if (mainResNum != null) {
                val sodaNeMatcher = sodaNePatternForClick.matcher(contentString)
                while (sodaNeMatcher.find()) {
                    var spanStart = -1
                    var spanEnd = -1
                    val matchedSodaNeWithDigits = sodaNeMatcher.group(1)
                    val matchedNoDotPatternWithPlus = sodaNeMatcher.group(2)

                    if (matchedSodaNeWithDigits != null) {
                        spanStart = sodaNeMatcher.start(1)
                        spanEnd = sodaNeMatcher.end(1)
                    } else if (matchedNoDotPatternWithPlus != null) {
                        val tempResNumPattern = Pattern.compile("No\\.(\\d+)")
                        val tempMatcher = tempResNumPattern.matcher(matchedNoDotPatternWithPlus)
                        if (tempMatcher.find()) {
                            val numInPlusContext = tempMatcher.group(1)
                            if (numInPlusContext == mainResNum) {
                                val actualPlusSign = sodaNeMatcher.group(3)
                                if (actualPlusSign != null) {
                                    spanStart = sodaNeMatcher.start(3)
                                    spanEnd = sodaNeMatcher.end(3)
                                } else {
                                    continue
                                }
                            } else {
                                continue
                            }
                        } else {
                            continue
                        }
                    }

                    if (spanStart != -1 && spanEnd != -1 && spanEnd <= spannableBuilder.length) {
                        spannableBuilder.setSpan(
                            SodaNeClickableSpan(mainResNum, onSodaNeClickListener),
                            spanStart,
                            spanEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }

            if (!searchQuery.isNullOrEmpty()) {
                val textLc = contentString.lowercase()
                val queryLc = searchQuery.lowercase()
                var startIndex = textLc.indexOf(queryLc)
                while (startIndex >= 0) {
                    val endIndex = startIndex + queryLc.length
                    if (startIndex < endIndex && endIndex <= spannableBuilder.length) {
                        spannableBuilder.setSpan(
                            BackgroundColorSpan(Color.YELLOW),
                            startIndex,
                            endIndex,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    } else {
                        break
                    }
                    startIndex = textLc.indexOf(queryLc, endIndex)
                    if (startIndex == -1) break
                }
            }

            textView.text = spannableBuilder
            textView.movementMethod = object : LinkMovementMethod() {
                override fun handleMovementKey(widget: TextView?, buffer: Spannable?, keyCode: Int, movementMetaState: Int, event: KeyEvent?): Boolean {
                    return false
                }

                override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
                    val action = event.action

                    if (action == MotionEvent.ACTION_UP) {
                        var x = event.x.toInt()
                        var y = event.y.toInt()

                        x -= widget.totalPaddingLeft
                        y -= widget.totalPaddingTop
                        x += widget.scrollX
                        y += widget.scrollY

                        val layout = widget.layout
                        val line = layout.getLineForVertical(y)
                        if (line < 0 || line >= layout.lineCount) {
                            return super.onTouchEvent(widget, buffer, event)
                        }
                        val off = layout.getOffsetForHorizontal(line, x.toFloat())
                        if (off < 0 || off > buffer.length) {
                            return super.onTouchEvent(widget, buffer, event)
                        }

                        val spans = buffer.getSpans(off, off, ClickableSpan::class.java)

                        if (spans.isNotEmpty()) {
                            val sodaNeSpan = spans.firstOrNull { it is SodaNeClickableSpan }
                            if (sodaNeSpan != null) {
                                sodaNeSpan.onClick(widget)
                                return true
                            }

                            // ★★★ ここからが修正箇所 ★★★
                            val urlSpan = spans.firstOrNull { it is URLSpan }
                            if (urlSpan != null) {
                                val urlValue = (urlSpan as URLSpan).url
                                if (urlValue != null) {
                                    // リンク先がメディアファイルかどうかを判定
                                    val isMediaFile = urlValue.substringAfterLast('.', "").lowercase() in adapter.mediaExt
                                    if (isMediaFile) {
                                        // メディアファイルなら、ファイル名で返信画面を呼ぶ
                                        val fileName = urlValue.substringAfterLast('/')
                                        adapter.onQuoteClickListener?.invoke(fileName)
                                        return true // 処理完了
                                    } else if (!urlValue.startsWith("javascript:")) {
                                        // 通常のURLならブラウザで開く
                                        urlSpan.onClick(widget)
                                        return true // 処理完了
                                    }
                                }
                            }
                            // ★★★ ここまで ★★★

                            val otherClickableSpans = spans
                                .filter { it !is SodaNeClickableSpan && it !is URLSpan }

                            val target = otherClickableSpans.minByOrNull { buffer.getSpanEnd(it) - buffer.getSpanStart(it) }

                            if (target != null) {
                                target.onClick(widget)
                                return true
                            }
                        }
                    }
                    return super.onTouchEvent(widget, buffer, event)
                }
            }
            textView.setOnLongClickListener {
                showCopyDialog(it.context, spannableBuilder.toString())
                true
            }
        }

        private fun showCopyDialog(context: Context, textToCopy: String) {
            AlertDialog.Builder(context)
                .setItems(arrayOf("テキストをコピー")) { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val modifiedText = textToCopy.lines().joinToString("\n") { "> $it" }
                    val clip = ClipData.newPlainText("Copied Text", modifiedText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "テキストをコピーしました", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    class ImageViewHolder(
        view: View,
        private val onQuoteClickListener: ((quotedText: String) -> Unit)?,
        private val fileNamePattern: Pattern
    ) : RecyclerView.ViewHolder(view) {
        private val imageView: ImageView = view.findViewById(R.id.detailImageView)
        private val promptTextView: TextView = view.findViewById(R.id.promptTextView)

        private fun Int.dpToPx(): Int {
            val scale = itemView.resources.displayMetrics.density
            return (this * scale + 0.5f).toInt()
        }

        fun bind(item: DetailContent.Image, searchQuery: String?) {
            val progressDrawable = CircularProgressDrawable(itemView.context).apply {
                strokeWidth = 5f
                centerRadius = 30f
                setColorSchemeColors(Color.DKGRAY)
                start()
            }

            imageView.load(item.imageUrl) {
                crossfade(true)
                placeholder(progressDrawable)
                error(android.R.drawable.ic_dialog_alert)
                listener(
                    onStart = { _ ->
                        imageView.minimumHeight = 10.dpToPx()
                    },
                    onSuccess = { _, _ ->
                        imageView.minimumHeight = 0
                    },
                    onError = { _, _ ->
                        imageView.minimumHeight = 0
                    }
                )
                size(ViewSizeResolver(imageView))
            }

            imageView.setOnClickListener {
                val context = itemView.context
                val intent = Intent(context, MediaViewActivity::class.java)
                intent.putExtra(DetailAdapter.EXTRA_TYPE, DetailAdapter.TYPE_IMAGE)
                intent.putExtra(DetailAdapter.EXTRA_URL, item.imageUrl)
                item.prompt?.let { intent.putExtra(DetailAdapter.EXTRA_TEXT, it) }
                context.startActivity(intent)
            }

            if (!item.prompt.isNullOrBlank()) {
                promptTextView.isVisible = true
                val promptText = item.prompt
                val spannableBuilder = SpannableStringBuilder(promptText)

                val fileMatcher = fileNamePattern.matcher(promptText)
                while (fileMatcher.find()) {
                    val fileName = fileMatcher.group(1)
                    if (fileName != null) {
                        val start = fileMatcher.start()
                        val end = fileMatcher.end()
                        val clickableSpan = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                onQuoteClickListener?.invoke(fileName)
                            }
                            override fun updateDrawState(ds: TextPaint) {
                                super.updateDrawState(ds)
                                ds.isUnderlineText = true
                            }
                        }
                        if (start >= 0 && end <= spannableBuilder.length) {
                            spannableBuilder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }

                if (!searchQuery.isNullOrEmpty()) {
                    val textLc = promptText.lowercase()
                    val queryLc = searchQuery.lowercase()
                    var startPos = textLc.indexOf(queryLc)
                    while (startPos >= 0) {
                        val endPos = startPos + queryLc.length
                        if (startPos >= 0 && endPos <= spannableBuilder.length) {
                            spannableBuilder.setSpan(BackgroundColorSpan(Color.YELLOW), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        startPos = textLc.indexOf(queryLc, endPos)
                    }
                }
                promptTextView.text = spannableBuilder
                promptTextView.movementMethod = object : LinkMovementMethod() {
                    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
                        if (event.action == MotionEvent.ACTION_UP) {
                            var x = event.x.toInt()
                            var y = event.y.toInt()
                            x -= widget.totalPaddingLeft
                            y -= widget.totalPaddingTop
                            x += widget.scrollX
                            y += widget.scrollY
                            val layout = widget.layout
                            val line = layout.getLineForVertical(y)
                            val off = layout.getOffsetForHorizontal(line, x.toFloat())
                            val spans = buffer.getSpans(off, off, ClickableSpan::class.java)

                            if (spans.isNotEmpty()) {
                                val target = spans.minByOrNull { buffer.getSpanEnd(it) - buffer.getSpanStart(it) }
                                target?.onClick(widget)
                                return true
                            }

                            val context = widget.context
                            val intent = Intent(context, MediaViewActivity::class.java)
                            intent.putExtra(DetailAdapter.EXTRA_TYPE, DetailAdapter.TYPE_TEXT)
                            intent.putExtra(DetailAdapter.EXTRA_TEXT, widget.text.toString())
                            context.startActivity(intent)
                            return true
                        }
                        return super.onTouchEvent(widget, buffer, event)
                    }
                }
            } else {
                promptTextView.isVisible = false
                promptTextView.text = null
            }

            imageView.setOnLongClickListener {
                showSaveDialog(item)
                true
            }
            item.prompt?.takeIf { it.isNotBlank() }?.let { textToCopy ->
                promptTextView.setOnLongClickListener {
                    showCopyDialog(itemView.context, textToCopy)
                    true
                }
            }
        }

        private fun showSaveDialog(item: DetailContent.Image) {
            val context = itemView.context
            AlertDialog.Builder(context)
                .setTitle("画像の保存")
                .setMessage("この画像を保存しますか？")
                .setPositiveButton("保存") { _, _ ->
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                        MediaSaver.saveImage(context, item.imageUrl)
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        private fun showCopyDialog(context: Context, textToCopy: String) {
            AlertDialog.Builder(context)
                .setItems(arrayOf("テキストをコピー")) { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Text", textToCopy)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "テキストをコピーしました", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    class VideoViewHolder(
        view: View,
        private val onQuoteClickListener: ((quotedText: String) -> Unit)?,
        private val fileNamePattern: Pattern
    ) : RecyclerView.ViewHolder(view) {
        private val playerView: PlayerView = view.findViewById(R.id.playerView)
        private val promptTextView: TextView = view.findViewById(R.id.promptTextView)
        private var exoPlayer: ExoPlayer? = null

        fun bind(item: DetailContent.Video, searchQuery: String?) {
            releasePlayer()
            val context = itemView.context
            exoPlayer = ExoPlayer.Builder(context).build().also { player ->
                playerView.player = player
                val mediaItem = MediaItem.fromUri(item.videoUrl)
                player.setMediaItem(mediaItem)
                player.playWhenReady = false
                player.prepare()
            }

            playerView.setOnClickListener {
                val context = itemView.context
                val intent = Intent(context, MediaViewActivity::class.java)
                intent.putExtra(DetailAdapter.EXTRA_TYPE, DetailAdapter.TYPE_VIDEO)
                intent.putExtra(DetailAdapter.EXTRA_URL, item.videoUrl)
                item.prompt?.let { intent.putExtra(DetailAdapter.EXTRA_TEXT, it) }
                context.startActivity(intent)
            }

            if (!item.prompt.isNullOrBlank()) {
                promptTextView.isVisible = true
                val promptText = item.prompt
                val spannableBuilder = SpannableStringBuilder(promptText)

                val fileMatcher = fileNamePattern.matcher(promptText)
                while (fileMatcher.find()) {
                    val fileName = fileMatcher.group(1)
                    if (fileName != null) {
                        val start = fileMatcher.start()
                        val end = fileMatcher.end()
                        val clickableSpan = object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                onQuoteClickListener?.invoke(fileName)
                            }
                            override fun updateDrawState(ds: TextPaint) {
                                super.updateDrawState(ds)
                                ds.isUnderlineText = true
                            }
                        }
                        if (start >= 0 && end <= spannableBuilder.length) {
                            spannableBuilder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }

                if (!searchQuery.isNullOrEmpty()) {
                    val textLc = promptText.lowercase()
                    val queryLc = searchQuery.lowercase()
                    var startPos = textLc.indexOf(queryLc)
                    while (startPos >= 0) {
                        val endPos = startPos + queryLc.length
                        if (startPos >= 0 && endPos <= spannableBuilder.length) {
                            spannableBuilder.setSpan(BackgroundColorSpan(Color.YELLOW), startPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        startPos = textLc.indexOf(queryLc, endPos)
                    }
                }
                promptTextView.text = spannableBuilder
                promptTextView.movementMethod = object : LinkMovementMethod() {
                    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
                        if (event.action == MotionEvent.ACTION_UP) {
                            var x = event.x.toInt()
                            var y = event.y.toInt()
                            x -= widget.totalPaddingLeft
                            y -= widget.totalPaddingTop
                            x += widget.scrollX
                            y += widget.scrollY
                            val layout = widget.layout
                            val line = layout.getLineForVertical(y)
                            val off = layout.getOffsetForHorizontal(line, x.toFloat())
                            val spans = buffer.getSpans(off, off, ClickableSpan::class.java)

                            if (spans.isNotEmpty()) {
                                val target = spans.minByOrNull { buffer.getSpanEnd(it) - buffer.getSpanStart(it) }
                                target?.onClick(widget)
                                return true
                            }

                            val context = widget.context
                            val intent = Intent(context, MediaViewActivity::class.java)
                            intent.putExtra(DetailAdapter.EXTRA_TYPE, DetailAdapter.TYPE_TEXT)
                            intent.putExtra(DetailAdapter.EXTRA_TEXT, widget.text.toString())
                            context.startActivity(intent)
                            return true
                        }
                        return super.onTouchEvent(widget, buffer, event)
                    }
                }
            } else {
                promptTextView.isVisible = false
                promptTextView.text = null
            }

            playerView.setOnLongClickListener {
                showSaveDialog(item)
                true
            }
            item.prompt?.takeIf { it.isNotBlank() }?.let { textToCopy ->
                promptTextView.setOnLongClickListener {
                    showCopyDialog(itemView.context, textToCopy)
                    true
                }
            }
        }

        private fun showSaveDialog(item: DetailContent.Video) {
            val context = itemView.context
            AlertDialog.Builder(context)
                .setTitle("動画の保存")
                .setMessage("この動画を保存しますか？")
                .setPositiveButton("保存") { _, _ ->
                    itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                        MediaSaver.saveVideo(context, item.videoUrl)
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        private fun showCopyDialog(context: Context, textToCopy: String) {
            AlertDialog.Builder(context)
                .setItems(arrayOf("テキストをコピー")) { _, _ ->
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied Text", textToCopy)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "テキストをコピーしました", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        fun releasePlayer() {
            exoPlayer?.let { player ->
                player.release()
                exoPlayer = null
                playerView.player = null
            }
        }
    }

    class ThreadEndTimeViewHolder(
        view: View,
        private val onThreadEndTimeClickListener: (() -> Unit)?
    ) : RecyclerView.ViewHolder(view) {
        private val endTimeTextView: TextView = view.findViewById(R.id.endTimeTextView)

        fun bind(item: DetailContent.ThreadEndTime) {
            endTimeTextView.text = item.endTime
            endTimeTextView.setOnClickListener {
                onThreadEndTimeClickListener?.invoke()
            }
            endTimeTextView.setOnLongClickListener {
                val context = itemView.context
                AlertDialog.Builder(context)
                    .setItems(arrayOf("テキストをコピー")) { _, _ ->
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Copied End Time", item.endTime)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "終了時刻をコピーしました", Toast.LENGTH_SHORT).show()
                    }
                    .show()
                true
            }
        }
    }

    class SodaNeClickableSpan(
        private val resNum: String,
        private val listener: ((String) -> Unit)?
    ) : ClickableSpan() {
        override fun onClick(widget: View) {
            listener?.invoke(resNum)
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.isUnderlineText = true
            ds.color = Color.MAGENTA
        }
    }

    class DetailDiffCallback : DiffUtil.ItemCallback<DetailContent>() {
        override fun areItemsTheSame(oldItem: DetailContent, newItem: DetailContent): Boolean {
            return when {
                oldItem is DetailContent.Image && newItem is DetailContent.Image -> oldItem.imageUrl == newItem.imageUrl
                oldItem is DetailContent.Video && newItem is DetailContent.Video -> oldItem.videoUrl == newItem.videoUrl
                oldItem is DetailContent.Text && newItem is DetailContent.Text -> oldItem.id == newItem.id
                oldItem is DetailContent.ThreadEndTime && newItem is DetailContent.ThreadEndTime -> oldItem.id == newItem.id
                else -> oldItem.javaClass == newItem.javaClass && oldItem.id == newItem.id
            }
        }

        override fun areContentsTheSame(oldItem: DetailContent, newItem: DetailContent): Boolean {
            return oldItem == newItem
        }
    }
}