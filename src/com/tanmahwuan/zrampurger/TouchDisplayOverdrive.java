package com.tanmahwuan.zrampurger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * TouchDisplayOverdrive - Low-latency touch response, refresh rate locking, and edge protection.
 */
public final class TouchDisplayOverdrive {

    private static final String TAG = "QanHAdb:TouchDisplay";
    private static final String PREFS = "qanh_touch_prefs";

    private TouchDisplayOverdrive() {}

    public static boolean setTouchSensitivityBoost(boolean enable) {
        Log.i(TAG, "Touch sensitivity overdrive: " + enable);
        String cmd = enable
                ? "settings put system pointer_speed 7 2>/dev/null; " +
                  "settings put system touch_sensitivity 1 2>/dev/null; " +
                  "setprop ro.input.resampling 0 2>/dev/null; " +
                  "setprop windowsmgr.max_events_per_sec 240 2>/dev/null;"
                : "settings put system pointer_speed 0 2>/dev/null; " +
                  "settings put system touch_sensitivity 0 2>/dev/null; " +
                  "setprop ro.input.resampling 1 2>/dev/null;";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    public static boolean setRefreshRateLock(Context context, int targetHz) {
        Log.i(TAG, "Locking refresh rate to: " + targetHz + "Hz");
        String cmd;
        if (targetHz > 0) {
            cmd = "settings put system min_refresh_rate " + targetHz + " 2>/dev/null; " +
                  "settings put system peak_refresh_rate " + targetHz + " 2>/dev/null; " +
                  "settings put system user_refresh_rate " + targetHz + " 2>/dev/null;";
        } else {
            cmd = "settings delete system min_refresh_rate 2>/dev/null; " +
                  "settings delete system peak_refresh_rate 2>/dev/null; " +
                  "settings delete system user_refresh_rate 2>/dev/null;";
        }
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    public static boolean setEdgeProtection(boolean enable) {
        Log.i(TAG, "Edge touch filter: " + enable);
        String cmd = enable
                ? "settings put secure edge_prevent_mode 1 2>/dev/null; settings put secure touch_edge_filter 1 2>/dev/null;"
                : "settings put secure edge_prevent_mode 0 2>/dev/null; settings put secure touch_edge_filter 0 2>/dev/null;";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    public static boolean setBrightLock(boolean enable) {
        Log.i(TAG, "Brightlock (auto-brightness sensor freeze): " + enable);
        String cmd = enable
                ? "settings put system screen_brightness_mode 0 2>/dev/null;"
                : "settings put system screen_brightness_mode 1 2>/dev/null;";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    public static boolean setZeroAnimations(boolean enable) {
        String val = enable ? "0" : "1";
        String cmd = "settings put global window_animation_scale " + val + " 2>/dev/null; " +
                     "settings put global transition_animation_scale " + val + " 2>/dev/null; " +
                     "settings put global animator_duration_scale " + val + " 2>/dev/null;";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }
}
