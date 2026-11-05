package io.github.nspanelhascreen;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * One fullscreen web page, kept on screen: the page set with
 * {@code adb shell am start -n io.github.nspanelhascreen/.MainActivity --es url <address>}.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "screen";
    private static final String KEY_URL = "url";
    private static final String KEY_DEBUG = "debug";
    private static final String EXTRA_URL = "url"; //< --es url http://...: show this page from now on
    private static final String EXTRA_DEBUG = "debug"; //< --ez debug true: allow Chrome DevTools on the page
    private static final String EXTRA_RELOAD = "reload"; //< --ez reload true: load the page again
    private static final String SETUP_PAGE = "file:///android_asset/setup.html";
    private static final long RETRY_MS = 10_000; //< page didn't load (Wi-Fi or Home Assistant down): try again after this
    private static final long RESTART_AFTER_CRASH_MS = 3_000;
    private static final int RESTART_REQUEST = 1;
    private static final int IMMERSIVE = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::loadPage;
    private WebView web;
    private ScreenBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restartAfterCrash();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyIntent(getIntent());
        WebView.setWebContentsDebuggingEnabled(prefs().getBoolean(KEY_DEBUG, false));
        createWebView();
        loadPage();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (applyIntent(intent)) {
            WebView.setWebContentsDebuggingEnabled(prefs().getBoolean(KEY_DEBUG, false));
            loadPage();
        }
    }

    /** Saves what adb passed; true when the page should load again. */
    private boolean applyIntent(Intent intent) {
        if (intent == null) return false;
        SharedPreferences.Editor editor = prefs().edit();
        boolean changed = false;
        String url = intent.getStringExtra(EXTRA_URL);
        if (url != null && !url.isEmpty()) {
            editor.putString(KEY_URL, url);
            changed = true;
        }
        if (intent.hasExtra(EXTRA_DEBUG)) {
            editor.putBoolean(KEY_DEBUG, intent.getBooleanExtra(EXTRA_DEBUG, false));
            changed = true;
        }
        editor.apply();
        return changed || intent.getBooleanExtra(EXTRA_RELOAD, false);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void createWebView() {
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false); //< doorbell chime and camera sound without a tap first
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new PageClient());
        bridge = new ScreenBridge(this, web);
        web.addJavascriptInterface(bridge, ScreenBridge.NAME);
        setContentView(web);
    }

    private void loadPage() {
        handler.removeCallbacks(retry);
        web.loadUrl(prefs().getString(KEY_URL, SETUP_PAGE));
    }

    @SuppressWarnings("deprecation") //< still the way on Android 8, which these panels run
    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(IMMERSIVE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        web.onResume();
        bridge.start();
    }

    @Override
    protected void onPause() {
        bridge.stop();
        web.onPause();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override
    @SuppressWarnings({"MissingSuperCall", "deprecation"})
    public void onBackPressed() {
        // a wall screen has nowhere to go back to
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        bridge.stop();
        web.destroy();
        super.onDestroy();
    }

    /** If the app crashes, it comes back by itself a moment later. */
    private void restartAfterCrash() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Intent restart = new Intent(getApplicationContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pending = PendingIntent.getActivity(getApplicationContext(), RESTART_REQUEST, restart,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager alarms = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RESTART_AFTER_CRASH_MS, pending);
            if (previous != null) previous.uncaughtException(thread, error);
            else System.exit(1);
        });
    }

    private class PageClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return false; //< links and redirects (the Home Assistant login) stay in this screen
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            handler.removeCallbacks(retry);
            handler.postDelayed(retry, RETRY_MS);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // the page's renderer died (out of memory on small panels): start over with a fresh web view
            bridge.stop();
            web.destroy();
            createWebView();
            loadPage();
            bridge.start();
            return true;
        }
    }
}
