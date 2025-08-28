package com.valoser.futaburakari

import android.content.Context
import android.net.Uri
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

object MetadataExtractor {

    // ====== 同時接続数制限設定 ======
    private const val MAX_CONCURRENT_CONNECTIONS = 1 // 同時接続数を2に制限
    private val connectionSemaphore = Semaphore(MAX_CONCURRENT_CONNECTIONS)
    private val activeConnectionCount = AtomicInteger(0)

    // ====== 既存の設定値 ======
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    private const val FIRST_EXIF_BYTES = 256 * 1024
    private const val PNG_WINDOW_BYTES = 256 * 1024
    private const val MP4_HEAD_BYTES  = 128 * 1024
    private const val MP4_TAIL_BYTES  = 512 * 1024
    private const val GLOBAL_MAX_BYTES = 2 * 1024 * 1024

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
                        extractFromExif(head)
                    } else null
                }
                "png" -> {
                    extractPngPromptStreamingWithLimit(uriOrUrl, networkClient)
                }
                "mp4", "webm", "mov", "m4v" -> {
                    extractMp4PromptStreamingWithLimit(uriOrUrl, networkClient)
                }
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
                println("アクティブ接続数: $connectionCount") // デバッグ用
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
                println("アクティブ接続数: $connectionCount") // デバッグ用
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
            extractFromMp4Bytes(merged)?.let { return it }
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
            extractFromMp4Bytes(merged)?.let { return it }
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
            uriOrUrl.endsWith(".mp4", true) || uriOrUrl.endsWith(".webm", true) ||
                    uriOrUrl.endsWith(".mov", true) || uriOrUrl.endsWith(".m4v", true) -> {
                extractFromMp4Bytes(fileBytes)
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
                        if (isPromptKey(key)) {
                            val value = String(data, nul + 1, data.size - (nul + 1), StandardCharsets.ISO_8859_1)
                            if (value.isNotBlank()) prompts += value
                        }
                    }
                }
                "zTXt" -> {
                    val nul = data.indexOf(0.toByte())
                    if (nul > 0 && nul + 1 < data.size) {
                        val key = String(data, 0, nul, StandardCharsets.ISO_8859_1)
                        if (isPromptKey(key)) {
                            val compressed = data.copyOfRange(nul + 2, data.size)
                            val valueBytes = decompress(compressed)
                            val value = valueBytes.toString(StandardCharsets.UTF_8)
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
                            if (isPromptKey(key) && value.isNotBlank()) prompts += value
                        } else {
                            p = langEnd + 1
                            val transEnd = indexOfZero(data, p)
                            if (transEnd == -1) {
                                val textField = data.copyOfRange(p, data.size)
                                val valueBytes = if (compFlag == 1) decompress(textField) else textField
                                val value = valueBytes.toString(StandardCharsets.UTF_8)
                                if (isPromptKey(key) && value.isNotBlank()) prompts += value
                            } else {
                                p = transEnd + 1
                                if (p <= data.size) {
                                    val textField = data.copyOfRange(p, data.size)
                                    val valueBytes = if (compFlag == 1) decompress(textField) else textField
                                    val value = valueBytes.toString(StandardCharsets.UTF_8)
                                    if (isPromptKey(key) && value.isNotBlank()) prompts += value
                                }
                            }
                        }
                    }
                }
                "IEND" -> return prompts.joinToString("\n\n").ifEmpty { null }
            }
            offset += 12 + length
        }
        return prompts.joinToString("\n\n").ifEmpty { null }
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
