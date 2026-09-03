# No-WiFi ADB Probe

Rootless Android 16 experiment for testing whether a Pixel can satisfy Android Wireless Debugging's real Wi-Fi preconditions without an external access point.

## Experiment

1. Start a `LocalOnlyHotspot` with public Android APIs.
2. Read its generated SSID and security configuration.
3. Request a Wi-Fi STA connection back to that same hotspot using `WifiNetworkSpecifier`.
4. Compare callback `WifiInfo` with global `WifiManager.getConnectionInfo()`.
5. Flag a potential success only when network ID, SSID, and BSSID all look valid.

## Test

Disconnect from normal Wi-Fi, launch the app, grant Nearby Wi-Fi and Location permissions, tap **Start self-Wi-Fi experiment**, approve any connection prompt, and leave the experiment running while trying **Developer options > Wireless debugging**.

The probe uses no root, Shizuku, hidden-API bypass, or privileged permission grants.
