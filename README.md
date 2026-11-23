# nspanel-ha-screen

A tiny Android app that turns a wall panel into one fullscreen web page. It is made for Home Assistant dashboards on the Sonoff NSPanel Pro (Android 8.1) and similar wall screens.

It is a lighter alternative to a full kiosk browser: one activity, the system WebView, no settings screen and no libraries. The APK is about 33 KB.

## What it does

- Shows one page fullscreen, with the status and navigation bars hidden, and keeps the screen on.
- Starts when the device boots and comes back by itself if it crashes. It retries when the page can't load (Wi-Fi or Home Assistant down) and rebuilds the page if the WebView's renderer dies.
- Plays audio and video without a tap first, such as a doorbell chime or a camera stream with sound.
- Reports the panel's light and proximity sensors to Home Assistant over MQTT, as a device found by MQTT discovery.
- Gives the page the same sensors, the battery level and control of the backlight, through `window.haScreen`.

It does not replace the launcher, and the back button does nothing.

## Install

Download `nspanel-ha-screen-<version>.apk` from the [releases](../../releases) and install it from a computer with [adb](https://developer.android.com/tools/adb):

```sh
adb connect <panel-ip>:5555            # the NSPanel Pro listens on 5555 once adb is enabled
adb install -r nspanel-ha-screen-0.6.0.apk
```

Each release lists the APK's SHA-256, so you can check the download (`sha256sum` on Linux or macOS, `certutil -hashfile <file> SHA256` on Windows).

The release APKs are all signed with the same key, so a newer release installs over an older one and keeps its settings. An APK you built yourself is signed with your own (or the debug) key, and Android won't install a release over it: uninstall it first (`adb uninstall io.github.nspanelhascreen`). That clears its settings and the Home Assistant login.

## Setup

Everything is set from adb through the app's `.Setup` entry point, and the app remembers it:

```sh
adb shell am start -n io.github.nspanelhascreen/.Setup --es url http://homeassistant.local:8123/lovelace/0
```

Then log in to Home Assistant once on the panel; the WebView keeps the session.

| Extra | Effect |
|---|---|
| `--es url <address>` | Show this page from now on (`http://` or `https://` only) |
| `--ez reload true` | Load the page again |
| `--ez debug true` | Allow Chrome DevTools on the page (`chrome://inspect`, or `adb forward` to `webview_devtools_remote_<pid>`); `false` turns it off again |
| `--es name <name>` | The panel's name in Home Assistant, e.g. `"Bedroom screen"` |
| `--es mqtt <host[:port]>` | Report the sensors to this MQTT broker (port 1883 by default); `""` stops |
| `--es mqtt_user <user>` / `--es mqtt_password <password>` | The broker login |
| `--ez wireless_adb true` | Keep wireless debugging on (Android 11+), see [Staying reachable over adb](#staying-reachable-over-adb); `false` stops |
| `--es near_above <value>` | Proximity above this means something is in front (for raw sensors like the NSPanel Pro's; `20` there is about 25 cm). Unset (`""`): the Android way, near = below the sensor's range |

Several extras can go in one command. Extras sent to `.MainActivity` (the launcher entry) are ignored. See [Security](#security) for why.

Coming from 0.3 or older: the same commands work with `.Setup` in place of `.MainActivity`.

### Starting at boot on Android 10 and newer

Android 10+ only lets an app open itself at boot if it may draw over other apps:

```sh
adb shell appops set io.github.nspanelhascreen SYSTEM_ALERT_WINDOW allow
```

Android 8 and 9 (the NSPanel Pro) need nothing.

If another kiosk app also starts at boot, turn that off or disable it, so the two don't fight over the screen.

### Staying reachable over adb

Android 11+ turns wireless debugging off at every restart and whenever Wi-Fi drops, and picks a new port each time it is turned on, so a screen on the wall soon can't be reached without walking up to it. The app can switch it back on each time Wi-Fi connects. This needs a permission only adb can grant, plus the setting:

```sh
adb shell pm grant io.github.nspanelhascreen android.permission.WRITE_SECURE_SETTINGS
adb shell am start -n io.github.nspanelhascreen/.Setup --ez wireless_adb true
```

The port still changes after a restart, but the screen announces it on the network. `tools/adb-reconnect.ps1 <screen-ip>` finds it (`adb mdns services`), connects, and moves adb to the fixed port 5555 (`adb tcpip 5555`), which then works until the next restart. Wireless debugging stays limited to the Wi-Fi networks you allowed it on and to computers you paired. Also turn on **Developer options → Disable adb authorization timeout**, so Android doesn't forget a paired computer after 7 days.

The NSPanel Pro (Android 8.1) doesn't need any of this: once adb is on, it listens on 5555 for good.

## Sensors in Home Assistant

Give the panel a name and an MQTT broker:

```sh
adb shell am start -n io.github.nspanelhascreen/.Setup --es name "'Bedroom screen'" --es mqtt homeassistant.local --es mqtt_user <user> --es mqtt_password <password>
```

The inner quotes keep a name with spaces in one piece through `adb shell`. The password ends up in your shell history; to avoid that, run the command from a script that asks for it, or clear the history line afterwards.

Home Assistant's MQTT integration finds a device with that name, with these entities:

| Entity | Value |
|---|---|
| `sensor.<name>_light` | Light level in lux as Android reports it, at most every 5 seconds |
| `sensor.<name>_proximity` | Proximity, averaged over one second, sent when it moves by 2 or more |
| `binary_sensor.<name>_near` | Something in front of the screen (see `near_above`) |

Only the sensors the panel has are created. They turn unavailable when the app stops (MQTT last will). The values are retained, so Home Assistant has them again after it restarts.

With the Mosquitto broker app, any Home Assistant user can log in to MQTT. A dedicated, non-admin user for the panels is best.

## The page side: `window.haScreen`

| Call | Returns |
|---|---|
| `haScreen.light()` | Light level in lux as Android reports it, or `NaN` |
| `haScreen.proximity()` | Proximity, averaged over the last second, or `NaN` without a sensor |
| `haScreen.proximityRange()` | The sensor's reported maximum |
| `haScreen.near()` | Something in front of the screen (see `near_above`) |
| `haScreen.battery()` | Battery charge in percent, or `NaN` without a battery (0.6.0+) |
| `haScreen.charging()` | Plugged in: charger, USB or wireless (0.6.0+) |
| `haScreen.setBrightness(level)` | Backlight 0–255 while the app shows; `-1` hands it back to the system |
| `haScreen.info()` | JSON: app version, device model, Android version |

Changes also arrive as events, so the page doesn't need to poll:

```js
addEventListener("ha-screen-sensor", (event) => {
  const { type, value } = event.detail; // type: "light" | "proximity" | "near" (1 or 0) | "battery" (percent) | "charging" (1 or 0)
});
```

A page that should also work in a normal browser checks first: `if (window.haScreen) { ... }`.

To get the readings into Home Assistant, use the MQTT sensors above rather than relaying them from the page. A dashboard's user usually isn't an admin, and only admins may fire events.

## How it works

The app is six classes under `app/src/main/java/io/github/nspanelhascreen/`:

| File | Role |
|---|---|
| `MainActivity.java` | The screen: settings, the WebView, fullscreen, retries and crash recovery |
| `ScreenBridge.java` | Reads the sensors, turns them into readings for the page (`window.haScreen`, events) and for MQTT |
| `HaSensors.java` | Home Assistant's side of the readings: MQTT discovery and state topics |
| `MqttLink.java` | A minimal MQTT 3.1.1 client (about 300 lines, no library) |
| `BootReceiver.java` | Opens the screen after the device boots |
| `WirelessAdb.java` | Switches wireless debugging back on when Wi-Fi connects, if asked to (Android 11+) |

### The screen

- **Settings** come from the `.Setup` extras and are saved in the app's private preferences (`screen`), so they survive restarts and updates. A changed address or `debug` setting reloads the page. A changed name or broker restarts the MQTT link, and a changed `near_above` applies at once.
- **Before a page is set**, a bundled page shows the `adb` command to set one.
- **Fullscreen:** immersive sticky mode hides the system bars and brings them back hidden after every focus change. The window keeps the screen on.
- **Links and redirects** stay inside the app, including the Home Assistant login. There is no address bar and no way out, which is what a wall screen wants.
- **When the page fails to load** (the main document, not a single image or request), it tries again every 10 seconds. A panel that boots faster than the Wi-Fi or Home Assistant shows the dashboard once both are up.
- **When the WebView's renderer dies**, which happens when small panels run out of memory, the app throws the WebView away and builds a new one with the same page. The rest of the app keeps running.
- **When the app itself crashes**, an alarm set by the crash handler opens it again 3 seconds later.
- **At boot**, `BootReceiver` opens the screen. The activity is `singleTask`, so a second start reuses the open screen instead of stacking another.
- **Media** may play without a tap, so a doorbell chime or camera audio works on a screen nobody touched.

### The sensors

`ScreenBridge` listens to the light and proximity sensors only while the screen is in front.

- **Light** is passed on when it changes by 10% and by at least 2 lx, at most once a second. If a change comes sooner, the latest value goes out when the second is over. Dark rooms jitter by a lux or two, which the 2 lx floor ignores.
- **Proximity** is averaged over one-second windows. Some panels, the NSPanel Pro among them, report a raw, jittery value many times a second. An average is passed on when it is 2 or more away from the last one sent.
- **Near** is worked out from each average:
  - With `near_above` set, near means the average is above it. This suits raw sensors whose value grows as things come closer.
  - Without it, near means below the sensor's maximum range, as Android defines proximity.
  - It is passed on only when it flips.

Every reading goes to the page as a `ha-screen-sensor` event and to `HaSensors`.

### MQTT

`HaSensors` turns the name into a slug (`Bedroom screen` → `bedroom_screen`) and uses it everywhere:

| Topic | Payload |
|---|---|
| `homeassistant/sensor/<slug>/light/config` | Discovery: illuminance in lx, measurement |
| `homeassistant/sensor/<slug>/proximity/config` | Discovery: measurement |
| `homeassistant/binary_sensor/<slug>/near/config` | Discovery: occupancy |
| `nspanel-ha-screen/<slug>/light` | Lux, rounded; at most every 5 s, the latest value wins |
| `nspanel-ha-screen/<slug>/proximity` | The one-second average, one decimal |
| `nspanel-ha-screen/<slug>/near` | `ON` / `OFF` |
| `nspanel-ha-screen/<slug>/status` | `online`, or `offline` from the last will |

The discovery messages share one device: the panel's name, the manufacturer and model Android reports, and the app version. The unique ids are `nspanel-ha-screen_<slug>_<sensor>`, so renaming a panel makes a new device in Home Assistant; delete the old one there.

`MqttLink` keeps the connection:

- It logs in with the client id `nspanel-ha-screen-<slug>` and a clean session. The last will is a retained `offline` on the status topic.
- Every message is published retained, at QoS 0.
- It keeps the latest message per topic and sends them all again after every reconnect, so the broker is never left with stale discovery or state.
- **Liveness:** after 30 seconds with nothing from the broker it sends a ping. If the next 30 seconds are silent too, it counts the link as dead and reconnects. The keep-alive it announces is 60 seconds.
- **Reconnects** start 5 seconds after a drop and double each time, up to 2 minutes.
- When the app stops, the socket is closed without a goodbye, so the broker publishes the last will and Home Assistant marks the sensors unavailable.

## Security

A wall panel is a shared, always-on device, so the app keeps what it trusts narrow.

- **Only adb can change the settings.** The page, the MQTT broker and login, and debugging are taken only through the `.Setup` entry point. That entry point requires `android.permission.DUMP`, which only the system and adb's shell hold; ordinary apps can't get it. Without this, any app installed on the panel could point the screen at another page, or point the sensors at its own broker and receive the saved MQTT login. The launcher entry (`.MainActivity`) ignores extras.
- **Only web pages.** The page address must be `http://` or `https://`. The WebView may not read files or content providers on the device.
- **Credentials stay on the device.** The MQTT login is saved in the app's private storage, and Android backups are turned off for the app (`allowBackup="false"`), so it isn't copied off the panel.
- **Not encrypted on the network.**
  - MQTT goes over plain TCP; TLS isn't supported. The MQTT login and readings can be seen by anyone on the same network, so keep the broker on your local network and give the panels their own MQTT user.
  - Plain `http://` pages are allowed, because most Home Assistant installs are reached that way at home. The same applies to the dashboard and its login unless you use `https://`.
- **The page bridge is small.** `window.haScreen` can read the light and proximity sensors and the battery state, and set the backlight, nothing else. It is there on any page the screen shows, including one reached by a link from your dashboard. A page elsewhere could at most read those values or dim the screen.
- **DevTools is off** until you turn it on with `--ez debug true`.
- **Wireless debugging is only kept on when you ask for it**, with both the `WRITE_SECURE_SETTINGS` grant (adb only) and `--ez wireless_adb true`. The app writes that one setting and nothing else with the permission. Anyone who can reach adb on the screen can do anything adb can, so only turn it on for screens on a network you trust.
- **Permissions:** internet, network state (to notice Wi-Fi coming back), start at boot, "draw over other apps" (only used so Android 10+ lets it start at boot), and optionally `WRITE_SECURE_SETTINGS` (see above).

## About the NSPanel Pro's sensors

On the NSPanel Pro, both sensors report raw values rather than calibrated ones:

- Proximity reads about 10–15 with nothing in front, and its reported maximum (9) is below that, so the Android meaning of near would always be "far". Set `near_above`.
- Light reads in the thousands indoors.

Watch the values for a while before deciding what "near" or "dark" means on your panel. On ours, proximity above 20 meant something within about 25 cm (`--es near_above 20`). Light went from 20 000+ in daylight to 60–150 with the blinds closed.

## Troubleshooting

- **What the app logs:** `adb logcat -s HaScreen MqttLink` shows ignored settings and MQTT connection problems (refused login, broker unreachable).
- **Sensors stay unavailable in Home Assistant:**
  - check the name and broker were set (both are needed) and that the login works;
  - a refused connection logs `broker refused the connection, code 4` (bad user or password) or `code 5` (not authorized).
- **Blank or old page:** `adb shell am start -n io.github.nspanelhascreen/.Setup --ez reload true`. With `--ez debug true` you can open the page in Chrome DevTools from a computer.
- **The setting didn't change anything:** make sure the command uses `.Setup`, not `.MainActivity`.
- **Doesn't open after a reboot (Android 10+):** grant `SYSTEM_ALERT_WINDOW` as shown above, and check no other kiosk app starts at boot.

## Building

You need JDK 17+ and the Android SDK; Android Studio has both.

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without signing settings, the release build is signed with the debug key, so it installs as is. To sign with your own key, put this in `~/.gradle/gradle.properties` (outside the repository):

```properties
nspanelHaScreenStoreFile=/path/to/your-release-key.p12
nspanelHaScreenStorePassword=...
nspanelHaScreenKeyAlias=nspanel-ha-screen
```

The key is expected to be PKCS12 with the same password for the store and the key:

```sh
keytool -genkeypair -storetype PKCS12 -keystore your-release-key.p12 -alias nspanel-ha-screen -keyalg RSA -keysize 4096 -validity 11000
```

The app supports Android 8.0 (API 26) and newer.

## License

MIT
