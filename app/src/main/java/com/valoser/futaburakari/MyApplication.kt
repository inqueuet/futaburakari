package com.valoser.futaburakari

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
import okhttp3.OkHttpClient
import javax.inject.Inject
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory

@HiltAndroidApp
class MyApplication : Application(), ImageLoaderFactory, Configuration.Provider {

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // CookieJar をアプリ起動時に初期化（OkHttp提供前に安全化）
        PersistentCookieJar.init(applicationContext)
        // Initialize Google Mobile Ads SDK
        MobileAds.initialize(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

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
            .okHttpClient { okHttpClient }
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
