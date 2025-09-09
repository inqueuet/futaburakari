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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException

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
    private fun parseCookieString(s: String?): Map<String, String> =
        s?.split(";")?.mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }?.toMap() ?: emptyMap()

    // 複数ソースのCookie文字列をマージ（同名は後勝ち）
    private fun mergeCookies(vararg cookieStrs: String?): String? {
        val merged = cookieStrs.fold(emptyMap<String, String>()) { acc, s -> acc + parseCookieString(s) }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifBlank { null }
    }

    // ===== HTML GET（SJIS/UTF-8 自動判定） =====
    // HTMLを取得し、レスポンスのContent-Type等からエンコードを推定してJsoup Documentに変換
    suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Ua.STRING)
            .header("Accept", "*/*")
            .header("Accept-Language", "ja,en-US;q=0.9,en;q=0.8")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTPエラー: ${resp.code} ${resp.message}")
            }
            val bytes = resp.body!!.bytes()
            val decoded = EncodingUtils.decode(bytes, resp.header("Content-Type"))
            Jsoup.parse(decoded, url)
        }
    }

    // 任意URLのバイト列を取得（失敗時はnull）
    suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", Ua.STRING)
            .build()
        return@withContext try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    // HEADでContent-Lengthを取得（失敗時はnull）
    suspend fun headContentLength(url: String): Long? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", Ua.STRING)
            .build()
        return@withContext try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.header("Content-Length")?.toLongOrNull()
            }
        } catch (_: Exception) {
            null
        }
    }

    // Range GET で部分取得（サーバが200を返した場合は手動でスライス）。
    // 最大2MBまで読み取り、必要十分な先頭範囲の取得に利用。
    suspend fun fetchRange(url: String, start: Long, length: Long): ByteArray? = withContext(Dispatchers.IO) {
        val end = if (length > 0) start + length - 1 else null
        val rangeValue = if (end != null) "bytes=$start-$end" else "bytes=$start-"
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Range", rangeValue)
            .header("User-Agent", Ua.STRING)
            .build()
        return@withContext try {
            httpClient.newCall(req).execute().use { resp ->
                val code = resp.code
                val body = resp.body ?: return@use null
                val maxToRead = if (length > 0) length.coerceAtMost(2L * 1024 * 1024L) else 2L * 1024 * 1024L
                val bytes = body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
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
        } catch (_: Exception) {
            null
        }
    }

    // ===== そうだね =====
    // レス番号(resNum)に対して「そうだね」を送信。必要に応じてRefererのページ取得後に再試行。
    suspend fun postSodaNe(resNum: String, referer: String): Int? = withContext(Dispatchers.IO) {
        val refUrl = referer.toHttpUrl()
        val board = refUrl.pathSegments.firstOrNull() ?: return@withContext null
        val origin = "${refUrl.scheme}://${refUrl.host}"
        val sdUrl = "$origin/sd.php?$board.$resNum"

        // ★ ここを“必ず戻り値を返す式”に修正
        suspend fun once(): Int? {
            val jarCookies: List<Cookie> = runCatching { httpClient.cookieJar.loadForRequest(sdUrl.toHttpUrl()) }
                .getOrElse { emptyList() }
            val jarCookie = jarCookies.joinToString("; ") { "${it.name}=${it.value}" }.ifBlank { null }

            val cm = CookieManager.getInstance()
            val webCookieRef = cm.getCookie(referer)
            val webCookieOrg = cm.getCookie(origin)
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
            return httpClient.newCall(req).execute().use { resp ->
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

    // ===== カタログ設定 =====
    // 板名URL（末尾は futaba.php までのベース）に対して catset パラメータをPOSTして適用
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

            httpClient.newCall(req).execute().use { resp ->
                Log.d("NetworkClient", "applySettings: HTTP ${resp.code}")
            }
        }
    }

    // ===== レス削除 =====
    // 通常の usrdel（ユーザ削除）エンドポイント。画像のみ削除フラグにも対応。
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

            return@withContext httpClient.newCall(req).execute().use { resp ->
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

    // ===== del.php 経由の削除（管理用エンドポイント想定） =====
    // 例: https://may.2chan.net/b/res/1347318913.htm を参照中 →
    //     POST https://may.2chan.net/del.php
    //     body: mode=post&b=b&d=1347319371&reason=110&responsemode=ajax
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
            val cm = CookieManager.getInstance()
            val webCookieRef = cm.getCookie(threadUrl)
            val webCookieOrg = cm.getCookie(origin)
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

            return@withContext httpClient.newCall(req).execute().use { resp ->
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
