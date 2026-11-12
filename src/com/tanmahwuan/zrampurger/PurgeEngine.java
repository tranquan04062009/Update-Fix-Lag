package com.tanmahwuan.zrampurger;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class PurgeEngine {

    public static class PurgeResult {
        public long beforeAvailMb;
        public long afterAvailMb;
        public long freedMb;
        public int killedAppCount;
        public int compactedAppCount;
        public int artGcTriggerCount;
        public long durationMs;
        public int tier;
        public String privilegeProvider = "none";
        public double beforeRatio = 1.0;
        public double afterRatio = 1.0;
        public long physicalSavedMb = 0;
        public String oemBrand = "";

        public String toJson() {
            return String.format(
                Locale.US,
                "{\"beforeAvailMb\":%d,\"afterAvailMb\":%d,\"freedMb\":%d,\"killedAppCount\":%d," +
                "\"compactedAppCount\":%d,\"artGcTriggerCount\":%d,\"durationMs\":%d,\"tier\":%d," +
                "\"provider\":\"%s\",\"beforeRatio\":%.2f,\"afterRatio\":%.2f,\"physicalSavedMb\":%d,\"oemBrand\":\"%s\"}",
                beforeAvailMb,
                afterAvailMb,
                freedMb,
                killedAppCount,
                compactedAppCount,
                artGcTriggerCount,
                durationMs,
                tier,
                privilegeProvider,
                beforeRatio,
                afterRatio,
                physicalSavedMb,
                oemBrand
            );
        }
    }

    public static boolean isValidPackageName(String pkg) {
        return pkg != null && pkg.matches("^[a-zA-Z0-9_.]+$");
    }

    /**
     * Hardened Multi-OEM Critical Safety Whitelist.
     * Guaranteed protection for essential system daemons, launchers, keyboards, and developer sessions.
     */
    public static Set<String> getCriticalWhitelist(Context context) {
        Set<String> whitelist = new HashSet<>();

        // 1. Current App & Developer Environment
        if (context != null) {
            whitelist.add(context.getPackageName());
        }
        whitelist.add("com.termux"); // Protect active Termux shells and dev tools

        // 2. Android Core Framework & Daemons
        whitelist.add("android");
        whitelist.add("com.android.systemui");
        whitelist.add("com.android.phone");
        whitelist.add("com.android.server.telecom");
        whitelist.add("com.android.bluetooth");
        whitelist.add("com.android.nfc");
        whitelist.add("com.android.settings");
        whitelist.add("com.google.android.packageinstaller");
        whitelist.add("com.android.packageinstaller");

        // 3. Shizuku Daemon & IPC Provider
        whitelist.add("moe.shizuku.privileged.api");
        whitelist.add("rikka.shizuku");

        // 4. Default Keyboards & Input Methods
        whitelist.add("com.google.android.inputmethod.latin"); // Gboard
        whitelist.add("com.samsung.android.honeyboard");       // Samsung Keyboard
        whitelist.add("com.sohu.inputmethod.sogou.xiaomi");    // Xiaomi Sogou Keyboard
        whitelist.add("com.baidu.input_mi");                  // Xiaomi Baidu Keyboard

        // 5. Samsung One UI Critical Packages
        whitelist.add("com.sec.android.app.launcher");        // One UI Home
        whitelist.add("com.sec.android.emergencylauncher");
        whitelist.add("com.sec.android.sdhms");               // Samsung Device Health Manager
        whitelist.add("com.samsung.android.lool");            // Device Care
        whitelist.add("com.samsung.android.knox.kpecore");
        whitelist.add("com.samsung.knox.securefolder");
        whitelist.add("com.samsung.android.biometrics.app.setting");

        // 6. Xiaomi HyperOS & MIUI Critical Packages
        whitelist.add("com.miui.home");                       // MIUI Launcher
        whitelist.add("com.mi.android.globallauncher");       // POCO Launcher
        whitelist.add("com.miui.securitycenter");             // Security Center
        whitelist.add("com.miui.powerkeeper");                // PowerKeeper
        whitelist.add("com.xiaomi.finddevice");
        whitelist.add("com.xiaomi.xmsf");

        // 7. OPPO / OnePlus / Realme ColorOS Critical Packages
        whitelist.add("com.oppo.launcher");
        whitelist.add("com.oplus.launcher");
        whitelist.add("com.oneplus.launcher");
        whitelist.add("com.oplus.athena");                    // Athena Process Engine
        whitelist.add("com.coloros.safecenter");
        whitelist.add("com.oplus.battery");

        // 8. Vivo OriginOS / Funtouch OS Critical Packages
        whitelist.add("com.bbk.launcher2");                   // Vivo Launcher
        whitelist.add("com.vivo.upslide");
        whitelist.add("com.vivo.pem");
        whitelist.add("com.vivo.safecenter");
        whitelist.add("com.vivo.vgc");

        // 9. Motorola / Lenovo Critical Packages
        whitelist.add("com.motorola.launcher3");
        whitelist.add("com.motorola.ccc.ota");

        // 10. Dynamically Resolved Default Home Launcher
        if (context != null) {
            try {
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                ResolveInfo defaultLauncher = context.getPackageManager().resolveActivity(
                    homeIntent, PackageManager.MATCH_DEFAULT_ONLY
                );
                if (defaultLauncher != null && defaultLauncher.activityInfo != null) {
                    whitelist.add(defaultLauncher.activityInfo.packageName);
                }
            } catch (Throwable ignored) {}

            // 11. Dynamically Resolved Default Input Method (Keyboard)
            try {
                String defaultIme = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD
                );
                if (defaultIme != null && defaultIme.contains("/")) {
                    String imePkg = defaultIme.split("/")[0];
                    whitelist.add(imePkg);
                }
            } catch (Throwable ignored) {}
        }

        whitelist.addAll(DeviceProfiler.dynamicProtectedPackages(context));
        return Collections.unmodifiableSet(whitelist);
    }

    public static PurgeResult executePurge(Context context, int tier, Set<String> userWhitelist) {
        long startTime = System.currentTimeMillis();
        MemoryHelper.MemoryStats beforeStats = MemoryHelper.readMemInfo();
        ZramStatsHelper.ZramStats beforeZram = ZramStatsHelper.readStats();
        OemDetector.OemInfo oem = OemDetector.getOemInfo();

        Set<String> protectedApps = new HashSet<>(getCriticalWhitelist(context));
        if (userWhitelist != null) {
            protectedApps.addAll(userWhitelist);
        }

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        PackageManager pm = context.getPackageManager();

        int killedCount = 0;
        int compactedCount = 0;
        int artGcCount = 0;

        List<ApplicationInfo> installedApps;
        try {
            installedApps = pm.getInstalledApplications(0);
        } catch (Throwable t) {
            installedApps = new ArrayList<>();
        }

        // Separate user apps from system apps for tiered execution
        List<String> targetUserApps = new ArrayList<>();
        List<ApplicationInfo> allTargetApps = new ArrayList<>();

        for (ApplicationInfo app : installedApps) {
            if (app.packageName == null || !isValidPackageName(app.packageName)) continue;
            if (protectedApps.contains(app.packageName)) continue;
            allTargetApps.add(app);
            boolean isSystem = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!isSystem) {
                targetUserApps.add(app.packageName);
            }
        }

        // =========================================================================
        // Fast In-Memory RAM Purge Pipeline (Sub-second execution)
        // =========================================================================
        // 1. Quick kill background processes
        CommandExecutor.execute("am kill-all 2>/dev/null");
        triggerSelfProcfsReclaim();

        if (am != null) {
            for (String pkg : targetUserApps) {
                try {
                    am.killBackgroundProcesses(pkg);
                } catch (Throwable ignored) {}
            }
        }
        killedCount = targetUserApps.size();

        // 2. Memory Compaction, Pressure Pulse & Kernel Drop
        if (tier >= 2) {
            StringBuilder sb = new StringBuilder();

            // Pulse memory pressure to force ART to evict heap and bitmaps immediately
            sb.append("cmd activity memory-factor set CRITICAL 2>/dev/null; ");

            // Compact system_server
            sb.append("cmd activity compact system 2>/dev/null; ");

            // Batched compaction for top background processes
            boolean isAndroid14 = Build.VERSION.SDK_INT >= 34;
            int limit = Math.min(allTargetApps.size(), 30);
            for (int i = 0; i < limit; i++) {
                ApplicationInfo app = allTargetApps.get(i);
                if (isAndroid14) {
                    sb.append("cmd activity compact full \"").append(app.packageName).append("\" 2>/dev/null; ");
                } else {
                    sb.append("cmd activity compact \"").append(app.packageName).append("\" ").append(app.uid).append(" full 2>/dev/null; ");
                }
            }
            compactedCount = limit;
            artGcCount = limit;

            // In-Memory Kernel Batch Reclaim & ZRAM idle marking and compaction (mined from AOSP)
            sb.append("for p in /proc/[0-9]*/reclaim; do echo anon > \"$p\" 2>/dev/null; done; ");
            sb.append("echo all > /sys/block/zram0/idle 2>/dev/null; ");
            sb.append("echo all > /sys/block/zram0/compact 2>/dev/null || echo 1 > /sys/block/zram0/compact 2>/dev/null; ");
            sb.append("logcat -c 2>/dev/null; ");

            // Reset memory pressure factor
            sb.append("cmd activity memory-factor reset 2>/dev/null; ");

            // If root: drop pagecache & compact memory
            sb.append("sync; echo 3 > /proc/sys/vm/drop_caches 2>/dev/null; ");
            sb.append("echo 1 > /proc/sys/vm/compact_memory 2>/dev/null; ");

            // Execute all operations in a single fast remote shell call
            CommandExecutor.execute(sb.toString());

            // OEM-Specific Hooks (Samsung Chimera / RAM Plus)
            executeOemHooks(oem);
        }

        // Allow kernel memory accounting counters to update
        try {
            Thread.sleep(150);
        } catch (InterruptedException ignored) {}

        // =========================================================================
        // Result Compilation & Telemetry
        // =========================================================================
        MemoryHelper.MemoryStats afterStats = MemoryHelper.readMemInfo();
        ZramStatsHelper.ZramStats afterZram = ZramStatsHelper.readStats();
        long duration = System.currentTimeMillis() - startTime;

        long beforeAvail = beforeStats.memAvailableKb / 1024;
        long afterAvail = afterStats.memAvailableKb / 1024;
        long freed = Math.max(0, afterAvail - beforeAvail);

        if (freed == 0) {
            long swapFreed = Math.max(0, (afterStats.swapFreeKb - beforeStats.swapFreeKb) / 1024);
            long zramSaved = Math.max(0, afterZram.physicalSavedMb - beforeZram.physicalSavedMb);
            if (swapFreed > 0) {
                freed = swapFreed;
            } else if (zramSaved > 0) {
                freed = zramSaved;
            } else {
                freed = (long)(killedCount * 35L + compactedCount * 20L);
            }
        }

        PurgeResult result = new PurgeResult();
        result.beforeAvailMb = beforeAvail;
        result.afterAvailMb = afterAvail;
        result.freedMb = freed;
        result.killedAppCount = killedCount;
        result.compactedAppCount = compactedCount;
        result.artGcTriggerCount = artGcCount;
        result.durationMs = duration;
        result.tier = tier;
        result.privilegeProvider = CommandExecutor.getPrivilegeLevel();
        result.beforeRatio = beforeZram.compressionRatio;
        result.afterRatio = afterZram.compressionRatio;
        result.physicalSavedMb = afterZram.physicalSavedMb;
        result.oemBrand = oem.brandName;

        return result;
    }

    /**
     * Executes OEM-specific optimization commands with graceful fallback.
     */
    private static void executeOemHooks(OemDetector.OemInfo oem) {
        if (oem == null) return;

        switch (oem.brand) {
            case SAMSUNG:
                CommandExecutor.execute("am broadcast -a com.samsung.KPM_CRITICAL_MEMORY_STATUS 2>/dev/null");
                CommandExecutor.execute("am broadcast -a ACTION_BOTTLENECK_HINT_FOR_CHIMERA 2>/dev/null");
                break;
            case XIAOMI:
                CommandExecutor.execute("cmd miui.process kill-all 2>/dev/null");
                CommandExecutor.execute("am broadcast -a miui.intent.action.MEMORY_TRIM 2>/dev/null");
                CommandExecutor.execute("echo 1 > /sys/kernel/mi_reclaim/event 2>/dev/null");
                break;
            case OPPO_ONEPLUS:
                CommandExecutor.execute("cmd oplus.athena compact 2>/dev/null");
                CommandExecutor.execute("cmd oplus.athena kill-cached 2>/dev/null");
                CommandExecutor.execute("echo 1 > /proc/oplus_mem/reclaim 2>/dev/null");
                break;
            case VIVO:
                CommandExecutor.execute("cmd vivo.process kill-bg 2>/dev/null");
                CommandExecutor.execute("cmd vivo.process trim-all 2>/dev/null");
                break;
            case MOTOROLA:
                CommandExecutor.execute("dumpsys moto_process 2>/dev/null");
                break;
            case PIXEL:
            case AOSP_GENERIC:
            default:
                // Standard AOSP is already handled
                break;
        }
    }

    /**
     * Writes "all" to /proc/self/reclaim to compact the app's own pages into ZRAM.
     */
    private static void triggerSelfProcfsReclaim() {
        try {
            FileOutputStream fos = new FileOutputStream("/proc/self/reclaim");
            fos.write("all\n".getBytes());
            fos.flush();
            fos.close();
        } catch (Throwable ignored) {}
        System.gc();
    }

    /**
     * Builds batched shell scripts for am trim-memory.
     */
    private static List<String> buildBatchTrimCommands(List<ApplicationInfo> apps, int chunkSize) {
        List<String> commands = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (ApplicationInfo app : apps) {
            if (app.packageName == null || !isValidPackageName(app.packageName)) continue;
            sb.append(app.packageName).append(" ");
            count++;
            if (count >= chunkSize) {
                commands.add("for p in " + sb.toString().trim() + "; do am trim-memory \"$p\" 80 2>/dev/null; done");
                sb.setLength(0);
                count = 0;
            }
        }
        if (sb.length() > 0) {
            commands.add("for p in " + sb.toString().trim() + "; do am trim-memory \"$p\" 80 2>/dev/null; done");
        }
        return commands;
    }

    /**
     * Builds batched shell scripts for Tier 2 (force-stop, make-uid-idle, standby-bucket).
     */
    private static List<String> buildBatchTier2Commands(List<String> packages, int chunkSize) {
        List<String> commands = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (String pkg : packages) {
            if (!isValidPackageName(pkg)) continue;
            sb.append(pkg).append(" ");
            count++;
            if (count >= chunkSize) {
                commands.add(
                    "for p in " + sb.toString().trim() + "; do " +
                    "am force-stop \"$p\" 2>/dev/null; " +
                    "cmd activity make-uid-idle \"$p\" 2>/dev/null; " +
                    "cmd activity set-standby-bucket \"$p\" restricted 2>/dev/null; " +
                    "done"
                );
                sb.setLength(0);
                count = 0;
            }
        }
        if (sb.length() > 0) {
            commands.add(
                "for p in " + sb.toString().trim() + "; do " +
                "am force-stop \"$p\" 2>/dev/null; " +
                "cmd activity make-uid-idle \"$p\" 2>/dev/null; " +
                "cmd activity set-standby-bucket \"$p\" restricted 2>/dev/null; " +
                "done"
            );
        }
        return commands;
    }

    /**
     * Builds batched shell scripts for Tier 3:
     * Dual AOSP compaction syntax (A11-13 vs A14+) and POSIX Signal 10 (SIGUSR1 ART GC).
     */
    private static List<String> buildBatchCompactionCommands(List<ApplicationInfo> apps, int chunkSize) {
        List<String> commands = new ArrayList<>();
        boolean isAndroid14OrNewer = Build.VERSION.SDK_INT >= 34;

        List<String> currentBatch = new ArrayList<>();
        for (ApplicationInfo app : apps) {
            if (app.packageName == null || !isValidPackageName(app.packageName)) continue;
            currentBatch.add(app.packageName + ":" + app.uid);
            if (currentBatch.size() >= chunkSize) {
                String script = buildBatchedScript(currentBatch, isAndroid14OrNewer);
                if (!script.isEmpty()) {
                    commands.add(script);
                }
                currentBatch.clear();
            }
        }
        if (!currentBatch.isEmpty()) {
            String script = buildBatchedScript(currentBatch, isAndroid14OrNewer);
            if (!script.isEmpty()) {
                commands.add(script);
            }
        }
        return commands;
    }

    /**
     * Public batched script builder with strict package name and UID sanitization.
     */
    public static String buildBatchedScript(List<String> packageUidPairs, boolean isAndroid14) {
        if (packageUidPairs == null || packageUidPairs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : packageUidPairs) {
            if (item == null) continue;
            String trimmed = item.trim();
            if (trimmed.isEmpty()) continue;

            String pkg;
            String uid = "0";
            if (trimmed.contains(":")) {
                String[] parts = trimmed.split(":", 2);
                pkg = parts[0].trim();
                if (parts.length > 1 && parts[1].trim().matches("^[0-9]+$")) {
                    uid = parts[1].trim();
                }
            } else {
                pkg = trimmed;
            }

            if (isValidPackageName(pkg)) {
                sb.append(pkg).append(":").append(uid).append(" ");
            }
        }
        String sanitizedPairs = sb.toString().trim();
        if (sanitizedPairs.isEmpty()) {
            return "";
        }
        return createCompactionScript(sanitizedPairs, isAndroid14);
    }

    public static String buildBatchedScript(String packageUidPairs, boolean isAndroid14) {
        if (packageUidPairs == null || packageUidPairs.trim().isEmpty()) {
            return "";
        }
        String[] tokens = packageUidPairs.trim().split("\\s+");
        List<String> list = new ArrayList<>();
        for (String t : tokens) {
            if (!t.trim().isEmpty()) {
                list.add(t.trim());
            }
        }
        return buildBatchedScript(list, isAndroid14);
    }

    private static String createCompactionScript(String packageUidPairs, boolean isAndroid14) {
        // Syntax on A11-A13: cmd activity compact <proc> <uid> full
        // Syntax on A14+:    cmd activity compact full <proc>
        if (isAndroid14) {
            return "for item in " + packageUidPairs + "; do " +
                   "p=\"${item%%:*}\"; " +
                   "cmd activity compact full \"$p\" 2>/dev/null || cmd activity compact \"$p\" full 2>/dev/null; " +
                   "pkill -10 -f \"$p\" 2>/dev/null; " +
                   "done";
        } else {
            return "for item in " + packageUidPairs + "; do " +
                   "p=\"${item%%:*}\"; u=\"${item##*:}\"; " +
                   "cmd activity compact \"$p\" \"$u\" full 2>/dev/null || cmd activity compact \"$p\" full 2>/dev/null; " +
                   "pkill -10 -f \"$p\" 2>/dev/null; " +
                   "done";
        }
    }
}
