package com.mekromn.nowifiadb;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AdbTileService extends TileService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshAsync();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (isLikelyActive()) {
            Tile tile = getQsTile();
            if (tile != null) {
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setSubtitle("Disabling…");
                tile.updateTile();
            }
            worker.submit(() -> {
                new AdbEngine(this, null).disable();
                main.postDelayed(this::refreshNow, 500);
            });
        } else {
            openApp();
        }
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void refreshAsync() {
        worker.submit(() -> {
            boolean active = isLikelyActive();
            main.post(() -> apply(active));
        });
    }

    private void refreshNow() {
        apply(isLikelyActive());
    }

    private void apply(boolean active) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setLabel("No-WiFi ADB");
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            int port = getSharedPreferences(AdbEngine.PREFS, Context.MODE_PRIVATE)
                    .getInt(AdbEngine.KEY_CLASSIC_PORT, 0);
            tile.setSubtitle(active ? (port > 0 ? "Active · localhost:" + port : "Active") : "Tap to bootstrap");
        }
        tile.updateTile();
    }

    private boolean isLikelyActive() {
        int saved = getSharedPreferences(AdbEngine.PREFS, Context.MODE_PRIVATE)
                .getInt(AdbEngine.KEY_CLASSIC_PORT, 0);
        if (saved > 0 && portOpen(saved)) return true;
        return portOpen(5555);
    }

    private boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 350);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                //noinspection deprecation
                startActivityAndCollapse(intent);
            }
        } catch (Throwable ignored) {
            startActivity(intent);
        }
    }
}
