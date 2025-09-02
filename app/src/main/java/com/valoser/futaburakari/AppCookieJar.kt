package com.valoser.futaburakari

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

object AppCookieJar : CookieJar {
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val currentCookies = cookieStore.getOrPut(host) { mutableListOf() }

        cookies.forEach { newCookie ->
            currentCookies.removeAll { it.name == newCookie.name }
        }
        currentCookies.addAll(cookies.filter { it.expiresAt > System.currentTimeMillis() || it.persistent })

        Log.d("AppCookieJar", "Saved cookies for $host: ${cookieStore[host]?.map { it.name + "=" + it.value }}")

        val webViewCookieManager = android.webkit.CookieManager.getInstance()
        cookies.forEach { cookie ->
            val cookieString = cookie.toString()
            webViewCookieManager.setCookie(url.toString(), cookieString)
            Log.d("AppCookieJar", "Set to WebView CookieManager: $cookieString for url $url")
        }
        webViewCookieManager.flush()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val storedCookies = cookieStore[host]?.toMutableList() ?: mutableListOf()

        val iterator = storedCookies.iterator()
        while (iterator.hasNext()) {
            val cookie = iterator.next()
            if (cookie.expiresAt <= System.currentTimeMillis() && !cookie.persistent) {
                iterator.remove()
                Log.d("AppCookieJar", "Removed expired cookie for $host: ${cookie.name}")
            }
        }
        Log.d("AppCookieJar", "Loading cookies for $host (found ${storedCookies.size}): ${storedCookies.map { it.name + "=" + it.value }}")
        return storedCookies
    }

    @Synchronized
    fun clearAllCookies() {
        cookieStore.clear()
        Log.d("AppCookieJar", "All cookies cleared.")
    }

    @Synchronized
    fun clearCookiesForHost(host: String) {
        cookieStore.remove(host)
        Log.d("AppCookieJar", "Cookies cleared for host: $host")
    }
}