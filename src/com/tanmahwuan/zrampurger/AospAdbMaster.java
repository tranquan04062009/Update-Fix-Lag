package com.tanmahwuan.zrampurger;

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

    private static android.content.Context appCtx;

    /** Must be called once with any Context so reversible tweaks can persist their previous values. */
    public static void init(android.content.Context c) {
        if (c != null) appCtx = c.getApplicationContext();
    }

    private static long totalRamMb() {
        try {
            android.app.ActivityManager a = (android.app.ActivityManager) appCtx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            a.getMemoryInfo(mi);
            return mi.totalMem / (1024 * 1024);
        } catch (Throwable t) {
            return 0;
        }
    }

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
     * Freezes DeviceConfig (Phenotype) sync persistently across reboots.
     * Mined from AOSP DeviceConfigService.java:
     * "persistent: Sync is disabled, this state will survive a reboot."
     * Prevents Google Play Services or OEM background services from wiping performance tweaks.
     */
    public static boolean setDeviceConfigSyncDisabledPersistent(boolean disabled) {
        String mode = disabled ? "persistent" : "none";
        Log.i(TAG, "Setting DeviceConfig sync mode: " + mode);
        CommandExecutor.CommandResult res = CommandExecutor.execute("cmd device_config set_sync_disabled_for_tests " + mode + " 2>&1");
        return res.exitCode == 0;
    }

    public static boolean isDeviceConfigSyncDisabledPersistent() {
        CommandExecutor.CommandResult res = CommandExecutor.execute("cmd device_config get_sync_disabled_for_tests 2>/dev/null");
        return res.stdout != null && res.stdout.trim().equalsIgnoreCase("persistent");
    }

    /**
     * Applies proactive low-RAM DeviceConfig (Phenotype) flags extracted from CachedAppOptimizer.java
     * and ActivityManagerConstants.java.
     */
    public static boolean applyDeviceConfigLowRam(boolean enable) {
        if (appCtx == null) return false;
        if (enable) {
            setDeviceConfigSyncDisabledPersistent(true);
        }
        TweakEngine.Tweak t = TweakEngine.lowRam(android.os.Build.VERSION.SDK_INT, totalRamMb());
        TweakEngine.Result r = enable ? TweakEngine.apply(appCtx, t) : TweakEngine.restore(appCtx, t);
        Log.i(TAG, "LowRam DeviceConfig enable=" + enable + " -> " + r.ok + " (" + r.message + ")");
        return r.ok;
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
        CommandExecutor.execute(cmd);
        // Verify by reading back: the exit code of a compound command is not reliable
        return Math.abs(getUiAnimationScale() - scale) < 0.05f;
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

        String cmd = "cmd package compile -m speed -f \"" + packageName + "\" 2>&1 || cmd package compile -m speed-profile -f \"" + packageName + "\" 2>&1; " +
                     "cmd package compile --compile-layouts \"" + packageName + "\" 2>&1; " +
                     "cmd game mode performance \"" + packageName + "\" 2>/dev/null; " +
                     "cmd game set --mode performance --fps 120 \"" + packageName + "\" 2>/dev/null; " +
                     "cmd device_config put game_overlay \"" + packageName + "\" mode=2,fps=120 2>/dev/null";
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

    /**
     * Enables or disables High Touch Sensitivity / Glove Mode to reduce screen touch input latency.
     */
    public static boolean setTouchSensitivityBoost(boolean enable) {
        if (appCtx == null) return false;
        TweakEngine.Result r = enable ? TweakEngine.apply(appCtx, TweakEngine.TOUCH)
                                      : TweakEngine.restore(appCtx, TweakEngine.TOUCH);
        return r.ok;
    }

    /**
     * Optimizes SurfaceFlinger and HWUI frame presentation pipeline:
     * - latch_unsignaled: eliminates fence wait latency for instant frame latching
     * - enable_layer_caching: caches static GPU layers to prevent redundant redraws
     * - predict_hwc_composition_strategy: preemptively schedules hardware composer overlays
     * - enable_gl_backpressure 0: eliminates GPU backpressure stuttering during touch gestures
     * - prime_shader_cache: pre-primes shaders to eliminate jank when opening windows
     * - set_idle_timer_ms 0: prevents display refresh rate downclocking during interaction
     * - render_thread_priority: sets RenderThread to RT priority (-20)
     * - early phase offsets: minimizes touch-to-display latency
     * - disable-blur: disables expensive Gaussian blur passes
     * - looper_stats disable: eliminates binder looper profiling overhead
     */
    public static boolean setSurfaceFlingerZeroLatency(boolean enable) {
        Log.i(TAG, "SurfaceFlinger Zero Latency Pipeline: " + enable);
        StringBuilder sb = new StringBuilder();
        if (enable) {
            sb.append("setprop debug.sf.latch_unsignaled 1 2>/dev/null; ");
            sb.append("setprop debug.sf.enable_layer_caching 1 2>/dev/null; ");
            sb.append("setprop debug.sf.predict_hwc_composition_strategy 1 2>/dev/null; ");
            sb.append("setprop debug.sf.enable_gl_backpressure 0 2>/dev/null; ");
            sb.append("setprop debug.sf.set_idle_timer_ms 0 2>/dev/null; ");
            sb.append("setprop debug.sf.prime_shader_cache.solid_layers 1 2>/dev/null; ");
            sb.append("setprop debug.sf.prime_shader_cache.image_layers 1 2>/dev/null; ");
            sb.append("setprop debug.sf.early_phase_offset_ns 500000 2>/dev/null; ");
            sb.append("setprop debug.sf.early_app_phase_offset_ns 500000 2>/dev/null; ");
            sb.append("setprop debug.sf.early_gl_phase_offset_ns 3000000 2>/dev/null; ");
            sb.append("setprop debug.sf.high_fps_early_phase_offset_ns 500000 2>/dev/null; ");
            sb.append("setprop debug.sf.high_fps_early_gl_phase_offset_ns 3000000 2>/dev/null; ");
            sb.append("setprop debug.hwui.render_thread_priority -20 2>/dev/null; ");
            sb.append("setprop debug.hwui.use_hint_manager true 2>/dev/null; ");
            sb.append("setprop debug.hwui.target_cpu_time_percent 100 2>/dev/null; ");
            sb.append("setprop debug.hwui.fps_divisor 1 2>/dev/null; ");
            sb.append("cmd window disable-blur 1 2>/dev/null; ");
            sb.append("cmd looper_stats disable 2>/dev/null; ");
        } else {
            sb.append("setprop debug.sf.latch_unsignaled 0 2>/dev/null; ");
            sb.append("setprop debug.sf.enable_gl_backpressure 1 2>/dev/null; ");
            sb.append("setprop debug.hwui.render_thread_priority 0 2>/dev/null; ");
            sb.append("cmd window disable-blur 0 2>/dev/null; ");
            sb.append("cmd looper_stats enable 2>/dev/null; ");
        }
        CommandExecutor.CommandResult res = CommandExecutor.execute(sb.toString());
        return res.exitCode == 0;
    }

    /**
     * Enforces Low-Latency TCP and WiFi keepalive during high performance gaming.
     */
    public static boolean setLowLatencyNetworking(boolean enable) {
        Log.i(TAG, "Low Latency Networking: " + enable);
        String cmd = enable
            ? "settings put global tcp_default_init_rwnd 60 2>/dev/null; " +
              "settings put global wifi_sleep_policy 2 2>/dev/null"
            : "settings put global tcp_default_init_rwnd 10 2>/dev/null; " +
              "settings put global wifi_sleep_policy 2 2>/dev/null";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Configures advanced Game Manager intervention configs (target FPS and dynamic downscale).
     * Supports both standard cmd game set and persistent device_config game_overlay overrides.
     */
    public static boolean setGameResolutionAndFps(String packageName, float downscale, int targetFps) {
        if (!isValidPackageName(packageName)) return false;
        Log.i(TAG, "Configuring GameManagerService for " + packageName + ": downscale=" + downscale + ", fps=" + targetFps);
        StringBuilder sb = new StringBuilder();

        // 1. Force performance mode
        sb.append("cmd game mode performance \"").append(packageName).append("\" 2>/dev/null; ");

        // 2. Set cmd game intervention config
        String downscaleArg = (downscale > 0.0f && downscale < 1.0f)
                ? String.format(Locale.US, "%.2f", downscale)
                : "disable";
        String fpsArg = (targetFps > 0) ? String.valueOf(targetFps) : "disable";

        sb.append("cmd game set --mode performance --downscale ").append(downscaleArg)
          .append(" --fps ").append(fpsArg)
          .append(" \"").append(packageName).append("\" 2>/dev/null; ");

        // 3. DeviceConfig persistent overlay fallback (crucial for games not flagged with appCategory="game")
        if (downscale > 0.0f && downscale < 1.0f && targetFps > 0) {
            sb.append("cmd device_config put game_overlay \"").append(packageName)
              .append("\" mode=2,downscaleFactor=").append(downscaleArg)
              .append(",fps=").append(fpsArg).append(" 2>/dev/null; ");
        } else if (downscale > 0.0f && downscale < 1.0f) {
            sb.append("cmd device_config put game_overlay \"").append(packageName)
              .append("\" mode=2,downscaleFactor=").append(downscaleArg).append(" 2>/dev/null; ");
        } else if (targetFps > 0) {
            sb.append("cmd device_config put game_overlay \"").append(packageName)
              .append("\" mode=2,fps=").append(fpsArg).append(" 2>/dev/null; ");
        } else {
            sb.append("cmd device_config delete game_overlay \"").append(packageName).append("\" 2>/dev/null; ");
        }

        // 4. Ensure device_config sync is disabled so Phenotype doesn't erase custom interventions
        sb.append("cmd device_config set_sync_disabled_for_tests persistent 2>/dev/null; ");

        CommandExecutor.CommandResult res = CommandExecutor.execute(sb.toString());
        return res.exitCode == 0;
    }

    /**
     * Backward-compatible alias for setGameResolutionAndFps.
     */
    public static boolean setGameInterventions(String packageName, int targetFps, float downscale) {
        return setGameResolutionAndFps(packageName, downscale, targetFps);
    }

    /**
     * Resets GameManagerService interventions back to default native 100% resolution and OEM profile.
     */
    public static boolean resetGameResolution(String packageName) {
        if (!isValidPackageName(packageName)) return false;
        Log.i(TAG, "Resetting GameManagerService settings for " + packageName + " to OEM default");
        String cmd = "cmd game reset \"" + packageName + "\" 2>&1; " +
                     "cmd game set --mode performance --downscale disable --fps disable \"" + packageName + "\" 2>/dev/null; " +
                     "cmd game set --mode standard --downscale disable --fps disable \"" + packageName + "\" 2>/dev/null; " +
                     "cmd game mode standard \"" + packageName + "\" 2>/dev/null; " +
                     "cmd device_config delete game_overlay \"" + packageName + "\" 2>/dev/null";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Inspects active game intervention or overlay config for a package.
     */
    public static String getGameResolutionStatus(String packageName) {
        if (!isValidPackageName(packageName)) return "Chưa chọn ứng dụng";
        CommandExecutor.CommandResult overlayRes = CommandExecutor.execute("cmd device_config get game_overlay \"" + packageName + "\" 2>/dev/null");
        String overlay = (overlayRes.stdout != null) ? overlayRes.stdout.trim() : "";
        if (!overlay.isEmpty() && !overlay.equals("null") && !overlay.equals("undefined")) {
            return "Đang can thiệp: " + overlay;
        }
        CommandExecutor.CommandResult modeRes = CommandExecutor.execute("cmd game mode \"" + packageName + "\" 2>/dev/null");
        String mode = (modeRes.stdout != null) ? modeRes.stdout.trim() : "";
        if (mode.contains("2") || mode.contains("performance")) {
            return "Chế độ: Performance (Phân giải gốc 100%)";
        } else if (!mode.isEmpty()) {
            return "Chế độ: " + mode + " (Mặc định 100%)";
        }
        return "Mặc định (Chưa can thiệp 100%)";
    }

    /**
     * Restricts background app from running and draining RAM/battery via AppOps.
     */
    public static boolean setAppBackgroundRestricted(String packageName, boolean restrict) {
        if (!isValidPackageName(packageName)) return false;
        String mode = restrict ? "ignore" : "allow";
        String cmd = "cmd appops set --user 0 \"" + packageName + "\" RUN_IN_BACKGROUND " + mode + " 2>/dev/null; " +
                     "cmd appops set --user 0 \"" + packageName + "\" RUN_ANY_IN_BACKGROUND " + mode + " 2>/dev/null";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Triggers AOSP memory pressure factor pulse to force all apps to release memory caches.
     */
    public static void pulseMemoryPressure() {
        Log.i(TAG, "Pulsing AOSP memory pressure factor CRITICAL...");
        CommandExecutor.execute("cmd activity memory-factor set CRITICAL 2>/dev/null");
        try {
            Thread.sleep(600);
        } catch (InterruptedException ignored) {}
        CommandExecutor.execute("cmd activity memory-factor reset 2>/dev/null");
        Log.i(TAG, "Memory pressure factor reset to normal.");
    }

    /**
     * Redirects graphics engine to ANGLE (Vulkan backend) for OpenGL ES.
     * Mined from AOSP GraphicsEnvironment.java & frameworks/base.
     */
    public static boolean setAngleVulkanBackend(boolean enable) {
        Log.i(TAG, "Setting ANGLE Vulkan backend: " + enable);
        StringBuilder sb = new StringBuilder();
        if (enable) {
            sb.append("settings put global angle_gl_driver_all_angle 1 2>/dev/null; ");
            sb.append("settings put global show_angle_in_use_dialog_box 0 2>/dev/null; ");
            sb.append("settings put global updatable_driver_all_apps 1 2>/dev/null; ");
            sb.append("setprop debug.angle.backend 2 2>/dev/null; ");
            sb.append("setprop persist.graphics.egl angle 2>/dev/null; ");
        } else {
            sb.append("settings put global angle_gl_driver_all_angle 0 2>/dev/null; ");
            sb.append("settings delete global angle_gl_driver_all_angle 2>/dev/null; ");
            sb.append("setprop debug.angle.backend 0 2>/dev/null; ");
            sb.append("setprop persist.graphics.egl \"\" 2>/dev/null; ");
        }
        CommandExecutor.CommandResult res = CommandExecutor.execute(sb.toString());
        return res.exitCode == 0;
    }

    /**
     * Suppresses background logging, tracing, and crash dumps to free CPU cycles.
     */
    public static boolean setZeroTelemetryLogSuppression(boolean enable) {
        Log.i(TAG, "Zero-telemetry log suppression: " + enable);
        StringBuilder sb = new StringBuilder();
        if (enable) {
            sb.append("setprop log.tag.all SUPPRESS 2>/dev/null; ");
            sb.append("setprop persist.logd.size 64K 2>/dev/null; ");
            sb.append("logcat -G 64K 2>/dev/null; ");
            sb.append("logcat -c 2>/dev/null; ");
            sb.append("setprop persist.traced.enable 0 2>/dev/null; ");
            sb.append("settings put global dropbox_max_files 0 2>/dev/null; ");
            sb.append("cmd looper_stats disable 2>/dev/null; ");
            sb.append("dumpsys binder_calls_stats --disable 2>/dev/null; ");
        } else {
            sb.append("setprop log.tag.all \"\" 2>/dev/null; ");
            sb.append("setprop persist.logd.size 256K 2>/dev/null; ");
            sb.append("setprop persist.traced.enable 1 2>/dev/null; ");
            sb.append("settings put global dropbox_max_files 50 2>/dev/null; ");
            sb.append("cmd looper_stats enable 2>/dev/null; ");
        }
        CommandExecutor.CommandResult res = CommandExecutor.execute(sb.toString());
        return res.exitCode == 0;
    }

    /**
     * Sets 0ms touch input latency (disables synthetic input resampling).
     */
    public static boolean setZeroLatencyTouchInput(boolean enable) {
        Log.i(TAG, "Setting 0ms Touch Input: " + enable);
        StringBuilder sb = new StringBuilder();
        if (enable) {
            sb.append("setprop ro.input.resampling 0 2>/dev/null; ");
            sb.append("setprop persist.input.touch.resampling 0 2>/dev/null; ");
            sb.append("setprop windowsmgr.max_events_per_sec 240 2>/dev/null; ");
        } else {
            sb.append("setprop ro.input.resampling 1 2>/dev/null; ");
            sb.append("setprop persist.input.touch.resampling 1 2>/dev/null; ");
            sb.append("setprop windowsmgr.max_events_per_sec 90 2>/dev/null; ");
        }
        CommandExecutor.CommandResult res = CommandExecutor.execute(sb.toString());
        return res.exitCode == 0;
    }

    // ---------------- Kernel VM tuning (reversible, only on nodes this kernel exposes) ----------------

    private static final java.util.regex.Pattern NODE_OK =
            java.util.regex.Pattern.compile("^/(proc/sys/vm|sys/block)/[A-Za-z0-9_./-]+$");
    private static final String VM_PREFS = "kernel_vm";

    /** Builds the (path, value) list for THIS device. Pure function, unit-tested. */
    static java.util.LinkedHashMap<String, String> kernelVmTargets(java.util.List<String> blockDevs, boolean byteLimitsInUse) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
        m.put("/proc/sys/vm/page-cluster", "0");
        m.put("/proc/sys/vm/swappiness", "160");
        m.put("/proc/sys/vm/watermark_boost_factor", "0");
        m.put("/proc/sys/vm/compaction_proactiveness", "20");
        m.put("/proc/sys/vm/vfs_cache_pressure", "50");
        m.put("/proc/sys/vm/stat_interval", "10");
        if (!byteLimitsInUse) {          // writing a ratio would silently reset *_bytes limits
            m.put("/proc/sys/vm/dirty_background_ratio", "5");
            m.put("/proc/sys/vm/dirty_ratio", "10");
        }
        for (String d : blockDevs) m.put("/sys/block/" + d + "/queue/read_ahead_kb", "512");
        return m;
    }

    static java.util.List<String> parseBlockDevices(String ls) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (ls == null) return out;
        for (String n : ls.trim().split("\\s+"))
            if (n.matches("sd[a-z]+|mmcblk\\d+|nvme\\d+n\\d+")) out.add(n);
        return out;
    }

    private static java.util.Map<String, String> readNodes(java.util.Collection<String> paths) {
        StringBuilder sb = new StringBuilder();
        for (String p : paths) sb.append("echo \"").append(p).append("=$(cat ").append(p).append(" 2>/dev/null)\"\n");
        java.util.Map<String, String> out = new java.util.HashMap<>();
        CommandExecutor.CommandResult r = CommandExecutor.execute(sb.toString());
        if (r == null || r.stdout == null) return out;
        for (String line : r.stdout.split("\n")) {
            int i = line.indexOf('=');
            if (i > 0 && i < line.length() - 1) out.put(line.substring(0, i), line.substring(i + 1).trim());
        }
        return out;
    }

    public static boolean applyKernelVmOptimization(android.content.Context c) {
        init(c);
        if (appCtx == null) return false;
        android.content.SharedPreferences sp = appCtx.getSharedPreferences(VM_PREFS, android.content.Context.MODE_PRIVATE);
        if (sp.contains("snap")) return true;      // already applied; keep the ORIGINAL values

        CommandExecutor.CommandResult ls = CommandExecutor.execute("ls /sys/block 2>/dev/null");
        java.util.List<String> devs = parseBlockDevices(ls == null ? null : ls.stdout);
        java.util.Map<String, String> bytes = readNodes(java.util.Arrays.asList(
                "/proc/sys/vm/dirty_bytes", "/proc/sys/vm/dirty_background_bytes"));
        boolean byteLimits = !"0".equals(bytes.get("/proc/sys/vm/dirty_bytes")) && bytes.get("/proc/sys/vm/dirty_bytes") != null
                || !"0".equals(bytes.get("/proc/sys/vm/dirty_background_bytes")) && bytes.get("/proc/sys/vm/dirty_background_bytes") != null;

        java.util.LinkedHashMap<String, String> targets = kernelVmTargets(devs, byteLimits);
        java.util.Map<String, String> before = readNodes(targets.keySet());

        StringBuilder w = new StringBuilder();
        java.util.List<String> touched = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : targets.entrySet()) {
            String prev = before.get(e.getKey());
            if (prev == null || prev.isEmpty() || !NODE_OK.matcher(e.getKey()).matches()) continue;  // node absent here
            if (prev.equals(e.getValue())) continue;
            w.append("echo ").append(e.getValue()).append(" > ").append(e.getKey()).append(" 2>/dev/null\n");
            touched.add(e.getKey());
        }
        if (touched.isEmpty()) return false;
        CommandExecutor.execute(w.toString());
        java.util.Map<String, String> after = readNodes(touched);

        org.json.JSONObject snap = new org.json.JSONObject();
        int verified = 0;
        try {
            for (String path : touched) {
                if (targets.get(path).equals(after.get(path))) {
                    snap.put(path, before.get(path));
                    verified++;
                } else if (!before.get(path).equals(after.get(path))) {
                    snap.put(path, before.get(path));   // partially changed: still restorable
                }
            }
        } catch (Exception ignored) {}
        if (snap.length() > 0) sp.edit().putString("snap", snap.toString()).apply();
        Log.i(TAG, "Kernel VM tuning verified on " + verified + "/" + touched.size() + " nodes");
        return verified > 0;
    }

    public static boolean restoreKernelVm(android.content.Context c) {
        init(c);
        if (appCtx == null) return false;
        android.content.SharedPreferences sp = appCtx.getSharedPreferences(VM_PREFS, android.content.Context.MODE_PRIVATE);
        String raw = sp.getString("snap", null);
        if (raw == null) return true;
        try {
            org.json.JSONObject snap = new org.json.JSONObject(raw);
            StringBuilder w = new StringBuilder();
            java.util.Iterator<String> it = snap.keys();
            while (it.hasNext()) {
                String path = it.next();
                String val = snap.getString(path);
                if (NODE_OK.matcher(path).matches() && val.matches("[0-9]+"))
                    w.append("echo ").append(val).append(" > ").append(path).append(" 2>/dev/null\n");
            }
            CommandExecutor.execute(w.toString());
        } catch (Exception ignored) {}
        sp.edit().remove("snap").apply();
        return true;
    }
}
