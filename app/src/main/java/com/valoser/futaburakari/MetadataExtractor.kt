package com.valoser.futaburakari

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.exifinterface.media.ExifInterface
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
 
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.InflaterInputStream
import java.util.regex.Pattern
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Optional MP4/WebM parsers
import java.nio.channels.WritableByteChannel
// Media3 extractor imports (Matroska)
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi

object MetadataExtractor {
    private const val TAG = "MetadataExtractor"

    // ====== 同時接続数制限設定 ======
    private const val MAX_CONCURRENT_CONNECTIONS = 1 // 同時接続数を2に制限
    private val connectionSemaphore = Semaphore(MAX_CONCURRENT_CONNECTIONS)
    private val activeConnectionCount = AtomicInteger(0)

    // ====== 既存の設定値 ======
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    private const val FIRST_EXIF_BYTES = 64 * 1024
    private const val PNG_WINDOW_BYTES = 64 * 1024
    private const val MP4_HEAD_BYTES  = 96 * 1024
    private const val MP4_TAIL_BYTES  = 128 * 1024
    private const val GLOBAL_MAX_BYTES = 256 * 1024

    private val PROMPT_KEYS = setOf("parameters", "Description", "Comment", "prompt")
    private val GSON = Gson()

    // ====== Public API ======
    suspend fun extract(context: Context, uriOrUrl: String, networkClient: NetworkClient): String? = withContext(Dispatchers.IO) {
        try {
            if (uriOrUrl.startsWith("content://") || uriOrUrl.startsWith("file://")) {
                context.contentResolver.openInputStream(Uri.parse(uriOrUrl))?.use { input ->
                    // ローカルは全体（または上限）を読んでタイプ別に抽出
                    val all = input.readBytes(limit = GLOBAL_MAX_BYTES)
                    return@withContext extractByType(all, uriOrUrl)
                }
                return@withContext null
            }

            val ext = uriOrUrl.substringAfterLast('.', "").lowercase()
            return@withContext when (ext) {
                "jpg", "jpeg", "webp" -> {
                    val head = httpGetRangeWithLimit(uriOrUrl, 0, FIRST_EXIF_BYTES.toLong(), networkClient)
                    if (head != null) {
                        // EXIF優先 → JPEGのAPP1(XMP)/APP13(IPTC) → テキスト走査
                        extractFromExif(head)
                            ?: extractFromJpegAppSegments(head)
                            ?: extractBySniff(head, uriOrUrl)
                    } else null
                }
                "png" -> {
                    extractPngPromptStreamingWithLimit(uriOrUrl, networkClient)
                }
                "mp4", "mov", "m4v" -> extractMp4PromptStreamingWithLimit(uriOrUrl, networkClient)
                "webm" -> extractWebmPromptStreamingWithLimit(uriOrUrl, networkClient)
                else -> {
                    val head = httpGetRangeWithLimit(uriOrUrl, 0, FIRST_EXIF_BYTES.toLong(), networkClient) ?: return@withContext null
                    extractBySniff(head, uriOrUrl)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ====== 同時接続数制限付きHTTPメソッド ======

    /**
     * 同時接続数を制限してRange GETを実行
     */
    private suspend fun httpGetRangeWithLimit(urlStr: String, start: Long, length: Long, networkClient: NetworkClient): ByteArray? {
        return connectionSemaphore.withPermit {
            val connectionCount = activeConnectionCount.incrementAndGet()
            try {
                Log.d(TAG, "activeConnections=$connectionCount")
                httpGetRange(urlStr, start, length, networkClient)
            } finally {
                activeConnectionCount.decrementAndGet()
            }
        }
    }

    /**
     * 同時接続数を制限してHEADリクエストを実行
     */
    private suspend fun httpHeadWithLimit(urlStr: String, networkClient: NetworkClient): HeadInfo? {
        return connectionSemaphore.withPermit {
            val connectionCount = activeConnectionCount.incrementAndGet()
            try {
                Log.d(TAG, "activeConnections=$connectionCount")
                httpHead(urlStr, networkClient)
            } finally {
                activeConnectionCount.decrementAndGet()
            }
        }
    }

    private suspend fun httpHeadContentLengthWithLimit(urlStr: String, networkClient: NetworkClient): Long? {
        val info = httpHeadWithLimit(urlStr, networkClient)
        return info?.contentLength
    }

    // ====== PNG: 同時接続数制限付きストリーミング処理 ======
    private suspend fun extractPngPromptStreamingWithLimit(fileUrl: String, networkClient: NetworkClient): String? {
        var windowSize = PNG_WINDOW_BYTES
        var totalFetched = 0
        val buf = ByteArrayOutputStream()

        val first = httpGetRangeWithLimit(fileUrl, 0, windowSize.toLong(), networkClient) ?: return null
        buf.write(first)
        totalFetched += first.size

        var bytes = buf.toByteArray()
        if (!isPng(bytes)) return null
        extractFromPngChunks(bytes)?.let { return it }

        while (totalFetched < GLOBAL_MAX_BYTES) {
            val offset = bytes.size.toLong()
            windowSize = min(PNG_WINDOW_BYTES, GLOBAL_MAX_BYTES - totalFetched)
            if (windowSize <= 0) break

            val more = httpGetRangeWithLimit(fileUrl, offset, windowSize.toLong(), networkClient) ?: break
            buf.write(more)
            totalFetched += more.size
            bytes = buf.toByteArray()
            extractFromPngChunks(bytes)?.let { return it }

            if (bytes.indexOfChunkType("IEND")) break
        }
        return null
    }

    // ====== MP4: 同時接続数制限付きストリーミング処理 ======
    private suspend fun extractMp4PromptStreamingWithLimit(fileUrl: String, networkClient: NetworkClient): String? {
        // 最初にHEADとheadを並行取得ではなく、順次実行に変更
        val size = httpHeadContentLengthWithLimit(fileUrl, networkClient)
        val head = httpGetRangeWithLimit(fileUrl, 0, MP4_HEAD_BYTES.toLong(), networkClient)

        val tail = if (size != null && size > MP4_TAIL_BYTES) {
            httpGetRangeWithLimit(fileUrl, size - MP4_TAIL_BYTES, MP4_TAIL_BYTES.toLong(), networkClient)
        } else {
            httpGetRangeWithLimit(fileUrl, 0, min(GLOBAL_MAX_BYTES, MP4_TAIL_BYTES).toLong(), networkClient)
        }

        var merged = concatNonNull(head, tail)
        if (merged != null) {
            extractFromMp4WithLib(merged)?.let { return it }
            extractFromMp4Container(merged)?.let { return it }
        }

        // 必要なら段階的に拡張（一度に一つの接続のみ）
        var extra = 512 * 1024
        var total = (merged?.size ?: 0)
        while (total < GLOBAL_MAX_BYTES) {
            val more = if (size != null) {
                val start = max(0L, size - MP4_TAIL_BYTES - extra)
                val len = min(extra, GLOBAL_MAX_BYTES - total)
                httpGetRangeWithLimit(fileUrl, start, len.toLong(), networkClient)
            } else {
                httpGetRangeWithLimit(fileUrl, 0, min(GLOBAL_MAX_BYTES - total, extra).toLong(), networkClient)
            } ?: break

            merged = if (merged == null) more else merged + more
            extractFromMp4WithLib(merged)?.let { return it }
            extractFromMp4Container(merged)?.let { return it }
            total = merged.size
            extra *= 2
        }

        return null
    }

    // ====== 接続管理用のユーティリティ関数 ======

    /**
     * 現在のアクティブ接続数を取得
     */
    fun getActiveConnectionCount(): Int = activeConnectionCount.get()

    /**
     * 最大同時接続数を取得
     */
    fun getMaxConcurrentConnections(): Int = MAX_CONCURRENT_CONNECTIONS

    // ====== 既存のHTTPヘルパー関数（変更なし） ======

    private data class HeadInfo(val contentLength: Long?, val acceptRanges: Boolean)

    private suspend fun httpHead(urlStr: String, networkClient: NetworkClient): HeadInfo? {
        return try {
            val len = networkClient.headContentLength(urlStr)
            HeadInfo(len, true)
        } catch (_: Exception) { null }
    }

    private suspend fun httpGetRange(urlStr: String, start: Long, length: Long, networkClient: NetworkClient): ByteArray? {
        return try { networkClient.fetchRange(urlStr, start, length) } catch (_: Exception) { null }
    }

    // ====== 既存の処理ロジック（変更なし） ======

    private fun extractByType(fileBytes: ByteArray, uriOrUrl: String): String? {
        return when {
            uriOrUrl.endsWith(".mp4", true) || uriOrUrl.endsWith(".mov", true) || uriOrUrl.endsWith(".m4v", true) -> {
                extractFromMp4WithLib(fileBytes)
                    ?: extractFromMp4Container(fileBytes)
            }
            uriOrUrl.endsWith(".webm", true) -> {
                extractFromWebmWithLib(fileBytes)
                    ?: extractFromWebmContainer(fileBytes)
            }
            isPng(fileBytes) -> extractFromPngChunks(fileBytes)
            else -> extractFromExif(fileBytes)
        }
    }

    private fun extractBySniff(bytes: ByteArray, @Suppress("UNUSED_PARAMETER") uriOrUrl: String): String? {
        if (isPng(bytes)) {
            return extractFromPngChunks(bytes)
        }
        extractFromExif(bytes)?.let { return it }
        extractFromJpegAppSegments(bytes)?.let { return it }
        return scanTextForPrompts(String(bytes, StandardCharsets.UTF_8))
    }

    private fun extractFromMp4Bytes(bytes: ByteArray): String? {
        val latin = String(bytes, StandardCharsets.ISO_8859_1)
        scanTextForPrompts(latin)?.let { return it }

        val utf8 = String(bytes, StandardCharsets.UTF_8)
        scanTextForPrompts(utf8)?.let { return it }

        val nearMeta = regexSearchWindow(latin, "(moov|udta|meta|ilst)".toRegex(RegexOption.IGNORE_CASE), 4096)
        if (nearMeta != null) {
            scanTextForPrompts(nearMeta)?.let { return it }
        }
        return null
    }

    private fun extractFromMp4WithLib(bytes: ByteArray): String? {
        return try {
            val isoFileCls = Class.forName("org.mp4parser.IsoFile")
            val bbChanCls = Class.forName("org.mp4parser.tools.ByteBufferByteChannel")
            val readableChanCls = Class.forName("java.nio.channels.ReadableByteChannel")

            val channel = bbChanCls.getConstructor(ByteBuffer::class.java).newInstance(ByteBuffer.wrap(bytes))
            val iso = isoFileCls.getConstructor(readableChanCls).newInstance(channel)

            fun getBoxes(container: Any): List<Any> {
                val m = container.javaClass.methods.firstOrNull { it.name == "getBoxes" && it.parameterCount == 0 }
                @Suppress("UNCHECKED_CAST")
                return (m?.invoke(container) as? List<Any>) ?: emptyList()
            }
            fun getType(box: Any): String {
                val m = box.javaClass.methods.firstOrNull { it.name == "getType" }
                return (m?.invoke(box) as? String) ?: ""
            }
            fun findChild(container: Any, type: String): Any? = getBoxes(container).firstOrNull { getType(it) == type }
            fun findChildren(container: Any, type: String): List<Any> = getBoxes(container).filter { getType(it) == type }
            fun boxToBytes(box: Any): ByteArray {
                val out = ByteArrayOutputStream()
                val ch = object : WritableByteChannel {
                    private var open = true
                    override fun isOpen(): Boolean = open
                    override fun close() { open = false }
                    override fun write(src: java.nio.ByteBuffer): Int {
                        val remaining = src.remaining()
                        val arr = ByteArray(remaining)
                        src.get(arr)
                        out.write(arr)
                        return remaining
                    }
                }
                val m = box.javaClass.methods.firstOrNull { it.name == "getBox" && it.parameterCount == 1 }
                m?.invoke(box, ch)
                ch.close()
                return out.toByteArray()
            }

            fun decodeIlstItem(item: Any): String? {
                val datas = findChildren(item, "data")
                for (d in datas) {
                    val raw = boxToBytes(d)
                    if (raw.size <= 16) continue
                    val payload = raw.copyOfRange(16, raw.size)
                    val s = try { String(payload, StandardCharsets.UTF_8) } catch (_: Exception) {
                        try { String(payload, StandardCharsets.UTF_16) } catch (_: Exception) { String(payload, StandardCharsets.ISO_8859_1) }
                    }
                    scanTextForPrompts(s)?.let { return it }
                    if (s.isNotBlank() && !isLabely(s)) return s.trim()
                }
                return null
            }

            val roots = getBoxes(iso)
            val moov = roots.firstOrNull { getType(it) == "moov" } ?: return null
            val udta = findChild(moov, "udta")
            if (udta != null) {
                // XMP_
                val xmp = findChild(udta, "XMP_")
                if (xmp != null) {
                    val raw = boxToBytes(xmp)
                    if (raw.size > 8) {
                        val content = raw.copyOfRange(8, raw.size)
                        val xmpStr = runCatching { String(content, StandardCharsets.UTF_8) }.getOrNull()
                        if (!xmpStr.isNullOrEmpty()) scanXmpForPrompts(xmpStr)?.let { return it }
                    }
                }
                // meta → ilst
                val meta = findChild(udta, "meta")
                val ilst = if (meta != null) findChild(meta, "ilst") else null
                if (ilst != null) {
                    val keyCandidates = setOf("\u00A9des", "desc", "\u00A9cmt")
                    for (item in getBoxes(ilst)) {
                        if (getType(item) in keyCandidates) {
                            decodeIlstItem(item)?.let { return it }
                        }
                    }
                }
            }

            // moov直下のmetaにも対応
            val metaMoov = findChild(moov, "meta")
            val ilstMoov = if (metaMoov != null) findChild(metaMoov, "ilst") else null
            if (ilstMoov != null) {
                val keyCandidates = setOf("\u00A9des", "desc", "\u00A9cmt")
                for (item in getBoxes(ilstMoov)) {
                    if (getType(item) in keyCandidates) {
                        decodeIlstItem(item)?.let { return it }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    // ====== WebM: 同時接続数制限付きストリーミング処理 ======
    private suspend fun extractWebmPromptStreamingWithLimit(fileUrl: String, networkClient: NetworkClient): String? {
        val size = httpHeadContentLengthWithLimit(fileUrl, networkClient)
        val head = httpGetRangeWithLimit(fileUrl, 0, MP4_HEAD_BYTES.toLong(), networkClient)
        val tail = if (size != null && size > MP4_TAIL_BYTES) {
            httpGetRangeWithLimit(fileUrl, size - MP4_TAIL_BYTES, MP4_TAIL_BYTES.toLong(), networkClient)
        } else {
            httpGetRangeWithLimit(fileUrl, 0, min(GLOBAL_MAX_BYTES, MP4_TAIL_BYTES).toLong(), networkClient)
        }
        var merged = concatNonNull(head, tail)
        if (merged != null) {
            extractFromWebmWithLib(merged)?.let { return it }
            extractFromWebmContainer(merged)?.let { return it }
        }
        var extra = 256 * 1024
        var total = (merged?.size ?: 0)
        while (total < GLOBAL_MAX_BYTES) {
            val more = if (size != null) {
                val start = max(0L, size - MP4_TAIL_BYTES - extra)
                val len = min(extra, GLOBAL_MAX_BYTES - total)
                httpGetRangeWithLimit(fileUrl, start, len.toLong(), networkClient)
            } else {
                httpGetRangeWithLimit(fileUrl, 0, min(GLOBAL_MAX_BYTES - total, extra).toLong(), networkClient)
            } ?: break

            merged = if (merged == null) more else merged + more
            extractFromWebmWithLib(merged)?.let { return it }
            extractFromWebmContainer(merged)?.let { return it }
            total = merged.size
            extra *= 2
        }
        return null
    }

    // ====== MP4/MOV/M4V: ISOBMFF Box 構造の軽量パース ======
    private fun extractFromMp4Container(bytes: ByteArray): String? {
        fun u32(off: Int): Long = if (off + 4 <= bytes.size)
            ((bytes[off].toLong() and 0xFF) shl 24) or ((bytes[off + 1].toLong() and 0xFF) shl 16) or ((bytes[off + 2].toLong() and 0xFF) shl 8) or (bytes[off + 3].toLong() and 0xFF)
        else 0
        fun typ(off: Int): String? = if (off + 4 <= bytes.size) String(bytes, off, 4, StandardCharsets.ISO_8859_1) else null

        data class Box(val type: String, val start: Int, val size: Int, val contentStart: Int, val contentEnd: Int)
        fun readBoxes(start: Int, end: Int, isMeta: Boolean = false): List<Box> {
            val list = mutableListOf<Box>()
            var p = start
            while (p + 8 <= end) {
                var size = u32(p).toInt()
                val type = typ(p + 4) ?: break
                var header = 8
                if (size == 1 && p + 16 <= end) { // 64-bit size
                    val hi = u32(p + 8)
                    val lo = u32(p + 12)
                    val s64 = (hi shl 32) or lo
                    if (s64 > Int.MAX_VALUE) break
                    size = s64.toInt()
                    header = 16
                } else if (size == 0) {
                    size = end - p
                }
                val boxStart = p
                val boxEnd = (p + size).coerceAtMost(end)
                val cStart = (p + header) + (if (isMeta) 4 else 0) // meta は version/flags 4bytes
                val cEnd = boxEnd
                if (boxEnd <= boxStart || cStart > cEnd) break
                list.add(Box(type, boxStart, boxEnd - boxStart, cStart, cEnd))
                p = boxEnd
            }
            return list
        }

        fun isType(box: Box, t: String) = box.type.equals(t, ignoreCase = false)
        fun findChild(parent: Box, t: String, isMeta: Boolean = false): Box? =
            readBoxes(parent.contentStart, parent.contentEnd, isMeta).firstOrNull { it.type == t }
        fun findChildren(parent: Box, t: String, isMeta: Boolean = false): List<Box> =
            readBoxes(parent.contentStart, parent.contentEnd, isMeta).filter { it.type == t }

        val top = readBoxes(0, bytes.size)
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val udta = findChild(moov, "udta")
        val meta = if (udta != null) findChild(udta, "meta", isMeta = true) else findChild(moov, "meta", isMeta = true)
        // XMP_ 直下も探索
        if (udta != null) {
            val xmp = findChild(udta, "XMP_")
            if (xmp != null) {
                val xmpStr = try { String(bytes, xmp.contentStart, (xmp.contentEnd - xmp.contentStart), StandardCharsets.UTF_8) } catch (_: Exception) { null }
                if (!xmpStr.isNullOrEmpty()) scanXmpForPrompts(xmpStr)?.let { return it }
            }
        }
        if (meta != null) {
            // ilst を探す
            val ilst = findChild(meta, "ilst", isMeta = false)
            if (ilst != null) {
                val items = readBoxes(ilst.contentStart, ilst.contentEnd)
                val keyCandidates = listOf(
                    byteArrayOf(0xA9.toByte(), 'd'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte()), // ©des
                    "desc".toByteArray(StandardCharsets.ISO_8859_1),
                    byteArrayOf(0xA9.toByte(), 'c'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte())  // ©cmt
                )
                for (item in items) {
                    val tBytes = bytes.copyOfRange(item.start + 4, item.start + 8)
                    val isTarget = keyCandidates.any { it.contentEquals(tBytes) }
                    if (!isTarget) continue
                    // data ボックス
                    val datas = findChildren(item, "data")
                    for (d in datas) {
                        val payloadStart = d.contentStart + 8 // 4:type + 4:locale
                        if (payloadStart <= d.contentEnd) {
                            val payload = bytes.copyOfRange(payloadStart, d.contentEnd)
                            val str = try { String(payload, StandardCharsets.UTF_8) } catch (_: Exception) {
                                try { String(payload, StandardCharsets.UTF_16) } catch (_: Exception) { String(payload, StandardCharsets.ISO_8859_1) }
                            }
                            scanTextForPrompts(str)?.let { return it }
                            if (str.isNotBlank() && !isLabely(str)) return str.trim()
                        }
                    }
                }
            }
        }
        return null
    }

    // ====== WebM: EBML構造の軽量パース（Tags → SimpleTag） ======
    private fun extractFromWebmContainer(bytes: ByteArray): String? {
        class Reader {
            var p = 0
            fun eof() = p >= bytes.size
            fun readVint(): Pair<Long, Int>? {
                if (p >= bytes.size) return null
                val b0 = bytes[p].toInt() and 0xFF
                var mask = 0x80
                var length = 1
                while (length <= 8 && (b0 and mask) == 0) { mask = mask shr 1; length++ }
                if (length > 8 || p + length > bytes.size) return null
                var value = (b0 and (mask - 1)).toLong()
                for (i in 1 until length) value = (value shl 8) or (bytes[p + i].toLong() and 0xFF)
                val oldP = p
                p += length
                return Pair(value, length)
            }
            fun readBytes(n: Int): ByteArray? { if (p + n > bytes.size) return null; val out = bytes.copyOfRange(p, p + n); p += n; return out }
        }
        var lastTagName: String? = null
        fun readElement(r: Reader, end: Int, onTag: (name: String, value: String) -> Unit) {
            while (r.p < end) {
                val idPair = r.readVint() ?: return
                val sizePair = r.readVint() ?: return
                val id = idPair.first
                val size = sizePair.first.toInt()
                val start = r.p
                val limit = (start + size).coerceAtMost(bytes.size)
                when (id) {
                    0x18538067L, // Segment
                    0x1254C367L, // Tags
                    0x7373L,     // Tag
                    0x1549A966L  // Info（スキップ）
                    -> { readElement(r, limit, onTag) }
                    0x67C8L -> { // SimpleTag
                        readElement(r, limit, onTag)
                    }
                    0x45A3L -> { // TagName (UTF-8)
                        val data = r.readBytes(size) ?: return
                        lastTagName = String(data, StandardCharsets.UTF_8).trim()
                    }
                    0x4487L -> { // TagString (UTF-8)
                        val value = String(r.readBytes(size) ?: return, StandardCharsets.UTF_8)
                        val name = lastTagName ?: ""
                        val keyMatch = name.equals("prompt", true) || name.equals("parameters", true) || name.equals("description", true) || name.equals("comment", true)
                        val maybe = scanTextForPrompts(value)
                        if (maybe != null) return onTag(name.ifBlank { "TagString" }, maybe)
                        if (keyMatch && value.isNotBlank()) return onTag(name, value.trim())
                    }
                    else -> {
                        // その他バイナリは読み飛ばし
                        r.p = limit
                    }
                }
                r.p = limit
            }
        }

        var result: String? = null
        val r = Reader()
        readElement(r, bytes.size) { _, v -> if (result == null) result = v }
        return result
    }

    // Media3 MatroskaExtractor を使ったWebM正式パース（UnstableApi使用）

    @OptIn(UnstableApi::class)
    private fun extractFromWebmWithLib(bytes: ByteArray): String? {
        try {
            class BAReader(private val data: ByteArray) : DataReader {
                private var pos = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val remaining = data.size - pos
                    if (remaining <= 0) return -1
                    val toRead = min(length, remaining)
                    System.arraycopy(data, pos, buffer, offset, toRead)
                    pos += toRead
                    return toRead
                }
            }

            val input = DefaultExtractorInput(BAReader(bytes), 0, bytes.size.toLong())
            val found = StringBuilder()

            val output = object : ExtractorOutput {
                override fun seekMap(seekMap: SeekMap) {}
                override fun track(id: Int, type: Int): TrackOutput {
                    return object : TrackOutput {
                        override fun format(format: Format) {
                            val meta = format.metadata
                            if (meta != null) {
                                for (i in 0 until meta.length()) {
                                    val entryStr = meta[i].toString()
                                    val fromJson = scanTextForPrompts(entryStr)
                                    if (!fromJson.isNullOrBlank() && found.isEmpty()) {
                                        found.append(fromJson)
                                    } else if (found.isEmpty()) {
                                        val t = entryStr.trim()
                                        if (t.isNotEmpty() && (t.contains("prompt", true) || t.contains("parameters", true) || t.contains("description", true) || t.contains("comment", true))) {
                                            found.append(t)
                                        }
                                    }
                                }
                            }
                        }

                        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                            val tmp = ByteArray(min(16 * 1024, length))
                            var remain = length
                            var total = 0
                            while (remain > 0) {
                                val r = input.read(tmp, 0, min(tmp.size, remain))
                                if (r == -1) break
                                remain -= r
                                total += r
                            }
                            return if (total == 0 && allowEndOfInput) -1 else total
                        }

                        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                            data.skipBytes(length)
                        }

                        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
                    }
                }
                override fun endTracks() {}
            }

            val extractor: Extractor = MatroskaExtractor()
            extractor.init(output)
            val posHolder = PositionHolder()
            var result: Int
            do {
                result = extractor.read(input, posHolder)
            } while (result == Extractor.RESULT_CONTINUE && found.isEmpty())
            if (found.isNotEmpty()) return found.toString()
        } catch (_: Throwable) {
            // ignore and fall back
        }
        return null
    }

    private fun scanTextForPrompts(text: String): String? {
        val promptPattern = Pattern.compile("""prompt"\s*:\s*("([^"\\]*(\\.[^"\\]*)*)"|\{.*?\})""", Pattern.DOTALL)
        promptPattern.matcher(text).apply {
            if (find()) parsePromptJson(group(1) ?: "")?.let { return it }
        }
        val workflowPattern = Pattern.compile("""workflow"\s*:\s*(\{.*?\})""", Pattern.DOTALL)
        workflowPattern.matcher(text).apply {
            if (find()) parseWorkflowJson(group(1) ?: "")?.let { return it }
        }
        val clipTextEncodePattern = Pattern.compile(
            """CLIPTextEncode"[\s\S]{0,2000}?"title"\s*:\s*"([^"]*Positive[^"]*)"[\s\S]{0,1000}?"(text|string)"\s*:\s*"((?:\\.|[^"\\])*)"""",
            Pattern.CASE_INSENSITIVE
        )
        clipTextEncodePattern.matcher(text).apply {
            if (find()) return (group(3) ?: "").replace("\\\"", "\"")
        }
        return null
    }

    // ====== 以下、既存のメソッドをそのまま保持 ======

    private fun extractFromExif(fileBytes: ByteArray): String? {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(fileBytes))
            listOf(
                exif.getAttribute(ExifInterface.TAG_USER_COMMENT),
                exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION),
                exif.getAttribute("XPComment")?.let { decodeXpString(it) }
            ).firstOrNull { !it.isNullOrBlank() }
        } catch (_: Exception) {
            null
        }
    }

    

    private fun decodeXpString(raw: String): String? {
        val bytes = raw.toByteArray(StandardCharsets.ISO_8859_1)
        return try {
            val s = String(bytes, StandardCharsets.UTF_16LE).trim()
            if (s.isBlank()) null else s
        } catch (_: Exception) {
            null
        }
    }

    private fun isPng(fileBytes: ByteArray): Boolean {
        if (fileBytes.size < 8) return false
        return fileBytes[0] == 137.toByte() &&
                fileBytes[1] == 80.toByte() &&
                fileBytes[2] == 78.toByte() &&
                fileBytes[3] == 71.toByte() &&
                fileBytes[4] == 13.toByte() &&
                fileBytes[5] == 10.toByte() &&
                fileBytes[6] == 26.toByte() &&
                fileBytes[7] == 10.toByte()
    }

    private fun extractFromPngChunks(bytes: ByteArray): String? {
        if (!isPng(bytes)) return null
        val prompts = mutableListOf<String>()
        var offset = 8

        fun isPromptKey(key: String): Boolean {
            val k = key.trim().lowercase()
            return k == "parameters" || k == "description" || k == "comment" || k == "prompt"
        }

        while (offset + 12 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            if (length < 0 || offset + 12 + length > bytes.size) break

            val type = String(bytes, offset + 4, 4, StandardCharsets.US_ASCII)
            val dataStart = offset + 8
            val dataEnd = dataStart + length
            val data = bytes.copyOfRange(dataStart, dataEnd)

            when (type) {
                "tEXt" -> {
                    val nul = data.indexOf(0.toByte())
                    if (nul > 0) {
                        val key = String(data, 0, nul, StandardCharsets.ISO_8859_1)
                        val value = String(data, nul + 1, data.size - (nul + 1), StandardCharsets.ISO_8859_1)
                        if (key.equals("XML:com.adobe.xmp", ignoreCase = true)) {
                            scanXmpForPrompts(value)?.let { prompts += it }
                        } else if (isPromptKey(key)) {
                            if (value.isNotBlank()) prompts += value
                        }
                    }
                }
                "zTXt" -> {
                    val nul = data.indexOf(0.toByte())
                    if (nul > 0 && nul + 1 < data.size) {
                        val key = String(data, 0, nul, StandardCharsets.ISO_8859_1)
                        val compressed = data.copyOfRange(nul + 2, data.size)
                        val valueBytes = decompress(compressed)
                        val value = valueBytes.toString(StandardCharsets.UTF_8)
                        if (key.equals("XML:com.adobe.xmp", ignoreCase = true)) {
                            scanXmpForPrompts(value)?.let { prompts += it }
                        } else if (isPromptKey(key)) {
                            if (value.isNotBlank()) prompts += value
                        }
                    }
                }
                "iTXt" -> {
                    val nul = data.indexOf(0.toByte())
                    if (nul > 0 && nul + 2 < data.size) {
                        val key = String(data, 0, nul, StandardCharsets.ISO_8859_1)
                        val compFlag = data[nul + 1].toInt() and 0xFF
                        var p = nul + 3

                        val langEnd = indexOfZero(data, p)
                        if (langEnd == -1) {
                            val textField = data.copyOfRange(p, data.size)
                            val valueBytes = if (compFlag == 1) decompress(textField) else textField
                            val value = valueBytes.toString(StandardCharsets.UTF_8)
                            if (key.equals("XML:com.adobe.xmp", ignoreCase = true)) {
                                scanXmpForPrompts(value)?.let { prompts += it }
                            } else if (isPromptKey(key) && value.isNotBlank()) prompts += value
                        } else {
                            p = langEnd + 1
                            val transEnd = indexOfZero(data, p)
                            if (transEnd == -1) {
                                val textField = data.copyOfRange(p, data.size)
                                val valueBytes = if (compFlag == 1) decompress(textField) else textField
                                val value = valueBytes.toString(StandardCharsets.UTF_8)
                                if (key.equals("XML:com.adobe.xmp", ignoreCase = true)) {
                                    scanXmpForPrompts(value)?.let { prompts += it }
                                } else if (isPromptKey(key) && value.isNotBlank()) prompts += value
                            } else {
                                p = transEnd + 1
                                if (p <= data.size) {
                                    val textField = data.copyOfRange(p, data.size)
                                    val valueBytes = if (compFlag == 1) decompress(textField) else textField
                                    val value = valueBytes.toString(StandardCharsets.UTF_8)
                                    if (key.equals("XML:com.adobe.xmp", ignoreCase = true)) {
                                        scanXmpForPrompts(value)?.let { prompts += it }
                                    } else if (isPromptKey(key) && value.isNotBlank()) prompts += value
                                }
                            }
                        }
                    }
                }
                // C2PA: PNGのカスタムチャンク "c2pa" (JUMBF/manifest store) を簡易走査
                // バイナリ中にJSON文字列が含まれる場合は既存のテキスト解析で拾う
                "c2pa" -> {
                    extractPromptFromC2paData(data)?.let { prompts += it }
                }
                "IEND" -> return prompts.joinToString("\n\n").ifEmpty { null }
            }
            offset += 12 + length
        }
        return prompts.joinToString("\n\n").ifEmpty { null }
    }

    private fun extractFromJpegAppSegments(bytes: ByteArray): String? {
        // JPEGシグネチャ確認
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var p = 2
        while (p + 4 <= bytes.size) {
            if (bytes[p] != 0xFF.toByte()) { p++; continue }
            var marker = bytes[p + 1].toInt() and 0xFF
            p += 2
            if (marker == 0xD9 || marker == 0xDA) break // EOI/SOS
            if (p + 2 > bytes.size) break
            val len = ((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF)
            p += 2
            val dataLen = len - 2
            if (p + dataLen > bytes.size || dataLen <= 0) break
            val seg = bytes.copyOfRange(p, p + dataLen)
            when (marker) {
                0xE1 -> { // APP1: XMP (またはEXIF)
                    val xmpPrefix = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(StandardCharsets.ISO_8859_1)
                    if (seg.size > xmpPrefix.size && seg.copyOfRange(0, xmpPrefix.size).contentEquals(xmpPrefix)) {
                        val xmpBytes = seg.copyOfRange(xmpPrefix.size, seg.size)
                        val xmpStr = try { String(xmpBytes, StandardCharsets.UTF_8) } catch (_: Exception) { String(xmpBytes, StandardCharsets.ISO_8859_1) }
                        scanXmpForPrompts(xmpStr)?.let { return it }
                    }
                }
                0xED -> { // APP13: Photoshop IRB (IPTC)
                    parsePhotoshopIrbForIptc(seg)?.let { return it }
                }
            }
            p += dataLen
        }
        return null
    }

    private fun parsePhotoshopIrbForIptc(app13: ByteArray): String? {
        val header = "Photoshop 3.0\u0000".toByteArray(StandardCharsets.ISO_8859_1)
        if (app13.size < header.size || !app13.copyOfRange(0, header.size).contentEquals(header)) return null
        var p = header.size
        while (p + 12 <= app13.size) {
            if (app13[p] != '8'.code.toByte() || app13[p + 1] != 'B'.code.toByte() || app13[p + 2] != 'I'.code.toByte() || app13[p + 3] != 'M'.code.toByte()) break
            p += 4
            if (p + 2 > app13.size) break
            val resId = ((app13[p].toInt() and 0xFF) shl 8) or (app13[p + 1].toInt() and 0xFF)
            p += 2
            if (p >= app13.size) break
            val nameLen = app13[p].toInt() and 0xFF
            p += 1
            val nameEnd = (p + nameLen).coerceAtMost(app13.size)
            p = nameEnd
            if ((1 + nameLen) % 2 == 1) p += 1
            if (p + 4 > app13.size) break
            val size = ((app13[p].toInt() and 0xFF) shl 24) or ((app13[p + 1].toInt() and 0xFF) shl 16) or ((app13[p + 2].toInt() and 0xFF) shl 8) or (app13[p + 3].toInt() and 0xFF)
            p += 4
            if (p + size > app13.size) break
            val data = app13.copyOfRange(p, p + size)
            p += size
            if (size % 2 == 1) p += 1
            if (resId == 0x0404) {
                parseIptcIimForPrompt(data)?.let { return it }
            }
        }
        return null
    }

    private fun parseIptcIimForPrompt(data: ByteArray): String? {
        var p = 0
        while (p + 5 <= data.size) {
            if (data[p] != 0x1C.toByte()) { p++; continue }
            val rec = data[p + 1].toInt() and 0xFF
            val dset = data[p + 2].toInt() and 0xFF
            val len = ((data[p + 3].toInt() and 0xFF) shl 8) or (data[p + 4].toInt() and 0xFF)
            p += 5
            if (p + len > data.size) break
            val valueBytes = data.copyOfRange(p, p + len)
            p += len
            if (rec == 2 && (dset == 120 || dset == 105 || dset == 116 || dset == 122)) {
                val str = try { String(valueBytes, StandardCharsets.UTF_8) } catch (_: Exception) { String(valueBytes, StandardCharsets.ISO_8859_1) }
                scanTextForPrompts(str)?.let { return it }
                if (str.isNotBlank() && !isLabely(str)) return str.trim()
            }
        }
        return null
    }

    private fun scanXmpForPrompts(xmp: String): String? {
        // 属性 prompt/parameters="..."
        run {
            val attrPattern = Pattern.compile("""([a-zA-Z0-9_:.\-]*?(prompt|parameters))\s*=\s*"((?:\\.|[^"])*)"""", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
            val m = attrPattern.matcher(xmp)
            if (m.find()) {
                val v = m.group(3) ?: ""
                if (v.isNotBlank()) return v.replace("\\\"", "\"")
            }
        }
        // タグ <ns:prompt>...</ns:prompt> or <ns:parameters>...</ns:parameters>
        run {
            val tagPattern = Pattern.compile("""<([a-zA-Z0-9_:.\-]*?(prompt|parameters))[^>]*>([\\s\\S]*?)</[^>]+>""", Pattern.CASE_INSENSITIVE)
            val m = tagPattern.matcher(xmp)
            if (m.find()) {
                val v = m.group(3) ?: ""
                if (v.isNotBlank()) return v.trim()
            }
        }
        // dc:description/rdf:Alt/rdf:li のテキスト
        run {
            val descPattern = Pattern.compile("<dc:description[^>]*>\\s*<rdf:Alt>\\s*<rdf:li[^>]*>([\\s\\S]*?)</rdf:li>", Pattern.CASE_INSENSITIVE)
            val m = descPattern.matcher(xmp)
            if (m.find()) {
                val v = m.group(1) ?: ""
                if (v.isNotBlank() && !isLabely(v)) return v.trim()
            }
        }
        // XMP内にJSONが埋まっている可能性にも対応
        scanTextForPrompts(xmp)?.let { return it }
        return null
    }

    private fun extractPromptFromC2paData(data: ByteArray): String? {
        // C2PAのmanifest storeはJUMBF/CBOR等のバイナリだが、JSON-LDが素で含まれる場合がある。
        // 文字列化して既存のJSON/ワークフロー抽出ロジックに委譲する。
        kotlin.run {
            val latin = try { String(data, StandardCharsets.ISO_8859_1) } catch (_: Exception) { null }
            if (!latin.isNullOrEmpty()) scanTextForPrompts(latin)?.let { return it }
        }
        kotlin.run {
            val utf8 = try { String(data, StandardCharsets.UTF_8) } catch (_: Exception) { null }
            if (!utf8.isNullOrEmpty()) scanTextForPrompts(utf8)?.let { return it }
        }
        return null
    }

    private fun indexOfZero(arr: ByteArray, from: Int): Int {
        if (from >= arr.size) return -1
        for (i in from until arr.size) if (arr[i].toInt() == 0) return i
        return -1
    }

    private fun decompress(compressedData: ByteArray): ByteArray {
        val inflater = InflaterInputStream(ByteArrayInputStream(compressedData))
        val out = ByteArrayOutputStream()
        inflater.use { i -> out.use { o -> i.copyTo(o) } }
        return out.toByteArray()
    }

    private fun parsePromptJson(jsonCandidate: String): String? {
        return try {
            if (jsonCandidate.startsWith("\"") && jsonCandidate.endsWith("\"")) {
                val unescaped = GSON.fromJson(jsonCandidate, String::class.java)
                val map = GSON.fromJson<Map<String, Any>>(unescaped, object : TypeToken<Map<String, Any>>() {}.type)
                extractDataFromMap(map)
            } else {
                val map = GSON.fromJson<Map<String, Any>>(jsonCandidate, object : TypeToken<Map<String, Any>>() {}.type)
                extractDataFromMap(map)
            }
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    private fun parseWorkflowJson(jsonCandidate: String): String? {
        return try {
            val map = GSON.fromJson<Map<String, Any>>(jsonCandidate, object : TypeToken<Map<String, Any>>() {}.type)
            extractDataFromMap(map)
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    private fun extractDataFromMap(dataMap: Map<String, Any>): String? {
        val nodes = dataMap["nodes"] as? List<Map<String, Any>>
        if (nodes != null) {
            return pickFromNodes(nodes) ?: scanHeuristically(dataMap)
        }
        return scanHeuristically(dataMap)
    }

    private fun isLabely(text: String?): Boolean {
        val t = text?.trim() ?: return false
        return t.matches(Regex("^(TxtEmb|TextEmb)", RegexOption.IGNORE_CASE)) ||
                (!t.contains(Regex("""\s""")) && t.length < 24)
    }

    private fun bestStrFromInputs(inputs: Any?): String? {
        if (inputs !is Map<*, *>) return null
        val priorityKeys = listOf("populated_text", "wildcard_text", "prompt", "positive_prompt", "result", "text", "string", "value")
        for (key in priorityKeys) {
            val value = inputs[key]
            if (value is String && value.trim().isNotEmpty()) {
                return value.trim()
            }
        }
        var best: String? = null
        for ((_, value) in inputs) {
            if (value is String && value.trim().isNotEmpty()) {
                if (best == null || value.length > best.length) {
                    best = value
                }
            }
        }
        return best?.trim()
    }

    private fun pickFromNodes(nodes: List<Map<String, Any>>): String? {
        val nodeMap: Map<String, Map<String, Any>> =
            nodes.mapNotNull { node ->
                val id = node["id"]?.toString()
                if (id.isNullOrEmpty()) null else id to node
            }.toMap()

        fun resolveNode(node: Map<String, Any>?, depth: Int = 0): String? {
            if (node == null || depth > 4) return null

            val inputs = node["inputs"]
            var s = bestStrFromInputs(inputs)
            if (s != null && s.isNotEmpty() && !isLabely(s)) return s

            if (inputs is Map<*, *>) {
                for ((_, value) in inputs) {
                    if (value is List<*> && value.isNotEmpty()) {
                        val linkedNodeId = value[0]?.toString()
                        val linkedNode = if (linkedNodeId != null) nodeMap[linkedNodeId] else null
                        val r = resolveNode(linkedNode, depth + 1)
                        if (r != null && !isLabely(r)) return r
                    } else if (value is String && value.trim().isNotEmpty() && !isLabely(value)) {
                        return value.trim()
                    }
                }
            }

            val widgetsValues = node["widgets_values"] as? List<*>
            if (widgetsValues != null) {
                for (v in widgetsValues) {
                    if (v is String && v.trim().isNotEmpty() && !isLabely(v)) return v.trim()
                }
            }
            return null
        }

        val specificChecks = listOf(
            "ImpactWildcardProcessor",
            "WanVideoTextEncodeSingle",
            "WanVideoTextEncode"
        )
        for (typePattern in specificChecks) {
            for (node in nodes) {
                val nodeType = node["type"] as? String ?: node["class_type"] as? String ?: ""
                if (nodeType.contains(typePattern, ignoreCase = true)) {
                    val s = resolveNode(node)
                    if (s != null && s.isNotEmpty()) return s
                }
            }
        }

        for (node in nodes) {
            val nodeType = node["type"] as? String ?: node["class_type"] as? String ?: ""
            val title = node["title"] as? String ?: (node["_meta"] as? Map<String, String>)?.get("title") ?: ""
            if (nodeType.contains("CLIPTextEncode", ignoreCase = true) && title.contains("Positive", ignoreCase = true) && !title.contains("Negative", ignoreCase = true)) {
                var s = bestStrFromInputs(node["inputs"])
                if (s.isNullOrEmpty() && node["widgets_values"] is List<*>) {
                    s = (node["widgets_values"] as List<*>).getOrNull(0) as? String
                }
                if (s != null && s.trim().isNotEmpty() && !isLabely(s)) return s.trim()
            }
        }
        for (node in nodes) {
            val title = node["title"] as? String ?: (node["_meta"] as? Map<String, String>)?.get("title") ?: ""
            if (Regex("PointMosaic|Mosaic|Mask|TxtEmb|TextEmb", RegexOption.IGNORE_CASE).containsMatchIn(title)) continue

            var s = bestStrFromInputs(node["inputs"])
            if (s.isNullOrEmpty() && node["widgets_values"] is List<*>) {
                s = (node["widgets_values"] as List<*>).getOrNull(0) as? String
            }
            if (s != null && s.trim().isNotEmpty() && !isLabely(s) && title.contains("Positive", ignoreCase = true) && !title.contains("Negative", ignoreCase = true)) return s.trim()
        }
        return null
    }

    private fun scanHeuristically(obj: Map<String, Any>): String? {
        val EX_T = Regex("PointMosaic|Mosaic|Mask|TxtEmb|TextEmb", RegexOption.IGNORE_CASE)
        val EX_C = Regex("ShowText|Display|Note|Preview|VHS_|Image|Resize|Seed|INTConstant|SimpleMath|Any Switch|StringConstant(?!Multiline)", RegexOption.IGNORE_CASE)
        var best: String? = null
        var maxScore = -1_000_000_000.0
        val stack = mutableListOf<Any>(obj)

        while (stack.isNotEmpty()) {
            val current = stack.removeAt(stack.size - 1)
            if (current !is Map<*, *>) continue
            val currentMap = current as Map<String, Any>

            val classType = currentMap["class_type"] as? String ?: currentMap["type"] as? String ?: ""
            val meta = currentMap["_meta"] as? Map<String, Any>
            val title = meta?.get("title") as? String ?: currentMap["title"] as? String ?: ""

            var v = bestStrFromInputs(currentMap["inputs"])
            if (v.isNullOrEmpty()) {
                val widgetsValues = currentMap["widgets_values"] as? List<*>
                if (widgetsValues != null && widgetsValues.isNotEmpty()) v = widgetsValues[0] as? String
            }

            if (v is String && v.trim().isNotEmpty()) {
                var score = 0.0
                if (title.contains("Positive", ignoreCase = true)) score += 1000
                if (title.contains("Negative", ignoreCase = true)) score -= 1000
                if (classType.contains("TextEncode", ignoreCase = true) || classType.contains("CLIPText", ignoreCase = true)) score += 120
                if (classType.contains("ImpactWildcardProcessor", ignoreCase = true) || classType.contains("WanVideoTextEncodeSingle", ignoreCase = true)) score += 300
                score += min(220.0, floor(v.length / 8.0))
                if (EX_T.containsMatchIn(title) || EX_T.containsMatchIn(classType)) score -= 900
                if (EX_C.containsMatchIn(classType)) score -= 400
                if (isLabely(v)) score -= 500
                if (score > maxScore) { maxScore = score; best = v.trim() }
            }

            currentMap.values.forEach { value ->
                if (value is Map<*, *> || value is List<*>) stack.add(value)
            }
        }
        return best
    }

    private fun ByteArray.indexOfChunkType(type: String): Boolean {
        val needle = type.toByteArray(StandardCharsets.US_ASCII)
        if (needle.isEmpty() || this.size < needle.size) return false
        outer@ for (i in 0..(this.size - needle.size)) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun regexSearchWindow(text: String, regex: Regex, window: Int): String? {
        val m = regex.find(text) ?: return null
        val s = max(0, m.range.first - window)
        val e = min(text.length, m.range.last + 1 + window)
        return text.substring(s, e)
    }

    private fun concatNonNull(a: ByteArray?, b: ByteArray?): ByteArray? {
        return when {
            a == null && b == null -> null
            a == null -> b
            b == null -> a
            else -> a + b
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val buffer = ByteArray(16 * 1024)
        while (remaining > 0) {
            val toRead = min(remaining.toInt(), buffer.size)
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            remaining -= read
        }
    }

    private fun InputStream.readBytes(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val r = this.read(buf)
            if (r <= 0) break
            val canWrite = min(limit - total, r)
            if (canWrite <= 0) break
            out.write(buf, 0, canWrite)
            total += canWrite
        }
        return out.toByteArray()
    }
}
