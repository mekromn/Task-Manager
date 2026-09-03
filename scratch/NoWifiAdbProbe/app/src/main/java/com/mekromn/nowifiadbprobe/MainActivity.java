package com.mekromn.nowifiadbprobe;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_PERMS = 1001;

    private WifiManager wifi;
    private ConnectivityManager connectivity;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private ConnectivityManager.NetworkCallback selfNetworkCallback;

    private TextView status;
    private TextView logView;
    private final StringBuilder log = new StringBuilder();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wifi = getSystemService(WifiManager.class);
        connectivity = getSystemService(ConnectivityManager.class);
        setContentView(makeUi());
        append("No-WiFi ADB Probe v0.1");
        append("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        append("Goal: determine whether a normal app can create a Wi-Fi state that Android Wireless Debugging accepts.");
        requestNeededPermissions();
        main.postDelayed(this::runBaseline, 400);
    }

    private View makeUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(Color.rgb(8, 10, 14));

        TextView title = text("No-WiFi ADB Probe", 27, Color.WHITE, true);
        root.addView(title);

        TextView subtitle = text(
                "Rootless Pixel experiment: create a local-only hotspot, then try to associate the phone's STA interface back to its own AP. If WifiManager reports a real network ID + SSID + BSSID, Android's Wireless Debugging preconditions may be satisfied.",
                15, Color.rgb(190, 198, 210), false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.setMargins(0, dp(7), 0, dp(14));
        root.addView(subtitle, subLp);

        status = text("Ready", 16, Color.rgb(140, 210, 255), true);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        status.setBackgroundColor(Color.rgb(20, 27, 36));
        root.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(button("1. Run baseline check", v -> runBaseline()));
        root.addView(button("2. Start self-Wi-Fi experiment", v -> startSelfWifiExperiment()));
        root.addView(button("3. Stop experiment", v -> stopExperiment()));
        root.addView(button("4. Open Developer options", v -> openDeveloperOptions()));
        root.addView(button("5. Copy full report", v -> copyReport()));

        TextView hint = text(
                "Best test: disconnect from every normal Wi-Fi network first. Start the experiment, approve any Wi-Fi connection dialog Android shows, then try the Wireless debugging switch while the experiment remains active.",
                14, Color.rgb(170, 178, 190), false);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(8), 0, dp(8));
        root.addView(hint, hintLp);

        logView = text("", 12, Color.rgb(215, 221, 230), false);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setPadding(dp(12), dp(12), dp(12), dp(12));
        logView.setBackgroundColor(Color.rgb(14, 17, 22));

        ScrollView logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.addView(logView, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(logScroll, logLp);
        return root;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean nearby = checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
            boolean location = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!nearby || !location) {
                requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERMS);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        append("Permission result received.");
        runBaseline();
    }

    private boolean permissionsOk() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            status("Nearby Wi-Fi permission required");
            requestNeededPermissions();
            return false;
        }
        return true;
    }

    private void runBaseline() {
        append("\n=== BASELINE ===");
        try {
            append("Wi-Fi enabled: " + wifi.isWifiEnabled());
        } catch (Throwable t) {
            append("isWifiEnabled failed: " + t);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                append("STA+AP concurrency supported: " + wifi.isStaApConcurrencySupported());
            } catch (Throwable t) {
                append("STA+AP concurrency query failed: " + t);
            }
        }
        dumpGlobalWifiInfo("WifiManager.getConnectionInfo");
        dumpNetworks();
        evaluateCandidate("baseline");
    }

    @SuppressWarnings("deprecation")
    private WifiInfo globalWifiInfo() {
        try {
            return wifi.getConnectionInfo();
        } catch (Throwable t) {
            append("getConnectionInfo exception: " + t);
            return null;
        }
    }

    private void dumpGlobalWifiInfo(String label) {
        WifiInfo info = globalWifiInfo();
        append(label + ": " + describeWifi(info));
    }

    private String describeWifi(WifiInfo info) {
        if (info == null) return "null";
        String ssid;
        String bssid;
        try { ssid = info.getSSID(); } catch (Throwable t) { ssid = "<error:" + t.getClass().getSimpleName() + ">"; }
        try { bssid = info.getBSSID(); } catch (Throwable t) { bssid = "<error:" + t.getClass().getSimpleName() + ">"; }
        return "networkId=" + info.getNetworkId()
                + ", SSID=" + ssid
                + ", BSSID=" + bssid
                + ", RSSI=" + info.getRssi()
                + ", freq=" + info.getFrequency()
                + ", link=" + info.getLinkSpeed() + "Mbps";
    }

    private void dumpNetworks() {
        append("ConnectivityManager networks:");
        for (Network n : connectivity.getAllNetworks()) {
            try {
                NetworkCapabilities nc = connectivity.getNetworkCapabilities(n);
                LinkProperties lp = connectivity.getLinkProperties(n);
                append("  " + n + " caps=" + shortCaps(nc) + " iface=" + (lp == null ? "?" : lp.getInterfaceName()));
                if (nc != null && nc.getTransportInfo() instanceof WifiInfo) {
                    append("    transport WifiInfo: " + describeWifi((WifiInfo) nc.getTransportInfo()));
                }
            } catch (Throwable t) {
                append("  " + n + " inspect failed: " + t);
            }
        }
    }

    private String shortCaps(NetworkCapabilities nc) {
        if (nc == null) return "null";
        StringBuilder s = new StringBuilder();
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s.append("WIFI ");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) s.append("CELL ");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) s.append("VPN ");
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) s.append("ETH ");
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) s.append("INTERNET ");
        if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) s.append("VALIDATED ");
        if (Build.VERSION.SDK_INT >= 35 && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK)) s.append("LOCAL ");
        return s.toString().trim();
    }

    private void startSelfWifiExperiment() {
        if (!permissionsOk()) return;
        stopExperiment();
        append("\n=== SELF-WIFI EXPERIMENT ===");
        status("Starting local-only hotspot…");
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    hotspotReservation = reservation;
                    SoftApConfiguration c = reservation.getSoftApConfiguration();
                    String ssid = c.getSsid();
                    String pass = c.getPassphrase();
                    append("LocalOnlyHotspot started.");
                    append("  SSID=" + ssid);
                    append("  securityType=" + c.getSecurityType());
                    append("  passphrase=" + (pass == null ? "<none>" : "<present:" + pass.length() + " chars>"));
                    dumpGlobalWifiInfo("Immediately after LOHS");
                    evaluateCandidate("LOHS-only");
                    main.postDelayed(() -> requestSelfAssociation(c), 1000);
                }

                @Override
                public void onStopped() {
                    append("LocalOnlyHotspot stopped by framework.");
                    hotspotReservation = null;
                    status("Hotspot stopped");
                }

                @Override
                public void onFailed(int reason) {
                    append("LocalOnlyHotspot FAILED reason=" + reason + " (ERROR_NO_CHANNEL=" + WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL + ", ERROR_INCOMPATIBLE_MODE=" + WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE + ")");
                    status("Local-only hotspot failed: " + reason);
                }
            }, main);
        } catch (SecurityException e) {
            append("startLocalOnlyHotspot SecurityException: " + e);
            status("Permission denied by framework");
            requestNeededPermissions();
        } catch (Throwable t) {
            append("startLocalOnlyHotspot exception: " + t);
            status("Hotspot start failed");
        }
    }

    private void requestSelfAssociation(SoftApConfiguration c) {
        String ssid = c.getSsid();
        if (ssid == null || ssid.isEmpty()) {
            append("Cannot self-associate: LocalOnlyHotspot returned no SSID.");
            status("No hotspot SSID");
            return;
        }

        WifiNetworkSpecifier.Builder sb = new WifiNetworkSpecifier.Builder().setSsid(ssid);
        String pass = c.getPassphrase();
        try {
            switch (c.getSecurityType()) {
                case SoftApConfiguration.SECURITY_TYPE_WPA2_PSK:
                    sb.setWpa2Passphrase(pass);
                    break;
                case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE:
                    sb.setWpa3Passphrase(pass);
                    break;
                case SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION:
                    sb.setWpa2Passphrase(pass);
                    break;
                case SoftApConfiguration.SECURITY_TYPE_OPEN:
                case SoftApConfiguration.SECURITY_TYPE_WPA3_OWE:
                case SoftApConfiguration.SECURITY_TYPE_WPA3_OWE_TRANSITION:
                    break;
                default:
                    append("Unknown hotspot security type " + c.getSecurityType() + "; trying without credentials.");
            }
        } catch (Throwable t) {
            append("Specifier credential configuration failed: " + t);
            status("Could not build self-association specifier");
            return;
        }

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(sb.build())
                .build();

        selfNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                append("SELF-ASSOCIATION: onAvailable " + network);
                status("Self Wi-Fi network AVAILABLE — check Wireless debugging!");
                inspectSelfNetwork(network, "onAvailable");
                main.postDelayed(() -> {
                    dumpGlobalWifiInfo("2s after self-association");
                    dumpNetworks();
                    evaluateCandidate("self-association +2s");
                }, 2000);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
                append("SELF-ASSOCIATION capabilities changed: " + shortCaps(nc));
                if (nc.getTransportInfo() instanceof WifiInfo) {
                    append("  callback WifiInfo: " + describeWifi((WifiInfo) nc.getTransportInfo()));
                }
                evaluateCandidate("capabilitiesChanged");
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                append("SELF-ASSOCIATION link: iface=" + lp.getInterfaceName() + " addrs=" + lp.getLinkAddresses());
            }

            @Override
            public void onUnavailable() {
                append("SELF-ASSOCIATION: onUnavailable. Driver/framework did not allow the phone to join its own hotspot.");
                status("Self-association unavailable");
                dumpGlobalWifiInfo("After self-association failure");
                evaluateCandidate("self-association unavailable");
            }

            @Override
            public void onLost(Network network) {
                append("SELF-ASSOCIATION: network lost " + network);
                status("Self Wi-Fi network lost");
                evaluateCandidate("self-association lost");
            }
        };

        append("Requesting STA association to our own LocalOnlyHotspot…");
        append("Android may show a system 'connect to Wi-Fi' approval dialog. Approve it.");
        status("Requesting self-association…");
        try {
            connectivity.requestNetwork(request, selfNetworkCallback, 30000);
        } catch (Throwable t) {
            append("requestNetwork failed immediately: " + t);
            status("Self-association request rejected");
        }
    }

    private void inspectSelfNetwork(Network network, String where) {
        try {
            NetworkCapabilities nc = connectivity.getNetworkCapabilities(network);
            LinkProperties lp = connectivity.getLinkProperties(network);
            append(where + " caps=" + shortCaps(nc));
            append(where + " iface=" + (lp == null ? "?" : lp.getInterfaceName()));
            if (nc != null && nc.getTransportInfo() instanceof WifiInfo) {
                append(where + " WifiInfo=" + describeWifi((WifiInfo) nc.getTransportInfo()));
            }
            dumpGlobalWifiInfo(where + " global WifiInfo");
        } catch (Throwable t) {
            append(where + " inspect failed: " + t);
        }
    }

    private void evaluateCandidate(String where) {
        WifiInfo i = globalWifiInfo();
        if (i == null) {
            append("ADB precondition [" + where + "]: FAIL — WifiInfo is null");
            return;
        }
        int id = i.getNetworkId();
        String ssid = null;
        String bssid = null;
        try { ssid = i.getSSID(); } catch (Throwable ignored) {}
        try { bssid = i.getBSSID(); } catch (Throwable ignored) {}

        boolean idOk = id != -1;
        boolean ssidOk = ssid != null && !ssid.isEmpty() && !WifiManager.UNKNOWN_SSID.equals(ssid);
        boolean bssidOk = bssid != null && !bssid.isEmpty() && !"02:00:00:00:00:00".equals(bssid);

        append("ADB precondition [" + where + "]: networkId=" + idOk + ", SSID=" + ssidOk + ", BSSID=" + bssidOk);
        if (idOk && ssidOk && bssidOk) {
            append("*** POTENTIAL SUCCESS: the public WifiInfo shape matches the fields AdbDebuggingManager requires. Keep experiment running and try Settings > Developer options > Wireless debugging NOW. ***");
            status("POTENTIAL ADB-WIFI MATCH — try Wireless debugging now");
        }
    }

    private void stopExperiment() {
        if (selfNetworkCallback != null) {
            try { connectivity.unregisterNetworkCallback(selfNetworkCallback); } catch (Throwable ignored) {}
            selfNetworkCallback = null;
        }
        if (hotspotReservation != null) {
            try { hotspotReservation.close(); } catch (Throwable ignored) {}
            hotspotReservation = null;
        }
        status("Experiment stopped / ready");
    }

    private void openDeveloperOptions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable first) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable second) {
                Toast.makeText(this, "Could not open Settings", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void copyReport() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("No-WiFi ADB Probe report", log.toString()));
        Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show();
    }

    private void status(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    private void append(String s) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = stamp + "  " + s + "\n";
        synchronized (log) {
            log.append(line);
        }
        runOnUiThread(() -> {
            if (logView != null) logView.setText(log.toString());
        });
    }

    @Override
    protected void onDestroy() {
        stopExperiment();
        super.onDestroy();
    }
}
