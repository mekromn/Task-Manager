# Task Manager

A native Android process/task manager built for power users.

Task Manager is **not a to-do app**. It is a live Android process explorer and app-process control utility with three progressively deeper backends:

1. **Standard Android** — works with no special setup, within Android's normal process-visibility limits.
2. **Shizuku** — runs privileged process queries/actions as ADB shell, or root when Shizuku itself is root.
3. **Root** — direct `su` backend for the deepest process control.

## Highlights

- Live CPU, RAM, load-average and uptime dashboard
- 60-sample CPU/RAM history graphs
- Running process list with PID, PPID, UID, RSS memory and CPU
- App/system/native process filters
- Search by app label, process, package or PID
- Sort by CPU, memory, name or PID
- Real process actions:
  - End process (`SIGTERM`)
  - Force kill (`SIGKILL`)
  - Force-stop Android package
  - Batch stop selected processes/apps
- Critical-process protection for `init`, zygote, `system_server`, SurfaceFlinger and other core processes
- Installed-app browser
- Running-state indicator
- Optional Usage Access for last-used timestamps
- App launch, App Info, package-name copy and uninstall shortcuts
- `dumpsys meminfo` viewer in Enhanced mode
- Standard / Shizuku / Root / Auto backend selection
- Shizuku permission/status UI
- Root test/enable UI
- 1s / 2s / 5s / 10s live refresh
- System, Light, Dark and true-black AMOLED themes
- Material 3 / Jetpack Compose UI
- Android 10+ (`minSdk 29`), optimized for modern Android

## Android limitations

Modern Android intentionally limits process visibility and cross-app termination for ordinary apps. Standard mode therefore shows only what Android exposes to Task Manager.

For a true system-wide `ps`/`top` process list and privileged controls, use **Shizuku** or **root**. Shizuku started through wireless debugging usually runs as Android's `shell` UID; Shizuku started with root runs as UID 0.

Some raw PID signals can still be rejected in shell-mode Shizuku because Linux process ownership rules remain in effect. Package-level `am force-stop` is generally the better Shizuku action. Root is the most complete backend.

## Build

The repository includes a GitHub Actions workflow that builds an installable debug APK on every push.

Local command, with Android SDK 37 / Build Tools 36.0.0 and Gradle 9.5+ installed:

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Stack

- Kotlin
- Jetpack Compose / Material 3
- Android Gradle Plugin 9.3
- Compose BOM 2026.08
- Shizuku API 13.1.5
- Coroutines / StateFlow

## Package

```text
com.mekromn.taskmanager
```
