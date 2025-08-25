package com.valoser.hutaburakari

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
import java.nio.charset.Charset

object NetworkClient {

    private val httpClient: OkHttpClient = NetworkModule.okHttpClient

    // ===== Cookie ユーティリティ =====
    private fun parseCookieString(s: String?): Map<String, String> =
        s?.split(";")?.mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }?.toMap() ?: emptyMap()

    private fun mergeCookies(vararg cookieStrs: String?): String? {
        val merged = cookieStrs.fold(emptyMap<String, String>()) { acc, s -> acc + parseCookieString(s) }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }.ifBlank { null }
    }

    // ===== HTML GET（Shift_JIS） =====
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
            val decoded = String(bytes, Charset.forName("Shift_JIS"))
            Jsoup.parse(decoded, url)
        }
    }

    // ===== そうだね =====
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

            // use の“戻り値”をそのまま返す
            return httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.bytes() ?: return@use null
                val text = try {
                    String(raw, Charset.forName("UTF-8")).trim()
                } catch (_: Exception) {
                    String(raw).trim()
                }
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
    suspend fun deletePost(
        postUrl: String,
        referer: String,
        resNum: String,
        pwd: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val form = FormBody.Builder()
                .add(resNum, "delete")
                .add("responsemode", "ajax")
                .add("pwd", pwd)
                .add("onlyimgdel", "")
                .add("mode", "usrdel")
                .build()

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
                    String(body, Charset.forName("Shift_JIS")).trim().equals("OK", true)
                }.getOrDefault(false)
                okBySize || okByText
            }
        } catch (e: Exception) {
            Log.e("NetworkClient", "deletePostで例外発生", e)
            return@withContext false
        }
    }
}