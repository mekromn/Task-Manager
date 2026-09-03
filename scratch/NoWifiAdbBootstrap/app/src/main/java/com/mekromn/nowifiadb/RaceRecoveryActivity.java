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

public final class RaceRecoveryActivity extends Activity {
    private static final int REQ_NEARBY_WIFI = 701;
    private static final int RACE_TIMEOUT_MS = 30_000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder log = new StringBuilder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger discoveredConnectPort = new AtomicInteger(0);

    private TextView status;
    private TextView capability;
    private TextView logView;
    private WifiManager wifi;
    private NsdManager nsd;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private NsdManager.DiscoveryListener connectDiscovery;
    private volatile boolean hotspotStarting;
    private volatile boolean pendingRaceAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifi = getSystemService(WifiManager.class);
        nsd = getSystemService(NsdManager.class);
        setContentView(buildUi());

        append("No-WiFi ADB v0.3 race recovery");
        append("One-time setup uses the already-proven localhost ADB shell to grant WRITE_SECURE_SETTINGS.");
        append("Reboot recovery automates the Android 16 hotspot / adb_wifi_enabled race, then converts recovered ADB TLS to TCP:5555.");
        updateCapabilityCard();
        runAsync("Checking one-time capability", this::prepareOneTimeCapabilityInternal);
    }

    @Override
    protected void onDestroy() {
        stopNsdDiscovery();
        stopLocalHotspot();
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView outer = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(24));
        root.setBackgroundColor(Color.rgb(5, 7, 10));
        outer.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(18) + bars.left, dp(16) + bars.top, dp(18) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        root.requestApplyInsets();

        root.addView(text("No-WiFi ADB", 29, Color.WHITE, true));
        root.addView(text(
                "Android 16 reboot recovery experiment. After one successful v0.2 bootstrap, this tries to restore ADB without joining any external Wi-Fi network.",
                15, Color.rgb(185, 194, 208), false), margins(0, 5, 0, 14));

        status = text("Checking…", 16, Color.rgb(138, 216, 255), true);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackgroundColor(Color.rgb(18, 25, 34));
        root.addView(status, matchWrap());

        capability = text("Reboot recovery permission: checking…", 14, Color.rgb(255, 199, 120), true);
        root.addView(capability, margins(0, 10, 0, 14));

        root.addView(section("1 · Do this now, before reboot"));
        root.addView(text(
                "Install v0.3 over v0.2 while 127.0.0.1:5555 still works. This grants the app WRITE_SECURE_SETTINGS through your already-authorized uid=2000 shell. Android does not grant it merely because the app asks for it.",
                14, Color.rgb(205, 211, 220), false), margins(0, 5, 0, 6));
        root.addView(button("Prepare reboot recovery (one time)", v -> runAsync("Preparing reboot recovery", this::prepareOneTimeCapabilityInternal)));
        root.addView(button("Test localhost:5555", v -> runAsync("Testing localhost:5555", this::testLocalhostInternal)));

        root.addView(section("2 · After reboot, with no external Wi-Fi"));
        root.addView(text(
                "Tap Automatic recovery. The app creates its own LocalOnlyHotspot, rapidly forces fresh adb_wifi_enabled 0→1 edges, scans for _adb-tls-connect._tcp, connects with the paired key preserved from v0.2, then immediately issues adb tcpip 5555.",
                14, Color.rgb(205, 211, 220), false), margins(0, 5, 0, 6));
        root.addView(button("Automatic no-WiFi reboot recovery", v -> startRaceFromUi()));
        root.addView(button("Stop race / local hotspot", v -> {
            stopNsdDiscovery();
            stopLocalHotspot();
            setStatus("Stopped");
        }));
        root.addView(button("Open Mobile Hotspot settings (fallback)", v -> openHotspotSettings()));
        root.addView(button("Open Wireless debugging settings", v -> openWirelessDebugging()));

        root.addView(section("Manual v0.2 tools"));
        root.addView(button("Open pairing / manual bootstrap screen", v -> startActivity(new Intent(this, MainActivity.class))));

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

    private TextView section(String s) {
        TextView t = text(s, 18, Color.WHITE, true);
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
        return android.os.Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
    }

    private void updateCapabilityCard() {
        runOnUiThread(() -> {
            if (capability == null) return;
            boolean secure = hasSecureSettings();
            capability.setText("Reboot recovery permission: " + (secure ? "GRANTED ✓" : "NOT GRANTED"));
            capability.setTextColor(secure ? Color.rgb(135, 235, 170) : Color.rgb(255, 199, 120));
        });
    }

    private String prepareOneTimeCapabilityInternal() {
        append("=== ONE-TIME REBOOT RECOVERY SETUP ===");
        if (hasSecureSettings()) {
            updateCapabilityCard();
            return "READY: WRITE_SECURE_SETTINGS is already granted. You can reboot-test the no-WiFi recovery path.";
        }

        String local = testLocalhostInternal();
        if (!local.contains("uid=2000")) {
            updateCapabilityCard();
            return "NOT READY: localhost ADB is not available. Do not reboot yet. Use the manual v0.2 screen to restore TCP:5555, then return here.";
        }

        append("Granting WRITE_SECURE_SETTINGS through 127.0.0.1:5555…");
        Result grant = adb(Arrays.asList("-s", "127.0.0.1:5555", "shell", "pm", "grant",
                getPackageName(), Manifest.permission.WRITE_SECURE_SETTINGS), 12);
        append("pm grant exit=" + grant.exitCode + (grant.output.isEmpty() ? "" : " output=" + oneLine(grant.output)));
        sleep(400);
        boolean granted = hasSecureSettings();
        append("WRITE_SECURE_SETTINGS granted=" + granted);
        updateCapabilityCard();
        if (granted) {
            return "READY: one-time grant succeeded. The permission and paired ADB key should survive ordinary reboots.";
        }
        return "GRANT FAILED: uid=2000 shell worked, but Android did not retain WRITE_SECURE_SETTINGS. Send the log.";
    }

    private void startRaceFromUi() {
        if (!hasSecureSettings()) {
            toast("Run 'Prepare reboot recovery' while localhost ADB still works");
            return;
        }
        if (!hasNearbyWifi()) {
            pendingRaceAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_NEARBY_WIFI);
            return;
        }
        runAsync("Running Android 16 no-WiFi recovery race", this::raceRecoveryInternal);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NEARBY_WIFI) {
            boolean ok = hasNearbyWifi();
            append("Nearby Wi-Fi permission granted=" + ok);
            if (ok && pendingRaceAfterPermission) {
                pendingRaceAfterPermission = false;
                runAsync("Running Android 16 no-WiFi recovery race", this::raceRecoveryInternal);
            } else {
                pendingRaceAfterPermission = false;
                if (!ok) setStatus("Nearby Wi-Fi permission is required to create the local hotspot");
            }
        }
    }

    private String raceRecoveryInternal() {
        append("=== ANDROID 16 NO-WIFI REBOOT RECOVERY RACE ===");
        append("Wi-Fi enabled=" + safeWifiEnabled() + "; localhost:5555 preexisting=" + rawPortOpen());
        if (!hasSecureSettings()) return "WRITE_SECURE_SETTINGS is missing.";

        String existing = testLocalhostInternal();
        if (existing.contains("uid=2000")) return "LOCALHOST ADB ALREADY ACTIVE. No race needed.\n" + existing;

        discoveredConnectPort.set(0);
        startLocalHotspot();
        long hotspotDeadline = System.currentTimeMillis() + 10_000;
        while (hotspotReservation == null && hotspotStarting && System.currentTimeMillis() < hotspotDeadline) sleep(100);
        if (hotspotReservation == null) {
            return "LOCAL HOTSPOT DID NOT START. Enable Mobile Hotspot with the fallback button and rerun Automatic recovery.";
        }

        startNsdDiscovery();
        append("Racing adb_wifi_enabled while scanning _adb-tls-connect._tcp …");
        long deadline = System.currentTimeMillis() + RACE_TIMEOUT_MS;
        int writes = 0;
        int connectAttempts = 0;
        String lastConnect = "";

        try {
            while (System.currentTimeMillis() < deadline) {
                Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 0);
                sleep(12);
                Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                writes++;

                int port = discoveredConnectPort.get();
                if (port <= 0 && writes % 10 == 0) {
                    Result mdns = adb(Arrays.asList("mdns", "services"), 4);
                    int parsed = parseTlsConnectPort(mdns.output);
                    if (parsed > 0) {
                        port = parsed;
                        discoveredConnectPort.compareAndSet(0, parsed);
                        append("ADB mDNS fallback found connect port=" + parsed);
                    }
                }

                if (port > 0) {
                    String ep = "127.0.0.1:" + port;
                    connectAttempts++;
                    append("Race candidate " + connectAttempts + ": " + ep);
                    Result conn = adb(Arrays.asList("connect", ep), 5);
                    lastConnect = conn.output;
                    append(conn.output);
                    String low = conn.output.toLowerCase(Locale.US);
                    if (conn.exitCode == 0 && (low.contains("connected to") || low.contains("already connected"))) {
                        append("Encrypted local ADB recovered. Requesting tcpip 5555 immediately…");
                        String result = bootstrapInternal(ep);
                        if (result.contains("uid=2000")) {
                            stopNsdDiscovery();
                            stopLocalHotspot();
                            return "SUCCESS: NO-EXTERNAL-WIFI REBOOT RECOVERY WORKED.\n" + result;
                        }
                    }
                    discoveredConnectPort.set(0);
                }

                if (writes % 25 == 0) {
                    int observed = Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", -1);
                    append("race writes=" + writes + ", adb_wifi_enabled=" + observed + ", port=" + discoveredConnectPort.get());
                }
                sleep(24);
            }
        } catch (SecurityException e) {
            append("Secure settings write rejected: " + e);
            return "RACE FAILED: WRITE_SECURE_SETTINGS was rejected at runtime.";
        } finally {
            stopNsdDiscovery();
            stopLocalHotspot();
        }

        return "RACE TIMED OUT after " + writes + " setting cycles and " + connectAttempts + " connect attempts. "
                + "LocalOnlyHotspot may not reproduce the exact Mobile Hotspot race on this Pixel build. Enable Mobile Hotspot manually, then rerun Automatic recovery."
                + (lastConnect.isEmpty() ? "" : "\nLast connect: " + oneLine(lastConnect));
    }

    private boolean safeWifiEnabled() {
        try { return wifi != null && wifi.isWifiEnabled(); }
        catch (Throwable ignored) { return false; }
    }

    private void startLocalHotspot() {
        if (hotspotReservation != null || hotspotStarting) return;
        hotspotStarting = true;
        append("Starting app-owned LocalOnlyHotspot…");
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspotReservation = reservation;
                    hotspotStarting = false;
                    SoftApConfiguration c = reservation.getSoftApConfiguration();
                    append("LocalOnlyHotspot started: SSID=" + c.getSsid() + ", securityType=" + c.getSecurityType());
                }
                @Override public void onStopped() {
                    hotspotReservation = null;
                    hotspotStarting = false;
                    append("LocalOnlyHotspot stopped by framework");
                }
                @Override public void onFailed(int reason) {
                    hotspotReservation = null;
                    hotspotStarting = false;
                    append("LocalOnlyHotspot FAILED reason=" + reason);
                }
            }, main);
        } catch (Throwable t) {
            hotspotStarting = false;
            append("startLocalOnlyHotspot failed: " + t);
        }
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
    private void startNsdDiscovery() {
        stopNsdDiscovery();
        discoveredConnectPort.set(0);
        connectDiscovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String serviceType) { append("NSD started: " + serviceType); }
            @Override public void onDiscoveryStopped(String serviceType) { append("NSD stopped"); }
            @Override public void onServiceLost(NsdServiceInfo serviceInfo) { }
            @Override public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                append("NSD start failed code=" + errorCode);
                try { nsd.stopServiceDiscovery(this); } catch (Throwable ignored) { }
            }
            @Override public void onStopDiscoveryFailed(String serviceType, int errorCode) { append("NSD stop failed code=" + errorCode); }
            @Override public void onServiceFound(NsdServiceInfo serviceInfo) {
                try {
                    nsd.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                            if (errorCode != NsdManager.FAILURE_ALREADY_ACTIVE) append("NSD resolve failed code=" + errorCode);
                        }
                        @Override public void onServiceResolved(NsdServiceInfo resolved) {
                            int p = resolved.getPort();
                            if (p > 0) {
                                discoveredConnectPort.set(p);
                                append("NSD resolved ADB TLS connect port=" + p + " host=" + resolved.getHost());
                            }
                        }
                    });
                } catch (Throwable t) {
                    append("NSD resolve exception: " + t.getClass().getSimpleName());
                }
            }
        };
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, connectDiscovery);
        } catch (Throwable t) {
            append("NSD discovery exception: " + t);
            connectDiscovery = null;
        }
    }

    private void stopNsdDiscovery() {
        NsdManager.DiscoveryListener d = connectDiscovery;
        connectDiscovery = null;
        if (d != null && nsd != null) {
            try { nsd.stopServiceDiscovery(d); } catch (Throwable ignored) { }
        }
    }

    private int parseTlsConnectPort(String output) {
        if (output == null) return 0;
        for (String line : output.split("\\r?\\n")) {
            if (!line.contains("_adb-tls-connect._tcp")) continue;
            int colon = line.lastIndexOf(':');
            if (colon >= 0 && colon + 1 < line.length()) {
                String tail = line.substring(colon + 1).trim();
                StringBuilder digits = new StringBuilder();
                for (int i = 0; i < tail.length(); i++) {
                    char ch = tail.charAt(i);
                    if (Character.isDigit(ch)) digits.append(ch); else break;
                }
                if (digits.length() > 0) {
                    try { return Integer.parseInt(digits.toString()); }
                    catch (NumberFormatException ignored) { }
                }
            }
        }
        return 0;
    }

    private String bootstrapInternal(String wirelessEp) {
        Result tcp = adb(Arrays.asList("-s", wirelessEp, "tcpip", "5555"), 18);
        append(tcp.output);
        if (tcp.exitCode != 0 || !tcp.output.toLowerCase(Locale.US).contains("tcp")) {
            return "TCPIP REQUEST FAILED\n" + tcp.output;
        }
        sleep(2400);
        for (int i = 1; i <= 7; i++) {
            Result c = adb(Arrays.asList("connect", "127.0.0.1:5555"), 7);
            append(c.output);
            String low = c.output.toLowerCase(Locale.US);
            if (low.contains("connected") || low.contains("already connected")) break;
            sleep(800);
        }
        String test = testLocalhostInternal();
        if (test.contains("uid=2000")) return "SUCCESS: classic localhost ADB is active.\n" + test;
        return "TCP 5555 requested, but localhost verification failed.\n" + test;
    }

    private String testLocalhostInternal() {
        append("=== TEST 127.0.0.1:5555 ===");
        if (!rawPortOpen()) {
            append("Raw TCP socket: closed/unreachable");
            return "127.0.0.1:5555 is not listening.";
        }
        append("Raw TCP socket: OPEN");
        Result connect = adb(Arrays.asList("connect", "127.0.0.1:5555"), 8);
        append(connect.output);
        Result id = adb(Arrays.asList("-s", "127.0.0.1:5555", "shell", "id"), 10);
        append("shell id: " + oneLine(id.output));
        if (id.exitCode == 0 && id.output.contains("uid=2000")) return "LOCALHOST ADB WORKS: " + oneLine(id.output);
        return "Port is open, but ADB shell failed: " + oneLine(id.output);
    }

    private boolean rawPortOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 900);
            return true;
        } catch (Throwable ignored) { return false; }
    }

    private Result adb(List<String> args, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        command.add(adbPath());
        command.addAll(args);
        append("$ adb " + String.join(" ", args));
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(getFilesDir());
            pb.redirectErrorStream(true);
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());
            p = pb.start();

            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Thread collector = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (out) { out.append(line).append('\n'); }
                    }
                } catch (Throwable ignored) { }
            }, "adb-output");
            collector.start();

            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                collector.join(700);
                String s;
                synchronized (out) { s = out.toString(); }
                return new Result(124, s + "TIMEOUT after " + timeoutSeconds + "s");
            }
            collector.join(900);
            String s;
            synchronized (out) { s = out.toString(); }
            return new Result(p.exitValue(), s.trim());
        } catch (Throwable t) {
            if (p != null) p.destroyForcibly();
            return new Result(127, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private String adbPath() {
        return getApplicationInfo().nativeLibraryDir + File.separator + "libadb.so";
    }

    private void openWirelessDebugging() {
        try { startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")); }
        catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (Throwable ignored) { toast("Could not open Developer options"); }
        }
    }

    private void openHotspotSettings() {
        try { startActivity(new Intent(Settings.ACTION_TETHER_SETTINGS)); }
        catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            catch (Throwable ignored) { toast("Could not open Hotspot settings"); }
        }
    }

    private void runAsync(String busy, Task task) {
        setStatus(busy + "…");
        worker.submit(() -> {
            String result;
            try { result = task.run(); }
            catch (Throwable t) { result = "ERROR: " + t; append(result); }
            final String f = result;
            runOnUiThread(() -> {
                status.setTextColor((f.contains("SUCCESS") || f.contains("READY") || f.contains("LOCALHOST ADB WORKS"))
                        ? Color.rgb(135, 235, 170) : Color.rgb(138, 216, 255));
                status.setText(f);
                updateCapabilityCard();
            });
        });
    }

    private void setStatus(String s) { runOnUiThread(() -> status.setText(s)); }

    private void append(String s) {
        if (s == null || s.isEmpty()) return;
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (log) {
            for (String line : s.split("\\r?\\n")) log.append(stamp).append("  ").append(line).append('\n');
        }
        runOnUiThread(() -> {
            if (logView != null) synchronized (log) { logView.setText(log.toString()); }
        });
    }

    private void copyLog() {
        String s;
        synchronized (log) { s = log.toString(); }
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("No-WiFi ADB v0.3 log", s));
        toast("Log copied");
    }

    private String oneLine(String s) {
        return s == null ? "" : s.trim().replace('\n', ' ').replace('\r', ' ');
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    private interface Task { String run() throws Exception; }

    private static final class Result {
        final int exitCode;
        final String output;
        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
