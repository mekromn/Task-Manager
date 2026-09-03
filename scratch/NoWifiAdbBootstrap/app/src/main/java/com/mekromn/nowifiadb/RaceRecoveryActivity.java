package com.mekromn.nowifiadb;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v0.3 experiment:
 *  - While v0.2's proven localhost ADB is still alive, self-grant WRITE_SECURE_SETTINGS once.
 *  - After a later reboot, create a local hotspot and race adb_wifi_enabled while discovering
 *    _adb-tls-connect._tcp. If the real adbd TLS endpoint comes alive, reconnect with the ADB
 *    host key retained from v0.2 and immediately switch adbd back to classic TCP:5555.
 */
public final class RaceRecoveryActivity extends Activity {
    private static final int REQ_NEARBY_WIFI = 701;
    private static final long RACE_MS = 30_000L;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private final AtomicInteger discoveredPort = new AtomicInteger(0);

    private TextView status;
    private TextView capability;
    private TextView logView;

    private WifiManager wifi;
    private NsdManager nsd;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private NsdManager.DiscoveryListener nsdListener;
    private volatile boolean hotspotStarting;
    private volatile boolean runAfterPermission;
    private volatile boolean stopRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifi = getSystemService(WifiManager.class);
        nsd = getSystemService(NsdManager.class);
        setContentView(buildUi());

        append("No-WiFi ADB v0.3");
        append("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT);
        append("v0.2 proved 127.0.0.1:5555 can provide uid=2000(shell). v0.3 tests a rootless post-reboot recovery path.");
        refreshCapability();

        runAsync("Checking current capability", this::prepareInternal);
    }

    @Override
    protected void onDestroy() {
        stopRequested = true;
        stopNsd();
        stopLocalHotspot();
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView outer = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(28));
        root.setBackgroundColor(Color.rgb(5, 7, 10));
        outer.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // targetSdk 36 is edge-to-edge. Consume the actual system-bar insets.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(18) + bars.left, dp(16) + bars.top, dp(18) + bars.right, dp(28) + bars.bottom);
            return insets;
        });
        root.requestApplyInsets();

        root.addView(text("No-WiFi ADB", 29, Color.WHITE, true));
        root.addView(text(
                "Rootless Android 16 recovery experiment. Prepare it once while v0.2's localhost ADB is still alive; then test recovery after reboot without joining an external Wi-Fi network.",
                15, Color.rgb(188, 196, 208), false), margins(0, 6, 0, 14));

        status = text("Checking…", 16, Color.rgb(138, 216, 255), true);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackgroundColor(Color.rgb(18, 25, 34));
        root.addView(status, matchWrap());

        capability = text("Recovery permission: checking…", 14, Color.rgb(255, 199, 120), true);
        root.addView(capability, margins(0, 10, 0, 14));

        root.addView(section("1 · Before reboot"));
        root.addView(text(
                "Install v0.3 over v0.2 while 127.0.0.1:5555 still works. The app uses that already-authorized uid=2000 shell to grant itself WRITE_SECURE_SETTINGS. The app will not say READY unless Android confirms the grant.",
                14, Color.rgb(207, 213, 222), false), margins(0, 5, 0, 5));
        root.addView(button("Prepare reboot recovery (one time)", v -> runAsync("Preparing reboot recovery", this::prepareInternal)));
        root.addView(button("Test localhost:5555", v -> runAsync("Testing localhost:5555", this::testLocalhost)));

        root.addView(section("2 · After reboot, no external Wi-Fi"));
        root.addView(text(
                "Automatic recovery starts an app-owned local hotspot, rapidly creates fresh adb_wifi_enabled 0→1 transitions, discovers the local ADB TLS service, reconnects with the paired key retained from v0.2, and converts adbd back to TCP:5555.",
                14, Color.rgb(207, 213, 222), false), margins(0, 5, 0, 5));
        root.addView(button("Automatic no-WiFi reboot recovery", v -> startRecoveryFromUi()));
        root.addView(button("Stop recovery / local hotspot", v -> stopRecovery()));
        root.addView(button("Open Mobile Hotspot settings (fallback)", v -> openHotspotSettings()));
        root.addView(button("Open Wireless debugging settings", v -> openWirelessDebugging()));

        root.addView(section("Known-good v0.2 fallback"));
        root.addView(button("Open manual pairing / bootstrap tools", v -> startActivity(new Intent(this, MainActivity.class))));

        root.addView(section("Diagnostic log"));
        root.addView(button("Copy full log", v -> copyLog()));
        logView = text("", 12, Color.rgb(213, 219, 228), false);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logView.setBackgroundColor(Color.rgb(12, 15, 20));
        root.addView(logView, margins(0, 8, 0, 0));
        return outer;
    }

    private TextView section(String value) {
        TextView t = text(value, 18, Color.WHITE, true);
        t.setPadding(0, dp(16), 0, dp(2));
        return t;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(listener);
        b.setLayoutParams(margins(0, 6, 0, 0));
        return b;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private boolean hasSecureSettings() {
        return checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasNearbyWifi() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshCapability() {
        runOnUiThread(() -> {
            if (capability == null) return;
            boolean ready = hasSecureSettings();
            capability.setText("Reboot recovery permission: " + (ready ? "GRANTED ✓" : "NOT GRANTED"));
            capability.setTextColor(ready ? Color.rgb(135, 235, 170) : Color.rgb(255, 199, 120));
        });
    }

    /** One-time step while v0.2's localhost ADB transport is still available. */
    private String prepareInternal() {
        append("=== ONE-TIME PREPARATION ===");
        if (hasSecureSettings()) {
            refreshCapability();
            return "READY: WRITE_SECURE_SETTINGS is already granted. The post-reboot experiment can be tested.";
        }

        String local = testLocalhost();
        if (!local.contains("uid=2000")) {
            refreshCapability();
            return "NOT READY: 127.0.0.1:5555 is not currently a uid=2000 ADB shell. Do not reboot yet; restore it with the manual v0.2 tools first.";
        }

        append("Granting protected settings permission through the proven localhost shell…");
        Result grant = adb(Arrays.asList(
                "-s", "127.0.0.1:5555", "shell", "pm", "grant",
                getPackageName(), Manifest.permission.WRITE_SECURE_SETTINGS), 12);
        append("pm grant exit=" + grant.exitCode + (grant.output.isEmpty() ? "" : " output=" + oneLine(grant.output)));
        sleep(500);

        boolean granted = hasSecureSettings();
        append("WRITE_SECURE_SETTINGS granted=" + granted);
        refreshCapability();
        return granted
                ? "READY: one-time grant succeeded. Keep v0.3 installed; now the no-external-Wi-Fi reboot recovery can be tested."
                : "GRANT FAILED: localhost ADB worked, but Android did not retain WRITE_SECURE_SETTINGS. Send this log before rebooting.";
    }

    private void startRecoveryFromUi() {
        if (!hasSecureSettings()) {
            toast("Run the one-time preparation while localhost ADB still works");
            return;
        }
        if (!hasNearbyWifi()) {
            runAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_NEARBY_WIFI);
            return;
        }
        stopRequested = false;
        runAsync("Running no-WiFi recovery race", this::recoverAfterReboot);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_NEARBY_WIFI) return;
        boolean granted = hasNearbyWifi();
        append("Nearby Wi-Fi permission granted=" + granted);
        if (granted && runAfterPermission) {
            runAfterPermission = false;
            stopRequested = false;
            runAsync("Running no-WiFi recovery race", this::recoverAfterReboot);
        } else {
            runAfterPermission = false;
            setStatus("Nearby Wi-Fi permission is needed for the app-owned hotspot attempt");
        }
    }

    private void stopRecovery() {
        stopRequested = true;
        stopNsd();
        stopLocalHotspot();
        setStatus("Recovery stopped");
    }

    private String recoverAfterReboot() {
        append("=== ANDROID 16 NO-EXTERNAL-WIFI RECOVERY ===");
        append("Wi-Fi enabled=" + safeWifiEnabled() + ", localhost:5555 already open=" + rawPortOpen());

        if (!hasSecureSettings()) return "FAILED: WRITE_SECURE_SETTINGS is missing.";

        String existing = testLocalhost();
        if (existing.contains("uid=2000")) return "LOCALHOST ADB ALREADY ACTIVE — no recovery needed.\n" + existing;

        discoveredPort.set(0);
        boolean localHotspot = startLocalHotspotAndWait(8_000);
        if (localHotspot) {
            append("App-owned hotspot is active. Starting ADB TLS discovery.");
        } else {
            append("App-owned LocalOnlyHotspot did not become active. Continuing the race anyway in case full Mobile Hotspot is already enabled.");
        }

        startNsd();
        long end = System.currentTimeMillis() + RACE_MS;
        int cycles = 0;
        int connectAttempts = 0;
        String lastConnect = "";

        try {
            while (!stopRequested && System.currentTimeMillis() < end) {
                // The public reproduction is a race against framework teardown, so create many
                // clean 0 -> 1 edges rather than leaving the setting at one value.
                Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 0);
                sleep(12);
                Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                cycles++;

                int port = discoveredPort.get();

                // ADB itself can also perform mDNS discovery; use it as an independent fallback.
                if (port <= 0 && cycles % 10 == 0) {
                    Result mdns = adb(Arrays.asList("mdns", "services"), 4);
                    int parsed = parseTlsConnectPort(mdns.output);
                    if (parsed > 0) {
                        port = parsed;
                        discoveredPort.compareAndSet(0, parsed);
                        append("ADB mDNS found TLS connect port=" + parsed);
                    }
                }

                if (port > 0) {
                    String ep = "127.0.0.1:" + port;
                    connectAttempts++;
                    append("TLS candidate " + connectAttempts + ": " + ep);
                    Result connect = adb(Arrays.asList("connect", ep), 6);
                    lastConnect = connect.output;
                    append(connect.output);
                    String low = connect.output.toLowerCase(Locale.US);
                    if (connect.exitCode == 0 && (low.contains("connected to") || low.contains("already connected"))) {
                        append("Recovered encrypted ADB transport. Switching the real adbd to TCP:5555 now…");
                        String converted = convertTlsToClassic(ep);
                        if (converted.contains("uid=2000")) {
                            stopNsd();
                            stopLocalHotspot();
                            return "SUCCESS: NO-EXTERNAL-WIFI REBOOT RECOVERY WORKED.\n" + converted;
                        }
                    }
                    // A short-lived stale advertisement is common during the race. Wait for a
                    // fresh advertisement before trying it again.
                    discoveredPort.set(0);
                }

                if (cycles % 25 == 0) {
                    int setting = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", -1);
                    append("race cycles=" + cycles + ", adb_wifi_enabled=" + setting + ", discoveredPort=" + discoveredPort.get());
                }
                sleep(24);
            }
        } catch (SecurityException e) {
            append("Settings write rejected: " + e);
            return "FAILED: Android rejected WRITE_SECURE_SETTINGS at runtime.";
        } finally {
            stopNsd();
            stopLocalHotspot();
        }

        if (stopRequested) return "Recovery stopped by user.";
        return "RACE TIMED OUT after " + cycles + " toggle cycles and " + connectAttempts + " ADB connection attempts. "
                + "If LocalOnlyHotspot is not equivalent to the full Mobile Hotspot race on this Pixel build, open Mobile Hotspot settings, enable the phone hotspot, return here, and run Automatic recovery again."
                + (lastConnect.isEmpty() ? "" : "\nLast ADB connect result: " + oneLine(lastConnect));
    }

    private boolean startLocalHotspotAndWait(long timeoutMs) {
        if (hotspotReservation != null) return true;
        hotspotStarting = true;
        append("Starting LocalOnlyHotspot…");
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspotReservation = reservation;
                    hotspotStarting = false;
                    SoftApConfiguration c = reservation.getSoftApConfiguration();
                    append("LocalOnlyHotspot started: SSID=" + c.getSsid() + ", securityType=" + c.getSecurityType());
                }

                @Override
                public void onStopped() {
                    hotspotReservation = null;
                    hotspotStarting = false;
                    append("LocalOnlyHotspot stopped by framework");
                }

                @Override
                public void onFailed(int reason) {
                    hotspotReservation = null;
                    hotspotStarting = false;
                    append("LocalOnlyHotspot failed reason=" + reason);
                }
            }, main);
        } catch (Throwable t) {
            hotspotStarting = false;
            append("LocalOnlyHotspot start exception: " + t);
            return false;
        }

        long end = System.currentTimeMillis() + timeoutMs;
        while (!stopRequested && hotspotStarting && System.currentTimeMillis() < end) sleep(100);
        return hotspotReservation != null;
    }

    private void stopLocalHotspot() {
        WifiManager.LocalOnlyHotspotReservation r = hotspotReservation;
        hotspotReservation = null;
        hotspotStarting = false;
        if (r != null) {
            try { r.close(); } catch (Throwable ignored) { }
            append("LocalOnlyHotspot closed");
        }
    }

    @SuppressWarnings("deprecation")
    private void startNsd() {
        stopNsd();
        discoveredPort.set(0);
        nsdListener = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) { append("NSD discovery started: " + serviceType); }
            @Override public void onDiscoveryStopped(String serviceType) { append("NSD discovery stopped"); }
            @Override public void onServiceLost(NsdServiceInfo serviceInfo) { }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                append("NSD start failed code=" + errorCode);
                try { nsd.stopServiceDiscovery(this); } catch (Throwable ignored) { }
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { append("NSD stop failed code=" + errorCode); }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                try {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            if (errorCode != NsdManager.FAILURE_ALREADY_ACTIVE) append("NSD resolve failed code=" + errorCode);
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo info) {
                            int p = info.getPort();
                            if (p > 0) {
                                discoveredPort.set(p);
                                append("NSD resolved ADB TLS connect port=" + p + " host=" + info.getHost());
                            }
                        }
                    });
                } catch (Throwable t) {
                    append("NSD resolve exception: " + t.getClass().getSimpleName());
                }
            }
        };

        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (Throwable t) {
            append("NSD discovery exception: " + t);
            nsdListener = null;
        }
    }

    private void stopNsd() {
        NsdManager.DiscoveryListener listener = nsdListener;
        nsdListener = null;
        if (listener != null && nsd != null) {
            try { nsd.stopServiceDiscovery(listener); } catch (Throwable ignored) { }
        }
    }

    private int parseTlsConnectPort(String output) {
        if (output == null) return 0;
        for (String line : output.split("\\r?\\n")) {
            if (!line.contains("_adb-tls-connect._tcp")) continue;
            int colon = line.lastIndexOf(':');
            if (colon < 0 || colon + 1 >= line.length()) continue;
            String tail = line.substring(colon + 1).trim();
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                if (Character.isDigit(c)) digits.append(c); else break;
            }
            if (digits.length() == 0) continue;
            try { return Integer.parseInt(digits.toString()); }
            catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    private String convertTlsToClassic(String tlsEndpoint) {
        Result tcp = adb(Arrays.asList("-s", tlsEndpoint, "tcpip", "5555"), 18);
        append(tcp.output);
        if (tcp.exitCode != 0 || !tcp.output.toLowerCase(Locale.US).contains("tcp")) {
            return "TCPIP REQUEST FAILED: " + oneLine(tcp.output);
        }

        sleep(2_500);
        for (int i = 1; i <= 7; i++) {
            append("localhost reconnect attempt " + i + "/7");
            Result c = adb(Arrays.asList("connect", "127.0.0.1:5555"), 7);
            append(c.output);
            String low = c.output.toLowerCase(Locale.US);
            if (low.contains("connected to") || low.contains("already connected")) break;
            sleep(800);
        }
        return testLocalhost();
    }

    private String testLocalhost() {
        append("=== TEST 127.0.0.1:5555 ===");
        if (!rawPortOpen()) {
            append("Raw TCP socket: closed/unreachable");
            return "127.0.0.1:5555 is not listening.";
        }

        append("Raw TCP socket: OPEN");
        Result connect = adb(Arrays.asList("connect", "127.0.0.1:5555"), 9);
        append(connect.output);
        Result id = adb(Arrays.asList("-s", "127.0.0.1:5555", "shell", "id"), 11);
        append("shell id: " + oneLine(id.output));
        if (id.exitCode == 0 && id.output.contains("uid=2000")) {
            return "LOCALHOST ADB WORKS: " + oneLine(id.output);
        }
        return "Port is open, but ADB shell verification failed: " + oneLine(id.output);
    }

    private boolean rawPortOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 900);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean safeWifiEnabled() {
        try { return wifi != null && wifi.isWifiEnabled(); }
        catch (Throwable ignored) { return false; }
    }

    private Result adb(List<String> args, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        command.add(adbPath());
        command.addAll(args);
        append("$ adb " + String.join(" ", args));

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(getFilesDir());
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            process = pb.start();

            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            Thread collector = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (out) { out.append(line).append('\n'); }
                    }
                } catch (Throwable ignored) { }
            }, "adb-output");
            collector.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                collector.join(700);
                String text;
                synchronized (out) { text = out.toString(); }
                return new Result(124, text + "TIMEOUT after " + timeoutSeconds + "s");
            }
            collector.join(900);
            String text;
            synchronized (out) { text = out.toString(); }
            return new Result(process.exitValue(), text.trim());
        } catch (Throwable t) {
            if (process != null) process.destroyForcibly();
            return new Result(127, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private String adbPath() {
        return getApplicationInfo().nativeLibraryDir + File.separator + "libadb.so";
    }

    private void openHotspotSettings() {
        try {
            // There is no public Settings.ACTION_TETHER_SETTINGS field on API 36,
            // but this documented Settings activity action is accepted by Pixels.
            startActivity(new Intent("android.settings.TETHER_SETTINGS"));
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (Throwable ignored) { toast("Could not open Hotspot settings"); }
        }
    }

    private void openWirelessDebugging() {
        try { startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")); }
        catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (Throwable ignored) { toast("Could not open Developer options"); }
        }
    }

    private void runAsync(String busy, Task task) {
        setStatus(busy + "…");
        worker.submit(() -> {
            String result;
            try { result = task.run(); }
            catch (Throwable t) {
                result = "ERROR: " + t;
                append(result);
            }
            final String finalResult = result;
            runOnUiThread(() -> {
                boolean good = finalResult.contains("SUCCESS") || finalResult.contains("READY") || finalResult.contains("LOCALHOST ADB WORKS");
                status.setTextColor(good ? Color.rgb(135, 235, 170) : Color.rgb(138, 216, 255));
                status.setText(finalResult);
                refreshCapability();
            });
        });
    }

    private void setStatus(String value) {
        runOnUiThread(() -> {
            if (status != null) status.setText(value);
        });
    }

    private void append(String value) {
        if (value == null || value.isEmpty()) return;
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (log) {
            for (String line : value.split("\\r?\\n")) {
                log.append(stamp).append("  ").append(line).append('\n');
            }
        }
        runOnUiThread(() -> {
            if (logView != null) {
                synchronized (log) { logView.setText(log.toString()); }
            }
        });
    }

    private void copyLog() {
        String text;
        synchronized (log) { text = log.toString(); }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("No-WiFi ADB v0.3 log", text));
        toast("Log copied");
    }

    private String oneLine(String value) {
        return value == null ? "" : value.trim().replace('\n', ' ').replace('\r', ' ');
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private interface Task {
        String run() throws Exception;
    }

    private static final class Result {
        final int exitCode;
        final String output;
        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
