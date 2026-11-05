package io.github.nspanelhascreen;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.Locale;

/**
 * What the page can reach as {@code window.haScreen}: the light and proximity sensors and the backlight.
 * Sensor changes also arrive as {@code ha-screen-sensor} events on {@code window},
 * with {@code detail = {type: "light" | "proximity", value}}.
 * Proximity is averaged over a second first: some panels (NSPanel Pro) report a raw, jittery value many times a second.
 */
final class ScreenBridge implements SensorEventListener {
    static final String NAME = "haScreen";
    static final String EVENT = "ha-screen-sensor";
    private static final float LIGHT_CHANGE = 0.1f; //< a light change smaller than this share isn't sent...
    private static final float LIGHT_MIN_LUX = 2f; //< ...nor one under this many lux (dark rooms jitter)
    private static final long LIGHT_MIN_INTERVAL_MS = 1_000;
    private static final long PROXIMITY_WINDOW_MS = 1_000; //< proximity readings are averaged over this
    private static final float PROXIMITY_STEP = 2f; //< an average closer than this to the last one sent isn't sent
    private static final int BRIGHTNESS_MAX = 255;

    /** Gets every reading the page gets (Home Assistant over MQTT). */
    interface Listener {
        void onReading(String type, float value);
    }

    private final Activity activity;
    private final WebView web;
    private final SensorManager sensors;
    private final Sensor lightSensor;
    private final Sensor proximitySensor;
    private volatile float light = Float.NaN;
    private volatile float proximity = Float.NaN;
    private float sentLight = Float.NaN;
    private long sentLightAt;
    private float proximitySum;
    private int proximityCount;
    private float sentProximity = Float.NaN;
    private boolean listening;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable proximityWindow = this::closeProximityWindow;
    private Listener listener;

    /** listener may be null. */
    ScreenBridge(Activity activity, WebView web, Listener listener) {
        this.activity = activity;
        this.web = web;
        this.listener = listener;
        sensors = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        lightSensor = sensors.getDefaultSensor(Sensor.TYPE_LIGHT);
        proximitySensor = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
    }

    /** Also hands the new listener the readings so far. */
    void setListener(Listener listener) {
        this.listener = listener;
        if (listener == null) return;
        if (!Float.isNaN(light)) listener.onReading("light", light);
        if (!Float.isNaN(proximity)) listener.onReading("proximity", proximity);
    }

    void start() {
        if (listening) return;
        listening = true;
        if (lightSensor != null) sensors.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (proximitySensor != null) {
            sensors.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            handler.postDelayed(proximityWindow, PROXIMITY_WINDOW_MS);
        }
    }

    void stop() {
        if (!listening) return;
        listening = false;
        sensors.unregisterListener(this);
        handler.removeCallbacks(proximityWindow);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float value = event.values[0];
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            light = value;
            long now = SystemClock.elapsedRealtime();
            boolean bigChange = Float.isNaN(sentLight)
                    || Math.abs(value - sentLight) >= Math.max(LIGHT_MIN_LUX, sentLight * LIGHT_CHANGE);
            if (!bigChange || now - sentLightAt < LIGHT_MIN_INTERVAL_MS) return;
            sentLight = value;
            sentLightAt = now;
            send("light", value);
        } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            proximitySum += value;
            proximityCount++;
            if (Float.isNaN(proximity)) proximity = value; //< something to read before the first average
        }
    }

    /** Every second: the average of the readings since; a steady sensor keeps its last value. */
    private void closeProximityWindow() {
        if (proximityCount > 0) {
            proximity = Math.round(proximitySum / proximityCount * 10) / 10f;
            proximitySum = 0;
            proximityCount = 0;
        }
        if (!Float.isNaN(proximity) && (Float.isNaN(sentProximity) || Math.abs(proximity - sentProximity) >= PROXIMITY_STEP)) {
            sentProximity = proximity;
            send("proximity", proximity);
        }
        if (listening) handler.postDelayed(proximityWindow, PROXIMITY_WINDOW_MS);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void send(String type, float value) {
        if (listener != null) listener.onReading(type, value);
        String script = String.format(Locale.ROOT,
                "window.dispatchEvent(new CustomEvent('%s',{detail:{type:'%s',value:%s}}))", EVENT, type, value);
        activity.runOnUiThread(() -> web.evaluateJavascript(script, null));
    }

    /** Lux, or NaN before the first reading / without a light sensor. */
    @JavascriptInterface
    public double light() {
        return light;
    }

    /** The last one-second average: cm on most devices, a raw reading on some (NSPanel Pro). NaN without a sensor. */
    @JavascriptInterface
    public double proximity() {
        return proximity;
    }

    /** The proximity sensor's maximum: a reading below it means something is near. NaN without one. */
    @JavascriptInterface
    public double proximityRange() {
        return proximitySensor == null ? Double.NaN : proximitySensor.getMaximumRange();
    }

    /** Backlight 0–255 while this screen shows; -1 hands it back to the system setting. */
    @JavascriptInterface
    public void setBrightness(int level) {
        activity.runOnUiThread(() -> {
            WindowManager.LayoutParams params = activity.getWindow().getAttributes();
            params.screenBrightness = level < 0
                    ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    : Math.min(level, BRIGHTNESS_MAX) / (float) BRIGHTNESS_MAX;
            activity.getWindow().setAttributes(params);
        });
    }

    /** {"app": version, "model": ..., "android": ...} */
    @JavascriptInterface
    public String info() {
        return String.format(Locale.ROOT, "{\"app\":\"%s\",\"model\":\"%s\",\"android\":\"%s\"}",
                version(), Build.MODEL.replace("\"", ""), Build.VERSION.RELEASE);
    }

    private String version() {
        return version(activity);
    }

    static String version(Activity activity) {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception error) {
            return "?";
        }
    }
}
