package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

object PersistentCookieJar : CookieJar {

    private const val PREFS_NAME = "CookiePrefs"
    private const val COOKIES_KEY_PREFIX = "cookies_for_domain_"

    private lateinit var sharedPreferences: SharedPreferences
    private val gson = Gson()

    private val cookieStore = ConcurrentHashMap<String, MutableList<SerializableCookie>>()

    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadCookiesFromPrefs()
            isInitialized = true
            Log.d("PersistentCookieJar", "Initialized and cookies loaded from SharedPreferences.")
        }
    }

    private fun ensureInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("PersistentCookieJar has not been initialized. Call init() first.")
        }
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        ensureInitialized()
        val requestHost = url.host
        val now = System.currentTimeMillis()

        cookies.forEach { cookie ->
            val hostOnly = cookie.hostOnly
            val baseDomain = if (hostOnly) requestHost else cookie.domain
            val effectiveDomain = normalizeDomain(baseDomain.ifEmpty { requestHost })

            val storedCookiesForDomain = cookieStore.getOrPut(effectiveDomain) { mutableListOf() }
            storedCookiesForDomain.removeAll { it.name == cookie.name && it.path == cookie.path }

            if (cookie.persistent) {
                if (cookie.expiresAt > now) {
                    storedCookiesForDomain.add(SerializableCookie.fromOkHttpCookie(cookie))
                } else {
                    // expired persistent cookie: do not add
                }
            } else {
                // session cookie: keep in memory only
                storedCookiesForDomain.add(SerializableCookie.fromOkHttpCookie(cookie))
            }
        }
        saveCookiesToPrefs()

        // WebViewへのCookie同期 (UIスレッドで実行)
        try {
            val cm = android.webkit.CookieManager.getInstance()
            val cookieStrings = cookies.map { it.toString() }
            Handler(Looper.getMainLooper()).post {
                try {
                    cookieStrings.forEach { cs -> cm.setCookie(url.toString(), cs) }
                    cm.flush()
                } catch (e: Exception) {
                    Log.e("PersistentCookieJar", "Error synchronizing cookies to WebView", e)
                }
            }
        } catch (_: Exception) { /* ignore */ }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureInitialized()
        val requestHost = url.host
        val matchingCookies = mutableListOf<Cookie>()
        val now = System.currentTimeMillis()

        var removedAnyPersistent = false

        cookieStore.forEach { (cookieDomain, storedCookiesList) ->
            val iterator = storedCookiesList.iterator()
            while (iterator.hasNext()) {
                val sc = iterator.next()
                // Remove expired persistent cookies
                if (sc.persistent && sc.expiresAt <= now) {
                    iterator.remove()
                    removedAnyPersistent = true
                    continue
                }
                // Domain and path checks per cookie
                if (domainMatches(sc.domain, sc.hostOnly, requestHost) && pathMatches(sc.path, url.encodedPath)) {
                    if (sc.secure && !url.isHttps) {
                        // skip secure cookie over http
                    } else {
                        matchingCookies.add(sc.toOkHttpCookie())
                    }
                }
            }
        }
        if (removedAnyPersistent) {
            saveCookiesToPrefs()
        }
        return matchingCookies
    }

    @Synchronized
    fun clearAllCookies() {
        ensureInitialized()
        cookieStore.clear()
        sharedPreferences.edit().clear().apply()
        Log.d("PersistentCookieJar", "All cookies cleared from memory and SharedPreferences.")
    }

    @Synchronized
    fun clearCookiesForHost(host: String) {
        ensureInitialized()
        val domainsToRemove = mutableListOf<String>()
        val normalizedHost = normalizeDomain(host)
        cookieStore.keys.forEach { domain ->
            val d = normalizeDomain(domain)
            if (normalizedHost == d || normalizedHost.endsWith(".$d")) {
                domainsToRemove.add(domain)
            }
        }
        domainsToRemove.forEach { domain ->
            cookieStore.remove(domain)
            sharedPreferences.edit().remove(COOKIES_KEY_PREFIX + domain).apply()
            Log.d("PersistentCookieJar", "Cleared cookies for domain pattern: $domain (related to host $host)")
        }
        // Ensure changes are persisted if a domain was actually removed
        if (domainsToRemove.isNotEmpty()) {
            // saveCookiesToPrefs() // cookieStore is already modified, this might save an empty list for removed domains.
            // The removal from sharedPreferences is direct.
        }
    }

    private fun saveCookiesToPrefs() {
        val editor = sharedPreferences.edit()
        // remove keys which are no longer present or have no persistent cookies
        val currentPrefKeys = sharedPreferences.all.keys.filter { it.startsWith(COOKIES_KEY_PREFIX) }
        val domainsWithPersistent = cookieStore.filterValues { list -> list.any { it.persistent } }.keys
        val storeDomainsForPrefs = domainsWithPersistent.map { COOKIES_KEY_PREFIX + it }
        currentPrefKeys.forEach { prefKey ->
            if (prefKey !in storeDomainsForPrefs) {
                editor.remove(prefKey)
            }
        }

        // write only persistent cookies
        domainsWithPersistent.forEach { domain ->
            val persistentOnly = cookieStore[domain]?.filter { it.persistent } ?: emptyList()
            val jsonCookies = gson.toJson(persistentOnly)
            editor.putString(COOKIES_KEY_PREFIX + domain, jsonCookies)
        }
        editor.apply()
    }

    private fun loadCookiesFromPrefs() {
        cookieStore.clear()
        sharedPreferences.all.forEach { (key, value) ->
            if (key.startsWith(COOKIES_KEY_PREFIX) && value is String) {
                try {
                    val domain = key.removePrefix(COOKIES_KEY_PREFIX)
                    val typeToken = object : TypeToken<MutableList<SerializableCookie>>() {}.type
                    val cookiesList: MutableList<SerializableCookie>? = gson.fromJson(value, typeToken)
                    if (cookiesList != null) {
                        val now = System.currentTimeMillis()
                        // prefs should contain only persistent cookies; filter any expired ones just in case
                        val validCookies = cookiesList.filter { it.persistent && it.expiresAt > now }.toMutableList()
                        if (validCookies.isNotEmpty()) {
                            cookieStore[domain] = validCookies
                        } else if (cookiesList.isNotEmpty()){
                            // all expired; remove key
                            sharedPreferences.edit().remove(key).apply()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PersistentCookieJar", "Error deserializing cookies for key $key from SharedPreferences", e)
                    sharedPreferences.edit().remove(key).apply()
                }
            }
        }
    }

    private fun domainMatches(cookieDomain: String, hostOnly: Boolean, requestHost: String): Boolean {
        val cd = normalizeDomain(cookieDomain)
        val rh = normalizeDomain(requestHost)
        if (hostOnly) return cd == rh
        return rh == cd || rh.endsWith(".$cd")
    }
    
    private fun normalizeDomain(domain: String): String {
        return domain.trimStart('.')
    }

    private fun pathMatches(cookiePath: String, requestPath: String): Boolean {
        return requestPath.startsWith(cookiePath)
    }

    private data class SerializableCookie(
        val name: String,
        val value: String,
        val expiresAt: Long,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val persistent: Boolean,
        val hostOnly: Boolean
    ) {
        fun toOkHttpCookie(): Cookie {
            val builder = Cookie.Builder()
                .name(name)
                .value(value)
                .expiresAt(expiresAt)
                .path(path)

            if (httpOnly) builder.httpOnly()
            if (secure) builder.secure()
            // Restore hostOnly vs domain cookie
            if (hostOnly) builder.hostOnlyDomain(this.domain) else builder.domain(this.domain)

            return builder.build()
        }

        companion object {
            fun fromOkHttpCookie(cookie: Cookie): SerializableCookie {
                return SerializableCookie(
                    name = cookie.name,
                    value = cookie.value,
                    expiresAt = cookie.expiresAt,
                    domain = cookie.domain,
                    path = cookie.path,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    persistent = cookie.persistent,
                    hostOnly = cookie.hostOnly
                )
            }
        }
    }
}
