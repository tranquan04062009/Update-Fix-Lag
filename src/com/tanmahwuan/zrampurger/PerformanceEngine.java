package com.tanmahwuan.zrampurger;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PerformanceEngine - Deep Qualcomm Adreno 619, Kryo CPU EAS, ART AOT,
 * and Samsung One UI 5 / Android 13 optimization subsystem.
 *
 * Implements hardware-level unlocks and scheduler prioritization via
 * Shizuku/ADB UID 2000 and optional root privileges.
 */
public class PerformanceEngine {

    public static final String TAG_PERF = "ZRamPurger:PerformanceEngine";
    public static final String TAG_BOOSTER = "ZRamPurger:BoosterEngine";

    private static final Pattern SAFE_PKG_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$");

    // Adreno (KGSL) sysfs paths; only used when the node exists on this device
    public static final String KGSL_SYSFS_DIR = "/sys/class/kgsl/kgsl-3d0/";
    public static final String KGSL_CLOCK_MHZ = KGSL_SYSFS_DIR + "clock_mhz";
    public static final String KGSL_BUSY_PCT = KGSL_SYSFS_DIR + "gpu_busy_percentage";
    public static final String KGSL_GPUBUSY = KGSL_SYSFS_DIR + "gpubusy";
    public static final String KGSL_TEMP = KGSL_SYSFS_DIR + "temp";
    public static final String KGSL_MODEL = KGSL_SYSFS_DIR + "gpu_model";
    public static final String KGSL_FREQ_TABLE = KGSL_SYSFS_DIR + "freq_table_mhz";
    public static final String KGSL_MIN_CLOCK = KGSL_SYSFS_DIR + "min_clock_mhz";
    public static final String KGSL_MAX_CLOCK = KGSL_SYSFS_DIR + "max_clock_mhz";
    public static final String KGSL_IDLE_TIMER = KGSL_SYSFS_DIR + "idle_timer";

    // CPU Cluster Frequency sysfs
    public static final String CPU0_CUR_FREQ = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq";
    public static final String CPU6_CUR_FREQ = "/sys/devices/system/cpu/cpu6/cpufreq/scaling_cur_freq";

    /**
     * Telemetry state holder for real-time dashboard visualization.
     */
    public static class PerformanceTelemetry {
        public int gpuClockMhz = -1;
        public int gpuBusyPercent = -1;
        public double gpuTempC = 0.0;
        public double cpuTempC = 0.0;
        public double batteryTempC = 0.0;
        public String gpuModel = "";
        public int littleClusterFreqKHz = 0; // slowest cluster of this device
        public int bigClusterFreqKHz = 0;    // fastest cluster of this device
        public boolean isBoosted = false;
        public String activeMode = "BALANCED";
        public long timestampMs = System.currentTimeMillis();

        public String toJson() {
            try {
                JSONObject json = new JSONObject();
                json.put("gpuClockMhz", gpuClockMhz);
                json.put("gpuBusyPercent", gpuBusyPercent);
                json.put("gpuTempC", String.format(Locale.US, "%.1f", gpuTempC));
                json.put("cpuTempC", String.format(Locale.US, "%.1f", cpuTempC));
                json.put("batteryTempC", String.format(Locale.US, "%.1f", batteryTempC));
                json.put("gpuModel", gpuModel != null ? gpuModel : "");
                json.put("littleClusterFreqKHz", littleClusterFreqKHz);
                json.put("bigClusterFreqKHz", bigClusterFreqKHz);
                json.put("isBoosted", isBoosted);
                json.put("activeMode", activeMode);
                json.put("timestampMs", timestampMs);
                return json.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }

    /**
     * Builds shell script string for SurfaceFlinger rendering optimizations.
     */
    public static String buildSurfaceFlingerScript(boolean enable) {
        StringBuilder sb = new StringBuilder();
        if (enable) {
            sb.append("setprop debug.sf.latch_unsignaled 1\n");
            sb.append("setprop debug.sf.enable_gl_backpressure 0\n");
            sb.append("setprop debug.sf.prime_shader_cache.solid_layers 1\n");
            sb.append("setprop debug.sf.prime_shader_cache.shadow_layers 1\n");
            sb.append("setprop debug.sf.prime_shader_cache.hole_punch 1\n");
            sb.append("setprop debug.sf.prime_shader_cache.clipped_layers 1\n");
            sb.append("setprop debug.sf.prime_shader_cache.pip_image_layers 1\n");
            sb.append("setprop debug.composition.type dyn\n");
            sb.append("setprop debug.egl.hw 1\n");
        } else {
            sb.append("setprop debug.sf.latch_unsignaled 1\n");
            sb.append("setprop debug.sf.enable_gl_backpressure 1\n");
            sb.append("setprop debug.composition.type c2d\n");
            sb.append("setprop debug.egl.hw 0\n");
        }
        return sb.toString();
    }

    /**
     * Applies SurfaceFlinger and RenderEngine rendering optimizations.
     */
    public static boolean applySurfaceFlingerTuning(boolean enable) {
        Log.i(TAG_PERF, "Applying SurfaceFlinger tuning: enable=" + enable);
        String script = buildSurfaceFlingerScript(enable);
        CommandExecutor.CommandResult res = CommandExecutor.execute(script);
        Log.d(TAG_PERF, "SurfaceFlinger tuning executed via " + res.provider + " with exit " + res.exitCode);
        return res != null;
    }

    /**
     * Raw binder transaction numbers ("service call ... 76") differ between One UI versions and cannot be
     * verified here, so they stay OFF unless a developer explicitly enables them for a tested build.
     */
    public static final boolean ENABLE_UNVERIFIED_SAMSUNG_BINDER = false;

    static boolean samsungBinderAllowed() {
        return ENABLE_UNVERIFIED_SAMSUNG_BINDER && "samsung".equalsIgnoreCase(Build.MANUFACTURER);
    }

    /**
     * Builds shell script string for Samsung CFMS and GameSDK binder transactions.
     */
    public static String buildSamsungCfmsScript(boolean enable) {
        StringBuilder sb = new StringBuilder();
        if (enable) {
            sb.append("service call CustomFrequencyManagerService 76 i32 1\n");
            sb.append("service call CustomFrequencyManagerService 111 i32 1\n");
            sb.append("service call CustomFrequencyManagerService 73 i32 0\n");
            sb.append("service call gamesdk 31 i32 1\n");
            sb.append("service call gamesdk 32 i32 1\n");
            sb.append("service call gamesdk 27 i32 1\n");
        } else {
            sb.append("service call CustomFrequencyManagerService 76 i32 0\n");
            sb.append("service call CustomFrequencyManagerService 110\n");
            sb.append("service call CustomFrequencyManagerService 73 i32 1\n");
            sb.append("service call gamesdk 31 i32 0\n");
            sb.append("service call gamesdk 32 i32 0\n");
            sb.append("service call gamesdk 27 i32 0\n");
            sb.append("service call gamesdk 17\n");
        }
        return sb.toString();
    }

    /**
     * Samsung CFMS (CustomFrequencyManagerService) and GameSDK binder calls.
     */
    public static boolean applySamsungCfmsAndGameSdkBoost(boolean enable) {
        if (!samsungBinderAllowed()) return false;
        Log.i(TAG_PERF, "Configuring Samsung CFMS & GameSDK boosts: enable=" + enable);
        String script = buildSamsungCfmsScript(enable);
        CommandExecutor.CommandResult res = CommandExecutor.execute(script);
        Log.d(TAG_PERF, "CFMS & GameSDK commands dispatched with exit=" + res.exitCode);
        return res != null;
    }

    /**
     * Builds shell script string for SDHMS thermal delta and GOS neutralization.
     */
    public static String buildThermalMitigationsScript(boolean enable) {
        StringBuilder sb = new StringBuilder();
        if (enable) {
            sb.append("service call sdhms 30 i32 20\n");
            sb.append("cmd appops set com.samsung.android.game.gos RUN_IN_BACKGROUND ignore 2>/dev/null || true\n");
        } else {
            sb.append("service call sdhms 30 i32 0\n");
            sb.append("cmd appops set com.samsung.android.game.gos RUN_IN_BACKGROUND allow 2>/dev/null || true\n");
        }
        return sb.toString();
    }

    /**
     * Thermal Throttling Mitigations:
     */
    public static boolean applyThermalMitigations(boolean enable) {
        if (!samsungBinderAllowed()) return false;
        Log.i(TAG_PERF, "Configuring Thermal Mitigations: enable=" + enable);
        String script = buildThermalMitigationsScript(enable);
        CommandExecutor.CommandResult res = CommandExecutor.execute(script);
        return res != null;
    }

    /**
     * Builds shell script string for EAS migration of background packages.
     */
    public static String buildMigrationScript(List<String> targetPackages) {
        StringBuilder sb = new StringBuilder();
        if (targetPackages != null) {
            for (String pkg : targetPackages) {
                if (pkg != null && isValidPackageName(pkg)) {
                    sb.append("cmd activity make-uid-idle \"").append(pkg).append("\" 2>/dev/null; ");
                    sb.append("cmd activity set-standby-bucket --user 0 \"").append(pkg).append("\" restricted 2>/dev/null; ");
                    sb.append("cmd activity compact \"").append(pkg).append("\" 0 full 2>/dev/null || cmd activity compact full \"").append(pkg).append("\" 2>/dev/null\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * EAS CPU cpuset migration:
     * Migrates non-whitelisted background apps off Kryo Big cores (6-7) to Little cores (0-3).
     * Uses `cmd activity make-uid-idle` and `cmd activity set-standby-bucket restricted`.
     */
    public static int migrateBackgroundAppsToLittleCores(Context context, List<String> backgroundPackages, Set<String> whitelist) {
        if (backgroundPackages == null || backgroundPackages.isEmpty()) {
            return 0;
        }

        Set<String> criticalWhitelist = PurgeEngine.getCriticalWhitelist(context);
        if (whitelist != null) {
            criticalWhitelist = new HashSet<>(criticalWhitelist);
            criticalWhitelist.addAll(whitelist);
        }

        List<String> targetList = new ArrayList<>();
        for (String pkg : backgroundPackages) {
            if (pkg != null && isValidPackageName(pkg) && !criticalWhitelist.contains(pkg)) {
                targetList.add(pkg);
            }
        }

        Log.i(TAG_PERF, "Migrating " + targetList.size() + " background apps to Little cores (0-3)");
        if (targetList.isEmpty()) {
            return 0;
        }

        String script = buildMigrationScript(targetList);
        CommandExecutor.execute(script);
        return targetList.size();
    }

    /**
     * Ahead-Of-Time (AOT) ART Compilation.
     * Compiles target package to eliminate JIT compilation frame drops.
     */
    public static String triggerArtCompilation(String packageName, String mode) {
        if (packageName == null || !isValidPackageName(packageName)) {
            return "Error: Invalid package name";
        }

        Log.i(TAG_PERF, "Triggering ART dexopt compilation for " + packageName + " with mode=" + mode);
        StringBuilder sb = new StringBuilder();

        if ("speed".equalsIgnoreCase(mode)) {
            sb.append("cmd package compile -m speed -f \"").append(packageName).append("\"\n");
            sb.append("cmd package compile --compile-layouts \"").append(packageName).append("\"\n");
        } else if ("speed-profile".equalsIgnoreCase(mode)) {
            sb.append("cmd package compile -m speed-profile -f \"").append(packageName).append("\"\n");
            sb.append("cmd package compile --compile-layouts \"").append(packageName).append("\"\n");
        } else if ("reset".equalsIgnoreCase(mode)) {
            sb.append("cmd package compile --reset \"").append(packageName).append("\"\n");
        } else {
            return "Error: Unsupported compile mode '" + mode + "'. Use 'speed', 'speed-profile', or 'reset'.";
        }

        CommandExecutor.CommandResult res = CommandExecutor.execute(sb.toString());
        Log.d(TAG_PERF, "ART compile finished for " + packageName + ": stdout=" + res.stdout + ", stderr=" + res.stderr);

        if (res.exitCode == 0) {
            return "Success: " + (res.stdout != null && !res.stdout.isEmpty() ? res.stdout.trim() : "Compiled " + packageName);
        } else {
            return "Failed (" + res.exitCode + "): " + res.stderr;
        }
    }

    /**
     * Triggers the system-wide background dexopt maintenance job.
     */
    public static void triggerSystemDexoptJob() {
        new Thread(() -> {
            Log.i(TAG_PERF, "Triggering system-wide background dexopt job (cmd package bg-dexopt-job)...");
            CommandExecutor.CommandResult res = CommandExecutor.execute("cmd package bg-dexopt-job");
            Log.i(TAG_PERF, "bg-dexopt-job finished with exit=" + res.exitCode);
        }).start();
    }

    /**
     * Adreno (KGSL) GPU clock hold. Works on any Adreno model because the target comes from the GPU's
     * own frequency table; on non-Adreno devices (Mali, PowerVR...) it does nothing.
     *  - enable: raises min clock to the second-highest step of this GPU's table (root only).
     *  - disable: restores exactly the values captured in the baseline snapshot; unknown (-1) values are
     *    never written, so a wrong default can not be applied.
     * Returns true only when a hardware value was really changed and verified.
     */
    public static boolean applyAdrenoKgslTuning(boolean enable, int minClockMhz, int idleTimer) {
        boolean isRoot = CommandExecutor.isRootAvailable();
        boolean changed = false;
        if (isRoot && readNodePrivileged(KGSL_MIN_CLOCK) != null) {
            if (enable) {
                int target = DeviceProfiler.pickBoostFreq(readNodePrivileged(KGSL_FREQ_TABLE));
                if (target > 0) changed |= writeVerified(KGSL_MIN_CLOCK, String.valueOf(target));
                if (idleTimer > 0 && readNodePrivileged(KGSL_IDLE_TIMER) != null)
                    changed |= writeVerified(KGSL_IDLE_TIMER, String.valueOf(idleTimer));
            } else {
                if (minClockMhz > 0) changed |= writeVerified(KGSL_MIN_CLOCK, String.valueOf(minClockMhz));
                if (idleTimer > 0) changed |= writeVerified(KGSL_IDLE_TIMER, String.valueOf(idleTimer));
            }
        }
        // Framework-level sustained performance mode (AOSP, API 31+); harmless where unsupported
        if (Build.VERSION.SDK_INT >= 31) {
            CommandExecutor.execute("cmd power set-fixed-performance-mode-enabled " + enable + " 2>/dev/null || true");
        }
        return changed;
    }

    private static boolean writeVerified(String path, String value) {
        CommandExecutor.execute("echo " + value + " > " + path + " 2>/dev/null");
        return value.equals(readNodePrivileged(path));
    }

    /**
     * Sets Android 12/13 Game Mode interventions for a target gaming package.
     */
    public static boolean setTargetGameMode(String targetPackage, boolean enable) {
        if (targetPackage == null || !isValidPackageName(targetPackage)) {
            return false;
        }

        Log.i(TAG_PERF, "Setting Game Mode for " + targetPackage + ": enable=" + enable);
        String cmd;
        if (enable) {
            cmd = "cmd game mode performance \"" + targetPackage + "\" 2>/dev/null";
        } else {
            cmd = "cmd game reset \"" + targetPackage + "\" 2>/dev/null";
        }

        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Reads real-time performance telemetry for Adreno 619 and Kryo CPU clusters.
     */
    public static PerformanceTelemetry readPerformanceTelemetry(boolean isBoosted, String activeMode) {
        PerformanceTelemetry telemetry = new PerformanceTelemetry();
        telemetry.isBoosted = isBoosted;
        telemetry.activeMode = activeMode != null ? activeMode : (isBoosted ? "EXTREME_PERFORMANCE_MAX" : "OEM_BALANCED");

        // 1-3. GPU model / clock / load from whichever interface THIS device exposes (-1 = unknown)
        DeviceProfiler.Gpu gpu = DeviceProfiler.readGpu();
        telemetry.gpuModel = gpu.model;
        telemetry.gpuClockMhz = gpu.clockMhz;
        telemetry.gpuBusyPercent = gpu.busyPct;

        // 4. Accurate Hardware Temperatures via ThermalHelper
        ThermalHelper.ThermalStats tStats = ThermalHelper.readTemperatures(null);
        telemetry.gpuTempC = tStats.gpuTempC;
        telemetry.cpuTempC = tStats.cpuTempC;
        telemetry.batteryTempC = tStats.batteryTempC;

        // 5. CPU cluster frequencies from the real topology (no hard-coded cpu0 / cpu6)
        DeviceProfiler.Topology topo = DeviceProfiler.topology();
        telemetry.littleClusterFreqKHz = readFreq(topo.littleCpu);
        telemetry.bigClusterFreqKHz = readFreq(topo.bigCpu);

        return telemetry;
    }

    private static int readFreq(int cpu) {
        String v = readSysfsLine("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/scaling_cur_freq");
        if (v == null || v.isEmpty()) return 0;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return 0; }
    }

    /** Reads a privileged node through Shizuku/Root. Returns null when unreadable. */
    public static String readNodePrivileged(String path) {
        CommandExecutor.CommandResult r = CommandExecutor.execute("cat " + path + " 2>/dev/null");
        if (r == null || r.exitCode != 0 || r.stdout == null) return null;
        String t = r.stdout.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Reads first line of a sysfs node safely.
     */
    public static String readSysfsLine(String path) {
        File f = new File(path);
        if (!f.exists() || !f.canRead()) {
            return null;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean isValidPackageName(String pkg) {
        if (pkg == null || pkg.isEmpty() || pkg.length() > 256) {
            return false;
        }
        return SAFE_PKG_PATTERN.matcher(pkg).matches();
    }
}
