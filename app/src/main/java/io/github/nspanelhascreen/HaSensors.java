package io.github.nspanelhascreen;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * The panel's sensors in Home Assistant, over MQTT with discovery: one device named after the screen
 * ("Bedroom screen" → sensor.bedroom_screen_light, sensor.bedroom_screen_proximity,
 * binary_sensor.bedroom_screen_near), only those the panel has, unavailable while the app is off.
 */
final class HaSensors implements ScreenBridge.Listener {
    private static final String DISCOVERY_PREFIX = "homeassistant";
    private static final String TOPIC_ROOT = "nspanel-ha-screen";
    private static final String ONLINE = "online";
    private static final String OFFLINE = "offline";
    private static final long LIGHT_EVERY_MS = 5_000; //< at most one light report this often; the latest value wins

    private final MqttLink mqtt;
    private final String id;
    private final String base;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable sendLight = this::sendLight;
    private float pendingLight = Float.NaN;
    private long lightSentAt;
    private boolean lightQueued;

    HaSensors(Context context, String host, int port, String user, String password, String name, String appVersion) {
        id = slug(name);
        base = TOPIC_ROOT + "/" + id;
        mqtt = new MqttLink(host, port, TOPIC_ROOT + "-" + id, user, password, base + "/status", OFFLINE);
        SensorManager sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        JSONObject device = device(name, appVersion);
        if (sensors.getDefaultSensor(Sensor.TYPE_LIGHT) != null) {
            mqtt.publish(configTopic("sensor", "light"), config("Light", "light", device)
                    .put("unit_of_measurement", "lx").put("device_class", "illuminance")
                    .put("state_class", "measurement").toString());
        }
        if (sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null) {
            mqtt.publish(configTopic("sensor", "proximity"), config("Proximity", "proximity", device)
                    .put("icon", "mdi:hand-wave-outline").put("state_class", "measurement").toString());
            mqtt.publish(configTopic("binary_sensor", "near"), config("Near", "near", device)
                    .put("device_class", "occupancy").toString());
        }
        mqtt.publish(base + "/status", ONLINE);
    }

    void start() {
        mqtt.start();
    }

    void stop() {
        handler.removeCallbacks(sendLight);
        mqtt.stop();
    }

    @Override
    public void onReading(String type, float value) {
        if ("proximity".equals(type)) {
            mqtt.publish(base + "/proximity", String.format(Locale.ROOT, "%.1f", value));
        } else if ("near".equals(type)) {
            mqtt.publish(base + "/near", value > 0 ? "ON" : "OFF");
        } else if ("light".equals(type)) {
            pendingLight = value;
            if (lightQueued) return;
            lightQueued = true;
            long wait = Math.max(0, lightSentAt + LIGHT_EVERY_MS - SystemClock.elapsedRealtime());
            handler.postDelayed(sendLight, wait);
        }
    }

    private void sendLight() {
        lightQueued = false;
        lightSentAt = SystemClock.elapsedRealtime();
        mqtt.publish(base + "/light", String.valueOf(Math.round(pendingLight)));
    }

    private String configTopic(String component, String sensor) {
        return DISCOVERY_PREFIX + "/" + component + "/" + id + "/" + sensor + "/config";
    }

    private Config config(String name, String sensor, JSONObject device) {
        return new Config()
                .put("name", name)
                .put("unique_id", TOPIC_ROOT + "_" + id + "_" + sensor)
                .put("state_topic", base + "/" + sensor)
                .put("availability_topic", base + "/status")
                .put("device", device);
    }

    private JSONObject device(String name, String appVersion) {
        return new Config()
                .put("identifiers", new JSONArray().put(TOPIC_ROOT + "_" + id))
                .put("name", name)
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("sw_version", "nspanel-ha-screen " + appVersion)
                .json;
    }

    /** "Bedroom screen" → "bedroom_screen" */
    static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** JSONObject without the checked exceptions (keys here are never null). */
    private static final class Config {
        final JSONObject json = new JSONObject();

        Config put(String key, Object value) {
            try {
                json.put(key, value);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            return this;
        }

        @Override
        public String toString() {
            return json.toString();
        }
    }
}
