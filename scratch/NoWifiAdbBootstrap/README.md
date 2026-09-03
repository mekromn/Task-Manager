# No-WiFi ADB v0.4

Rootless Android 16 utility that converts an authenticated Wireless Debugging transport into classic `adbd` TCP and then uses the real ADB shell through localhost after Wi-Fi is disconnected.

## Proven foundation

On Pixel 9 Pro XL / Android 16, v0.2 successfully paired to the phone's own Wireless Debugging service, sent `adb tcpip 5555`, reconnected to `127.0.0.1:5555`, and verified the real `uid=2000(shell)` / `u:r:shell:s0` context after Wi-Fi loss. v0.3 then successfully migrated that live session from `5555` to multiple random high ports while preserving the same shell identity.

## v0.4

- Keeps automatic `_adb-tls-connect._tcp` discovery; no manual IP or debug-port entry.
- Keeps first-time `_adb-tls-pairing._tcp` discovery; the user enters only Android's six-digit pairing code.
- ADB host key remains in app-private storage across APK updates.
- Classic ADB uses a random high port rather than fixed port 5555.
- Legacy port 5555 is still detected for in-place migration from v0.2.
- Every active state is verified with `shell id` and requires both `uid=2000` and `u:r:shell:s0`.
- Built-in shell command runner.
- Shizuku startup now mirrors current Shizuku itself: locate the installed `libshizuku.so`, obtain Shizuku's APK path, and execute `libshizuku.so --apk=<sourceDir>` through the verified localhost ADB shell.
- New full repair lifecycle test deliberately sends `adb usb`, confirms the classic TCP listener is gone, then attempts to rediscover Wireless Debugging with the already-saved host key and rebuild a new random localhost transport.
- If Android stops advertising Wireless Debugging after `adb usb`, the repair test leaves the pairing key intact and instructs the user to toggle Wireless debugging once and press Bootstrap / Repair.
- Disable action returns `adbd` to USB mode.
- Quick Settings tile reports the local listener; tap while active disables TCP mode, tap while inactive opens the app.
- Android 16 edge-to-edge system-bar insets are handled correctly.

The production manifest requests only `android.permission.INTERNET`. It does not request location, Nearby Wi-Fi, Wi-Fi control, accessibility, root, Shizuku, or `WRITE_SECURE_SETTINGS` permissions.

## Security boundary

Normal ADB authentication remains enabled. Stock `adb tcpip` does not bind only to loopback; while a network interface is up, the randomly selected port may also listen on that interface. The random port reduces accidental exposure but is not treated as a security boundary. The ADB host-key challenge remains the authorization boundary. With Wi-Fi disconnected, the app continues through `127.0.0.1`.

## Reboot behavior

Classic TCP mode survives Wi-Fi loss but not a full reboot/adbd restart on stock Android. v0.4 deliberately does not ship the unproven protected-settings/hotspot race experiment as its default path. Reboot recovery and loopback-only binding remain separate research targets after the normal repair path is validated.

## ADB binary

CI downloads the arm64 `libadb.so` from LADB commit `60f48029cf9d8e0bc848ca41a7bd76694d4ab796` and packages it as an executable native library. LADB's license is reproduced in `licenses/LADB-LICENSE.txt`.
