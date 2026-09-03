package com.mekromn.nowifiadb;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AdbEngine {
    static final String PREFS = "no_wifi_adb";
    static final String KEY_CLASSIC_PORT = "classic_port";
    static final String KEY_RECOVERY_PENDING = "recovery_pending";
    static final String SHIZUKU_PACKAGE = "moe.shizuku.privileged.api";

    interface Logger {
        void log(String value);
    }

    static final class State {
        final boolean active;
        final int port;
        final String identity;
        final String message;

        State(boolean active, int port, String identity, String message) {
            this.active = active;
            this.port = port;
            this.identity = identity == null ? "" : identity;
            this.message = message == null ? "" : message;
        }
    }

    static final class Result {
        final boolean ok;
        final int exitCode;
        final String output;

        Result(boolean ok, int exitCode, String output) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }

    private static final Pattern ENDPOINT_PORT = Pattern.compile("(?:\\[[^]]+]|(?:[0-9a-fA-F:.%]+)):(\\d{2,5})(?!\\d)");
    private static final Pattern ANY_PORT = Pattern.compile(":(\\d{2,5})(?!\\d)");

    private final Context context;
    private final SharedPreferences prefs;
    private final Logger logger;
    private final SecureRandom random = new SecureRandom();

    AdbEngine(Context context, Logger logger) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.logger = logger;
    }

    int savedPort() {
        return prefs.getInt(KEY_CLASSIC_PORT, 0);
    }

    boolean recoveryPending() {
        return prefs.getBoolean(KEY_RECOVERY_PENDING, false);
    }

    boolean pairingKeyLooksPresent() {
        File home = context.getFilesDir();
        return new File(home, ".android/adbkey").isFile()
                || new File(home, ".android/adbkey.pub").isFile();
    }

    State detect() {
        Set<Integer> candidates = new LinkedHashSet<>();
        int saved = savedPort();
        if (validPort(saved)) candidates.add(saved);
        // v0.2 used 5555. Keep probing it so upgrades can migrate an older live session.
        candidates.add(5555);

        for (int port : candidates) {
            State s = testPort(port, true);
            if (s.active) {
                prefs.edit().putInt(KEY_CLASSIC_PORT, port).putBoolean(KEY_RECOVERY_PENDING, false).apply();
                return s;
            }
        }
        return new State(false, 0, "", "No verified localhost ADB transport is active.");
    }

    State testPort(int port, boolean connectFirst) {
        if (!validPort(port) || !rawPortOpen(port, 850)) {
            return new State(false, 0, "", "127.0.0.1:" + port + " is closed.");
        }

        String endpoint = localEndpoint(port);
        if (connectFirst) {
            Result connect = adb(Arrays.asList("connect", endpoint), null, 8);
            log(connect.output);
        }
        Result id = adb(Arrays.asList("-s", endpoint, "shell", "id"), null, 10);
        log("shell id: " + oneLine(id.output));
        boolean shell = id.exitCode == 0 && id.output.contains("uid=2000") && id.output.contains("u:r:shell:s0");
        return shell
                ? new State(true, port, oneLine(id.output), "Verified uid=2000(shell) on " + endpoint)
                : new State(false, 0, oneLine(id.output), "Port is open but is not a verified Android ADB shell.");
    }

    String smartBootstrap() {
        log("=== SMART BOOTSTRAP ===");
        State current = detect();
        if (current.active) {
            return "ACTIVE: " + current.message + "\n" + current.identity;
        }

        List<Integer> connectPorts = discoverPorts("_adb-tls-connect._tcp", 9_000L);
        if (connectPorts.isEmpty()) {
            if (recoveryPending()) {
                return "WIRELESS DEBUGGING RESTART REQUIRED.\n"
                        + "Android 16 stopped advertising its Wireless Debugging TLS service when adbd left classic TCP mode. "
                        + "Your ADB host key is still saved. While connected to Wi-Fi, toggle Wireless debugging OFF then ON and return to No-WiFi ADB; recovery will retry automatically and should not require a new pairing code.";
            }
            return "NO WIRELESS ADB SERVICE FOUND.\n"
                    + "Turn on Wireless debugging while connected to Wi-Fi, then return here and press Bootstrap again. "
                    + "If this phone has never been paired with this app, use Pair & Bootstrap once.";
        }

        String last = "";
        for (int port : connectPorts) {
            String ep = localEndpoint(port);
            Result connected = connect(ep);
            last = connected.output;
            if (connected.ok) return convertToClassic(ep);
        }

        prefs.edit().putBoolean(KEY_RECOVERY_PENDING, false).apply();
        return "WIRELESS ADB FOUND, BUT THE SAVED KEY WAS NOT AUTHORIZED.\n"
                + "Open ‘Pair device with pairing code’, enter the six-digit code in this app, and press Pair & Bootstrap. "
                + "No IP address or port entry is needed. Last connect result: " + oneLine(last);
    }

    String pairAndBootstrap(String code) {
        log("=== PAIR + BOOTSTRAP ===");
        String clean = code == null ? "" : code.trim();
        if (!clean.matches("\\d{6}")) {
            return "PAIRING CODE REQUIRED: enter the six-digit code Android shows in ‘Pair device with pairing code’.";
        }

        List<Integer> pairingPorts = discoverPorts("_adb-tls-pairing._tcp", 12_000L);
        if (pairingPorts.isEmpty()) {
            return "PAIRING SERVICE NOT FOUND.\nOpen Wireless debugging → Pair device with pairing code and leave that dialog visible, then try again.";
        }

        boolean paired = false;
        String last = "";
        for (int port : pairingPorts) {
            String ep = localEndpoint(port);
            log("Trying pairing service " + ep);
            Result r = adb(Arrays.asList("pair", ep), clean + "\n", 18);
            last = r.output;
            log(r.output);
            String low = r.output.toLowerCase(Locale.US);
            if (r.exitCode == 0 && low.contains("successfully paired")) {
                paired = true;
                break;
            }
        }
        if (!paired) return "PAIR FAILED.\n" + oneLine(last);

        prefs.edit().putBoolean(KEY_RECOVERY_PENDING, false).apply();
        sleep(900);
        List<Integer> connectPorts = discoverPorts("_adb-tls-connect._tcp", 14_000L);
        for (int port : connectPorts) {
            String ep = localEndpoint(port);
            Result c = connect(ep);
            if (c.ok) return convertToClassic(ep);
        }

        return "PAIRING SUCCEEDED, but the Wireless ADB connect service was not reachable yet. "
                + "Close the pairing dialog, leave Wireless debugging enabled, and press Bootstrap / Repair.";
    }

    String rotatePort() {
        log("=== ROTATE CLASSIC ADB PORT ===");
        State current = detect();
        if (!current.active) return "No active localhost ADB transport to rotate.";
        return convertToClassic(localEndpoint(current.port));
    }

    String fullRepairTest() {
        log("=== FULL REPAIR LIFECYCLE TEST ===");
        State current = detect();
        if (!current.active) {
            return "FULL REPAIR TEST NEEDS AN ACTIVE LOCALHOST SESSION FIRST.\n"
                    + "Restore No-WiFi ADB, then run this test. It deliberately shuts the classic transport down before attempting saved-key recovery.";
        }
        if (!pairingKeyLooksPresent()) {
            return "FULL REPAIR TEST STOPPED: no saved ADB host key was found. Pair this app before intentionally shutting down the working transport.";
        }

        int oldPort = current.port;
        String oldEndpoint = localEndpoint(oldPort);
        log("Phase 1/3: deliberately returning adbd to USB mode through " + oldEndpoint);
        Result usb = adb(Arrays.asList("-s", oldEndpoint, "usb"), null, 14);
        log(usb.output);
        sleep(1700);

        if (rawPortOpen(oldPort, 700)) {
            return "PHASE 1 FAILED: the old classic TCP port still answers after ‘adb usb’.\n" + oneLine(usb.output);
        }
        prefs.edit().remove(KEY_CLASSIC_PORT).putBoolean(KEY_RECOVERY_PENDING, true).apply();
        log("Phase 1 PASS: classic localhost ADB is confirmed down. Saved host key was preserved.");

        log("Phase 2/3: discovering Android Wireless Debugging TLS with the saved key…");
        List<Integer> connectPorts = discoverPorts("_adb-tls-connect._tcp", 14_000L);
        if (connectPorts.isEmpty()) {
            return "CLASSIC ADB STOPPED — WIRELESS DEBUGGING RESTART REQUIRED.\n"
                    + "This is the expected Pixel/Android 16 result we measured: after ‘adb usb’, Android stops advertising _adb-tls-connect._tcp. "
                    + "The saved pairing key is intact. With Wi-Fi connected, toggle Wireless debugging OFF then ON, then return to this app. v0.5 will automatically retry saved-key recovery.";
        }

        String last = "";
        for (int port : connectPorts) {
            String ep = localEndpoint(port);
            Result connected = connect(ep);
            last = connected.output;
            if (!connected.ok) continue;

            log("Phase 2 PASS: saved key authenticated to " + ep);
            log("Phase 3/3: converting the recovered TLS transport back to random classic TCP…");
            String converted = convertToClassic(ep);
            if (converted.contains("SUCCESS: NO-WIFI ADB IS ACTIVE")) {
                return "FULL REPAIR PASS\n"
                        + "Classic TCP was intentionally stopped, the saved Wireless Debugging key re-authenticated, and localhost uid=2000(shell) was rebuilt.\n\n"
                        + converted;
            }
            return "PHASE 3 FAILED AFTER TLS RECOVERY.\n" + converted;
        }

        prefs.edit().putBoolean(KEY_RECOVERY_PENDING, false).apply();
        return "WIRELESS DEBUGGING RETURNED, BUT THE SAVED KEY DID NOT AUTHENTICATE.\n"
                + "Use Pair & Bootstrap once. Last connect result: " + oneLine(last);
    }

    String disable() {
        log("=== DISABLE CLASSIC ADB ===");
        State current = detect();
        if (!current.active) {
            prefs.edit().remove(KEY_CLASSIC_PORT).putBoolean(KEY_RECOVERY_PENDING, false).apply();
            return "Classic localhost ADB is already inactive. Automatic recovery was cancelled.";
        }

        String ep = localEndpoint(current.port);
        Result r = adb(Arrays.asList("-s", ep, "usb"), null, 14);
        log(r.output);
        sleep(1300);
        boolean stillOpen = rawPortOpen(current.port, 650);
        if (!stillOpen) {
            prefs.edit().remove(KEY_CLASSIC_PORT).putBoolean(KEY_RECOVERY_PENDING, false).apply();
        }
        return (!stillOpen ? "DISABLED: adbd left TCP mode. Automatic recovery is off." : "ADB requested USB mode, but the TCP port still answers.")
                + (r.output.isEmpty() ? "" : "\n" + oneLine(r.output));
    }

    String runShell(String command) {
        State current = detect();
        if (!current.active) return "No active localhost ADB shell.";
        String clean = command == null ? "" : command.trim();
        if (clean.isEmpty()) return "Enter a shell command.";
        String ep = localEndpoint(current.port);
        Result r = adb(Arrays.asList("-s", ep, "shell", "sh", "-c", clean), null, 30);
        return r.output.isEmpty() ? "(command completed with no output; exit=" + r.exitCode + ")" : r.output;
    }

    String startShizuku() {
        log("=== START SHIZUKU ===");
        State current = detect();
        if (!current.active) return "No active localhost ADB shell.";
        String ep = localEndpoint(current.port);

        // v0.4 queried Shizuku with `pm path` from the shell and produced a false
        // negative on the test Pixel even though the exact package was installed.
        // v0.5 asks Android PackageManager from this app instead. The manifest's
        // <queries> declaration makes this single package visible without asking
        // for QUERY_ALL_PACKAGES or any runtime permission.
        final ApplicationInfo shizuku;
        try {
            shizuku = context.getPackageManager().getApplicationInfo(SHIZUKU_PACKAGE, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return "Shizuku package lookup failed in Android PackageManager. Expected package: " + SHIZUKU_PACKAGE;
        }

        String apk = shizuku.sourceDir;
        String libDir = shizuku.nativeLibraryDir;
        if (apk == null || apk.isEmpty()) {
            return "Shizuku is visible to PackageManager, but sourceDir is empty.";
        }
        if (libDir == null || libDir.isEmpty()) {
            return "Shizuku is visible to PackageManager, but nativeLibraryDir is empty. APK=" + apk;
        }

        String starter = libDir + File.separator + "libshizuku.so";
        log("Shizuku PackageManager sourceDir=" + apk);
        log("Shizuku PackageManager nativeLibraryDir=" + libDir);
        log("Shizuku expected starter=" + starter);

        // Match current Shizuku's Starter.internalCommand exactly in spirit:
        // <nativeLibraryDir>/libshizuku.so --apk=<sourceDir>.
        String command =
                "APK=" + shellQuote(apk) + "; "
                + "STARTER=" + shellQuote(starter) + "; "
                + "echo SHIZUKU_APK=\"$APK\"; echo SHIZUKU_STARTER=\"$STARTER\"; "
                + "if [ ! -f \"$APK\" ]; then echo SHIZUKU_APK_NOT_READABLE; ls -ld \"$(dirname \"$APK\")\" 2>/dev/null || true; exit 22; fi; "
                + "if [ ! -x \"$STARTER\" ]; then "
                + "  echo SHIZUKU_EXPECTED_STARTER_NOT_EXECUTABLE; "
                + "  ALT=\"$(find \"$(dirname \"$APK\")\" -type f -name libshizuku.so -print 2>/dev/null | head -n1)\"; "
                + "  if [ -n \"$ALT\" ] && [ -x \"$ALT\" ]; then STARTER=\"$ALT\"; echo SHIZUKU_FALLBACK_STARTER=\"$STARTER\"; else exit 21; fi; "
                + "fi; "
                + "\"$STARTER\" --apk=\"$APK\"; RC=$?; "
                + "sleep 1; PID=\"$(pidof shizuku_server 2>/dev/null || true)\"; "
                + "if [ -n \"$PID\" ]; then echo SHIZUKU_SERVER_RUNNING pid=\"$PID\"; fi; exit $RC";

        Result start = adb(Arrays.asList("-s", ep, "shell", "sh", "-c", command), null, 35);
        log(start.output);

        if (start.output.contains("SHIZUKU_APK_NOT_READABLE")) {
            return "Shizuku was found by Android, but the ADB shell could not read its APK path.\n" + start.output;
        }
        if (start.output.contains("SHIZUKU_EXPECTED_STARTER_NOT_EXECUTABLE")
                && !start.output.contains("SHIZUKU_FALLBACK_STARTER")) {
            return "Shizuku was found, but libshizuku.so was not executable at Android's reported nativeLibraryDir.\n" + start.output;
        }

        boolean success = start.output.contains("SHIZUKU_SERVER_RUNNING")
                || start.output.contains("shizuku_starter exit with 0");
        if (success) {
            return "SHIZUKU STARTED THROUGH NO-WIFI ADB.\n" + start.output;
        }
        return "Shizuku starter ran but server verification did not succeed (exit=" + start.exitCode + ")."
                + (start.output.isEmpty() ? "" : "\n" + start.output);
    }

    private Result connect(String ep) {
        log("=== CONNECT " + ep + " ===");
        Result r = adb(Arrays.asList("connect", ep), null, 12);
        log(r.output);
        String low = r.output.toLowerCase(Locale.US);
        boolean ok = r.exitCode == 0 && (low.contains("connected to") || low.contains("already connected"));
        return new Result(ok, r.exitCode, r.output);
    }

    private String convertToClassic(String sourceEndpoint) {
        int newPort = randomClassicPort();
        log("=== REQUEST adbd TCP:" + newPort + " THROUGH " + sourceEndpoint + " ===");
        Result tcp = adb(Arrays.asList("-s", sourceEndpoint, "tcpip", String.valueOf(newPort)), null, 18);
        log(tcp.output);
        String low = tcp.output.toLowerCase(Locale.US);
        if (tcp.exitCode != 0 || !low.contains("tcp")) {
            return "TCP MODE REQUEST FAILED.\n" + oneLine(tcp.output);
        }

        sleep(2400);
        State state = null;
        for (int i = 1; i <= 8; i++) {
            log("localhost reconnect attempt " + i + "/8 on port " + newPort);
            Result c = adb(Arrays.asList("connect", localEndpoint(newPort)), null, 7);
            log(c.output);
            state = testPort(newPort, false);
            if (state.active) break;
            sleep(850);
        }

        if (state != null && state.active) {
            prefs.edit().putInt(KEY_CLASSIC_PORT, newPort).putBoolean(KEY_RECOVERY_PENDING, false).apply();
            return "SUCCESS: NO-WIFI ADB IS ACTIVE\n"
                    + "localhost=" + localEndpoint(newPort) + "\n"
                    + state.identity + "\n\n"
                    + "You can now disconnect Wi-Fi completely. The saved ADB host key and this port remain usable until adbd restarts or the phone reboots.";
        }
        return "adbd accepted TCP mode on port " + newPort + ", but localhost uid=2000 verification failed.";
    }

    private int randomClassicPort() {
        int saved = savedPort();
        for (int i = 0; i < 20; i++) {
            // High, non-default port. This is not a security boundary; normal ADB authentication remains mandatory.
            int p = 38000 + random.nextInt(19000); // 38000..56999
            if (p != 5555 && p != saved && !rawPortOpen(p, 70)) return p;
        }
        return 47123;
    }

    private List<Integer> discoverPorts(String serviceMarker, long timeoutMs) {
        log("Discovering " + serviceMarker + " with ADB mDNS…");
        LinkedHashSet<Integer> found = new LinkedHashSet<>();
        long end = System.currentTimeMillis() + timeoutMs;
        String lastOutput = "";

        while (System.currentTimeMillis() < end && found.isEmpty()) {
            Result r = adb(Arrays.asList("mdns", "services"), null, 5);
            lastOutput = r.output;
            parsePorts(r.output, serviceMarker, found);
            if (found.isEmpty()) sleep(650);
        }

        if (!found.isEmpty()) {
            log("mDNS " + serviceMarker + " candidate ports=" + found);
        } else if (!lastOutput.isEmpty()) {
            log("mDNS returned no matching service. Last output: " + oneLine(lastOutput));
        } else {
            log("mDNS returned no services.");
        }
        return new ArrayList<>(found);
    }

    private void parsePorts(String output, String marker, Set<Integer> out) {
        if (output == null) return;
        for (String line : output.split("\\r?\\n")) {
            if (!line.contains(marker)) continue;
            Matcher m = ENDPOINT_PORT.matcher(line);
            boolean any = false;
            while (m.find()) {
                int p = parsePort(m.group(1));
                if (validPort(p)) {
                    out.add(p);
                    any = true;
                }
            }
            if (!any) {
                Matcher fallback = ANY_PORT.matcher(line);
                while (fallback.find()) {
                    int p = parsePort(fallback.group(1));
                    if (validPort(p)) out.add(p);
                }
            }
        }
    }

    private int parsePort(String s) {
        try { return Integer.parseInt(s); } catch (Throwable ignored) { return 0; }
    }

    boolean rawSavedPortOpen() {
        int p = savedPort();
        if (validPort(p) && rawPortOpen(p, 450)) return true;
        return rawPortOpen(5555, 450);
    }

    private boolean rawPortOpen(int port, int timeoutMs) {
        if (!validPort(port)) return false;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), timeoutMs);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean validPort(int port) {
        return port > 0 && port <= 65535;
    }

    private String localEndpoint(int port) {
        return "127.0.0.1:" + port;
    }

    private Result adb(List<String> args, String stdin, int timeoutSeconds) {
        List<String> command = new ArrayList<>();
        command.add(adbPath());
        command.addAll(args);
        log("$ adb " + String.join(" ", args));

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(context.getFilesDir());
            pb.redirectErrorStream(true);
            // Stable app-private HOME makes adb pair's host key survive normal APK updates.
            pb.environment().put("HOME", context.getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", context.getCacheDir().getAbsolutePath());
            process = pb.start();

            if (stdin != null) {
                try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(process.getOutputStream()))) {
                    writer.print(stdin);
                    writer.flush();
                }
            }

            StringBuilder out = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            Thread collector = new Thread(() -> {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (out) { out.append(line).append('\n'); }
                    }
                } catch (Throwable ignored) { }
            }, "no-wifi-adb-output");
            collector.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                collector.join(900);
                String value;
                synchronized (out) { value = out.toString(); }
                return new Result(false, 124, value + "TIMEOUT after " + timeoutSeconds + "s");
            }
            collector.join(1300);
            String value;
            synchronized (out) { value = out.toString().trim(); }
            return new Result(process.exitValue() == 0, process.exitValue(), value);
        } catch (Throwable t) {
            if (process != null) process.destroyForcibly();
            return new Result(false, 127, t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private String adbPath() {
        return context.getApplicationInfo().nativeLibraryDir + File.separator + "libadb.so";
    }

    private void log(String value) {
        if (logger != null && value != null && !value.isEmpty()) logger.log(value);
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return value.trim().replace('\n', ' ').replace('\r', ' ');
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
