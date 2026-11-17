# The page calls these through addJavascriptInterface; keep them by name.
-keepclassmembers class com.aravind.hendrifs.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
