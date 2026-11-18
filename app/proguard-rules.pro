# Keep @JavascriptInterface methods and WebView bridge intact under R8/ProGuard
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.example.bridge.AndroidBridge { *; }
-keepclassmembers class com.example.bridge.AndroidBridge {
    public *;
}

# Preserve line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable
