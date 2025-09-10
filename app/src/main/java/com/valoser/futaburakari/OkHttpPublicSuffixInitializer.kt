package com.valoser.futaburakari

import android.content.Context
import androidx.startup.Initializer
import okhttp3.HttpUrl

/**
 * OkHttp の Public Suffix List (PSL) をアプリ起動時にウォームアップする Initializer。
 *
 * `HttpUrl.topPrivateDomain()` を一度呼び出して PSL を読み込み、
 * 実運用時の初回アクセスでの待ちを避けます。AndroidX Startup によって自動実行されます。
 */
class OkHttpPublicSuffixInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // OkHttp の PSL（Public Suffix List）を早期ロード
        runCatching {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("www.example.co.uk")
                .build()
            url.topPrivateDomain()
        }
    }
    /**
     * 依存する Initializer はありません。
     */
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
