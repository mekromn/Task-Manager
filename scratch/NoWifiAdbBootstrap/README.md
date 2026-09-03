# No-WiFi ADB v0.3

Rootless Android 16 experiment for recovering ADB after reboot without joining an external Wi-Fi network.

## One-time preparation

Install v0.3 over v0.2 while the proven `127.0.0.1:5555` ADB shell is still alive. v0.3 declares `WRITE_SECURE_SETTINGS` and uses that already-authorized shell to grant the protected permission to itself once. The package, app data, paired ADB host key, and permission grant survive ordinary reboots.

## Reboot recovery experiment

After reboot with normal Wi-Fi unavailable, the app starts a public-API `LocalOnlyHotspot`, rapidly cycles `Settings.Global` key `adb_wifi_enabled`, and scans `_adb-tls-connect._tcp`. This automates the Android 16 hotspot / Wireless Debugging race reported and reproduced publicly in March 2026. If the encrypted local ADB service survives long enough, the already-paired embedded ADB client connects to it and immediately sends `adb tcpip 5555`, restoring the same `uid=2000(shell)` localhost path proven by v0.2.

LocalOnlyHotspot may not trigger the exact race on every Pixel build. v0.3 keeps a manual Mobile Hotspot fallback button for that case.

## ADB binary

CI downloads the arm64 `libadb.so` from LADB commit `60f48029cf9d8e0bc848ca41a7bd76694d4ab796` and packages it as an executable native library. LADB's license is reproduced in `licenses/LADB-LICENSE.txt`.
