import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.dagger.hilt.android")
    // KSP は version catalog でバージョンを管理（libs.plugins.ksp を作成）
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
}

kotlin {
    // JDK は 11 を使用（必要なら 17 にしてOK）
    jvmToolchain(17)
    compilerOptions {
        // 旧 kotlinOptions { jvmTarget = "11" } の代替（deprecated解消）
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
        versionCode = 3
        versionName = "1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    // ViewModel for Compose
    implementation(libs.androidx.lifecycle.viewmodel.compose.android)
    // Coil for Compose (画像読み込み)
    implementation(libs.coil.compose)
    // Jsoup (HTMLパーサー)
    implementation(libs.jsoup)
    // Lifecycle-aware state collection
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Unified dependencies from catalog
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.okhttp)
    implementation(platform(libs.okhttp.bom))
    implementation(libs.gson)

    // Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.exifinterface)

    // AndroidX Startup
    implementation(libs.androidx.startup.runtime)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.preference.ktx)

    // Coil extensions
    implementation(libs.coil.core)
    implementation(libs.coil.video)
    implementation(libs.coil.gif)

    // Google Mobile Ads SDK (AdMob)
    implementation(libs.play.services.ads)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)
    // Hilt WorkManager integration
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
