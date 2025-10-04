# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class com.valoser.futaburakari.PersistentCookieJar$SerializableCookie { *; }
-keep class com.valoser.futaburakari.Bookmark { *; }
-keep class com.valoser.futaburakari.cache.CachedDetails { *; }
-keep class com.valoser.futaburakari.DetailContent { *; }
-keep class com.valoser.futaburakari.DetailContent$* { *; }
-keep class com.valoser.futaburakari.HistoryEntry { *; }
-keep class ** extends androidx.work.ListenableWorker

# OkHttp クラスの保持（内部実装含む）
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# Kotlin関連
-dontwarn kotlin.**

# Release builds should not emit verbose/debug/info logs from our code.
# R8 strips these calls so that only warnings/errors remain and logcat stays clean.
-assumenosideeffects class android.util.Log {
    public static int v(java.lang.String, java.lang.String);
    public static int v(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int d(java.lang.String, java.lang.String);
    public static int d(java.lang.String, java.lang.String, java.lang.Throwable);
    public static int i(java.lang.String, java.lang.String);
    public static int i(java.lang.String, java.lang.String, java.lang.Throwable);
}
