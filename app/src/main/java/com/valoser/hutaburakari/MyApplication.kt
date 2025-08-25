package com.valoser.hutaburakari

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.VideoFrameDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import com.google.android.gms.ads.MobileAds

@HiltAndroidApp
class MyApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // ▼▼▼ 修正: PersistentCookieJarを初期化するコードをここに追加 ▼▼▼
        PersistentCookieJar.init(applicationContext)
        // Initialize Google Mobile Ads SDK
        MobileAds.initialize(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            // ★★★ このブロックを追加してデコーダーを登録 ★★★
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    // Android 9 (API 28) 以上の場合
                    add(ImageDecoderDecoder.Factory())
                } else {
                    // それより古いバージョンの場合
                    add(GifDecoder.Factory())
                }
                add(VideoFrameDecoder.Factory())
            }
            .okHttpClient { NetworkModule.okHttpClient }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }
}
