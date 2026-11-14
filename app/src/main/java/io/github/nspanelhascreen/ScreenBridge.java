package io.github.nspanelhascreen;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.util.Locale;

/**
 * What the page can reach as {@code window.haScreen}: the light and proximity sensors, the battery and the backlight.
 * Changes also arrive as {@code ha-screen-sensor} events on {@code window},
 * with {@code detail = {type: "light" | "proximity" | "near" | "battery" | "charging", value}} (near and charging: 1 or 0).
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
    private static final int NEAR_UNKNOWN = -1;
    private static final int FAR = 0;
    private static final int NEAR = 1;
    private static final int PERCENT = 100;
    private static final int API_RECEIVER_FLAGS = 33; //< Android 13: registerReceiver takes an exported / not exported flag
    private static final int RECEIVER_NOT_EXPORTED = 4; //< Context.RECEIVER_NOT_EXPORTED

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
    private final Runnable lateLight = this::sendLateLight;
    private boolean lightQueued;
    private float nearAbove = Float.NaN; //< NaN: near = below the sensor's range (the Android way)
    private int near = NEAR_UNKNOWN;
    private Listener listener;
    private volatile float battery = Float.NaN; //< percent
    private volatile boolean charging;
    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            readBattery(intent);
        }
    };

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
        if (near != NEAR_UNKNOWN) listener.onReading("near", near);
    }

    /**
     * Proximity readings above this mean something is in front: for sensors that report a raw
     * value growing as things come closer (NSPanel Pro). NaN: the Android way, near = below the range.
     */
    void setNearAbove(float threshold) {
        nearAbove = threshold;
    }

    boolean hasLight() {
        return lightSensor != null;
    }

    boolean hasProximity() {
        return proximitySensor != null;
    }

    void start() {
        if (listening) return;
        listening = true;
        if (lightSensor != null) sensors.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        if (proximitySensor != null) {
            sensors.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            handler.postDelayed(proximityWindow, PROXIMITY_WINDOW_MS);
        }
        // the battery state is a sticky broadcast: the current one arrives at once
        IntentFilter batteryChanges = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= API_RECEIVER_FLAGS) activity.registerReceiver(batteryReceiver, batteryChanges, RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(batteryReceiver, batteryChanges);
    }

    void stop() {
        if (!listening) return;
        listening = false;
        sensors.unregisterListener(this);
        activity.unregisterReceiver(batteryReceiver);
        handler.removeCallbacks(proximityWindow);
        handler.removeCallbacks(lateLight);
        lightQueued = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float value = event.values[0];
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            light = value;
            if (!lightChanged() || lightQueued) return;
            long wait = sentLightAt + LIGHT_MIN_INTERVAL_MS - SystemClock.elapsedRealtime();
            if (wait <= 0) {
                sendLight();
                return;
            }
            lightQueued = true; //< too soon: the latest value goes out once the interval is over
            handler.postDelayed(lateLight, wait);
        } else if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            proximitySum += value;
            proximityCount++;
            if (Float.isNaN(proximity)) proximity = value; //< something to read before the first average
        }
    }

    private boolean lightChanged() {
        return Float.isNaN(sentLight) || Math.abs(light - sentLight) >= Math.max(LIGHT_MIN_LUX, sentLight * LIGHT_CHANGE);
    }

    private void sendLight() {
        sentLight = light;
        sentLightAt = SystemClock.elapsedRealtime();
        send("light", light);
    }

    private void sendLateLight() {
        lightQueued = false;
        if (lightChanged()) sendLight();
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
        if (!Float.isNaN(proximity)) {
            boolean something = Float.isNaN(nearAbove) ? proximity < proximitySensor.getMaximumRange() : proximity > nearAbove;
            int nowNear = something ? NEAR : FAR;
            if (nowNear != near) {
                near = nowNear;
                send("near", nowNear);
            }
        }
        if (listening) handler.postDelayed(proximityWindow, PROXIMITY_WINDOW_MS);
    }

    private void readBattery(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        boolean nowCharging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (level >= 0 && scale > 0) {
            float percent = Math.round(level * PERCENT / (float) scale);
            if (percent != battery) {
                battery = percent;
                send("battery", percent);
            }
        }
        if (nowCharging != charging) {
            charging = nowCharging;
            send("charging", nowCharging ? 1 : 0);
        }
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

    /** Something in front of the screen (see setNearAbove). */
    @JavascriptInterface
    public boolean near() {
        return near == NEAR;
    }

    /** The proximity sensor's maximum: a reading below it means something is near. NaN without one. */
    @JavascriptInterface
    public double proximityRange() {
        return proximitySensor == null ? Double.NaN : proximitySensor.getMaximumRange();
    }

    /** Battery charge in percent, or NaN before the first reading / on a device without a battery. */
    @JavascriptInterface
    public double battery() {
        return battery;
    }

    /** Plugged in (charger, USB or wireless), whether or not the battery is actually gaining charge. */
    @JavascriptInterface
    public boolean charging() {
        return charging;
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
