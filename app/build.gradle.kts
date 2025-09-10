// アプリモジュールのビルド設定（Kotlin DSL）。
// - Kotlin/JDK 17 ツールチェーンと JVM 11 バイトコードを使用
// - Jetpack Compose・Hilt・KSP・Firebase・AdMob を利用
// - リリース時は ProGuard 最適化とネイティブデバッグシンボル出力を有効化
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.dagger.hilt.android")
    // KSP は Version Catalog でバージョンを管理（libs.plugins.ksp を使用）
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
}

kotlin {
    // JDK は 17 を使用（ビルド環境のツールチェーン）
    jvmToolchain(17)
    compilerOptions {
        // 旧 kotlinOptions { jvmTarget = "11" } の代替（deprecated の解消）
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

android {
    namespace = "com.valoser.futaburakari"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.valoser.futaburakari"
        minSdk = 24
        targetSdk = 36
        versionCode = 40
        versionName = "5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Play Console でのネイティブクラッシュ解析用にデバッグシンボルを出力（NDK コードがある場合）
            // 出力先: app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE" // 必要に応じて "FULL"（サイズ大）
            }
        }
    }

    compileOptions {
        // Java のソース/ターゲット互換性
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Compose Foundation（LazyVerticalGrid など）
    implementation("androidx.compose.foundation:foundation")

    // Material Icons（拡張）
    implementation("androidx.compose.material:material-icons-extended")

    // ViewModel for Compose（compose-viewmodel）
    implementation(libs.androidx.lifecycle.viewmodel.compose.android)
    // Coil for Compose（画像読み込み）
    implementation(libs.coil.compose)
    // Jsoup（HTML パーサー）
    implementation(libs.jsoup)
    // Lifecycle 対応の state 収集
    implementation(libs.androidx.lifecycle.runtime.compose)
    // LiveData → Compose State 変換
    implementation("androidx.compose.runtime:runtime-livedata")

    // ネットワーク/JSON（Version Catalog 統一管理）
    implementation(libs.okhttp)
    implementation(platform(libs.okhttp.bom))
    implementation("com.squareup.okhttp3:okhttp")
    implementation("com.squareup.okhttp3:okhttp-android")
    implementation("com.squareup.okhttp3:okhttp-tls")
    implementation("com.squareup.okhttp3:okhttp-sse")
    implementation("com.squareup.okhttp3:okhttp-urlconnection")
    implementation("com.squareup.okhttp3:okhttp-coroutines")
    
    implementation(libs.gson)

    // Media3（ExoPlayer）
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // Media3 Extractor は不要（動画プロンプト解析を削除済み）
    implementation(libs.androidx.exifinterface)

    // AndroidX Startup（アプリ初期化）
    implementation(libs.androidx.startup.runtime)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // レガシー View 依存は削除（Compose へ移行）
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.preference.ktx)
    // Material Components（XML Material 3 テーマ/ウィジェット/ブリッジ）
    implementation(libs.material)
    // SplashScreen 互換（postSplashScreenTheme・後方互換）
    implementation(libs.androidx.core.splashscreen)

    // Coil extensions
    implementation(libs.coil.core)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    // Coil 3 network (OkHttp)
    implementation(libs.coil.network.okhttp)

    // Google Mobile Ads SDK（AdMob）
    implementation(libs.play.services.ads)

    // WorkManager（バックグラウンド処理）
    implementation(libs.androidx.work.runtime.ktx)
    // Hilt × WorkManager 連携
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Firebase（Analytics）
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    // MP4 パーサは不要（動画プロンプト解析を削除済み）

    // テスト依存関係
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
