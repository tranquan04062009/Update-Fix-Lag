package com.zalorust.zrampurger;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * AospAdbMaster - Deep Android Open Source Project (AOSP) ADB Privileged Exploitation Engine.
 *
 * Mined directly from Google AOSP source code:
 * - ActivityManager & CachedAppOptimizer (Memory compaction, idle-maintenance, trim-memory)
 * - AppHibernationService (cmd app_hibernation set-state / get-state)
 * - PackageManager (cmd package trim-caches, compile -m speed-profile, compile --compile-layouts)
 * - DeviceConfig / Phenotype Flag Engine (activity_manager, activity_manager_native_boot)
 * - PowerManager & Power HAL AIDL (set-fixed-performance-mode-enabled)
 * - ThermalManagerService (override-status 0, reset)
 * - GameManagerService (cmd game mode performance / reset)
 * - WindowManager & SurfaceFlinger (disable-blur, animation scales)
 * - DeviceIdleController (step deep, whitelist)
 *
 * All operations execute under UID 2000 (android.uid.shell) via Shizuku or ADB shell.
 */
public class AospAdbMaster {

    public static final String TAG = "ZRamPurger:AospAdbMaster";
    private static final Pattern SAFE_PKG_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$");

    public static boolean isValidPackageName(String pkg) {
        return pkg != null && !pkg.isEmpty() && pkg.length() <= 256 && SAFE_PKG_PATTERN.matcher(pkg).matches();
    }

    /**
     * Triggers AOSP system-wide idle maintenance.
     * In AOSP ActivityManagerService, this invokes:
     * - Eviction of dirty pages to ZRAM swap
     * - Background compaction across all cached processes
     * - Linux filesystem TRIM (fstrim) on /data
     * - Background dexopt optimization
     */
    public static CommandExecutor.CommandResult runIdleMaintenance() {
        Log.i(TAG, "Triggering AOSP idle-maintenance...");
        return CommandExecutor.execute("/system/bin/am idle-maintenance 2>&1 || cmd activity idle-maintenance 2>&1");
    }

    /**
     * Purges stale application caches across the entire system using StorageManagerService.
     * Does NOT touch user data or logins; clears temporary image, HTTP, and render caches.
     * Default: 2 GB (2147483648 bytes).
     */
    public static CommandExecutor.CommandResult trimSystemCaches(long targetBytes) {
        if (targetBytes <= 0) targetBytes = 2147483648L; // 2 GB
        Log.i(TAG, "Trimming system caches via AOSP PackageManager: " + targetBytes + " bytes");
        return CommandExecutor.execute("cmd package trim-caches " + targetBytes + " 2>&1 || pm trim-caches " + targetBytes + " 2>&1");
    }

    /**
     * Puts a specific application into deep AOSP Hibernation.
     * Mined from AppHibernationService.java:
     * - Force-stops the package immediately
     * - Automatically purges application cache files (deleteApplicationCacheFilesAsUser)
     */
    public static boolean setAppHibernation(String packageName, boolean hibernate) {
        if (!isValidPackageName(packageName)) return false;
        Log.i(TAG, "Setting AOSP App Hibernation for " + packageName + ": " + hibernate);
        String cmd = "cmd app_hibernation set-state --user 0 \"" + packageName + "\" " + hibernate + " 2>&1";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Queries whether an application is currently hibernated in AOSP AppHibernationService.
     */
    public static boolean isAppHibernating(String packageName) {
        if (!isValidPackageName(packageName)) return false;
        String cmd = "cmd app_hibernation get-state --user 0 \"" + packageName + "\" 2>&1";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.stdout != null && res.stdout.trim().equalsIgnoreCase("true");
    }

    /**
     * Applies proactive low-RAM DeviceConfig (Phenotype) flags extracted from CachedAppOptimizer.java
     * and ActivityManagerConstants.java.
     */
    public static boolean applyDeviceConfigLowRam(boolean enable) {
        Log.i(TAG, "Configuring AOSP DeviceConfig Low-RAM Flags: " + enable);
        StringBuilder sb = new StringBuilder();

        if (enable) {
            // Proactive memory compaction in CachedAppOptimizer
            sb.append("cmd device_config put activity_manager use_compaction true 2>/dev/null; ");
            sb.append("cmd device_config put activity_manager compact_action_1 3 2>/dev/null; ");
            sb.append("cmd device_config put activity_manager compact_action_2 3 2>/dev/null; ");
            sb.append("cmd device_config put activity_manager compact_throttle_1 1000 2>/dev/null; ");
            sb.append("cmd device_config put activity_manager compact_throttle_2 2000 2>/dev/null; ");
            sb.append("cmd device_config put activity_manager compact_full_rss_throttle_kb 8000 2>/dev/null; ");

            // OOM Re-Ranking based on frequent RSS and uses
            sb.append("cmd device_config put activity_manager use_oom_re_ranking true 2>/dev/null; ");

            // Max cached processes restricted to 24 to preserve active RAM
            sb.append("cmd device_config put activity_manager max_cached_processes 24 2>/dev/null; ");
            sb.append("cmd device_config put activity_manager kill_bg_restricted_cached_idle true 2>/dev/null; ");

            // CGroup v2 Freezer debounce reduced to 10s (stops background battery/cpu leak)
            sb.append("cmd device_config put activity_manager_native_boot use_freezer true 2>/dev/null; ");
            sb.append("cmd device_config put activity_manager_native_boot freezer_debounce_timeout 10000 2>/dev/null; ");
        } else {
            // Restore defaults
            sb.append("cmd device_config delete activity_manager use_compaction 2>/dev/null; ");
            sb.append("cmd device_config delete activity_manager compact_action_1 2>/dev/null; ");
            sb.append("cmd device_config delete activity_manager compact_action_2 2>/dev/null; ");
            sb.append("cmd device_config delete activity_manager compact_throttle_1 2>/dev/null; ");
            sb.append("cmd device_config delete activity_manager compact_throttle_2 2>/dev/null; ");
            sb.append("cmd device_config delete activity_manager compact_full_rss_throttle_kb 2>/dev/null; ");
            sb.append("cmd device_config delete activity_manager use_oom_re_ranking 2>/dev/null; ");
            sb.append("cmd device_config delete activity_manager max_cached_processes 2>/dev/null; ");
            sb.append("cmd device_config delete activity_manager kill_bg_restricted_cached_idle 2>/dev/null; ");
            sb.append("cmd device_config delete activity_manager_native_boot freezer_debounce_timeout 2>/dev/null; ");
        }

        CommandExecutor.CommandResult res = CommandExecutor.execute(sb.toString());
        return res.exitCode == 0;
    }

    /**
     * Sets Window and Animator scales to cut UI animation latency in half (0.5x) or restore (1.0x).
     */
    public static boolean setUiAnimationScale(float scale) {
        String s = String.format(Locale.US, "%.1f", scale);
        Log.i(TAG, "Setting UI animation scales to " + s + "x");
        String cmd = String.format(
            "settings put global window_animation_scale %s; " +
            "settings put global transition_animation_scale %s; " +
            "settings put global animator_duration_scale %s",
            s, s, s
        );
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Queries current window animation scale from Settings.Global.
     */
    public static float getUiAnimationScale() {
        CommandExecutor.CommandResult res = CommandExecutor.execute("settings get global window_animation_scale 2>/dev/null");
        if (res.stdout != null && !res.stdout.isEmpty()) {
            try {
                return Float.parseFloat(res.stdout.trim());
            } catch (NumberFormatException ignored) {}
        }
        return 1.0f;
    }

    /**
     * Overrides AOSP ThermalManagerService status to STATUS_NONE (0) for peak gaming throughput,
     * or resets back to hardware readings.
     */
    public static boolean setThermalGovernorOverride(boolean enable) {
        Log.i(TAG, "Thermal Governor Override: " + enable);
        String cmd = enable
            ? "cmd thermalservice override-status 0 2>&1"
            : "cmd thermalservice reset 2>&1";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Locks sustained performance clocks via Power HAL AIDL and disables power saver throttling.
     */
    public static boolean setPowerHalFixedPerformance(boolean enable) {
        Log.i(TAG, "Power HAL Fixed Performance Mode: " + enable);
        String cmd;
        if (enable) {
            cmd = "cmd power set-fixed-performance-mode-enabled true 2>/dev/null; " +
                  "cmd power set-mode 0 2>/dev/null; " +
                  "cmd power set-adaptive-power-saver-enabled false 2>/dev/null";
        } else {
            cmd = "cmd power set-fixed-performance-mode-enabled false 2>/dev/null; " +
                  "cmd power set-adaptive-power-saver-enabled true 2>/dev/null";
        }
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Enforces Android 12/13 Native Game Mode for a target package.
     */
    public static boolean setTargetGameMode(String packageName, boolean performance) {
        if (!isValidPackageName(packageName)) return false;
        Log.i(TAG, "AOSP GameManager Profile for " + packageName + ": " + performance);
        String cmd = performance
            ? "cmd game mode performance \"" + packageName + "\" 2>&1"
            : "cmd game reset \"" + packageName + "\" 2>&1";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Disables GPU backdrop blur system-wide during gaming to save Adreno GPU fillrate and eliminate frame drops.
     */
    public static boolean setDisableWindowBlur(boolean disable) {
        Log.i(TAG, "Disable Window Blur: " + disable);
        String cmd = "cmd window disable-blur " + (disable ? "1" : "0") + " 2>/dev/null || true";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    public static boolean setWindowBlurDisabled(boolean disable) {
        return setDisableWindowBlur(disable);
    }

    /**
     * Performs full Ahead-of-Time compilation on a target package (dex2oat speed-profile + layout XML pre-compilation).
     */
    public static String compileAppLayoutsAndSpeed(String packageName) {
        if (!isValidPackageName(packageName)) {
            return "{\"status\":\"error\",\"message\":\"Tên gói ứng dụng không hợp lệ\"}";
        }
        Log.i(TAG, "Compiling AOT speed-profile and layouts for " + packageName);
        long start = System.currentTimeMillis();

        String cmd = "cmd package compile -m speed-profile -f \"" + packageName + "\" 2>&1; " +
                     "cmd package compile --compile-layouts \"" + packageName + "\" 2>&1";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        long duration = System.currentTimeMillis() - start;

        try {
            JSONObject obj = new JSONObject();
            obj.put("packageName", packageName);
            obj.put("exitCode", res.exitCode);
            obj.put("output", res.stdout != null ? res.stdout.trim() : "");
            obj.put("durationMs", duration);
            obj.put("status", res.exitCode == 0 ? "success" : "warning");
            return obj.toString();
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    /**
     * Enforces Doze deep idle and whitelists Z-RAM Purger Extreme.
     */
    public static void stepDozeProtectSelf(Context context) {
        if (context == null) return;
        String myPkg = context.getPackageName();
        String cmd = "cmd deviceidle whitelist +" + myPkg + " 2>/dev/null; " +
                     "cmd deviceidle step deep 2>/dev/null || true";
        CommandExecutor.execute(cmd);
    }
}
