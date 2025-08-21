package com.example.hutaburakari

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
            val origin = baseBoardUrl.removeSuffix("/")

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
            bodyBuilder.addFormDataPart("mode", null, "regist".toShiftJISRequestBody())
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

            val finalBody = bodyBuilder.build()
            val req = Request.Builder()
                .url(boardUrl)
                .header("Referer", threadPageUrl)
                .header("Origin", origin)
                .post(finalBody)
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    val msg = resp.message
                    val raw = resp.body?.bytes() ?: ByteArray(0)
                    // Futaba は SJIS HTML を返すので内容も見ておく
                    val decoded = try {
                        String(raw, Charset.forName("Shift_JIS"))
                    } catch (_: Exception) {
                        raw.toString()
                    }
                    throw IOException("HTTP ${code} ${msg}\n$decoded")
                }
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                // 成功時も HTML のことが多いので、エラー文言が無いか軽く見る
                val html = String(bytes, Charset.forName("Shift_JIS"))
                if (looksLikeError(html)) {
                    throw IOException(parseErrorMessage(html))
                }
                html
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
        val lowered = html.lowercase()
        // 代表的な失敗キーワードを簡易判定（必要に応じて追加）
        return listOf(
            "エラー", "error", "連投", "本文なし", "不正", "ブロック", "拒否", "失敗"
        ).any { lowered.contains(it.lowercase()) }
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