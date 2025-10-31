package com.valoser.futaburakari

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import android.webkit.*
import androidx.fragment.app.Fragment
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * 非表示の `WebView` で投稿ページを開き、hidden/token を抽出して `TokenProvider` として返すフラグメント。
 * 投稿ページと同じベースドメイン（ホスト名の末尾2ラベル）に限定してナビゲーションし、抽出結果は JSON から `Map` に変換する。
 */
class ReplyTokenWorkerFragment : Fragment(), TokenProvider {

    private lateinit var webView: WebView
    private val pending = AtomicReference<((Result<Map<String, String>>) -> Unit)?>(null)
    private var allowedPattern: Regex? = null
    private var injectRunnable: Runnable? = null
    private var pendingUserAgent: String? = null
    private fun isAllowedHost(host: String?): Boolean {
        val h = host ?: return false
        val pattern = allowedPattern ?: return true
        return pattern.containsMatchIn(h)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        webView = WebView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1)
            settings.javaScriptEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // ★ 共通UAを適用
            val initialUa = pendingUserAgent ?: Ua.STRING
            settings.userAgentString = initialUa

            // Hardening: block file/content access and mixed content
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            try { settings.allowFileAccessFromFileURLs = false } catch (_: Throwable) {}
            @Suppress("DEPRECATION")
            try { settings.allowUniversalAccessFromFileURLs = false } catch (_: Throwable) {}
            try { settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW } catch (_: Throwable) {}

            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url
                    val host = url.host
                    val scheme = url.scheme
                    val allow = (scheme == "https" || scheme == "http") && isAllowedHost(host)
                    return !allow // block anything not explicitly allowed
                }
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    return try {
                        val u = android.net.Uri.parse(url)
                        val host = u.host
                        val scheme = u.scheme
                        val allow = (scheme == "https" || scheme == "http") && isAllowedHost(host)
                        !allow
                    } catch (_: Exception) { true }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    injectRunnable?.let { view.removeCallbacks(it) }
                    val runnable = Runnable {
                        injectRunnable = null
                        injectAndExtract()
                    }
                    injectRunnable = runnable
                    view.postDelayed(runnable, 250)
                }
            }
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onTokens(raw: String) {
                webView.post {
                    val cb = pending.getAndSet(null)
                    if (cb != null) {
                        runCatching { cb(Result.success(jsonToMap(raw))) }
                            .onFailure { cb(Result.failure(it)) }
                    }
                }
            }
            @JavascriptInterface
            fun onError(msg: String) {
                webView.post {
                    val cb = pending.getAndSet(null)
                    cb?.invoke(Result.failure(IllegalStateException(msg)))
                }
            }
        }, "AndroidBridge")

        return webView
    }

    override fun prepare(userAgent: String?) {
        val ua = userAgent?.takeUnless { it.isBlank() }
        pendingUserAgent = ua
        if (::webView.isInitialized) {
            val applied = ua ?: Ua.STRING
            webView.post { webView.settings.userAgentString = applied }
        }
    }

    override suspend fun fetchTokens(postPageUrl: String) = suspendCancellableCoroutine { cont ->
        pending.getAndSet { result ->
            if (cont.isActive) cont.resume(result)
        }?.invoke(Result.failure(IllegalStateException("cancelled by new fetchTokens")))

        webView.post {
            injectRunnable?.let { webView.removeCallbacks(it) }
            injectRunnable = null
            // restrict navigation to http(s) hosts whose base domain（末尾2ラベル） matches the post page
            allowedPattern = runCatching { buildAllowedPattern(postPageUrl) }.getOrNull()
            webView.loadUrl(postPageUrl)
        }

        cont.invokeOnCancellation {
            injectRunnable?.let { webView.removeCallbacks(it) }
            injectRunnable = null
            pending.getAndSet(null)
        }
    }

    private fun jsonToMap(raw: String): Map<String, String> {
        val normalized = raw.trim().let { s ->
            if (s.startsWith("\"") && s.endsWith("\""))
                s.substring(1, s.length - 1).replace("\\\"", "\"")
            else s
        }
        val obj = JSONObject(normalized)
        return obj.keys().asSequence().associateWith { k -> obj.optString(k, "") }
    }

    private fun injectAndExtract() {
        val js = """
            (function(){
              try{
                var fm = document.getElementById('fm') || document.forms[0];
                if(!fm){ AndroidBridge.onError('投稿フォームが見つかりません。IP制限やセッション切れの可能性があります。'); return; }

                function pick(name){
                  var el = fm.querySelector('[name="'+name+'"]');
                  if(!el) return null;
                  if((el.type==='checkbox'||el.type==='radio') && !el.checked) return null;
                  return (el.value!=null) ? String(el.value) : '';
                }

                var tokens = {};
                var names = ['MAX_FILE_SIZE','baseform','pthb','pthc','pthd','ptua','scsz','hash','js','chrenc','resto'];
                for (var i=0;i<names.length;i++){
                  var n = names[i], v = pick(n);
                  if (v!==null) tokens[n] = v;
                }
                var hs = fm.querySelectorAll('input[type="hidden"]');
                for (var i=0;i<hs.length;i++){
                  var h=hs[i];
                  if (h.name && tokens[h.name]===undefined) tokens[h.name] = String(h.value||'');
                }
                try { tokens["scsz"] = (screen.width+"x"+screen.height+"x"+(screen.colorDepth||24)); } catch(e){}
                if (tokens["js"]===undefined) tokens["js"]="on";

                AndroidBridge.onTokens(JSON.stringify(tokens));
              }catch(e){
                AndroidBridge.onError('環境変数の取得エラー（IP制限やCookie認証が原因の可能性があります）: '+e.message);
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onDestroyView() {
        pending.getAndSet(null)?.invoke(Result.failure(IllegalStateException("fragment destroyed")))
        injectRunnable?.let { webView.removeCallbacks(it) }
        injectRunnable = null
        allowedPattern = null
        webView.stopLoading()
        webView.destroy()
        super.onDestroyView()
    }

    private fun buildAllowedPattern(postPageUrl: String): Regex? {
        val host = runCatching { Uri.parse(postPageUrl).host }.getOrNull()?.takeUnless { it.isNullOrBlank() }
            ?: return null
        // For IPv4/IPv6 literals just match exactly.
        if (host.any { it == ':' } || host.all { it.isDigit() || it == '.' }) {
            return Regex("^${Regex.escape(host)}$", RegexOption.IGNORE_CASE)
        }
        val base = effectiveBaseDomain(host)
        val patternSource = """^(?:[A-Za-z0-9-]+\.)*${Regex.escape(base)}$"""
        return Regex(patternSource, RegexOption.IGNORE_CASE)
    }

    private fun effectiveBaseDomain(host: String): String {
        val parts = host.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return host
        val last = parts.last()
        val secondLast = parts[parts.lastIndex - 1]
        val commonSecondLevel = setOf(
            "ac", "ad", "co", "com", "ed", "edu", "go", "gov", "gr", "ge", "lg", "mil", "ne", "net", "or", "org"
        )
        val useThreeSegments = last.length == 2 && secondLast.length <= 3 && secondLast in commonSecondLevel && parts.size >= 3
        val segmentCount = if (useThreeSegments) 3 else 2
        return parts.takeLast(segmentCount).joinToString(".")
    }
}
