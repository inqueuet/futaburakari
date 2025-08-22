package com.example.hutaburakari

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.nio.charset.Charset

object NetworkClient {

    // NetworkModuleで定義された、アプリで唯一のOkHttpClientインスタンスを使用する
    private val httpClient = NetworkModule.okHttpClient

    /**
     * 指定されたURLからHTMLドキュメントを取得します。
     * OkHttpクライアントを使用するため、Cookieは自動的に管理されます。
     */
    suspend fun fetchDocument(url: String): Document {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTPエラー: ${response.code} ${response.message}")
                }
                // レスポンスボディをShift_JISでデコードする
                val responseBodyBytes = response.body!!.bytes()
                val decodedBody = String(responseBodyBytes, Charset.forName("Shift_JIS"))

                // Jsoupでパースして返す
                Jsoup.parse(decodedBody, url)
            }
        }
    }

    /**
     * 「そうだね」を送信します。
     * ホスト名はRefererから動的に取得します。
     */
    suspend fun postSodaNe(resNum: String, referer: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // RefererのURLからホスト名を動的に取得
                val host = referer.toHttpUrl().host
                val url = "https://${host}/sd.php?b.$resNum"

                val request = Request.Builder()
                    .url(url)
                    .header("Referer", referer)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    Log.d("NetworkClient", "postSodaNe: Response status code: ${response.code}")
                    response.isSuccessful
                }
            } catch (e: Exception) {
                Log.e("NetworkClient", "postSodaNeで例外発生", e)
                false
            }
        }
    }

    /**
     * カタログの設定をPOST送信します。
     */
    suspend fun applySettings(boardBaseUrl: String, settings: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val settingsUrl = "${boardBaseUrl}futaba.php?mode=catset"

            // POSTするフォームデータを作成
            val formBody = FormBody.Builder().apply {
                settings.forEach { (key, value) ->
                    add(key, value)
                }
            }.build()

            val request = Request.Builder()
                .url(settingsUrl)
                .post(formBody)
                .build()

            // レスポンスは特に使わないので、リクエストを投げるだけ
            httpClient.newCall(request).execute().use { response ->
                Log.d("NetworkClient", "applySettings: Response status code: ${response.code}")
            }
        }
    }

    /**
     * レス削除をPOSTします。
     * @param postUrl 例: https://<host>/<board>/futaba.php?guid=on
     * @param referer スレURL（削除対象レスがあるページ）
     * @param resNum  削除するレス番号（例: "123456789"）
     * @param pwd     削除キー（AppPreferencesから取得した値）
     * @return サーバが "OK"（2バイト/Shift_JIS）等を返しHTTP 200の場合 true
     */
    suspend fun deletePost(
        postUrl: String,
        referer: String,
        resNum: String,
        pwd: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val form = FormBody.Builder()
                    .add(resNum, "delete")
                    .add("responsemode", "ajax")
                    .add("pwd", pwd)
                    .add("onlyimgdel", "")   // 画像のみ削除しないなら空
                    .add("mode", "usrdel")
                    .build()

                val origin = "${referer.toHttpUrl().scheme}://${referer.toHttpUrl().host}"

                val req = Request.Builder()
                    .url(postUrl) // .../futaba.php?guid=on
                    .post(form)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", origin)
                    .header("Referer", referer)
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w("NetworkClient", "deletePost: HTTP ${resp.code}")
                        return@use false
                    }
                    // Futaba系は本文 "OK"（Shift_JISで2バイト）パターンあり
                    val bodyBytes = resp.body?.bytes() ?: return@use false
                    val okBySize = bodyBytes.size == 2
                    val okByText = runCatching {
                        String(bodyBytes, Charset.forName("Shift_JIS")).trim()
                            .equals("OK", ignoreCase = true)
                    }.getOrDefault(false)
                    okBySize || okByText
                }
            } catch (e: Exception) {
                Log.e("NetworkClient", "deletePostで例外発生", e)
                false
            }
        }
    }

}