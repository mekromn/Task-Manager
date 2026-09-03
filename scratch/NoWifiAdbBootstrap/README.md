# No-WiFi ADB v0.3

Rootless Android 16 utility that converts an authenticated Wireless Debugging transport into classic `adbd` TCP and then uses the real ADB shell through localhost after Wi-Fi is disconnected.

## Proven foundation

On Pixel 9 Pro XL / Android 16, v0.2 successfully paired to the phone's own Wireless Debugging service, sent `adb tcpip 5555`, reconnected to `127.0.0.1:5555`, and verified the real `uid=2000(shell)` / `u:r:shell:s0` context after Wi-Fi loss.

## v0.3 productionization

- Automatic `_adb-tls-connect._tcp` discovery; no manual IP or debug-port entry.
- First-time `_adb-tls-pairing._tcp` discovery; the user enters only Android's six-digit pairing code.
- ADB host key is kept in app-private storage and survives APK updates.
- New bootstraps choose a random high classic-ADB port rather than fixed port 5555.
- v0.2 port 5555 is still detected so v0.3 can update in place without breaking an already-active session.
- Every active state is verified with `shell id` and requires `uid=2000` plus `u:r:shell:s0`.
- Built-in shell command runner.
- One-tap Shizuku starter using Shizuku's standard ADB start script.
- Disable action returns `adbd` to USB mode.
- Quick Settings tile reports the local listener; tap while active disables TCP mode, tap while inactive opens the app.
- Android 16 edge-to-edge system-bar insets are handled correctly.

The production manifest requests only `android.permission.INTERNET`. It does not request location, Nearby Wi-Fi, Wi-Fi control, accessibility, root, Shizuku, or `WRITE_SECURE_SETTINGS` permissions.

## Security boundary

Normal ADB authentication remains enabled. Stock `adb tcpip` does not bind only to loopback; while a network interface is up, the randomly selected port may also listen on that interface. The random port reduces accidental exposure but is not treated as a security boundary. The ADB host-key challenge remains the authorization boundary. With Wi-Fi disconnected, the app continues through `127.0.0.1`.

## Reboot behavior

Classic TCP mode survives Wi-Fi loss but not a full reboot/adbd restart on stock Android. v0.3 deliberately does not ship the unproven protected-settings/hotspot race experiment as its default path. Reboot recovery and loopback-only binding are separate research targets after this production path is validated.

## ADB binary

CI downloads the arm64 `libadb.so` from LADB commit `60f48029cf9d8e0bc848ca41a7bd76694d4ab796` and packages it as an executable native library. LADB's license is reproduced in `licenses/LADB-LICENSE.txt`.
