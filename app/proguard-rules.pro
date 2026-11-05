# The page calls these by name through window.haScreen.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
