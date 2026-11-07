package io.github.nspanelhascreen;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * Keeps Android's wireless debugging (Android 11+) switched on, when asked to ({@code --ez wireless_adb true}) and
 * allowed to ({@code adb shell pm grant io.github.nspanelhascreen android.permission.WRITE_SECURE_SETTINGS}).
 * Android turns wireless debugging off at every restart and whenever Wi-Fi drops, so a wall screen soon can't be
 * reached without walking up to it. It is switched back on each time Wi-Fi connects; Android still picks a new port
 * every time, which a computer finds with {@code adb mdns services}.
 */
final class WirelessAdb {
    private static final String TAG = "HaScreen";
    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled"; //< Settings.Global.ADB_WIFI_ENABLED (hidden API)
    private static final int ON = 1;

    private final Context context;
    private ConnectivityManager.NetworkCallback callback;

    WirelessAdb(Context context) {
        this.context = context.getApplicationContext();
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /** Switch it on now and every time Wi-Fi connects again. */
    void start() {
        if (callback != null || !supported()) return;
        if (context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Wireless debugging not kept on: grant android.permission.WRITE_SECURE_SETTINGS with adb first");
            return;
        }
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                switchOn();
            }
        };
        NetworkRequest wifi = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
        connectivity().registerNetworkCallback(wifi, callback); //< also called right away when Wi-Fi is already up
    }

    void stop() {
        if (callback == null) return;
        connectivity().unregisterNetworkCallback(callback);
        callback = null;
    }

    private void switchOn() {
        try {
            if (Settings.Global.getInt(context.getContentResolver(), ADB_WIFI_ENABLED, 0) == ON) return;
            Settings.Global.putInt(context.getContentResolver(), ADB_WIFI_ENABLED, ON);
            Log.i(TAG, "Wireless debugging switched on");
        } catch (SecurityException error) {
            Log.w(TAG, "Wireless debugging could not be switched on: " + error.getMessage());
        }
    }

    private ConnectivityManager connectivity() {
        return (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }
}
