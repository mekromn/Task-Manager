# Task Manager development signing

Debug/sideload builds use `signing/taskmanager-dev.jks` as a deliberately stable **development-only** signing identity.

This prevents GitHub-hosted runners from silently generating a different Android debug certificate on each build, which would make Android reject later APKs with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / “App not installed”.

## Stable development identity

- Alias: `taskmanager-dev`
- Store password: `taskmanager`
- Key password: `taskmanager`
- SHA-256 certificate fingerprint: `A0:F2:C6:9A:84:01:C5:30:44:21:65:BE:B5:B2:97:2D:BB:C8:12:74:72:D5:AD:75:C1:6B:BC:C1:72:55:89:83`

The workflow verifies this fingerprint after every build and fails if it changes.

## Version codes

Canonical GitHub Actions APKs use `100000 + GITHUB_RUN_NUMBER`, so CI artifacts monotonically increase instead of remaining stuck at versionCode 1.

## Important

This key is checked into the public source tree on purpose so development builds remain update-compatible across ephemeral CI machines. It is **not a private production signing key** and must never be used for a Play Store or security-sensitive production release. A future production release should use a separately generated private key stored in GitHub Secrets or another secure signing service.

The first APK produced after this migration is the signing reset point: install it once after removing any APK signed by the old ephemeral runner key. All later Task Manager CI APKs are expected to update it in place.
