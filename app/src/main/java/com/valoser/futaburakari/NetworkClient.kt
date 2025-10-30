package com.valoser.futaburakari

import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import okhttp3.coroutines.executeAsync
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.io.OutputStream

/**
 * Futaba系サイト向けのネットワーク操作をまとめたクライアント。
 *
 * - HTML取得（SJIS/UTF-8 自動判定）
 * - バイト列/Range取得、HEADでの Content-Length 取得
 * - 「そうだね」投票、カタログ設定反映、レス削除（通常/管理エンドポイント）
 * - OkHttpのCookieJarとWebViewのCookieを統合して送信
 */
class NetworkClient(
    private val httpClient: OkHttpClient,
) {

    // ===== Cookie ユーティリティ =====
    // "k=v; k2=v2" 形式のCookie文字列をMapへ分解
    private fun parseCookieString(s: String?): Map<String, String> {
        if (s.isNullOrBlank()) return emptyMap()

        return s.split(";").mapNotNull { segment ->
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) return@mapNotNull null

            val i = trimmed.indexOf('=')
            when {
                // '='がない、または先頭にある場合は無効なCookieとして無視
                i <= 0 -> {
                    Log.w("NetworkClient", "Invalid cookie format (missing or leading '='): $trimmed")
                    null
                }
                else -> {
                    // 通常のkey=value形式
                    val key = trimmed.substring(0, i).trim()
                    val value = if (i + 1 < trimmed.length) {
                        trimmed.substring(i + 1).trim()
                    } else {
                        "" // '='の後に何もない場合は空文字列
                    }

                    // キーの妥当性チェック（RFC 6265準拠）
                    when {
                        key.isEmpty() -> {
                            Log.w("NetworkClient", "Invalid cookie: empty key")
                            null
                        }
                        key.any { it.isWhitespace() || it in setOf(';', ',', '=', '"', '\\') } -> {
                            Log.w("NetworkClient", "Invalid cookie key (contains illegal characters): $key")
                            null
                        }
                        else -> key to value
                    }
                }
            }
        }.toMap()
    }

    // 複数ソースのCookie文字列を安全にマージ
    private fun mergeCookies(vararg cookieStrs: String?): String? {
        // セキュリティクリティカルなCookieキー（認証系）
        val criticalKeys = setOf("session", "sessionid", "auth", "token", "csrf", "xsrf", "jwt")

        val merged = mutableMapOf<String, String>()
        val criticalCookies = mutableMapOf<String, String>()

        // 各Cookie文字列を処理（左から右へ、後勝ち）
        cookieStrs.forEach { cookieStr ->
            val parsed = parseCookieString(cookieStr)
            parsed.forEach { (key, value) ->
                val normalizedKey = key.lowercase()
                if (criticalKeys.any { normalizedKey.contains(it) }) {
                    // クリティカルなCookieは別途管理
                    criticalCookies[key] = value
                } else {
                    // 通常のCookieは単純に後勝ち
                    merged[key] = value
                }
            }
        }

        // クリティカルなCookieを最後に追加（優先度を保証）
        merged.putAll(criticalCookies)

        return if (merged.isEmpty()) {
            null
        } else {
            merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }

    /**
     * 任意 URL の内容をストリーミングで `output` へコピーする。
     *
     * - メモリに全量を展開せずに転送するため、大きなファイルでも安全。
     * - `callTimeoutMs` 指定時はコール単位のタイムアウトを短縮可能。
     *
     * @param url 取得対象の URL
     * @param output 転送先のストリーム（呼び出し側で管理）
     * @param referer 参照元 URL（必要な場合のみヘッダ付与）
     * @param callTimeoutMs コール単位のタイムアウト（ミリ秒）。未指定なら既定を使用
     * @return 成功した場合は true、失敗時は false
     */
    suspend fun downloadTo(
        url: String,
        output: OutputStream,
        referer: String? = null,
        callTimeoutMs: Long? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", Ua.STRING)
            .header("Accept", "*/*")
            .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
            .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
            .build()
        return@withContext try {
            val call = if (callTimeoutMs != null) {
                // タイムアウト指定がある場合は専用クライアントを作成
                val timeoutClient = httpClient.newBuilder()
                    .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                    .build()
                timeoutClient.newCall(req)
            } else {
                // デフォルトクライアントを使用
                httpClient.newCall(req)
            }
            call.executeAsync().use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body ?: return@use false
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
                true
            }
        } catch (e: Exception) {
            Log.w("NetworkClient", "Download failed for URL: $url", e)
            false
        }
    }

    /**
     * HTML を取得し、レスポンスの Content-Type 等からエンコードを推定して `Jsoup Document` に変換する。
     *
     * - SJIS/UTF-8 の自動判定を行い、文字化けを抑制。
     *
     * @param url 取得対象の URL
     * @throws IOException HTTP エラー時
     * @return 解析済みの `Document`
     */
    suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Ua.STRING)
            .header("Accept", "*/*")
            .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
            .build()

        httpClient.newCall(req).executeAsync().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTPエラー: ${resp.code} ${resp.message}")
            }
            val body = resp.body ?: throw IOException("レスポンスボディが空です")
            val bytes = body.bytes()
            val decoded = EncodingUtils.decode(bytes, resp.header("Content-Type"))
            Jsoup.parse(decoded, url)
        }
    }

    /**
     * 任意 URL のバイト列を取得する。
     *
     * @param url 取得対象の URL
     * @return 取得したバイト配列。失敗時は null
     */
    suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Ua.STRING)
            .header("Accept", "*/*")
            .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
            .build()
        return@withContext try {
            httpClient.newCall(req).executeAsync().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w("NetworkClient", "Failed to fetch bytes for URL: $url", e)
            null
        }
    }

    /**
     * HEAD リクエストで `Content-Length` を取得する。
     *
     * @param url 対象の URL
     * @return バイト長。取得できない場合は null
     */
    suspend fun headContentLength(url: String): Long? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", Ua.STRING)
            .header("Accept", "*/*")
            .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
            .build()
        return@withContext try {
            httpClient.newCall(req).executeAsync().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.header("Content-Length")?.toLongOrNull()
            }
        } catch (e: Exception) {
            Log.w("NetworkClient", "Failed to get content length for URL: $url", e)
            null
        }
    }

    /**
     * Range GET で指定範囲を部分取得する。
     *
     * - サーバが Range を無視して 200 を返す場合はクライアント側で手動スライス。
     * - 最大 2MB まで読み取り、先頭範囲の検査などに利用。
     *
     * @param url 取得対象の URL
     * @param start 開始オフセット（バイト）
     * @param length 取得長（バイト）。負や 0 の場合は末尾までを要求し、実際の読み込みは最大 2MB まで
     * @param referer 参照元 URL（必要な場合のみヘッダ付与）
     * @param callTimeoutMs コール単位のタイムアウト（ミリ秒）
     * @return 取得したバイト配列。失敗時は null
     */
    suspend fun fetchRange(
        url: String,
        start: Long,
        length: Long,
        referer: String? = null,
        callTimeoutMs: Long? = null,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val end = if (length > 0) start + length - 1 else null
        val rangeValue = if (end != null) "bytes=$start-$end" else "bytes=$start-"
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Range", rangeValue)
            .header("User-Agent", Ua.STRING)
            .header("Accept", "*/*")
            .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
            .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
            .build()
        return@withContext try {
            val call = if (callTimeoutMs != null) {
                // タイムアウト指定がある場合は専用クライアントを作成
                val timeoutClient = httpClient.newBuilder()
                    .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                    .build()
                timeoutClient.newCall(req)
            } else {
                // デフォルトクライアントを使用
                httpClient.newCall(req)
            }
            call.executeAsync().use { resp ->
                if (!resp.isSuccessful) {
                    return@use null
                }
                val code = resp.code
                val body = resp.body ?: return@use null

                // Content-Lengthをチェックして無駄な取得を避ける
                val contentLength = resp.header("Content-Length")?.toLongOrNull()
                val maxToRead = if (length > 0) length.coerceAtMost(2L * 1024 * 1024L) else 2L * 1024 * 1024L
                if (contentLength != null && contentLength > maxToRead && code != 200) {
                    return@use null
                }

                val bytes = body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    var remaining = maxToRead
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, buffer.size.coerceAtMost(remaining.toInt()))
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                    out.toByteArray()
                }
                if (code == 200 && start > 0) {
                    // サーバがRangeを無視して200を返したケース：クライアント側でスライス
                    if (start >= bytes.size) return@use null
                    val from = start.toInt()
                    val to = if (length > 0) (from + length.toInt()).coerceAtMost(bytes.size) else bytes.size
                    return@use bytes.copyOfRange(from, to)
                }
                return@use bytes
            }
        } catch (e: Exception) {
            Log.w("NetworkClient", "Failed to fetch range for URL: $url", e)
            null
        }
    }

    /**
     * レス番号に対して「そうだね」を送信する。
     *
     * - 失敗時は Referer ページの取得後、短い遅延を置いて再試行。
     *
     * @param resNum 対象レス番号
     * @param referer 操作元のスレッド URL
     * @return サーバ応答の数値（例: 件数）。失敗時は null
     */
    suspend fun postSodaNe(resNum: String, referer: String): Int? = withContext(Dispatchers.IO) {
        val refUrl = referer.toHttpUrl()
        val board = refUrl.pathSegments.firstOrNull() ?: return@withContext null
        val origin = "${refUrl.scheme}://${refUrl.host}"
        val sdUrl = "$origin/sd.php?$board.$resNum"

            // use ブロックの評価値（件数等の応答数値）をそのまま返す設計に統一
        suspend fun once(): Int? {
            val jarCookies: List<Cookie> = runCatching { httpClient.cookieJar.loadForRequest(sdUrl.toHttpUrl()) }
                .getOrElse { emptyList() }
            val jarCookie = jarCookies.joinToString("; ") { "${it.name}=${it.value}" }.ifBlank { null }

            // CookieManager は Looper が必要なのでメインスレッドで実行
            val (webCookieRef, webCookieOrg) = withContext(kotlinx.coroutines.Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                val ref = cm.getCookie(referer)
                val org = cm.getCookie(origin)
                ref to org
            }
            val mergedCookie = mergeCookies(jarCookie, webCookieOrg, webCookieRef)

            val req = Request.Builder()
                .url(sdUrl)
                .get()
                .header("User-Agent", Ua.STRING)
                .header("Referer", referer)
                .header("Accept", "*/*")
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("X-Requested-With", "XMLHttpRequest")
                .apply { if (!mergedCookie.isNullOrBlank()) header("Cookie", mergedCookie) }
                .build()

            // use の戻り値（件数等の応答数値）をそのまま返す
            return httpClient.newCall(req).executeAsync().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.bytes() ?: return@use null
                val text = EncodingUtils.decode(raw, resp.header("Content-Type")).trim()
                text.toIntOrNull()
            }
        }

        val first = once()
        if (first != null) return@withContext first

        runCatching { fetchDocument(referer) }
        delay(1000L)
        return@withContext once()
    }

    /**
     * カタログ設定を POST で適用する。
     *
     * @param boardBaseUrl 板名のベース URL。末尾は `/` など `futaba.php` の手前までを指定し、この関数側で `futaba.php?mode=catset` を付加します。
     * @param settings `catset` に渡すパラメータ群
     */
    suspend fun applySettings(boardBaseUrl: String, settings: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val settingsUrl = "${boardBaseUrl}futaba.php?mode=catset"
            val formBody = FormBody.Builder().apply {
                settings.forEach { (k, v) -> add(k, v) }
            }.build()

            val req = Request.Builder()
                .url(settingsUrl)
                .post(formBody)
                .header("User-Agent", Ua.STRING)
                .header("Accept", "*/*")
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .build()

            httpClient.newCall(req).executeAsync().use { resp ->
                Log.d("NetworkClient", "applySettings: HTTP ${resp.code}")
            }
        }
    }

    /**
     * 通常の `usrdel` エンドポイントでレス削除を行う。
     *
     * - 画像のみ削除にも対応（`onlyImage=true`）。
     *
     * @param postUrl `usrdel` POST 先の URL
     * @param referer 操作元のスレッド URL
     * @param resNum 対象レス番号
     * @param pwd 削除用パスワード
     * @param onlyImage 画像のみ削除フラグ
     * @return 成功時 true、失敗時 false
     */
    suspend fun deletePost(
        postUrl: String,
        referer: String,
        resNum: String,
        pwd: String,
        onlyImage: Boolean = false,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val formBuilder = FormBody.Builder()
                .add(resNum, "delete")
                .add("responsemode", "ajax")
                .add("pwd", pwd)
                .add("mode", "usrdel")
            if (onlyImage) {
                // Futaba仕様: 画像のみ削除の場合のフラグ
                formBuilder.add("onlyimgdel", "on")
            }
            val form = formBuilder.build()

            val ref = referer.toHttpUrl()
            val origin = "${ref.scheme}://${ref.host}"

            val req = Request.Builder()
                .url(postUrl)
                .post(form)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", origin)
                .header("Referer", referer)
                .header("User-Agent", Ua.STRING)
                .header("Accept", "*/*")
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .build()

            return@withContext httpClient.newCall(req).executeAsync().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("NetworkClient", "deletePost: HTTP ${resp.code}")
                    return@use false
                }
                val body = resp.body?.bytes() ?: return@use false
                val okBySize = body.size == 2
                val okByText = runCatching {
                    EncodingUtils.decode(body, resp.header("Content-Type")).trim().equals("OK", true)
                }.getOrDefault(false)
                okBySize || okByText
            }
        } catch (e: Exception) {
            Log.e("NetworkClient", "deletePostで例外発生", e)
            return@withContext false
        }
    }

    /**
     * `del.php` 経由での削除（管理用エンドポイント想定）を行う。
     *
     * 例）参照中: `https://may.2chan.net/b/res/1347318913.htm`
     * 送信先: `POST https://may.2chan.net/del.php`
     * body: `mode=post&b=b&d=1347319371&reason=110&responsemode=ajax`
     *
     * @param threadUrl 参照しているスレッド URL
     * @param targetResNum 対象レス番号
     * @param reason 理由コード（既定: 110）
     * @return 成功時 true、失敗時 false
     */
    suspend fun deleteViaDelPhp(
        threadUrl: String,
        targetResNum: String,
        reason: String = "110",
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val refUrl = threadUrl.toHttpUrl()
            val origin = "${refUrl.scheme}://${refUrl.host}"
            val board = refUrl.pathSegments.firstOrNull() ?: return@withContext false
            val endpoint = "$origin/del.php"

            // Cookie: OkHttpのJar + WebViewのCookie を統合
            val jarCookies: List<Cookie> = runCatching { httpClient.cookieJar.loadForRequest(endpoint.toHttpUrl()) }
                .getOrElse { emptyList() }
            val jarCookie = jarCookies.joinToString("; ") { "${it.name}=${it.value}" }.ifBlank { null }

            // CookieManager は Looper が必要なのでメインスレッドで実行
            val (webCookieRef, webCookieOrg) = withContext(kotlinx.coroutines.Dispatchers.Main) {
                val cm = CookieManager.getInstance()
                val ref = cm.getCookie(threadUrl)
                val org = cm.getCookie(origin)
                ref to org
            }
            val mergedCookie = mergeCookies(jarCookie, webCookieOrg, webCookieRef)

            val form = FormBody.Builder()
                .add("mode", "post")
                .add("b", board)
                .add("d", targetResNum)
                .add("reason", reason)
                .add("responsemode", "ajax")
                .build()

            val req = Request.Builder()
                .url(endpoint)
                .post(form)
                .header("User-Agent", Ua.STRING)
                .header("Accept", "*/*")
                .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", origin)
                .header("Referer", threadUrl)
                .apply { if (!mergedCookie.isNullOrBlank()) header("Cookie", mergedCookie) }
                .build()

            return@withContext httpClient.newCall(req).executeAsync().use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body?.bytes() ?: return@use false
                val okBySize = body.size == 2
                val okByText = runCatching {
                    EncodingUtils.decode(body, resp.header("Content-Type")).trim().equals("OK", true)
                }.getOrDefault(false)
                okBySize || okByText
            }
        } catch (e: Exception) {
            Log.e("NetworkClient", "deleteViaDelPhp で例外発生", e)
            return@withContext false
        }
    }
}
