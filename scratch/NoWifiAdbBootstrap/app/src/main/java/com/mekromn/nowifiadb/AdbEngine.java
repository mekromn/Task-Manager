package com.mekromn.nowifiadb;

import android.content.Context;
import android.content.SharedPreferences;

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

    boolean pairingKeyLooksPresent() {
        File home = context.getFilesDir();
        return new File(home, ".android/adbkey").isFile()
                || new File(home, ".android/adbkey.pub").isFile();
    }

    State detect() {
        Set<Integer> candidates = new LinkedHashSet<>();
        int saved = savedPort();
        if (validPort(saved)) candidates.add(saved);
        // v0.2 used 5555. Keeping this migration probe lets v0.3 install directly over it.
        candidates.add(5555);

        for (int port : candidates) {
            State s = testPort(port, true);
            if (s.active) {
                prefs.edit().putInt(KEY_CLASSIC_PORT, port).apply();
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
            return "NO WIRELESS ADB SERVICE FOUND.\n"
                    + "Turn on Wireless debugging while connected to Wi-Fi, then return here and press Bootstrap again. "
                    + "If this phone has never been paired with this app, use Pair & Bootstrap once.";
        }

        for (int port : connectPorts) {
            String ep = localEndpoint(port);
            Result connected = connect(ep);
            if (connected.ok) return convertToClassic(ep);
        }

        return "WIRELESS ADB FOUND, BUT THIS APP IS NOT AUTHORIZED.\n"
                + "Open ‘Pair device with pairing code’, enter the six-digit code in this app, and press Pair & Bootstrap. "
                + "No IP address or port entry is needed.";
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

    String disable() {
        log("=== DISABLE CLASSIC ADB ===");
        State current = detect();
        if (!current.active) {
            prefs.edit().remove(KEY_CLASSIC_PORT).apply();
            return "Classic localhost ADB is already inactive.";
        }

        String ep = localEndpoint(current.port);
        Result r = adb(Arrays.asList("-s", ep, "usb"), null, 14);
        log(r.output);
        sleep(1300);
        boolean stillOpen = rawPortOpen(current.port, 650);
        if (!stillOpen) prefs.edit().remove(KEY_CLASSIC_PORT).apply();
        return (!stillOpen ? "DISABLED: adbd left TCP mode." : "ADB requested USB mode, but the TCP port still answers.")
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
        String script = "/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh";
        Result exists = adb(Arrays.asList("-s", ep, "shell", "sh", "-c", "[ -f '" + script + "' ] && echo FOUND || echo MISSING"), null, 10);
        if (!exists.output.contains("FOUND")) {
            return "Shizuku starter script was not found. Install/open Shizuku once so its ADB starter is available.";
        }
        Result start = adb(Arrays.asList("-s", ep, "shell", "sh", script), null, 30);
        log(start.output);
        return (start.exitCode == 0 ? "Shizuku start command completed." : "Shizuku start command failed (exit=" + start.exitCode + ").")
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
            prefs.edit().putInt(KEY_CLASSIC_PORT, newPort).apply();
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
            // This is intentionally stable app-private storage. adb pair's host key survives APK updates.
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
