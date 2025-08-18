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
}