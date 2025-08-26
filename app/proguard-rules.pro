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

# PersistentCookieJarの内部データクラスSerializableCookieを難読化から除外する
# これにより、GsonがJSONとの間で正しくシリアライズ/デシリアライズできるようになる
-keep class com.valoser.futaburakari.PersistentCookieJar$SerializableCookie { *; }
-keep class com.valoser.futaburakari.Bookmark { *; }
-keep class com.valoser.futaburakari.cache.CachedDetails { *; }
-keep class com.valoser.futaburakari.DetailContent { *; }
-keep class com.valoser.futaburakari.DetailContent$* { *; }
-keep class com.valoser.futaburakari.FutabaResponse { *; }
-keep class com.valoser.futaburakari.HistoryEntry { *; }