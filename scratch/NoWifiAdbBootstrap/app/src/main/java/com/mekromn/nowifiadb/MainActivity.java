package com.mekromn.nowifiadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.quicksettings.TileService;
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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(5, 7, 10);
    private static final int CARD = Color.rgb(17, 23, 31);
    private static final int CARD_2 = Color.rgb(24, 30, 39);
    private static final int TEXT_2 = Color.rgb(188, 197, 210);
    private static final int BLUE = Color.rgb(126, 211, 255);
    private static final int GREEN = Color.rgb(125, 235, 166);
    private static final int AMBER = Color.rgb(255, 197, 112);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final StringBuilder log = new StringBuilder();

    private AdbEngine engine;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView keyState;
    private TextView logView;
    private EditText pairingCode;
    private EditText shellCommand;
    private TextView shellOutput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = new AdbEngine(this, this::append);
        setContentView(buildUi());
        append("No-WiFi ADB v0.4");
        append("Production path: real Android adbd → authenticated classic TCP → localhost uid=2000(shell).");
        append("No root, Shizuku dependency, location permission, Wi-Fi-control permission, or protected-settings grant is used by this build.");
        refreshState("Checking local ADB…");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine != null) refreshState("Checking local ADB…");
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView outer = new ScrollView(this);
        outer.setFillViewport(true);
        outer.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(30));
        root.setBackgroundColor(BG);
        outer.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(dp(18) + bars.left, dp(16) + bars.top, dp(18) + bars.right, dp(30) + bars.bottom);
            return insets;
        });
        root.requestApplyInsets();

        root.addView(text("No-WiFi ADB", 30, Color.WHITE, true));
        root.addView(text(
                "Full ADB shell on this phone through localhost. Wireless debugging is only the bootstrap; after success you can disconnect Wi-Fi completely.",
                15, TEXT_2, false), margins(0, 5, 0, 14));

        LinearLayout stateCard = new LinearLayout(this);
        stateCard.setOrientation(LinearLayout.VERTICAL);
        stateCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        stateCard.setBackground(round(CARD, 18));
        statusTitle = text("CHECKING", 18, BLUE, true);
        statusDetail = text("Looking for an existing localhost ADB shell…", 14, TEXT_2, false);
        keyState = text("ADB host key: checking…", 13, Color.rgb(150, 161, 176), false);
        stateCard.addView(statusTitle);
        stateCard.addView(statusDetail, margins(0, 4, 0, 0));
        stateCard.addView(keyState, margins(0, 6, 0, 0));
        root.addView(stateCard, matchWrap());

        root.addView(primaryButton("Bootstrap / Repair No-WiFi ADB", v -> bootstrap()));
        root.addView(secondaryButton("Open Wireless debugging", v -> openWirelessDebugging()));

        root.addView(section("First-time pairing only"));
        root.addView(text(
                "Normally you only pair once. Open Wireless debugging → Pair device with pairing code. Leave that dialog visible, enter only its six-digit code below, then tap Pair & Bootstrap. The app discovers both ports automatically.",
                14, TEXT_2, false), margins(0, 4, 0, 8));
        pairingCode = input("6-digit pairing code", true);
        root.addView(pairingCode, margins(0, 0, 0, 6));
        root.addView(secondaryButton("Pair & Bootstrap", v -> pairAndBootstrap()));

        root.addView(section("Active controls"));
        root.addView(secondaryButton("Verify uid=2000 localhost shell", v -> verify()));
        root.addView(secondaryButton("Rotate to a new random high port", v -> rotatePort()));
        root.addView(secondaryButton("Start Shizuku through localhost ADB", v -> startShizuku()));
        root.addView(dangerButton("Disable classic TCP ADB", v -> disableAdb()));

        root.addView(section("Lifecycle validation"));
        root.addView(text(
                "This is the real recovery test. It deliberately shuts down the currently working classic TCP transport, confirms that it is gone, then tries to find Wireless Debugging with the saved host key and rebuild a new random localhost port. If Android stops advertising Wireless Debugging after adb restarts, you may need to toggle Wireless debugging once to finish repair.",
                13, AMBER, false), margins(0, 4, 0, 7));
        root.addView(dangerButton("Full repair test: shut down → recover", v -> confirmFullRepairTest()));

        root.addView(section("ADB shell"));
        root.addView(text(
                "Commands run with the same uid=2000(shell) context you proved in v0.2.",
                14, TEXT_2, false), margins(0, 4, 0, 7));
        shellCommand = input("Shell command", false);
        shellCommand.setText("id");
        root.addView(shellCommand, margins(0, 0, 0, 6));
        root.addView(secondaryButton("Run shell command", v -> runShell()));
        shellOutput = text("", 13, Color.rgb(218, 225, 234), false);
        shellOutput.setTypeface(android.graphics.Typeface.MONOSPACE);
        shellOutput.setTextIsSelectable(true);
        shellOutput.setPadding(dp(12), dp(12), dp(12), dp(12));
        shellOutput.setBackground(round(Color.rgb(11, 15, 20), 14));
        root.addView(shellOutput, margins(0, 8, 0, 0));

        root.addView(section("Quick Settings"));
        root.addView(text(
                "The tile shows whether the saved localhost port is listening. When active, tapping it disables classic TCP ADB; when inactive, tapping it opens this app.",
                14, TEXT_2, false), margins(0, 4, 0, 7));
        root.addView(secondaryButton("Add No-WiFi ADB Quick Settings tile", v -> requestTile()));

        root.addView(section("Security & reboot behavior"));
        root.addView(text(
                "ADB authentication remains enabled. New bootstraps use a random high TCP port instead of 5555. Stock ‘adb tcpip’ still listens on network interfaces, not only loopback, so while Wi-Fi remains connected the port can also exist on the LAN; authentication is the security boundary. Turning Wi-Fi off leaves our localhost route. A full reboot restarts adbd and requires another bootstrap.",
                13, AMBER, false), margins(0, 4, 0, 8));

        root.addView(section("Diagnostic log"));
        root.addView(secondaryButton("Copy full diagnostic log", v -> copyLog()));
        logView = text("", 12, Color.rgb(205, 213, 223), false);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logView.setBackground(round(Color.rgb(10, 13, 18), 14));
        root.addView(logView, margins(0, 8, 0, 0));
        return outer;
    }

    private void bootstrap() {
        runAction("BOOTSTRAPPING", engine::smartBootstrap, null);
    }

    private void pairAndBootstrap() {
        final String code = pairingCode.getText().toString();
        runAction("PAIRING", () -> engine.pairAndBootstrap(code), null);
    }

    private void verify() {
        runAction("VERIFYING", () -> {
            AdbEngine.State state = engine.detect();
            return state.active
                    ? "VERIFIED: localhost ADB is uid=2000(shell).\n" + state.identity
                    : state.message;
        }, null);
    }

    private void rotatePort() {
        runAction("ROTATING PORT", engine::rotatePort, null);
    }

    private void disableAdb() {
        runAction("DISABLING", engine::disable, null);
    }

    private void startShizuku() {
        runAction("STARTING SHIZUKU", engine::startShizuku, null);
    }

    private void confirmFullRepairTest() {
        new AlertDialog.Builder(this)
                .setTitle("Run full repair test?")
                .setMessage("This intentionally shuts down the working classic ADB transport first. If Android does not keep Wireless debugging advertised after the restart, No-WiFi ADB will remain inactive until you toggle Wireless debugging and press Bootstrap / Repair. Your saved pairing key is preserved.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Run test", (dialog, which) ->
                        runAction("FULL REPAIR TEST", engine::fullRepairTest, null))
                .show();
    }

    private void runShell() {
        final String command = shellCommand.getText().toString();
        runAction("RUNNING SHELL", () -> engine.runShell(command), output -> shellOutput.setText(output));
    }

    private void runAction(String busy, Action action, OutputConsumer consumer) {
        setBusy(busy);
        worker.submit(() -> {
            String output;
            try {
                output = action.run();
            } catch (Throwable t) {
                output = "ERROR: " + t;
            }
            append(output);
            AdbEngine.State state = engine.detect();
            final String f = output;
            runOnUiThread(() -> {
                if (consumer != null) consumer.accept(f);
                applyState(state);
                toast(shortToast(f));
                requestTileRefresh();
            });
        });
    }

    private void refreshState(String busy) {
        setBusy(busy);
        worker.submit(() -> {
            AdbEngine.State state = engine.detect();
            runOnUiThread(() -> {
                applyState(state);
                requestTileRefresh();
            });
        });
    }

    private void applyState(AdbEngine.State state) {
        if (state.active) {
            statusTitle.setText("ACTIVE · NO-WIFI ADB READY");
            statusTitle.setTextColor(GREEN);
            statusDetail.setText("127.0.0.1:" + state.port + " · verified uid=2000(shell) / u:r:shell:s0");
        } else {
            statusTitle.setText("INACTIVE · BOOTSTRAP NEEDED");
            statusTitle.setTextColor(BLUE);
            statusDetail.setText(state.message);
        }
        keyState.setText("ADB host key: " + (engine.pairingKeyLooksPresent() ? "saved in app-private storage ✓" : "not created yet"));
    }

    private void setBusy(String value) {
        runOnUiThread(() -> {
            if (statusTitle != null) {
                statusTitle.setText(value);
                statusTitle.setTextColor(BLUE);
            }
            if (statusDetail != null) statusDetail.setText("Working…");
        });
    }

    private void openWirelessDebugging() {
        try {
            startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"));
        } catch (Throwable first) {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Throwable ignored) {
                toast("Could not open Developer options");
            }
        }
    }

    private void requestTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                StatusBarManager statusBarManager = getSystemService(StatusBarManager.class);
                if (statusBarManager == null) {
                    toast("Open Quick Settings edit and add No-WiFi ADB manually");
                    return;
                }
                statusBarManager.requestAddTileService(
                        new ComponentName(this, AdbTileService.class),
                        "No-WiFi ADB",
                        Icon.createWithResource(this, R.drawable.ic_adb_tile),
                        getMainExecutor(),
                        result -> toast("Quick Settings tile request result: " + result));
            } catch (Throwable t) {
                toast("Open Quick Settings edit and add No-WiFi ADB manually");
            }
        } else {
            toast("Open Quick Settings edit and add No-WiFi ADB manually");
        }
    }

    private void requestTileRefresh() {
        try {
            TileService.requestListeningState(this, new ComponentName(this, AdbTileService.class));
        } catch (Throwable ignored) { }
    }

    private TextView section(String value) {
        TextView t = text(value, 18, Color.WHITE, true);
        t.setPadding(0, dp(18), 0, dp(2));
        return t;
    }

    private EditText input(String hint, boolean numeric) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(123, 135, 151));
        e.setTextColor(Color.WHITE);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setPadding(dp(14), dp(11), dp(14), dp(11));
        e.setBackground(round(CARD_2, 15));
        if (numeric) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        return e;
    }

    private Button primaryButton(String label, View.OnClickListener listener) {
        return styledButton(label, Color.rgb(31, 111, 158), listener);
    }

    private Button secondaryButton(String label, View.OnClickListener listener) {
        return styledButton(label, Color.rgb(30, 36, 45), listener);
    }

    private Button dangerButton(String label, View.OnClickListener listener) {
        return styledButton(label, Color.rgb(70, 35, 39), listener);
    }

    private Button styledButton(String label, int color, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(52));
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setBackground(round(color, 15));
        b.setOnClickListener(listener);
        b.setLayoutParams(margins(0, 8, 0, 0));
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

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
        String value;
        synchronized (log) { value = log.toString(); }
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("No-WiFi ADB diagnostic log", value));
        toast("Diagnostic log copied");
    }

    private String shortToast(String value) {
        if (value == null || value.isEmpty()) return "Done";
        String first = value.split("\\r?\\n", 2)[0];
        return first.length() > 90 ? first.substring(0, 90) + "…" : first;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private interface Action { String run() throws Exception; }
    private interface OutputConsumer { void accept(String value); }
}
