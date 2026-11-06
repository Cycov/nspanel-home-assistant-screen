package io.github.nspanelhascreen;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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
 * {@code adb shell am start -n io.github.nspanelhascreen/.Setup --es url <address>}.
 * Settings only count when they come through the {@code .Setup} alias, which only adb's shell may open.
 */
public class MainActivity extends Activity {
    private static final String TAG = "HaScreen";
    private static final String SETUP_ALIAS = ".Setup"; //< see AndroidManifest.xml
    private static final String PREFS = "screen";
    private static final String KEY_URL = "url";
    private static final String KEY_DEBUG = "debug";
    private static final String KEY_NAME = "name";
    private static final String KEY_MQTT = "mqtt";
    private static final String KEY_MQTT_USER = "mqtt_user";
    private static final String KEY_MQTT_PASSWORD = "mqtt_password";
    private static final String KEY_NEAR_ABOVE = "near_above";
    private static final String EXTRA_URL = "url"; //< --es url http://...: show this page from now on
    private static final String EXTRA_DEBUG = "debug"; //< --ez debug true: allow Chrome DevTools on the page
    private static final String EXTRA_RELOAD = "reload"; //< --ez reload true: load the page again
    private static final String EXTRA_NAME = "name"; //< --es name "Bedroom screen": the device in Home Assistant
    private static final String EXTRA_MQTT = "mqtt"; //< --es mqtt host[:port]: report the sensors there ("" = stop)
    private static final String EXTRA_MQTT_USER = "mqtt_user";
    private static final String EXTRA_MQTT_PASSWORD = "mqtt_password";
    private static final String EXTRA_NEAR_ABOVE = "near_above"; //< --es near_above 20: proximity above this = something in front
    private static final int MQTT_PORT = 1883;
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
    private HaSensors sensors; //< null without MQTT settings
    private boolean sensorsChanged;
    private boolean nearChanged;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restartAfterCrash();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyIntent(getIntent());
        WebView.setWebContentsDebuggingEnabled(prefs().getBoolean(KEY_DEBUG, false));
        startSensors();
        createWebView();
        loadPage();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        boolean reload = applyIntent(intent);
        if (sensorsChanged) restartSensors();
        if (nearChanged) bridge.setNearAbove(nearAbove());
        if (reload) {
            WebView.setWebContentsDebuggingEnabled(prefs().getBoolean(KEY_DEBUG, false));
            loadPage();
        }
    }

    /** Saves what adb passed through .Setup; true when the page should load again. */
    private boolean applyIntent(Intent intent) {
        sensorsChanged = false;
        nearChanged = false;
        if (intent == null || !fromSetup(intent)) return false;
        SharedPreferences.Editor editor = prefs().edit();
        boolean changed = false;
        String url = intent.getStringExtra(EXTRA_URL);
        if (url != null && !url.isEmpty()) {
            if (isWebAddress(url)) {
                editor.putString(KEY_URL, url);
                changed = true;
            } else {
                Log.w(TAG, "Ignored url: only http:// and https:// pages can be shown");
            }
        }
        if (intent.hasExtra(EXTRA_DEBUG)) {
            editor.putBoolean(KEY_DEBUG, intent.getBooleanExtra(EXTRA_DEBUG, false));
            changed = true;
        }
        String[][] sensorExtras = {
                {EXTRA_NAME, KEY_NAME}, {EXTRA_MQTT, KEY_MQTT}, {EXTRA_MQTT_USER, KEY_MQTT_USER}, {EXTRA_MQTT_PASSWORD, KEY_MQTT_PASSWORD},
        };
        for (String[] extra : sensorExtras) {
            if (!intent.hasExtra(extra[0])) continue;
            String value = intent.getStringExtra(extra[0]);
            editor.putString(extra[1], value == null ? "" : value);
            sensorsChanged = true;
        }
        if (intent.hasExtra(EXTRA_NEAR_ABOVE)) {
            String threshold = intent.getStringExtra(EXTRA_NEAR_ABOVE);
            editor.putString(KEY_NEAR_ABOVE, threshold == null ? "" : threshold);
            nearChanged = true;
        }
        editor.commit(); //< the sensors read these right after
        return changed || intent.getBooleanExtra(EXTRA_RELOAD, false);
    }

    /** Opened through the .Setup alias (adb), not the launcher or another app. */
    private boolean fromSetup(Intent intent) {
        ComponentName component = intent.getComponent();
        return component != null && component.getClassName().equals(getPackageName() + SETUP_ALIAS);
    }

    private static boolean isWebAddress(String url) {
        String scheme = Uri.parse(url).getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Reports the light and proximity sensors over MQTT, when a broker and a name are set. */
    private void startSensors() {
        SharedPreferences prefs = prefs();
        String broker = prefs.getString(KEY_MQTT, "");
        String name = prefs.getString(KEY_NAME, "");
        if (broker.isEmpty() || HaSensors.slug(name).isEmpty()) return;
        String host = broker;
        int port = MQTT_PORT;
        int colon = broker.lastIndexOf(':');
        if (colon > 0) {
            host = broker.substring(0, colon);
            try {
                port = Integer.parseInt(broker.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                // keep the default port
            }
        }
        sensors = new HaSensors(this, host, port, prefs.getString(KEY_MQTT_USER, ""), prefs.getString(KEY_MQTT_PASSWORD, ""),
                name, ScreenBridge.version(this));
        sensors.start();
    }

    /** NaN when not set: the bridge then uses the Android meaning of near. */
    private float nearAbove() {
        try {
            return Float.parseFloat(prefs().getString(KEY_NEAR_ABOVE, ""));
        } catch (NumberFormatException unset) {
            return Float.NaN;
        }
    }

    private void restartSensors() {
        if (sensors != null) sensors.stop();
        sensors = null;
        startSensors();
        bridge.setListener(sensors);
    }

    private void createWebView() {
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); //< no device files for the page (the bundled setup page is an asset, still allowed)
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false); //< doorbell chime and camera sound without a tap first
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new PageClient());
        bridge = new ScreenBridge(this, web, sensors);
        bridge.setNearAbove(nearAbove());
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
        if (sensors != null) sensors.stop();
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
