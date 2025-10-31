package com.valoser.futaburakari

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import coil3.request.ImageRequest
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

// 動画プロンプト解析に関連する外部パーサーは削除（画像のみ対象）

/**
 * 画像からプロンプト/説明テキストを抽出するユーティリティ。
 *
 * - 入力: ローカルURI/ファイル、HTTP(S) URL をサポート（HTTP は `NetworkClient` 経由）。
 * - 取得戦略: セマフォで同時接続数を制限しつつ、Range GET で必要最小限のバイトのみ取得。
 * - JPEG/WEBP: EXIF → JPEGの APP1(XMP) / APP13(IPTC) → プレーンテキスト走査の順に試行。
 * - PNG: tEXt / zTXt / iTXt / c2pa および XMP/NovelAIステルス情報をストリーミング走査（IEND まで、または上限まで）。
 * - 解析: XMP/JSON/単純テキストから `prompt` / `parameters` 相当を正規表現で抽出。
 * - 非対応: 動画は解析対象外（常に null を返す）。
 * - 呼び出し元: 詳細画面の段階反映（`DetailViewModel.updateMetadataInBackground`）、メディアビュー（`MediaViewScreen`）。
 * - 戦略: 失敗時は null を返す（例外を投げない）ため、UI 側で段階反映・リトライ戦略を取りやすい。
 * - 備考: file://（アーカイブ済みのローカル画像）に対しても同様に抽出可能で、dat落ち履歴からの復元に寄与。
 */
object MetadataExtractor {
    private const val TAG = "MetadataExtractor"

    // ====== 同時接続数制限設定（ユーザー設定で可変） ======
    // AppPreferences の並列度(1..4)を最大3接続に丸めて適用し、
    // Range リクエスト等の同時実行を抑制する（端末/サーバ負荷のバランスを優先）。
    // AtomicReferenceを使用して、既存の待機スレッドも新しいセマフォを参照できるようにする
    @Volatile
    private var currentPermits: Int = 1
    private val connectionSemaphoreRef = java.util.concurrent.atomic.AtomicReference(Semaphore(currentPermits))
    private val connectionSemaphore: Semaphore get() = connectionSemaphoreRef.get()
    private fun permitsForLevel(level: Int): Int = minOf(level, 3) // 最大3並列
    @Synchronized
    private fun ensureSemaphore(context: Context) {
        val level = AppPreferences.getConcurrencyLevel(context)
        val desired = permitsForLevel(level)
        if (desired != currentPermits) {
            // 同期化してセマフォを安全に再作成
            // AtomicReferenceを使用することで、既存の待機中スレッドも
            // 次のアクセス時には新しいセマフォを参照できる
            currentPermits = desired
            connectionSemaphoreRef.set(Semaphore(desired))
        }
    }
    private val activeConnectionCount = AtomicInteger(0)

    // ====== 既存の設定値 ======
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    private const val FIRST_EXIF_BYTES = 128 * 1024
    private const val PNG_WINDOW_BYTES = 256 * 1024 // 256KB
    private const val GLOBAL_MAX_BYTES = 1024 * 1024 // 1MB

    private val PROMPT_KEYS = setOf("parameters", "Description", "Comment", "prompt")
    private val GSON = Gson()

    // ===== 正規表現（プリコンパイル） =====
    private val RE_JSON_PROMPT: Pattern = Pattern.compile(
        """prompt"\s*:\s*("([^"\\]*(\\.[^"\\]*)*)"|\{.*?\})""",
        Pattern.DOTALL
    )
    private val RE_JSON_WORKFLOW: Pattern = Pattern.compile(
        """workflow"\s*:\s*(\{.*?\})""",
        Pattern.DOTALL
    )
    private val RE_CLIPTEXTENCODE: Pattern = Pattern.compile(
        """CLIPTextEncode"[\s\S]{0,2000}?"title"\s*:\s*"([^"]*Positive[^"]*)"[\s\S]{0,1000}?"(text|string)"\s*:\s*"((?:\\.|[^"\\])*)"""",
        Pattern.CASE_INSENSITIVE
    )
    private val RE_NOVELAI_SOFTWARE: Pattern = Pattern.compile(
        """"software"\s*:\s*"NovelAI"""",
        Pattern.CASE_INSENSITIVE
    )
    // Match a JSON-like block enclosed in braces, non-greedy
    private val RE_JSON_BRACE: Pattern = Pattern.compile("""\{[\s\S]*?\}""", Pattern.DOTALL)
    private val RE_XMP_ATTR: Pattern = Pattern.compile(
        """([a-zA-Z0-9_:.\-]*?(prompt|parameters))\s*=\s*"((?:\\.|[^"])*)"""",
        Pattern.CASE_INSENSITIVE or Pattern.DOTALL
    )
    private val RE_XMP_TAG: Pattern = Pattern.compile(
        """<([a-zA-Z0-9_:.\-]*?(prompt|parameters))[^>]*>([\\s\\S]*?)</[^>]+>""",
        Pattern.CASE_INSENSITIVE
    )
    private val RE_XMP_DESC: Pattern = Pattern.compile(
        "<dc:description[^>]*>\\s*<rdf:Alt>\\s*<rdf:li[^>]*>([\\s\\S]*?)</rdf:li>",
        Pattern.CASE_INSENSITIVE
    )

    // ===== 結果キャッシュ（陽性のみ保存） =====
    private const val CACHE_MAX = 128 // キャッシュサイズを削減
    private val resultCache: java.util.LinkedHashMap<String, String> = object : java.util.LinkedHashMap<String, String>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CACHE_MAX
    }
    @Synchronized private fun cacheGet(key: String): String? = resultCache[key]
    @Synchronized private fun cachePut(key: String, value: String) { resultCache[key] = value }

    // ====== ScreenShot判定 ======
    /**
     * ScreenShotファイルかどうかを判定する。
     *
     * @param uriOrUrl ファイルのURI/URL
     * @return ScreenShotファイルの場合 true
     */
    private fun isScreenShotFile(uriOrUrl: String): Boolean {
        val lowercasePath = uriOrUrl.lowercase()

        // ScreenShotファイルのパターンをチェック（大文字小文字を考慮）
        return lowercasePath.contains("screenshot")
    }

    // ====== Coil キャッシュ統合 ======
    /**
     * Coilのディスクキャッシュから画像を取得してメタデータを抽出する。
     * ネットワークアクセスなしで即座に結果を返せる。
     *
     * @param context アプリケーションコンテキスト
     * @param imageUrl 画像のURL（キャッシュキーとして使用）
     * @return 抽出されたメタデータ。キャッシュにない、またはメタデータがない場合はnull
     */
    private suspend fun extractFromCoilCacheIfAvailable(
        context: Context,
        imageUrl: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Coilのシングルトンインスタンスを取得
            val imageLoader = coil3.SingletonImageLoader.get(context)
            val diskCache = imageLoader.diskCache ?: return@withContext null
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .build()

            val candidateKeys = buildList {
                add(imageUrl)
                request.diskCacheKey?.let { add(it) }
            }.distinct()

            for (key in candidateKeys) {
                val snapshot = diskCache.openSnapshot(key) ?: continue
                snapshot.use {
                    val cachedFile = snapshot.data.toFile()

                    if (!cachedFile.exists() || !cachedFile.canRead()) {
                        Log.d(TAG, "Coil cache file not accessible: $imageUrl (key=$key)")
                        return@use null
                    }

                    val fileSize = cachedFile.length()
                    Log.d(TAG, "Coil cache hit (${fileSize / 1024}KB): $imageUrl (key=$key)")

                    return@withContext if (fileSize > GLOBAL_MAX_BYTES) {
                        cachedFile.inputStream().use { input ->
                            val bytes = input.readBytes(limit = GLOBAL_MAX_BYTES)
                            extractBySniff(bytes, imageUrl)
                        }
                    } else {
                        val bytes = cachedFile.readBytes()
                        extractBySniff(bytes, imageUrl)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata from Coil cache: $imageUrl", e)
            null
        }
    }

    // ====== Public API ======
    /**
     * URI/URL からプロンプトらしき文字列を抽出して返す。見つからなければ null。
     *
     * - ファイル種別に応じて、先頭範囲のみ取得やチャンク走査を行う。
     * - HTTP(S) の取得には `NetworkClient` を用い、Range ヘッダを中心に必要最小限の取得を行う。
     * - HTTP(S) URLの場合、まずCoilのディスクキャッシュをチェックし、キャッシュヒット時はネットワークアクセスをスキップ。
     *
     * @param context 設定やリソース取得に使用する `Context`
     * @param uriOrUrl `content://`/`file://`/`http(s)://` のいずれか
     * @param networkClient ネットワークアクセス用クライアント
     * @return 抽出されたプロンプト文字列。見つからない場合は null
     */
    suspend fun extract(context: Context, uriOrUrl: String, networkClient: NetworkClient): String? = withContext(Dispatchers.IO) {
        try {
            if (!PromptSettings.isPromptFetchEnabled(context)) {
                Log.d(TAG, "Prompt extraction disabled. Skipping for $uriOrUrl")
                return@withContext null
            }

            // ScreenShotファイルは早期リターン（プロンプト抽出をスキップ）
            if (isScreenShotFile(uriOrUrl)) {
                Log.d(TAG, "Skipping prompt extraction for screenshot file: $uriOrUrl")
                return@withContext null
            }

            // 1) in-memory LRU
            cacheGet(uriOrUrl)?.let { return@withContext it }
            // 2) persistent cache（Hilt シングルトンを EntryPoint で取得）
            val metadataCache = MetadataCacheEntryPoint.resolve(context)
            runCatching { metadataCache.get(uriOrUrl) }.getOrNull()?.let { cached ->
                cachePut(uriOrUrl, cached)
                return@withContext cached
            }
            // 3) Coilディスクキャッシュをチェック（HTTP(S)のみ）
            if (uriOrUrl.startsWith("http://") || uriOrUrl.startsWith("https://")) {
                extractFromCoilCacheIfAvailable(context, uriOrUrl)?.let { result ->
                    Log.d(TAG, "Metadata extracted from Coil disk cache (no network): $uriOrUrl")
                    cachePut(uriOrUrl, result)
                    runCatching { metadataCache.put(uriOrUrl, result) }
                    return@withContext result
                }
            }
            // 4) キャッシュミス時はローカル読み込み/ネットワーク取得でフォールバック
            // セマフォを最新設定に同期
            ensureSemaphore(context)
            val result: String? = if (uriOrUrl.startsWith("content://") || uriOrUrl.startsWith("file://")) {
                context.contentResolver.openInputStream(Uri.parse(uriOrUrl))?.use { input ->
                    val all = input.readBytes(limit = GLOBAL_MAX_BYTES)
                    extractBySniff(all, uriOrUrl)
                }
            } else {
                val ext = uriOrUrl.substringAfterLast('.', "").lowercase()
                when (ext) {
                    "jpg", "jpeg", "webp" -> {
                        val head = httpGetRangeWithLimit(context, uriOrUrl, 0, FIRST_EXIF_BYTES.toLong(), networkClient)
                        if (head != null) {
                            extractFromExif(head)
                                ?: extractFromJpegAppSegments(head)
                                ?: extractBySniff(head, uriOrUrl)
                        } else null
                    }
                    "png" -> extractPngPromptStreamingWithLimit(context, uriOrUrl, networkClient)
                    // 動画のプロンプト取得は廃止
                    "mp4", "mov", "m4v", "webm" -> null
                    else -> {
                        val head = httpGetRangeWithLimit(context, uriOrUrl, 0, FIRST_EXIF_BYTES.toLong(), networkClient)
                        if (head != null) extractBySniff(head, uriOrUrl) else null
                    }
                }
            }
            if (!result.isNullOrBlank()) {
                // "UNICODE"のみの結果は無効として扱う
                if (result.trim() == "UNICODE") {
                    Log.w(TAG, "Extracted metadata is just 'UNICODE' marker, treating as null: $uriOrUrl")
                    return@withContext null
                }
                cachePut(uriOrUrl, result)
                runCatching { metadataCache.put(uriOrUrl, result) }
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    // ====== 同時接続数制限付きHTTPメソッド ======

    /**
     * 同時接続数を制限して Range GET を実行する。
     *
     * @param context 同時接続制御のための設定参照用 `Context`
     * @param urlStr 対象 URL
     * @param start 開始オフセット
     * @param length 取得長
     * @param networkClient 取得に使用するクライアント
     * @return 取得したバイト配列。失敗時は null
     */
    private suspend fun httpGetRangeWithLimit(context: Context, urlStr: String, start: Long, length: Long, networkClient: NetworkClient): ByteArray? {
        ensureSemaphore(context)
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
     * 同時接続数を制限して HEAD リクエストを実行する。
     *
     * @param context 同時接続制御のための設定参照用 `Context`
     * @param urlStr 対象 URL
     * @param networkClient 取得に使用するクライアント
     * @return `HeadInfo`。失敗時は null
     */
    private suspend fun httpHeadWithLimit(context: Context, urlStr: String, networkClient: NetworkClient): HeadInfo? {
        ensureSemaphore(context)
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

    private suspend fun httpHeadContentLengthWithLimit(context: Context, urlStr: String, networkClient: NetworkClient): Long? {
        val info = httpHeadWithLimit(context, urlStr, networkClient)
        return info?.contentLength
    }

    // ====== PNG: 同時接続数制限付きストリーミング処理 ======
    /**
     * PNG をストリーミングで走査し、tEXt/zTXt/iTXt や XMP からプロンプトを抽出する。
     *
     * - 初回は固定サイズを取得し、必要に応じて窓を広げる。
     * - IEND 検出またはグローバル上限到達で打ち切る。
     *
     * @param context 同時接続制御のための設定参照用 `Context`
     * @param fileUrl PNG 画像の URL
     * @param networkClient 取得に使用するクライアント
     * @return 抽出したプロンプト。見つからない場合は null
     */
    private suspend fun extractPngPromptStreamingWithLimit(context: Context, fileUrl: String, networkClient: NetworkClient): String? {
        var windowSize = PNG_WINDOW_BYTES
        var totalFetched = 0
        val buf = ByteArrayOutputStream()

        val first = httpGetRangeWithLimit(context, fileUrl, 0, windowSize.toLong(), networkClient) ?: return null
        buf.write(first)
        totalFetched += first.size

        var bytes = buf.toByteArray()
        if (!isPng(bytes)) return null
        extractFromPngChunks(bytes)?.let { return it }

        while (totalFetched < GLOBAL_MAX_BYTES) {
            val offset = bytes.size.toLong()
            windowSize = min(PNG_WINDOW_BYTES, GLOBAL_MAX_BYTES - totalFetched)
            if (windowSize <= 0) break

            val more = httpGetRangeWithLimit(context, fileUrl, offset, windowSize.toLong(), networkClient) ?: break
            buf.write(more)
            totalFetched += more.size
            bytes = buf.toByteArray()
            extractFromPngChunks(bytes)?.let { return it }

            if (bytes.indexOfChunkType("IEND")) break
        }
        return null
    }

    // 動画のプロンプト取得は廃止のため、動画解析用メソッドは削除済み

    // ====== 接続管理用のユーティリティ関数 ======

    /** 現在のアクティブ接続数を取得する。 */
    fun getActiveConnectionCount(): Int = activeConnectionCount.get()

    /** 最大同時接続数を取得する。 */
    fun getMaxConcurrentConnections(): Int = currentPermits

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

    // バイト列の種類に応じて抽出方法を切り替える簡易ルータ
    private fun extractByType(fileBytes: ByteArray, uriOrUrl: String): String? {
        return when {
            // 動画のプロンプト取得は廃止
            isPng(fileBytes) -> extractFromPngChunks(fileBytes)
            else -> extractFromExif(fileBytes)
        }
    }

    // バイト先頭を嗅ぎ分けてEXIF/JPEGセグメント/テキストを順に試すフォールバック
    private fun extractBySniff(bytes: ByteArray, @Suppress("UNUSED_PARAMETER") uriOrUrl: String): String? {
        if (isPng(bytes)) {
            return extractFromPngChunks(bytes)
        }
        extractFromExif(bytes)?.let { return it }
        extractFromJpegAppSegments(bytes)?.let { return it }
        return scanTextForPrompts(String(bytes, StandardCharsets.UTF_8))
    }

    

    // WebM についても同様に取得処理は廃止

    

    // テキスト中から prompt/workflow/CLIPTextEncode 由来の候補を正規表現で抽出
    private fun scanTextForPrompts(text: String): String? {
        RE_JSON_PROMPT.matcher(text).apply {
            if (find()) parsePromptJson(group(1) ?: "")?.let {
                if (it.trim() != "UNICODE") return it
            }
        }
        RE_JSON_WORKFLOW.matcher(text).apply {
            if (find()) parseWorkflowJson(group(1) ?: "")?.let {
                if (it.trim() != "UNICODE") return it
            }
        }
        RE_CLIPTEXTENCODE.matcher(text).apply {
            if (find()) {
                val result = (group(3) ?: "").replace("\\\"", "\"")
                if (result.trim() != "UNICODE") return result
            }
        }
        return null
    }

    // ====== 以下、既存のメソッドをそのまま保持 ======

    // EXIF の UserComment/ImageDescription/XPComment から最初に見つかったものを返す
    private fun extractFromExif(fileBytes: ByteArray): String? {
        return try {
            val exif = ExifInterface(ByteArrayInputStream(fileBytes))
            listOf(
                exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.let { decodeUserComment(it) },
                exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION),
                exif.getAttribute("XPComment")?.let { decodeXpString(it) }
            ).firstOrNull { !it.isNullOrBlank() }
        } catch (_: Exception) {
            null
        }
    }



    // EXIF UserComment の適切なデコード（エンコーディングヘッダーを考慮）
    private fun decodeUserComment(raw: String): String? {
        if (raw.length < 8) return raw.takeIf { it.isNotBlank() }

        val bytes = raw.toByteArray(StandardCharsets.ISO_8859_1)
        return try {
            when {
                // UNICODE (UTF-16) エンコーディングマーカー
                bytes.size >= 8 &&
                bytes[0] == 'U'.code.toByte() &&
                bytes[1] == 'N'.code.toByte() &&
                bytes[2] == 'I'.code.toByte() &&
                bytes[3] == 'C'.code.toByte() &&
                bytes[4] == 'O'.code.toByte() &&
                bytes[5] == 'D'.code.toByte() &&
                bytes[6] == 'E'.code.toByte() -> {
                    // 8バイト目以降をUTF-16LEとしてデコード
                    val textBytes = bytes.copyOfRange(8, bytes.size)
                    String(textBytes, StandardCharsets.UTF_16LE).trim().takeIf { it.isNotBlank() }
                }
                // ASCII エンコーディングマーカー
                bytes.size >= 8 &&
                bytes[0] == 'A'.code.toByte() &&
                bytes[1] == 'S'.code.toByte() &&
                bytes[2] == 'C'.code.toByte() &&
                bytes[3] == 'I'.code.toByte() &&
                bytes[4] == 'I'.code.toByte() -> {
                    // 8バイト目以降をASCIIとしてデコード
                    val textBytes = bytes.copyOfRange(8, bytes.size)
                    String(textBytes, StandardCharsets.US_ASCII).trim().takeIf { it.isNotBlank() }
                }
                // JIS エンコーディングマーカー（あまり使われないが一応対応）
                bytes.size >= 8 &&
                bytes.sliceArray(0..4).contentEquals("JIS\u0000\u0000".toByteArray(StandardCharsets.ISO_8859_1)) -> {
                    // 8バイト目以降をShift_JISとしてデコード（正確にはJISだが、実用上Shift_JISで処理）
                    val textBytes = bytes.copyOfRange(8, bytes.size)
                    String(textBytes, charset("Shift_JIS")).trim().takeIf { it.isNotBlank() }
                }
                // エンコーディングマーカーがない場合はそのまま
                else -> raw.trim().takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            // デコードに失敗した場合は元の文字列を返す（ただし"UNICODE"だけの場合は除外）
            raw.trim().takeIf { it.isNotBlank() && it != "UNICODE" }
        }
    }

    // XPComment は UTF-16LE を ISO-8859-1 として受け取るため、UTF-16LE で復号
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

    // PNG の tEXt/zTXt/iTXt/c2pa からキー(parameters/description/comment/prompt)や XMP/NovelAIステルス情報を抽出
    private fun extractFromPngChunks(bytes: ByteArray): String? {
        if (!isPng(bytes)) return null
        val prompts = mutableListOf<String>()
        var offset = 8

        fun isPromptKey(key: String): Boolean {
            val t = key.trim()
            return PROMPT_KEYS.any { it.equals(t, ignoreCase = true) }
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
                            if (value.isNotBlank() && value.trim() != "UNICODE") prompts += value
                        }
                    }
                }
                "zTXt" -> {
                    val nul = data.indexOf(0.toByte())
                    if (nul > 0 && nul + 1 < data.size) {
                        val key = String(data, 0, nul, StandardCharsets.ISO_8859_1)
                        // 目的のキー（XMP or prompt系）以外は伸長しない
                        val isTarget = key.equals("XML:com.adobe.xmp", ignoreCase = true) || isPromptKey(key)
                        if (isTarget) {
                            val compressed = data.copyOfRange(nul + 2, data.size)
                            val valueBytes = decompress(compressed)
                            val value = valueBytes.toString(StandardCharsets.UTF_8)
                            if (key.equals("XML:com.adobe.xmp", ignoreCase = true)) {
                                scanXmpForPrompts(value)?.let { prompts += it }
                            } else if (isPromptKey(key)) {
                                if (value.isNotBlank() && value.trim() != "UNICODE") prompts += value
                            }
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
                            // 目的キー以外は伸長/文字列化をスキップ
                            val isTarget = key.equals("XML:com.adobe.xmp", ignoreCase = true) || isPromptKey(key)
                            if (isTarget) {
                                val valueBytes = if (compFlag == 1) decompress(textField) else textField
                                val value = valueBytes.toString(StandardCharsets.UTF_8)
                                if (key.equals("XML:com.adobe.xmp", ignoreCase = true)) {
                                    scanXmpForPrompts(value)?.let { prompts += it }
                                } else if (isPromptKey(key) && value.isNotBlank() && value.trim() != "UNICODE") prompts += value
                            }
                        } else {
                            p = langEnd + 1
                            val transEnd = indexOfZero(data, p)
                            if (transEnd == -1) {
                                val textField = data.copyOfRange(p, data.size)
                                val valueBytes = if (compFlag == 1) decompress(textField) else textField
                                val value = valueBytes.toString(StandardCharsets.UTF_8)
                                if (key.equals("XML:com.adobe.xmp", ignoreCase = true)) {
                                    scanXmpForPrompts(value)?.let { prompts += it }
                                } else if (isPromptKey(key) && value.isNotBlank() && value.trim() != "UNICODE") prompts += value
                            } else {
                                p = transEnd + 1
                                if (p <= data.size) {
                                    val textField = data.copyOfRange(p, data.size)
                                    val valueBytes = if (compFlag == 1) decompress(textField) else textField
                                    val value = valueBytes.toString(StandardCharsets.UTF_8)
                                    if (key.equals("XML:com.adobe.xmp", ignoreCase = true)) {
                                        scanXmpForPrompts(value)?.let { prompts += it }
                                    } else if (isPromptKey(key) && value.isNotBlank() && value.trim() != "UNICODE") prompts += value
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
        // ここまでで見つからなければ、NovelAIの Stealth PNGInfo (アルファLSB) を試みる
        if (prompts.isEmpty()) {
            decodeNovelAIAlphaStego(bytes)?.let { return it }
        }
        return prompts.joinToString("\n\n").ifEmpty { null }
    }

    // NovelAI Stealth PNGInfo (アルファLSB) 実験的デコーダ
    // 仕様は非公開のため、ビット順を複数仮定してJSONを探索するベストエフォート実装
    private fun decodeNovelAIAlphaStego(png: ByteArray): String? {
        data class IHDR(val width: Int, val height: Int, val bitDepth: Int, val colorType: Int, val interlace: Int)

        fun readIHDR(): IHDR? {
            if (!isPng(png)) return null
            var p = 8
            while (p + 12 <= png.size) {
                val len = ByteBuffer.wrap(png, p, 4).int
                if (len < 0 || p + 12 + len > png.size) break
                val type = String(png, p + 4, 4, StandardCharsets.US_ASCII)
                if (type == "IHDR" && len >= 13) {
                    val w = ByteBuffer.wrap(png, p + 8, 4).int
                    val h = ByteBuffer.wrap(png, p + 12, 4).int
                    val bitDepth = png[p + 16].toInt() and 0xFF
                    val colorType = png[p + 17].toInt() and 0xFF
                    val interlace = png[p + 20].toInt() and 0xFF
                    return IHDR(w, h, bitDepth, colorType, interlace)
                }
                p += 12 + len
            }
            return null
        }

        val ihdr = readIHDR() ?: return null
        // RGBA(6) or Gray+Alpha(4) のみ対象。8bit/非インタレースのみ対応。
        if (ihdr.interlace != 0) return null
        if (ihdr.bitDepth != 8) return null
        val channels = when (ihdr.colorType) {
            6 -> 4 // RGBA
            4 -> 2 // Gray+Alpha
            else -> return null
        }
        val bpp = channels

        // 連結IDATを解凍
        val idat = ByteArrayOutputStream()
        run {
            var p = 8
            while (p + 12 <= png.size) {
                val len = ByteBuffer.wrap(png, p, 4).int
                if (len < 0 || p + 12 + len > png.size) break
                val type = String(png, p + 4, 4, StandardCharsets.US_ASCII)
                if (type == "IDAT" && len > 0) {
                    idat.write(png, p + 8, len)
                }
                p += 12 + len
            }
        }
        val decompressed = try {
            InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).use {
                it.readBytes(limit = 10 * 1024 * 1024) // 10MBに制限
            }
        } catch (_: Exception) { return null }

        val rowSize = ihdr.width * bpp
        val expected = ihdr.height * (1 + rowSize)
        if (decompressed.size < expected) return null

        // フィルタ解除
        val out = ByteArray(ihdr.height * rowSize)
        fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = kotlin.math.abs(p - a)
            val pb = kotlin.math.abs(p - b)
            val pc = kotlin.math.abs(p - c)
            return when {
                pa <= pb && pa <= pc -> a
                pb <= pc -> b
                else -> c
            }
        }
        var src = 0
        for (y in 0 until ihdr.height) {
            val filter = decompressed[src].toInt() and 0xFF
            src += 1
            val dstRow = y * rowSize
            when (filter) {
                0 -> { // None
                    System.arraycopy(decompressed, src, out, dstRow, rowSize)
                }
                1 -> { // Sub
                    for (x in 0 until rowSize) {
                        val left = if (x >= bpp) out[dstRow + x - bpp].toInt() and 0xFF else 0
                        val v = ((decompressed[src + x].toInt() and 0xFF) + left) and 0xFF
                        out[dstRow + x] = v.toByte()
                    }
                }
                2 -> { // Up
                    for (x in 0 until rowSize) {
                        val up = if (y > 0) out[dstRow - rowSize + x].toInt() and 0xFF else 0
                        val v = ((decompressed[src + x].toInt() and 0xFF) + up) and 0xFF
                        out[dstRow + x] = v.toByte()
                    }
                }
                3 -> { // Average
                    for (x in 0 until rowSize) {
                        val left = if (x >= bpp) out[dstRow + x - bpp].toInt() and 0xFF else 0
                        val up = if (y > 0) out[dstRow - rowSize + x].toInt() and 0xFF else 0
                        val v = ((decompressed[src + x].toInt() and 0xFF) + ((left + up) / 2)) and 0xFF
                        out[dstRow + x] = v.toByte()
                    }
                }
                4 -> { // Paeth
                    for (x in 0 until rowSize) {
                        val a = if (x >= bpp) out[dstRow + x - bpp].toInt() and 0xFF else 0
                        val b = if (y > 0) out[dstRow - rowSize + x].toInt() and 0xFF else 0
                        val c = if (y > 0 && x >= bpp) out[dstRow - rowSize + x - bpp].toInt() and 0xFF else 0
                        val v = ((decompressed[src + x].toInt() and 0xFF) + paeth(a, b, c)) and 0xFF
                        out[dstRow + x] = v.toByte()
                    }
                }
                else -> return null // 未対応
            }
            src += rowSize
        }

        // アルファチャネル抽出
        val alpha = ByteArray(ihdr.width * ihdr.height)
        run {
            var i = 0
            for (y in 0 until ihdr.height) {
                val row = y * rowSize
                var x = 0
                while (x < ihdr.width) {
                    val idx = when (channels) {
                        4 -> row + x * 4 + 3
                        2 -> row + x * 2 + 1
                        else -> return@run
                    }
                    alpha[i++] = out[idx]
                    x++
                }
            }
        }

        fun bitsToBytes(bits: IntArray, lsbFirst: Boolean): ByteArray {
            val outB = ByteArray(bits.size / 8)
            var bi = 0
            var acc = 0
            var count = 0
            for (b in bits) {
                acc = if (lsbFirst) acc or ((b and 1) shl count) else (acc shl 1) or (b and 1)
                count++
                if (count == 8) {
                    outB[bi++] = (if (lsbFirst) acc else acc and 0xFF).toByte()
                    acc = 0
                    count = 0
                    if (bi >= outB.size) break
                }
            }
            return outB
        }

        // 1ビット/ピクセルの仮定で両順序試行
        val bits = IntArray(alpha.size) { alpha[it].toInt() and 1 }
        val candidates = listOf(
            bitsToBytes(bits, lsbFirst = true),
            bitsToBytes(bits, lsbFirst = false)
        )

        fun tryParsePayload(bytes: ByteArray): String? {
            // 可読域を文字列化し、NovelAI JSON らしきブロックを探す
            val s = try { String(bytes, StandardCharsets.UTF_8) } catch (_: Exception) { return null }
            // 簡易正規表現で { ... } を広めに拾い、"software":"NovelAI" を含むものを採用
            val m = RE_JSON_BRACE.matcher(s)
            while (m.find()) {
                val cand = s.substring(m.start(), m.end())
                if (RE_NOVELAI_SOFTWARE.matcher(cand).find()) {
                    scanTextForPrompts(cand)?.let { return it }
                    // 最後の手段として丸ごと返す（ただし"UNICODE"のみは除外）
                    if (cand.trim() != "UNICODE") return cand
                }
            }
            return null
        }

        for (c in candidates) {
            val r = tryParsePayload(c)
            if (r != null) return r
        }
        return null
    }

    // JPEG の APP1(XMP) と APP13(Photoshop IRB/IPTC) を簡易パース
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

    // Photoshop IRB ブロックから IPTC(IIM) を取り出し、説明に相当する項目を抽出
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

    // IPTC IIM の見出し(2:xxx)から説明/キャプション相当を抽出
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
                if (str.isNotBlank() && !isLabely(str) && str.trim() != "UNICODE") return str.trim()
            }
        }
        return null
    }

    private fun scanXmpForPrompts(xmp: String): String? {
        // 属性 prompt/parameters="..."
        run {
            val m = RE_XMP_ATTR.matcher(xmp)
            if (m.find()) {
                val v = m.group(3) ?: ""
                if (v.isNotBlank() && v.trim() != "UNICODE") return v.replace("\\\"", "\"")
            }
        }
        // タグ <ns:prompt>...</ns:prompt> or <ns:parameters>...</ns:parameters>
        run {
            val m = RE_XMP_TAG.matcher(xmp)
            if (m.find()) {
                val v = m.group(3) ?: ""
                if (v.isNotBlank() && v.trim() != "UNICODE") return v.trim()
            }
        }
        // dc:description/rdf:Alt/rdf:li のテキスト
        run {
            val m = RE_XMP_DESC.matcher(xmp)
            if (m.find()) {
                val v = m.group(1) ?: ""
                if (v.isNotBlank() && !isLabely(v) && v.trim() != "UNICODE") return v.trim()
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
