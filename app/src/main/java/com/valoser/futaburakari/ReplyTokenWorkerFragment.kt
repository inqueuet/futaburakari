package com.valoser.futaburakari

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.*
import android.webkit.*
import androidx.fragment.app.Fragment
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class ReplyTokenWorkerFragment : Fragment(), TokenProvider {

    private lateinit var webView: WebView
    private val pending = AtomicReference<((Result<Map<String, String>>) -> Unit)?>(null)
    private var allowedPattern: Regex? = null
    private fun isAllowedHost(host: String?): Boolean {
        val h = host ?: return false
        return allowedPattern?.containsMatchIn(h) == true
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
            settings.userAgentString = Ua.STRING

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
                    view.postDelayed({ injectAndExtract() }, 250)
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
        if (!userAgent.isNullOrBlank() && ::webView.isInitialized) {
            webView.post { webView.settings.userAgentString = userAgent }
        }
    }

    override suspend fun fetchTokens(postPageUrl: String) = suspendCancellableCoroutine { cont ->
        pending.getAndSet { result ->
            if (cont.isActive) cont.resume(result)
        }?.invoke(Result.failure(IllegalStateException("cancelled by new fetchTokens")))

        webView.post {
            // restrict navigation to the same host as post page
            runCatching {
                val host = android.net.Uri.parse(postPageUrl).host
                // base domain = 最後の2ラベル（例: may.2chan.net → 2chan.net）
                val base = host?.split('.')?.takeLast(2)?.joinToString(".")
                if (!base.isNullOrBlank()) {
                    // 任意のサブドメイン（多段も可）+ base ドメインを許可
                    allowedPattern = Regex("""^(?:[A-Za-z0-9-]+\.)*${Regex.escape(base)}$""", RegexOption.IGNORE_CASE)
                } else {
                    allowedPattern = null
                }
            }
            webView.loadUrl(postPageUrl)
        }

        cont.invokeOnCancellation {
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
                if(!fm){ AndroidBridge.onError('form not found'); return; }

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
                AndroidBridge.onError('inject error: '+e.message);
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }
}
