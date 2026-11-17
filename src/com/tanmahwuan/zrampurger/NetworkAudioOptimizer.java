package com.tanmahwuan.zrampurger;

import android.util.Log;

/**
 * NetworkAudioOptimizer - Prioritizes game packets, eliminates background network probes,
 * and tunes low-latency gaming connection.
 */
public final class NetworkAudioOptimizer {

    private static final String TAG = "QanHAdb:NetOptimizer";

    private NetworkAudioOptimizer() {}

    public static boolean setGameDataPriority(boolean enable) {
        Log.i(TAG, "Configuring game network packet priority: " + enable);
        String cmd = enable
                ? "settings put global wifi_wakeup_enabled 0 2>/dev/null; " +
                  "settings put global wifi_scan_always_enabled 0 2>/dev/null; " +
                  "settings put global ble_scan_always_enabled 0 2>/dev/null; " +
                  "settings put global ota_disable_automatic_update 1 2>/dev/null; " +
                  "settings put global wifi_sleep_policy 2 2>/dev/null; " +
                  "cmd netpolicy restrict-background-blacklist 1000 2>/dev/null || true;"
                : "settings put global wifi_wakeup_enabled 1 2>/dev/null; " +
                  "settings put global wifi_scan_always_enabled 1 2>/dev/null; " +
                  "settings put global ota_disable_automatic_update 0 2>/dev/null;";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }
}
