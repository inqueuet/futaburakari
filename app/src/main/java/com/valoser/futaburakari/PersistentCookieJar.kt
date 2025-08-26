package com.valoser.futaburakari

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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
        Log.d("PersistentCookieJar", "saveFromResponse for url: ${url.host}, received ${cookies.size} cookies.")

        cookies.forEach { cookie ->
            val cookieDomain = cookie.domain
            val effectiveDomain = normalizeDomain(cookieDomain.ifEmpty { requestHost })

            val storedCookiesForDomain = cookieStore.getOrPut(effectiveDomain) { mutableListOf() }
            storedCookiesForDomain.removeAll { it.name == cookie.name && it.path == cookie.path }

            if (cookie.expiresAt > System.currentTimeMillis() || cookie.persistent) {
                storedCookiesForDomain.add(SerializableCookie.fromOkHttpCookie(cookie))
                Log.d("PersistentCookieJar", "Saved cookie: ${cookie.name}=${cookie.value}; domain=$effectiveDomain; path=${cookie.path}; expiresAt=${cookie.expiresAt}")
            } else {
                Log.d("PersistentCookieJar", "Not saving expired/session cookie: ${cookie.name} for domain $effectiveDomain")
            }
        }
        saveCookiesToPrefs()

        // WebViewへのCookie同期 (オプション)
        try {
            val webViewCookieManager = android.webkit.CookieManager.getInstance()
            cookies.forEach { cookie ->
                val cookieString = cookie.toString()
                webViewCookieManager.setCookie(url.toString(), cookieString)
            }
            webViewCookieManager.flush()
            Log.d("PersistentCookieJar", "Synchronized ${cookies.size} cookies to WebView.")
        } catch (e: Exception) {
            Log.e("PersistentCookieJar", "Error synchronizing cookies to WebView", e)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        ensureInitialized()
        val requestHost = url.host
        val matchingCookies = mutableListOf<Cookie>()
        val currentTime = System.currentTimeMillis()

        Log.d("PersistentCookieJar", "loadForRequest for url: ${url.host}")

        cookieStore.forEach { (cookieDomain, storedCookiesList) ->
            if (domainMatches(cookieDomain, requestHost)) {
                val iterator = storedCookiesList.iterator()
                while (iterator.hasNext()) {
                    val serializableCookie = iterator.next()
                    if (serializableCookie.expiresAt <= currentTime && !serializableCookie.persistent) {
                        iterator.remove()
                        Log.d("PersistentCookieJar", "Removed expired cookie from store: ${serializableCookie.name} from domain $cookieDomain")
                    } else if (pathMatches(serializableCookie.path, url.encodedPath)) {
                        if (serializableCookie.secure && !url.isHttps) {
                            // secure cookie on non-secure request, skip
                        } else {
                            matchingCookies.add(serializableCookie.toOkHttpCookie())
                        }
                    }
                }
            }
        }
        // If any cookies were removed due to expiration, save the updated store
        // This check could be more sophisticated (e.g., a dirty flag)
        if (matchingCookies.size != cookieStore.values.flatten().size) {
             saveCookiesToPrefs()
        }

        Log.d("PersistentCookieJar", "Loading ${matchingCookies.size} cookies for $requestHost: ${matchingCookies.map { it.name + "=" + it.value }}")
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
            if (domainMatches(domain, normalizedHost)) {
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
        // First, clear any old domains from prefs that are no longer in cookieStore
        val currentPrefKeys = sharedPreferences.all.keys.filter { it.startsWith(COOKIES_KEY_PREFIX) }
        val storeDomainsForPrefs = cookieStore.keys.map { COOKIES_KEY_PREFIX + it }
        currentPrefKeys.forEach { prefKey ->
            if (prefKey !in storeDomainsForPrefs) {
                editor.remove(prefKey)
            }
        }

        cookieStore.forEach { (domain, cookiesList) ->
            if (cookiesList.isNotEmpty()) {
                val jsonCookies = gson.toJson(cookiesList)
                editor.putString(COOKIES_KEY_PREFIX + domain, jsonCookies)
            } else {
                // If a domain's list becomes empty, remove it from prefs
                editor.remove(COOKIES_KEY_PREFIX + domain)
            }
        }
        editor.apply()
        Log.d("PersistentCookieJar", "Saved all cookie domains to SharedPreferences: ${cookieStore.keys.filter { cookieStore[it]?.isNotEmpty() == true }}")
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
                        val validCookies = cookiesList.filter {
                            it.expiresAt > System.currentTimeMillis() || it.persistent
                        }.toMutableList()
                        if (validCookies.isNotEmpty()) {
                            cookieStore[domain] = validCookies
                            Log.d("PersistentCookieJar", "Loaded ${validCookies.size}/${cookiesList.size} cookies for domain $domain from Prefs.")
                        } else if (cookiesList.isNotEmpty()){
                             Log.d("PersistentCookieJar", "All ${cookiesList.size} cookies for domain $domain from Prefs were expired.")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PersistentCookieJar", "Error deserializing cookies for key $key from SharedPreferences", e)
                    sharedPreferences.edit().remove(key).apply()
                }
            }
        }
         Log.d("PersistentCookieJar", "Finished loading from Prefs. Cookie store domains: ${cookieStore.keys}")
    }

    private fun domainMatches(cookieDomain: String, requestHost: String): Boolean {
        val normalizedCookieDomain = normalizeDomain(cookieDomain)
        val normalizedRequestHost = normalizeDomain(requestHost)

        if (normalizedCookieDomain == normalizedRequestHost) {
            return true
        }
        // Handles cases like cookieDomain=".example.com" and requestHost="www.example.com"
        // Also cookieDomain="example.com" (hostOnly=true implicitly) should not match "www.example.com" unless it's an exact match handled above
        return normalizedRequestHost.endsWith(".$normalizedCookieDomain") || (normalizedCookieDomain.startsWith(".") && normalizedRequestHost.endsWith(normalizedCookieDomain))
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
        val persistent: Boolean
    ) {
        fun toOkHttpCookie(): Cookie {
            val builder = Cookie.Builder()
                .name(name)
                .value(value)
                .expiresAt(expiresAt)
                .path(path)

            if (httpOnly) builder.httpOnly()
            if (secure) builder.secure()
            
            // OkHttp's domain setter handles hostOnly logic based on whether the domain starts with "."
            // However, our `domain` field should already be correctly set (e.g. "example.com" or ".example.com")
            builder.domain(this.domain)

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
                    persistent = cookie.persistent
                )
            }
        }
    }
}
