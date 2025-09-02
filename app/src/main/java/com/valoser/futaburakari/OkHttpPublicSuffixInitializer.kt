package com.valoser.futaburakari.init

import android.content.Context
import androidx.startup.Initializer
import okhttp3.HttpUrl

class OkHttpPublicSuffixInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // PSL を早期ロード
        runCatching {
            val url = HttpUrl.Builder()
                .scheme("https")
                .host("www.example.co.uk")
                .build()
            url.topPrivateDomain()
        }
    }
    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}