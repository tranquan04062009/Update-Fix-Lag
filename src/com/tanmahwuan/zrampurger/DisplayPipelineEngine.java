package com.tanmahwuan.zrampurger;

import android.content.Context;
import android.util.Log;

/**
 * DisplayPipelineEngine - Controls SurfaceFlinger native binder transactions,
 * WindowManager orientation locks, and DisplayManager hardware mode preferences.
 */
public final class DisplayPipelineEngine {

    private static final String TAG = "QanHAdb:DisplayPipeline";

    private DisplayPipelineEngine() {}

    /**
     * Bypasses Hardware Composer (HWC) to force pure GPU layer composition,
     * reducing touch-to-display rendering latency.
     */
    public static boolean setForceGpuComposition(boolean enable) {
        Log.i(TAG, "Setting SurfaceFlinger Force GPU Composition: " + enable);
        String val = enable ? "1" : "0";
        String cmd = "service call SurfaceFlinger 1034 i32 " + val + " 2>/dev/null; " +
                     "setprop debug.sf.disable_hwc_overlays " + val + " 2>/dev/null;";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Overrides SurfaceFlinger color mode to DCI-P3 / Wide Gamut for cinema-grade game graphics.
     */
    public static boolean setDisplayColorDciP3(boolean enable) {
        Log.i(TAG, "Setting SurfaceFlinger Display Color Mode DCI-P3: " + enable);
        // 2: DCI-P3, 0: Native / Standard
        int mode = enable ? 2 : 0;
        CommandExecutor.CommandResult res = CommandExecutor.execute("service call SurfaceFlinger 1022 i32 " + mode + " 2>/dev/null");
        return res.exitCode == 0;
    }

    /**
     * Locks WindowManager orientation recalculations to eliminate micro-stutters during combat.
     */
    public static boolean setIgnoreOrientationRequests(boolean ignore) {
        Log.i(TAG, "Setting WindowManager Ignore Orientation Requests: " + ignore);
        String cmd = ignore
                ? "cmd window set-ignore-orientation-request true 2>/dev/null;"
                : "cmd window set-ignore-orientation-request false 2>/dev/null;";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Sets user preferred hardware display mode via AOSP DisplayManager.
     */
    public static boolean setPreferredDisplayMode(int width, int height, int fps) {
        if (width <= 0 || height <= 0 || fps <= 0) {
            CommandExecutor.CommandResult res = CommandExecutor.execute("cmd display clear-user-preferred-display-mode 2>/dev/null");
            return res.exitCode == 0;
        }
        Log.i(TAG, "Setting preferred display mode: " + width + "x" + height + " @" + fps + "Hz");
        String cmd = "cmd display set-user-preferred-display-mode " + width + " " + height + " " + fps + " 2>/dev/null";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Adjusts screen density (DPI) dynamically to optimize field-of-view (FOV) in shooters.
     */
    public static boolean setDynamicDpi(int dpi) {
        String cmd = (dpi > 0) ? ("wm density " + dpi + " 2>/dev/null") : "wm density reset 2>/dev/null";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }
}
