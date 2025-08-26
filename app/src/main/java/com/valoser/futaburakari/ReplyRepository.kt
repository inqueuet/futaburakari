package com.valoser.futaburakari

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
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
import org.jsoup.Jsoup
import java.io.IOException
import java.nio.charset.Charset

/**
 * Futaba への投稿を担当するリポジトリ。
 * 既定の必須フィールドに加えて、JS依存で得た hidden/token を extra で上書き注入できるようにしている。
 */
class ReplyRepository(
    private val httpClient: OkHttpClient = NetworkModule.okHttpClient,
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
        extra: Map<String, String> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // ベースURL（Origin 用）とスレURL（Referer 用）を導出
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
                throw IOException("hash の取得に失敗: ${it.message}")
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
                val fileName = guessFileName(context.contentResolver, upfileUri)
                val mime = guessMimeType(context.contentResolver, upfileUri)
                val fileRequest = context.contentResolver.openInputStream(upfileUri)?.use { input ->
                    input.readBytes().toRequestBody(mime.toMediaTypeOrNull())
                } ?: throw IOException("添付ファイルの読み込みに失敗: $upfileUri")
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
                s?.split(";")?.mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
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
                .url(boardUrl)                    // 例: .../futaba.php?guid=on
                .header("Referer", threadPageUrl) // ブラウザ成功例と同じく res/*.htm
                .header("Origin", origin)         // ✅ 正しい Origin（パスなし）
                .header("User-Agent", Ua.STRING)
                .header("Accept", "*/*")
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .header("X-Requested-With", "XMLHttpRequest") // responsemode=ajax と相性◎
                .post(finalBody)
            if (!mergedCookie.isNullOrBlank()) rb.header("Cookie", mergedCookie)
            val req = rb.build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.bytes() ?: ByteArray(0)
                // Futaba は Shift_JIS な短文HTML or JSON を返す
                val sjis = try { String(raw, Charset.forName("Shift_JIS")) } catch (_: Exception) { String(raw) }
                //android.util.Log.d("ReplyRepo", "resp.head=${sjis.trim().take(200)}")
                if (!resp.isSuccessful) {
                    //android.util.Log.w("ReplyRepo", "HTTP ${resp.code} ${resp.message}")
                    throw IOException("HTTP ${resp.code} ${resp.message}\n$sjis")
                }

                 val trimmed = sjis.trim()
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
                throw IOException("投稿に失敗しました: $head")
            }
        }
    }

    /**
     * スレHTMLから hash を抽出する（Shift_JIS）。
     */
    private suspend fun fetchHashFromThreadPage(threadUrl: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(threadUrl).get().build()
                NetworkModule.okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("thread load failed: ${resp.code} ${resp.message}")
                    }
                    val body = resp.body?.bytes() ?: ByteArray(0)
                    val html = String(body, Charset.forName("Shift_JIS"))
                    val doc = Jsoup.parse(html, threadUrl)
                    val fm = doc.selectFirst("form#fm") ?: doc.selectFirst("form")
                    val hash = fm?.selectFirst("input[name=hash]")?.attr("value").orEmpty()
                    if (hash.isEmpty()) throw IllegalStateException("hash not found in thread page")
                    hash
                }
            }
        }

    private fun looksLikeError(html: String): Boolean {
        val t = html
        // 代表的な失敗キーワードを簡易判定（必要に応じて追加）
        val words = listOf(
            "エラー", "error", "連投", "本文なし", "不正", "ブロック", "拒否", "失敗",
            "NG", "荒らし", "規制", "拒絶", "同一内容", "時間をおいて", "Cookie", "IP", "環境変数"
        )
        return words.any { t.contains(it, ignoreCase = true) }
    }

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

    private fun String.toShiftJISRequestBody(): RequestBody =
        this.toByteArray(Charset.forName("Shift_JIS"))
            .toRequestBody("text/plain; charset=Shift_JIS".toMediaTypeOrNull())

    private fun guessMimeType(cr: ContentResolver, uri: Uri): String {
        val mime = cr.getType(uri)
        if (!mime.isNullOrBlank()) return mime
        val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
        val fromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return fromExt ?: "application/octet-stream"
    }

    private fun guessFileName(cr: ContentResolver, uri: Uri): String {
        // シンプルに末尾から取得（必要なら ContentResolver クエリに置換）
        val path = uri.lastPathSegment ?: "upload.bin"
        val idx = path.lastIndexOf('/')
        return if (idx >= 0 && idx + 1 < path.length) path.substring(idx + 1) else path
    }
}
