package com.mekromn.nowifiadb;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
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

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder log = new StringBuilder();

    private TextView status;
    private TextView logView;
    private EditText pairingEndpoint;
    private EditText pairingCode;
    private EditText debugEndpoint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        append("No-WiFi ADB v0.2");
        append("Purpose: bootstrap classic adbd TCP on 127.0.0.1:5555, then keep ADB shell access with Wi-Fi disconnected.");
        append("This is rootless, but one working ADB transport is required after each full reboot.");
        runAsync("Checking localhost:5555", this::testLocalhostInternal);
    }

    @Override
    protected void onDestroy() {
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
        // Android 15/16 enforce edge-to-edge for targetSdk 35+, so consume the
        // real system-bar insets instead of letting the title/buttons sit under them.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(18) + bars.left, dp(16) + bars.top, dp(18) + bars.right, dp(24) + bars.bottom);
            return insets;
        });
        root.requestApplyInsets();

        TextView title = text("No-WiFi ADB", 29, Color.WHITE, true);
        root.addView(title);
        TextView sub = text(
                "Rootless localhost ADB. Use Android Wireless Debugging only to bootstrap adbd into classic TCP mode; after that, 127.0.0.1:5555 does not need a Wi-Fi network.",
                15, Color.rgb(185, 194, 208), false);
        root.addView(sub, margins(0, 5, 0, 14));

        status = text("Checking…", 16, Color.rgb(138, 216, 255), true);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackgroundColor(Color.rgb(18, 25, 34));
        root.addView(status, matchWrap());

        TextView limitation = text(
                "Important: this survives Wi-Fi loss, not a full reboot. After reboot, stock Android 16 requires a new ADB bootstrap unless root or a privileged system modification is used.",
                14, Color.rgb(255, 199, 120), true);
        root.addView(limitation, margins(0, 12, 0, 14));

        root.addView(section("Bootstrap inputs"));
        pairingEndpoint = input("Pairing address:port (or just pairing port)", false);
        pairingCode = input("6-digit pairing code", true);
        debugEndpoint = input("Wireless debugging IP address & port (or just port)", false);
        root.addView(pairingEndpoint, margins(0, 6, 0, 6));
        root.addView(pairingCode, margins(0, 0, 0, 6));
        root.addView(debugEndpoint, margins(0, 0, 0, 10));

        root.addView(button("Open Wireless debugging settings", v -> openWirelessDebugging()));
        root.addView(button("Automatic: Pair → TCP 5555 → localhost shell", v -> autoBootstrap()));
        root.addView(button("Pair only", v -> pairOnly()));
        root.addView(button("Connect current Wireless ADB", v -> connectWirelessOnly()));
        root.addView(button("Bootstrap TCP 5555 (already paired)", v -> bootstrapOnly()));
        root.addView(button("Test localhost:5555 shell", v -> runAsync("Testing localhost:5555", this::testLocalhostInternal)));
        root.addView(button("Disable classic TCP / return adbd to USB", v -> disableTcp()));

        TextView steps = text(
                "First-time test: while connected to any normal Wi-Fi, open Wireless debugging → Pair device with pairing code. Enter the pairing address/port + code above, and also copy the main screen's IP address & port. Tap Automatic. When it reports uid=2000(shell), disconnect Wi-Fi completely and tap Test localhost:5555 shell.",
                14, Color.rgb(180, 188, 200), false);
        root.addView(steps, margins(0, 14, 0, 14));

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
        t.setPadding(0, dp(6), 0, dp(2));
        return t;
    }

    private EditText input(String hint, boolean numeric) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(125, 134, 146));
        e.setTextColor(Color.WHITE);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackgroundColor(Color.rgb(28, 32, 39));
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
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

    private void openWirelessDebugging() {
        try {
            startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Throwable t2) {
                toast("Could not open Developer options");
            }
        }
    }

    private void autoBootstrap() {
        final String pair = pairingEndpoint.getText().toString().trim();
        final String code = pairingCode.getText().toString().trim();
        final String debug = debugEndpoint.getText().toString().trim();
        if (pair.isEmpty() || code.isEmpty() || debug.isEmpty()) {
            toast("Enter pairing address/port, pairing code, and debug address/port");
            return;
        }
        runAsync("Automatic bootstrap running", () -> {
            Result pairResult = pairInternal(endpoint(pair), code);
            if (!pairResult.ok) return "PAIR FAILED\n" + pairResult.output;
            Result connect = connectInternal(endpoint(debug));
            if (!connect.ok) return "CONNECT FAILED\n" + connect.output;
            return bootstrapInternal(endpoint(debug));
        });
    }

    private void pairOnly() {
        final String pair = pairingEndpoint.getText().toString().trim();
        final String code = pairingCode.getText().toString().trim();
        if (pair.isEmpty() || code.isEmpty()) {
            toast("Enter pairing address/port and code");
            return;
        }
        runAsync("Pairing", () -> pairInternal(endpoint(pair), code).output);
    }

    private void connectWirelessOnly() {
        final String debug = debugEndpoint.getText().toString().trim();
        if (debug.isEmpty()) {
            toast("Enter the Wireless debugging IP address & port");
            return;
        }
        runAsync("Connecting Wireless ADB", () -> connectInternal(endpoint(debug)).output);
    }

    private void bootstrapOnly() {
        final String debug = debugEndpoint.getText().toString().trim();
        if (debug.isEmpty()) {
            toast("Enter the Wireless debugging IP address & port");
            return;
        }
        runAsync("Bootstrapping TCP 5555", () -> {
            String ep = endpoint(debug);
            Result connect = connectInternal(ep);
            if (!connect.ok) return "CONNECT FAILED\n" + connect.output;
            return bootstrapInternal(ep);
        });
    }

    private void disableTcp() {
        runAsync("Returning adbd to USB mode", () -> {
            Result r = adb(Arrays.asList("-s", "127.0.0.1:5555", "usb"), null, 15);
            return r.output + "\nThe localhost transport should close shortly.";
        });
    }

    private Result pairInternal(String ep, String code) {
        append("=== PAIR " + ep + " ===");
        adb(Arrays.asList("kill-server"), null, 8);
        Result r = adb(Arrays.asList("pair", ep), code + "\n", 20);
        append(r.output);
        boolean ok = r.exitCode == 0 && r.output.toLowerCase(Locale.US).contains("successfully paired");
        if (!ok && r.exitCode == 0) ok = !r.output.toLowerCase(Locale.US).contains("failed");
        return new Result(ok, r.exitCode, r.output);
    }

    private Result connectInternal(String ep) {
        append("=== CONNECT " + ep + " ===");
        Result r = adb(Arrays.asList("connect", ep), null, 15);
        append(r.output);
        Result devices = adb(Arrays.asList("devices", "-l"), null, 8);
        append(devices.output);
        String low = r.output.toLowerCase(Locale.US);
        boolean ok = r.exitCode == 0 && (low.contains("connected to") || low.contains("already connected"));
        return new Result(ok, r.exitCode, r.output + "\n" + devices.output);
    }

    private String bootstrapInternal(String wirelessEp) {
        append("=== REQUEST adbd TCP:5555 THROUGH " + wirelessEp + " ===");
        Result tcp = adb(Arrays.asList("-s", wirelessEp, "tcpip", "5555"), null, 20);
        append(tcp.output);
        if (tcp.exitCode != 0 || !tcp.output.toLowerCase(Locale.US).contains("tcp")) {
            return "TCPIP REQUEST FAILED\n" + tcp.output;
        }

        sleep(2600);
        Result lastConnect = null;
        for (int i = 1; i <= 6; i++) {
            append("localhost reconnect attempt " + i + "/6");
            lastConnect = adb(Arrays.asList("connect", "127.0.0.1:5555"), null, 8);
            append(lastConnect.output);
            if (lastConnect.output.toLowerCase(Locale.US).contains("connected")) break;
            sleep(1000);
        }

        String test = testLocalhostInternal();
        if (test.contains("uid=2000")) {
            return "SUCCESS: classic localhost ADB is active.\n" + test
                    + "\n\nNow turn Wi-Fi OFF and press ‘Test localhost:5555 shell’. If uid=2000(shell) remains, the no-Wi-Fi path is proven for this boot.";
        }
        return "TCP 5555 was requested, but localhost shell verification failed.\n" + test
                + (lastConnect == null ? "" : "\n" + lastConnect.output);
    }

    private String testLocalhostInternal() {
        append("=== TEST 127.0.0.1:5555 ===");
        boolean raw = rawPortOpen();
        append("Raw TCP socket: " + (raw ? "OPEN" : "closed/unreachable"));
        if (!raw) return "127.0.0.1:5555 is not listening.";

        Result connect = adb(Arrays.asList("connect", "127.0.0.1:5555"), null, 10);
        append(connect.output);
        Result id = adb(Arrays.asList("-s", "127.0.0.1:5555", "shell", "id"), null, 12);
        append("shell id: " + oneLine(id.output));
        if (id.exitCode == 0 && id.output.contains("uid=2000")) {
            return "LOCALHOST ADB WORKS: " + oneLine(id.output);
        }
        return "Port is open, but ADB shell failed: " + oneLine(id.output);
    }

    private boolean rawPortOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 1000);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Result adb(List<String> args, String stdin, int timeoutSeconds) {
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

            if (stdin != null) {
                try (PrintWriter w = new PrintWriter(new OutputStreamWriter(p.getOutputStream()))) {
                    w.print(stdin);
                    w.flush();
                }
            }

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
                collector.join(1000);
                String s;
                synchronized (out) { s = out.toString(); }
                return new Result(false, 124, s + "TIMEOUT after " + timeoutSeconds + "s\n");
            }
            collector.join(1500);
            String s;
            synchronized (out) { s = out.toString(); }
            return new Result(p.exitValue() == 0, p.exitValue(), s.trim());
        } catch (Throwable t) {
            if (p != null) p.destroyForcibly();
            return new Result(false, 127, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private String adbPath() {
        return getApplicationInfo().nativeLibraryDir + File.separator + "libadb.so";
    }

    private String endpoint(String raw) {
        String s = raw.trim().replace(" ", "");
        if (s.startsWith("[")) return s;
        if (s.contains(":")) return s;
        return "127.0.0.1:" + s;
    }

    private void runAsync(String busy, Task task) {
        setStatus(busy + "…");
        worker.submit(() -> {
            String result;
            try {
                result = task.run();
            } catch (Throwable t) {
                result = "ERROR: " + t;
                append(result);
            }
            final String f = result;
            runOnUiThread(() -> {
                if (f.contains("SUCCESS") || f.contains("LOCALHOST ADB WORKS")) {
                    status.setTextColor(Color.rgb(135, 235, 170));
                } else {
                    status.setTextColor(Color.rgb(138, 216, 255));
                }
                status.setText(f);
            });
        });
    }

    private void setStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    private void append(String s) {
        if (s == null || s.isEmpty()) return;
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        synchronized (log) {
            for (String line : s.split("\\r?\\n")) {
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
        String s;
        synchronized (log) { s = log.toString(); }
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("No-WiFi ADB log", s));
        toast("Log copied");
    }

    private String oneLine(String s) {
        if (s == null) return "";
        return s.trim().replace('\n', ' ').replace('\r', ' ');
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private interface Task { String run() throws Exception; }

    private static final class Result {
        final boolean ok;
        final int exitCode;
        final String output;
        Result(boolean ok, int exitCode, String output) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
