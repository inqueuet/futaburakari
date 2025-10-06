package com.valoser.futaburakari

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Cookie
import okhttp3.coroutines.executeAsync
import org.jsoup.Jsoup
import java.io.IOException
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import okio.source

/**
 * Futaba への投稿を担当するリポジトリ。
 *
 * - マルチパートフォームを Shift_JIS で組み立て、OkHttp で送信します。
 * - `hash` など JS で得られる hidden/token は `extra` で上書き注入可能（`extra` が最優先）。
 * - `pthc/pthb` はアプリ内で保存・再利用（初回は生成）。削除パス `pwd` も未指定時は保存済みまたは新規生成を利用します。
 * - Cookie は WebView と OkHttp の CookieJar をマージし、同名キーは WebView 側を優先します。
 * - `Origin`/`Referer` を適切に付与し、必要に応じて `guid=on` を付けた投稿先 URL を使用します。
 */
import javax.inject.Inject

class ReplyRepository @Inject constructor(
    private val httpClient: OkHttpClient,
) {

    /**
     * 投稿を実行する。
     *
     * @param boardUrl  例: https://may.2chan.net/27/futaba.php?guid=on
     * @param resto     レス先スレ番号 (例: "323716")
     * @param name      おなまえ（任意）
     * @param email     メール（任意）
     * @param sub       題名（任意）
     * @param com       本文
     * @param inputPwd  削除パス（任意、未入力なら自動生成/保存）
     * @param upfileUri 添付（任意）
     * @param textOnly  画像なし
     * @param context   コンテキスト（SJIS, 添付, Cookie/Prefs 用）
     * @param extra     追加の hidden / token（後勝ちで上書き注入される）
     * @return Result<String> 成功時は "送信完了" または "送信完了 No.xxx" を返す。
     *
     * - `extra` に `hash` が指定されていない場合はスレページを取得して `hash` を抽出します。
     * - `textOnly=true` もしくは `upfileUri=null` の場合は空の `upfile` を付けて送信します（ブラウザ挙動に合わせる）。
     * - 最後に必ず `mode=regist` を付与し、トークンに含まれる `mode` を上書きします。
     * - Cookie は WebView と OkHttp から収集してマージ、User-Agent は `Ua.STRING` を使用します。
     * - レスポンスは JSON（thisno）優先で番号を抽出、HTML でも成功の雰囲気なら成功扱いにします。
     */
    suspend fun postReply(
        boardUrl: String,
        resto: String,
        name: String?,
        email: String?,
        sub: String?,
        com: String,
        inputPwd: String?,
        upfileUri: Uri?,
        textOnly: Boolean,
        context: Context,
        extra: Map<String, String> = emptyMap(),
        callTimeoutMs: Long? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // 投稿先 URL からスレURL（Referer 用）を組み立て、Origin 用には HttpUrl からホスト情報を抽出
            val baseBoardUrl = boardUrl.substringBeforeLast("futaba.php")
            if (baseBoardUrl.isEmpty() || !boardUrl.contains("futaba.php")) {
                throw IllegalArgumentException("boardUrl が futaba.php を含んでいません: $boardUrl")
            }
            val threadPageUrl = baseBoardUrl + "res/$resto.htm"
            // ✅ Origin は「https://host[:port]」にする（パスは含めない）
            val parsed = boardUrl.toHttpUrl()
            val origin = buildString {
                append(parsed.scheme).append("://").append(parsed.host)
                val p = parsed.port
                if (!((parsed.scheme == "https" && p == 443) || (parsed.scheme == "http" && p == 80))) {
                    append(":").append(p)
                }
            }

            // hash は通常スレHTMLから抽出するが、extra に入っていればそれを最優先で利用
            // extra が無い場合のみフェッチ
            val ensuredHash = extra["hash"] ?: fetchHashFromThreadPage(threadPageUrl).getOrElse {
                throw IOException("環境変数(hash)の取得に失敗しました。Cookie・セッション情報が不足している可能性があります。: ${it.message}")
            }

            // pthc/pthb は保存／再利用。未保存なら生成
            var pthc = AppPreferences.getPthc(context)
            if (pthc.isNullOrBlank()) {
                pthc = System.currentTimeMillis().toString()
                AppPreferences.savePthc(context, pthc)
            }
            val pthb = pthc

            // pwd は入力優先。未入力なら保存済み or 新規生成
            val finalPwd = if (inputPwd.isNullOrBlank()) {
                val saved = AppPreferences.getPwd(context)
                if (saved.isNullOrBlank()) {
                    val gen = AppPreferences.generateNewPwd()
                    AppPreferences.savePwd(context, gen)
                    gen
                } else saved
            } else inputPwd

            val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)

            // 基本フォーム
            //bodyBuilder.addFormDataPart("mode", null, "regist".toShiftJISRequestBody())
            bodyBuilder.addFormDataPart("resto", null, resto.toShiftJISRequestBody())
            name?.takeIf { it.isNotEmpty() }?.let {
                bodyBuilder.addFormDataPart("name", null, it.toShiftJISRequestBody())
            }
            email?.takeIf { it.isNotEmpty() }?.let {
                bodyBuilder.addFormDataPart("email", null, it.toShiftJISRequestBody())
            }
            sub?.takeIf { it.isNotEmpty() }?.let {
                bodyBuilder.addFormDataPart("sub", null, it.toShiftJISRequestBody())
            }
            bodyBuilder.addFormDataPart("com", null, com.toShiftJISRequestBody())
            finalPwd?.let { bodyBuilder.addFormDataPart("pwd", null, it.toShiftJISRequestBody()) }

            // 既定 hidden
            bodyBuilder.addFormDataPart("pthc", null, pthc.toShiftJISRequestBody())
            bodyBuilder.addFormDataPart("pthb", null, pthb.toShiftJISRequestBody())
            bodyBuilder.addFormDataPart("hash", null, ensuredHash.toShiftJISRequestBody())

            // MAX_FILE_SIZE などは extra で最終上書きされることを想定（ここでは明示追加しない）

            // 添付/テキストのみ
            if (textOnly || upfileUri == null) {
                bodyBuilder.addFormDataPart("textonly", null, "on".toShiftJISRequestBody())
                // ✅ ブラウザ挙動に合わせて空の upfile も送る
                val empty = ByteArray(0).toRequestBody("application/octet-stream".toMediaTypeOrNull())
                bodyBuilder.addFormDataPart("upfile", "", empty)
            } else {
                val cr = context.contentResolver
                val fileName = guessFileName(cr, upfileUri)
                val mime = guessMimeType(cr, upfileUri)

                // ファイルサイズ検証（可能な場合）。上限は extra の MAX_FILE_SIZE を優先、既定は 8_192_000 bytes（約 8.2MB）。
                val maxBytes = extra["MAX_FILE_SIZE"]?.toLongOrNull() ?: 8_192_000L
                val contentLen = getContentLength(cr, upfileUri)
                if (contentLen != null && contentLen > maxBytes) {
                    throw IOException("添付ファイルが大きすぎます（${contentLen} > ${maxBytes} bytes）")
                }

                val fileRequest = streamingRequestBody(cr, upfileUri, mime, contentLen)
                bodyBuilder.addFormDataPart("upfile", fileName, fileRequest)
            }

            // extra を後勝ちで注入（hidden/token 上書き想定）
            extra.forEach { (k, v) ->
                bodyBuilder.addFormDataPart(k, null, v.toShiftJISRequestBody())
            }

            // ★ 最後に必ず固定：mode=regist（トークン内の mode を上書き）
            bodyBuilder.addFormDataPart("mode", null, "regist".toShiftJISRequestBody())

            val finalBody = bodyBuilder.build()
            //android.util.Log.d("ReplyRepo", "form extras: ${extra.keys}")

            // -----------------------------
            // Cookie 結合（WebView + OkHttpJar）
            // -----------------------------
            val cm = android.webkit.CookieManager.getInstance()
            val webViewCookie = cm.getCookie(threadPageUrl) ?: cm.getCookie(origin)

            val httpUrl = boardUrl.toHttpUrl()
            val jarCookies: List<Cookie> = runCatching {
                httpClient.cookieJar.loadForRequest(httpUrl)
            }.getOrElse { emptyList() }
            val jarCookie = jarCookies.joinToString("; ") { "${it.name}=${it.value}" }.ifBlank { null }

            fun parseCookieString(s: String?): Map<String, String> =
                s?.split(";")?.mapNotNull { segment ->
                    val trimmed = segment.trim()
                    if (trimmed.isEmpty()) return@mapNotNull null

                    val i = trimmed.indexOf('=')
                    if (i < 0) {
                        // '='がない場合は値なしのCookieとして扱う（空文字列値）
                        trimmed to ""
                    } else if (i == 0) {
                        // '='が先頭にある場合は無効なCookieとして無視
                        null
                    } else {
                        // 通常のkey=value形式
                        val key = trimmed.substring(0, i).trim()
                        val value = if (i + 1 < trimmed.length) {
                            trimmed.substring(i + 1).trim()
                        } else {
                            "" // '='の後に何もない場合は空文字列
                        }
                        if (key.isEmpty()) null else key to value
                    }
                }?.toMap() ?: emptyMap()

            // 同名キーは WebView を優先
            val merged = parseCookieString(jarCookie) + parseCookieString(webViewCookie)
            val mergedCookie = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifBlank { null }

            // UA を WebView / TokenProvider と合わせる（ptua 整合）
            val userAgent = Ua.STRING
            //android.util.Log.d("ReplyRepo", "ua=$userAgent")

            // ログ
            //android.util.Log.d("ReplyRepo", "origin=$origin")
            //android.util.Log.d("ReplyRepo", "referer=$threadPageUrl")
            //android.util.Log.d("ReplyRepo", "cookie.len=${mergedCookie?.length ?: 0} cookie.head=${mergedCookie?.take(120)}")
            //android.util.Log.d("ReplyRepo", "ua=$userAgent")

            // リクエスト
            val rb = Request.Builder()
                .url(ensureBoardPostUrl(boardUrl, context)) // 例: .../futaba.php[?guid=on]
                .header("Referer", threadPageUrl) // ブラウザ成功例と同じく res/*.htm
                .header("Origin", origin)         // ✅ 正しい Origin（パスなし）
                .header("User-Agent", Ua.STRING)
                .header("Accept", "*/*")
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .header("X-Requested-With", "XMLHttpRequest") // responsemode=ajax と相性◎
                .post(finalBody)
            if (!mergedCookie.isNullOrBlank()) rb.header("Cookie", mergedCookie)
            val req = rb.build()

            val call = httpClient.newCall(req)
            callTimeoutMs?.let { timeout ->
                if (timeout > 0L) {
                    call.timeout().timeout(timeout, TimeUnit.MILLISECONDS)
                }
            }

            call.executeAsync().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.bytes() ?: ByteArray(0)
                    val decoded = EncodingUtils.decode(raw, resp.header("Content-Type"))
                    throw IOException("HTTP ${resp.code} ${resp.message}\n$decoded")
                }

                val body = resp.body ?: throw IOException("Empty response body")
                val raw = body.bytes()
                val decoded = EncodingUtils.decode(raw, resp.header("Content-Type"))

                 val trimmed = decoded.trim()
                // 1) JSON なら thisno を抜いて返す（例: {"status":"ok","thisno":1345629398,...}）
                val jsonThisNo = Regex("""\"thisno\"\s*:\s*(\d{6,})""").find(trimmed)?.groupValues?.getOrNull(1)
                if (jsonThisNo != null) {
                    return@use "送信完了 No.$jsonThisNo"
                }

                // 2) HTML の「書きこみました/送信完了」系なら成功扱い。
                //    Futaba の成功ページは非常に短い（content-length ~ 80-120）ことが多い。
                if (!looksLikeError(trimmed)) {
                    // HTML 側からも番号らしきものが拾えれば返す（数字6桁以上が多い）
                    val htmlNo = Regex("""No\.?\s*(\d{6,})""").find(trimmed)?.groupValues?.getOrNull(1)
                    if (!htmlNo.isNullOrBlank()) {
                        return@use "送信完了 No.$htmlNo"
                    }
                    // 番号が見つからなくても成功として文言を返す
                    if (Regex("書きこみ|完了|送信完了").containsMatchIn(trimmed)) {
                        return@use "送信完了"
                    }
                }

                // それ以外は失敗扱い
                val head = if (trimmed.length > 200) trimmed.substring(0, 200) + "…" else trimmed
                throw IOException("投稿に失敗しました（サーバー側のIP制限やCookie認証が原因の可能性があります）: $head")
            }
        }
    }

    /**
     * スレHTMLから hash を抽出する（文字コードは `EncodingUtils` で推定して復号）。
     *
     * - HTML を取得して `form#fm`（なければ最初の `form`）から `input[name=hash]` を探します。
     * - 見つからない場合は失敗（`IllegalStateException`）として返します。
     */
    private suspend fun fetchHashFromThreadPage(threadUrl: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
            val req = Request.Builder()
                .url(threadUrl)
                .get()
                .header("User-Agent", Ua.STRING)
                .header("Accept", "*/*")
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .build()
                httpClient.newCall(req).executeAsync().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("thread load failed: ${resp.code} ${resp.message}")
                    }
                    val body = resp.body ?: throw IOException("Empty response body")
                    val html = body.byteStream().use { inputStream ->
                        val raw = inputStream.readBytes()
                        EncodingUtils.decode(raw, resp.header("Content-Type"))
                    }
                    val doc = Jsoup.parse(html, threadUrl)
                    val fm = doc.selectFirst("form#fm") ?: doc.selectFirst("form")
                    val hash = fm?.selectFirst("input[name=hash]")?.attr("value").orEmpty()
                    if (hash.isEmpty()) throw IllegalStateException("環境変数(hash)がスレッドページから取得できませんでした。IP制限やセッション切れの可能性があります。")
                    hash
                }
            }
        }

    /**
     * エラー判定の精度向上：HTMLタグを除去してからマッチング、文脈を考慮した判定
     */
    private fun looksLikeError(html: String): Boolean {
        // HTMLタグを除去してプレーンテキストで判定
        val plainText = try {
            org.jsoup.Jsoup.parse(html).text()
        } catch (e: Exception) {
            html.replace(Regex("<[^>]+>"), "")
        }

        // より正確なエラーパターン判定
        val errorPatterns = listOf(
            Regex("エラー.*発生", RegexOption.IGNORE_CASE),
            Regex("書.*込.*失敗|投稿.*失敗", RegexOption.IGNORE_CASE),
            Regex("連続.*投稿|連投", RegexOption.IGNORE_CASE),
            Regex("本文.*必要|本文.*なし", RegexOption.IGNORE_CASE),
            Regex("規制.*中|ブロック.*中", RegexOption.IGNORE_CASE),
            Regex("時間.*おいて|しばらく.*待", RegexOption.IGNORE_CASE),
            Regex("Cookie.*無効|セッション.*切れ", RegexOption.IGNORE_CASE)
        )

        // 成功を示すキーワードがある場合は成功として扱う
        val successPatterns = listOf(
            Regex("書.*込.*まし|送信.*完了|投稿.*完了", RegexOption.IGNORE_CASE),
            Regex("No\\.?\\s*\\d{6,}", RegexOption.IGNORE_CASE)
        )

        val hasSuccessPattern = successPatterns.any { it.containsMatchIn(plainText) }
        if (hasSuccessPattern) return false

        return errorPatterns.any { it.containsMatchIn(plainText) }
    }

    /**
     * HTML からエラーメッセージらしき文言を抽出して返します（最大 200 文字程度）。
     * フォールバックは汎用の失敗メッセージです。
     */
    private fun parseErrorMessage(html: String): String {
        return runCatching {
            val doc = Jsoup.parse(html)
            // よくある場所からエラー文言を拾う（板ごとに調整可能）
            val cand = doc.select("div,span,font,body").firstOrNull { it.text().contains("エラー") }
            (cand?.text()?.ifBlank { null })
                ?: doc.body()?.text()?.take(200)
                ?: "投稿に失敗しました"
        }.getOrDefault("投稿に失敗しました")
    }

    /**
     * 文字列を Shift_JIS として `RequestBody` に変換します。
     */
    private fun String.toShiftJISRequestBody(): RequestBody =
        this.toByteArray(Charset.forName("Shift_JIS"))
            .toRequestBody("text/plain; charset=Shift_JIS".toMediaTypeOrNull())

    /**
     * URI から適切な MIME Type を推測します（ContentResolver → 拡張子 → octet-stream）。
     */
    private fun guessMimeType(cr: ContentResolver, uri: Uri): String {
        val mime = cr.getType(uri)
        if (!mime.isNullOrBlank()) return mime
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        val fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return fromExt ?: "application/octet-stream"
    }

    /**
     * URI から送信用のファイル名を推測します（パス末尾のセグメント）。
     */
    private fun guessFileName(cr: ContentResolver, uri: Uri): String {
        // シンプルに末尾から取得（必要なら ContentResolver クエリに置換）
        val path = uri.lastPathSegment ?: "upload.bin"
        val idx = path.lastIndexOf('/')
        return if (idx >= 0 && idx + 1 < path.length) path.substring(idx + 1) else path
    }

    /**
     * 可能であれば URI のコンテンツ長を返す（不明な場合は null）。
     */
    private fun getContentLength(cr: ContentResolver, uri: Uri): Long? {
        return try {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val size = cursor.getLong(idx)
                    if (size >= 0) size else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * InputStream をそのまま Okio でシンクへ書き出すストリーミング RequestBody。
     * contentLength が不明な場合は -1 を返してチャンクド送信に委ねる。
     */
    private fun streamingRequestBody(
        cr: ContentResolver,
        uri: Uri,
        mime: String,
        contentLen: Long?
    ): RequestBody {
        val mediaType = mime.toMediaTypeOrNull()
        return object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength(): Long = contentLen ?: -1L
            override fun writeTo(sink: okio.BufferedSink) {
                cr.openInputStream(uri)?.use { input ->
                    val source = input.source()
                    sink.writeAll(source)
                } ?: throw IOException("添付ファイルの読み込みに失敗: $uri")
            }
        }
    }
}

/**
 * 板の投稿 URL に `guid=on` を必要に応じて付与して返す。
 *
 * - `futaba.php` 以外のパスが渡された場合は変更せずに返します。
 * - すでに `guid` パラメータがある場合はそのまま返します。
 * - `AppPreferences.getAppendGuidOn(context)` が真なら `guid=on` を付与します。
 */
private fun ensureBoardPostUrl(boardUrl: String, context: Context): String {
    return try {
        val url = boardUrl.toHttpUrl()
        if (!url.encodedPath.endsWith("/futaba.php")) return boardUrl // 期待外はそのまま
        // 既にguid指定があるなら尊重
        if (url.queryParameter("guid") != null) return boardUrl

        val appendGuid = AppPreferences.getAppendGuidOn(context)
        if (appendGuid) url.newBuilder().addQueryParameter("guid", "on").build().toString() else boardUrl
    } catch (_: Exception) {
        boardUrl
    }
}
