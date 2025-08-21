package com.example.hutaburakari

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * 不可視 WebView で投稿ページをロードし、最終的な hidden/token を吸い上げるワーカー。
 * 画面には出さないが、Fragment 配下に置いてライフサイクルを管理する。
 */
class ReplyTokenWorkerFragment : Fragment(), TokenProvider {

    private lateinit var webView: WebView
    private var cont: ((Result<Map<String, String>>) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retainInstance = true
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        webView = WebView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(1, 1) // 画面に出さない
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // UA は OkHttp と近いものに（厳密一致である必要はない）
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"
            addJavascriptInterface(Bridge(), "AndroidBridge")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    injectAndExtract()
                }
            }
        }
        return webView
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onTokens(json: String) {
            try {
                val map = jsonToMap(json)
                cont?.invoke(Result.success(map))
            } catch (e: Exception) {
                cont?.invoke(Result.failure(e))
            } finally {
                cont = null
            }
        }

        @JavascriptInterface
        fun onError(msg: String) {
            cont?.invoke(Result.failure(IllegalStateException(msg)))
            cont = null
        }
    }

    private fun jsonToMap(json: String): Map<String, String> {
        // 依存を増やさずシンプルに最小限の JSON パーサ（キー・値はダブルクォート前提）
        // 実運用では org.json.JSONObject を使う
        return org.json.JSONObject(json).let { obj ->
            obj.keys().asSequence().associateWith { key -> obj.getString(key) }
        }
    }

    private fun injectAndExtract() {
        val js = """
            (function(){
              try{
                var fm = document.getElementById('fm') || document.forms[0];
                if(!fm){ AndroidBridge.onError('form not found'); return; }

                function done(){
                  try{
                    var pick = function(name){
                      var el = fm.querySelector('input[name="'+name+'"]');
                      return (el && (el.value!==undefined)) ? String(el.value) : null;
                    };
                    var tokens = {};

                    // 代表的な hidden（ページ実装で変わる可能性あり）
                    var names = [
                      'MAX_FILE_SIZE','baseform','pthb','pthc','pthd','ptua','scsz',
                      'hash','js','chrenc','resto'
                    ];
                    names.forEach(function(n){
                      var v = pick(n);
                      if(v!==null){ tokens[n] = v; }
                    });

                    // 念のため hidden を総なめ
                    var hiddens = fm.querySelectorAll('input[type="hidden"]');
                    for(var i=0;i<hiddens.length;i++){
                      var h = hiddens[i];
                      if(h.name && (h.value!==undefined) && !(h.name in tokens)){
                        tokens[h.name] = String(h.value);
                      }
                    }

                    AndroidBridge.onTokens(JSON.stringify(tokens));
                  }catch(e){
                    AndroidBridge.onError('collect error: '+e.message);
                  }
                }

                if(document.readyState === 'complete'){
                  setTimeout(done, 200);
                }else{
                  window.addEventListener('load', function(){ setTimeout(done, 200); });
                }
              }catch(e){
                AndroidBridge.onError('inject error: '+e.message);
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    override suspend fun fetchTokens(postPageUrl: String): Result<Map<String, String>> =
        suspendCancellableCoroutine { c ->
            // すでに待ち受けが存在する場合は前の要求を失敗させる
            cont?.invoke(Result.failure(CancellationException("cancelled by new request")))
            cont = { result ->
                if (c.isActive) c.resume(result)
            }
            webView.loadUrl(postPageUrl)
            c.invokeOnCancellation {
                cont = null
            }
        }
}