# nspanel-ha-screen

A tiny Android app that turns a wall panel into one fullscreen web page, made for Home Assistant dashboards on the Sonoff NSPanel Pro (Android 8.1) and similar wall screens.

It is a lighter alternative to a full kiosk browser: one activity, one system WebView, no settings screen, no libraries. The APK is about 32 KB.

## What it does

- Shows one page fullscreen (status and navigation bars hidden) and keeps the screen on.
- Starts when the device boots, comes back if it crashes, retries when the page can't load (Wi-Fi or Home Assistant down), and rebuilds the page if the WebView's renderer dies.
- Plays audio and video without a tap first, such as a doorbell chime or a camera stream with sound.
- Reports the panel's light and proximity sensors to Home Assistant over MQTT, as a device found by MQTT discovery.
- Gives the page the same sensors and control of the backlight, through `window.haScreen`.

It does not replace the launcher, and the back button does nothing.

## Install

Build it (JDK 17+ and the Android SDK; Android Studio has both):

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key so it installs as is. Sign it with your own key if you hand builds to others.

Choose the page with adb. The app remembers it:

```sh
adb shell am start -n io.github.nspanelhascreen/.MainActivity --es url http://homeassistant.local:8123/lovelace/0
```

Log in to Home Assistant once on the panel; the WebView keeps the session.

Other extras:

| Extra | Effect |
|---|---|
| `--es url <address>` | Show this page from now on |
| `--ez reload true` | Load the page again |
| `--ez debug true` | Allow Chrome DevTools on the page (`chrome://inspect`, or `adb forward` to `webview_devtools_remote_<pid>`) |
| `--es name <name>` | The panel's name in Home Assistant, e.g. `"Bedroom screen"` |
| `--es mqtt <host[:port]>` | Report the sensors to this MQTT broker (port 1883 by default); `""` stops |
| `--es mqtt_user <user>` / `--es mqtt_password <password>` | The broker login |

## Sensors in Home Assistant

Give the panel a name and an MQTT broker:

```sh
adb shell am start -n io.github.nspanelhascreen/.MainActivity --es name "'Bedroom screen'"   --es mqtt homeassistant.local --es mqtt_user <user> --es mqtt_password <password>
```

(The inner quotes keep a name with spaces in one piece through `adb shell`.)

Home Assistant's MQTT integration finds a device with that name and two sensors:

| Entity | Value |
|---|---|
| `sensor.<name>_light` | Light level in lux as Android reports it, at most every 5 seconds |
| `sensor.<name>_proximity` | Proximity, averaged over one second, sent when it moves by 2 or more |

Both turn unavailable when the app stops (MQTT last will). The values are retained, so Home Assistant has them again after a restart.

With the Mosquitto broker app, a Home Assistant user can log in to MQTT; a dedicated user for the panels is best.

### Starting at boot on Android 10 and newer

Android 10+ only lets an app open itself at boot if it may draw over other apps:

```sh
adb shell appops set io.github.nspanelhascreen SYSTEM_ALERT_WINDOW allow
```

Android 8 and 9 (the NSPanel Pro) need nothing.

If another kiosk app also starts at boot, turn that off or disable it, so the two don't fight over the screen.

## The page side: `window.haScreen`

| Call | Returns |
|---|---|
| `haScreen.light()` | Light level in lux as Android reports it, or `NaN` |
| `haScreen.proximity()` | Proximity, averaged over the last second, or `NaN` without a sensor |
| `haScreen.proximityRange()` | The sensor's reported maximum |
| `haScreen.setBrightness(level)` | Backlight 0–255 while the app shows; `-1` hands it back to the system |
| `haScreen.info()` | JSON: app version, device model, Android version |

Changes also arrive as events, so the page doesn't need to poll:

```js
addEventListener("ha-screen-sensor", (event) => {
  const { type, value } = event.detail; // type: "light" | "proximity"
});
```

Light is sent when it changes by 10% (and at least 2 lx), at most once a second. Proximity is averaged over one second and sent when the average moves by 2 or more.

For Home Assistant, the MQTT sensors above are simpler than relaying the readings from the page: a dashboard's user usually isn't an admin, and only admins may fire events.

### About the NSPanel Pro's sensors

On the NSPanel Pro, both sensors report raw values rather than calibrated ones:

- Proximity reads about 10–15 with nothing in front, and its reported maximum (9) is below that.
- Light reads in the thousands indoors.

Watch the values for a while before deciding what "near" or "dark" means on your panel.

## License

MIT
