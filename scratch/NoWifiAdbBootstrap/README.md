# No-WiFi ADB v0.5

Rootless Android 16 utility that converts an authenticated Wireless Debugging transport into classic `adbd` TCP and then uses the real ADB shell through localhost after Wi-Fi is disconnected.

## Proven foundation

On Pixel 9 Pro XL / Android 16, v0.2 successfully paired to the phone's own Wireless Debugging service, sent `adb tcpip 5555`, reconnected to `127.0.0.1:5555`, and verified the real `uid=2000(shell)` / `u:r:shell:s0` context after Wi-Fi loss. v0.3 successfully migrated that live session from `5555` to multiple random high ports while preserving the same shell identity. v0.4's destructive lifecycle test confirmed that `adb usb` really shuts classic TCP down while preserving the app's saved ADB host key, and also measured that Android 16 stops advertising `_adb-tls-connect._tcp` until Wireless debugging is restarted.

## v0.5

- Keeps automatic `_adb-tls-connect._tcp` discovery; no manual IP or debug-port entry.
- Keeps first-time `_adb-tls-pairing._tcp` discovery; the user enters only Android's six-digit pairing code.
- ADB host key remains in app-private storage across APK updates.
- Classic ADB uses a random high port rather than fixed port 5555.
- Legacy port 5555 is still detected for in-place migration from v0.2.
- Every active state is verified with `shell id` and requires both `uid=2000` and `u:r:shell:s0`.
- Built-in shell command runner.
- Fixes the v0.4 false-negative Shizuku lookup. Instead of relying on `pm path` from the shell, the app now uses Android `PackageManager` to resolve exactly `moe.shizuku.privileged.api`, reads `sourceDir` and `nativeLibraryDir`, then starts Shizuku using the same `libshizuku.so --apk=<sourceDir>` shape as current Shizuku itself.
- Manifest package visibility is narrowly scoped with `<queries><package android:name="moe.shizuku.privileged.api"/></queries>`; this adds no runtime permission and does not request broad package visibility.
- Full repair lifecycle now records a persistent recovery-pending state after classic TCP is intentionally stopped.
- On the measured Pixel/Android 16 behavior where `_adb-tls-connect._tcp` disappears after `adb usb`, the UI now reports `WIRELESS DEBUGGING RESTART REQUIRED` rather than a generic failure.
- When recovery is pending, opening Wireless debugging and returning to No-WiFi ADB automatically retries the saved host key. A fresh six-digit pairing code should not be needed unless Android actually rejects the saved key.
- Disable action returns `adbd` to USB mode and explicitly cancels automatic recovery.
- Quick Settings tile reports the local listener; tap while active disables TCP mode, tap while inactive opens the app.
- Android 16 edge-to-edge system-bar insets are handled correctly.

The production manifest requests only `android.permission.INTERNET`. It does not request location, Nearby Wi-Fi, Wi-Fi control, accessibility, root, Shizuku, `QUERY_ALL_PACKAGES`, or `WRITE_SECURE_SETTINGS` permissions.

## Security boundary

Normal ADB authentication remains enabled. Stock `adb tcpip` does not bind only to loopback; while a network interface is up, the randomly selected port may also listen on that interface. The random port reduces accidental exposure but is not treated as a security boundary. The ADB host-key challenge remains the authorization boundary. With Wi-Fi disconnected, the app continues through `127.0.0.1`.

## Reboot behavior

Classic TCP mode survives Wi-Fi loss but not a full reboot/adbd restart on stock Android. v0.5 makes the normal post-`adb usb` recovery flow explicit and automatic after the user restarts Wireless debugging. Fully autonomous recovery after a cold reboot without an external Wi-Fi association remains a separate research target, as does true loopback-only `adbd` binding.

## ADB binary

CI downloads the arm64 `libadb.so` from LADB commit `60f48029cf9d8e0bc848ca41a7bd76694d4ab796` and packages it as an executable native library. LADB's license is reproduced in `licenses/LADB-LICENSE.txt`.
