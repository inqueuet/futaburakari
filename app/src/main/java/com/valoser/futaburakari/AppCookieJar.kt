package com.valoser.futaburakari

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * メモリ上のみで動作する簡易 CookieJar 実装。
 *
 * 特徴と注意点（実装に合わせた正確な説明）:
 * - ホスト単位で Cookie を保持（サブドメイン継承は考慮しない）
 * - 同一ホスト内では「名前が同じ Cookie」をパス/ドメインに関係なく上書き
 * - 返却時もホストが完全一致するものだけを返し、期限切れはその場で破棄する（ドメイン/パス/セキュア属性の検証はしない）
 * - WebView の CookieManager にも同期（Cookie#toString() で name=value 形式にして setCookie し、flush する）
 * - 永続化は行わない（プロセス終了で破棄）
 */
object AppCookieJar : CookieJar {
    // ホスト名をキーに Cookie を保持するメモリ上のストア
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    /**
     * レスポンスから受け取った Cookie をホスト単位で保存し、WebView にも同期する。
     *
     * 実装上の仕様:
     * - 既存の同名 Cookie を「パスやドメインを無視して」除去してから追加
     * - 追加対象は「期限が未来」の Cookie のみ
     * - OkHttp の Cookie オブジェクトから name=value 文字列を生成して WebView に setCookie し、flush する
     */
    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val currentCookies = cookieStore.getOrPut(host) { mutableListOf() }

        cookies.forEach { newCookie ->
            currentCookies.removeAll { it.name == newCookie.name }
        }
        currentCookies.addAll(cookies.filter { it.expiresAt > System.currentTimeMillis() })

        Log.d("AppCookieJar", "Saved cookies for $host: ${cookieStore[host]?.map { it.name + "=" + it.value }}")

        val webViewCookieManager = android.webkit.CookieManager.getInstance()
        // タイムアウト付きでWebViewのCookieManager同期を試行
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                cookies.forEach { cookie ->
                    val cookieString = cookie.toString()
                    webViewCookieManager.setCookie(url.toString(), cookieString)
                    Log.d("AppCookieJar", "Set to WebView CookieManager: $cookieString for url $url")
                }
                webViewCookieManager.flush()
            } catch (e: Exception) {
                // WebViewへのCookie設定失敗時のエラーログ
                // WebViewが初期化されていない、またはメモリ不足などの可能性がある
                Log.w("AppCookieJar", "Failed to sync cookies to WebView for $url: ${e.message}", e)
            }
        }, 0)
    }

    /**
     * リクエスト送出前に付与する Cookie のリストを返す。
     *
     * 実装上の仕様:
     * - 保持しているのは「要求ホストと完全一致」するエントリのみ（サブドメイン/ドメイン一致はしない）
     * - 期限切れの Cookie（expiresAt<=now）は削除される
     * - ドメイン/パス/セキュア属性のチェックは行わない
     */
    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val storedCookies = cookieStore[host]?.toMutableList() ?: mutableListOf()

        val iterator = storedCookies.iterator()
        var removedAny = false
        while (iterator.hasNext()) {
            val cookie = iterator.next()
            if (cookie.expiresAt <= System.currentTimeMillis()) {
                iterator.remove()
                removedAny = true
                Log.d("AppCookieJar", "Removed expired cookie for $host: ${cookie.name}")
            }
        }

        // 期限切れCookieを削除した場合、cookieStoreを更新
        if (removedAny) {
            if (storedCookies.isEmpty()) {
                cookieStore.remove(host)
            } else {
                cookieStore[host] = storedCookies
            }
        }

        Log.d("AppCookieJar", "Loading cookies for $host (found ${storedCookies.size}): ${storedCookies.map { it.name + "=" + it.value }}")
        return storedCookies
    }

    /**
     * すべての Cookie をメモリから破棄する（WebView 側は操作しない）。
     */
    @Synchronized
    fun clearAllCookies() {
        cookieStore.clear()
        Log.d("AppCookieJar", "All cookies cleared.")
    }

    /**
     * 指定ホストの Cookie をメモリから破棄する（WebView 側は操作しない）。
     */
    @Synchronized
    fun clearCookiesForHost(host: String) {
        cookieStore.remove(host)
        Log.d("AppCookieJar", "Cookies cleared for host: $host")
    }
}
