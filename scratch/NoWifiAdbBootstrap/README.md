# No-WiFi ADB v0.2

Rootless Android experiment for converting an already-authorized Android Wireless Debugging connection into classic ADB TCP on localhost:5555.

## Important limitation

The bootstrap requires one working ADB transport (Wireless Debugging or USB) after each full reboot. Once `adb tcpip 5555` succeeds, the phone can disconnect from Wi-Fi and the app can use `127.0.0.1:5555` until adbd is reset or the phone reboots.

## Why this route

Pixel 9 Pro XL / Android 16 testing showed that LocalOnlyHotspot can start and STA+AP concurrency is supported, but the phone refuses to associate its STA interface to its own SoftAP and `WifiManager.getConnectionInfo()` remains networkId=-1. Therefore the fake/self-Wi-Fi route cannot satisfy Android's Wireless Debugging framework check on this device.

## ADB binary

CI downloads the arm64 `libadb.so` from LADB commit `60f48029cf9d8e0bc848ca41a7bd76694d4ab796` and packages it as an executable native library. LADB's license is reproduced in `licenses/LADB-LICENSE.txt`. This project must not be uploaded to Google Play under that license.
