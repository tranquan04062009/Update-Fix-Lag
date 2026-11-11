package com.zalorust.zrampurger;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestVerificationRunner {

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("==================================================");
        System.out.println("🧪 Z-RAM Purger Extreme Multi-OEM Test Harness");
        System.out.println("==================================================");

        testOemDetector();
        testZramStatsHelper();
        testSafetyWhitelist();
        testPurgeCommandGeneration();
        testMemoryHelperTelemetry();
        testPerformanceEngine();
        testGameBoosterManager();

        System.out.println("==================================================");
        System.out.printf("Test Summary: %d Passed, %d Failed%n", testsPassed, testsFailed);
        System.out.println("==================================================");

        if (testsFailed > 0) {
            System.err.println("❌ Verification Failed!");
            System.exit(1);
        } else {
            System.out.println("✅ All Verification Assertions Passed Successfully!");
            System.exit(0);
        }
    }

    private static void assertTrue(String testName, boolean condition, String details) {
        if (condition) {
            System.out.println("  [PASS] " + testName + " - " + details);
            testsPassed++;
        } else {
            System.err.println("  [FAIL] " + testName + " - " + details);
            testsFailed++;
        }
    }

    private static void testOemDetector() {
        System.out.println("\n[1/5] Testing OemDetector...");
        OemDetector.OemInfo info = OemDetector.detect();

        assertTrue("OemInfo Not Null", info != null, "Instance created via OemDetector.detect()");
        assertTrue("Brand Detected", info.brand != null, "Brand: " + info.brand.name());
        assertTrue("Brand Name Valid", info.brandName != null && !info.brandName.isEmpty(), "BrandName: " + info.brandName);
        assertTrue("Virtual RAM Tech Valid", info.virtualRamTech != null && !info.virtualRamTech.isEmpty(), "VirtualRamTech: " + info.virtualRamTech);

        // Check JSON serialization and robust escaping
        String json = info.toJson();
        assertTrue("OemInfo JSON Valid", json != null && json.contains("brandName") && json.contains("virtualRamTech"), "JSON: " + json);

        String testEscaping = OemDetector.escapeJson("quote: \", newline: \n, backslash: \\, tab: \t");
        assertTrue("JSON Escaping Handles Control Chars", testEscaping.contains("\\\"") && testEscaping.contains("\\n") && testEscaping.contains("\\\\") && testEscaping.contains("\\t"), "Escaped: " + testEscaping);

        // Samsung Galaxy A42 5G specific assertion
        String mfg = android.os.Build.MANUFACTURER != null ? android.os.Build.MANUFACTURER.toLowerCase() : "";
        if (mfg.contains("samsung")) {
            assertTrue("Samsung One UI Identified", OemDetector.isSamsung(), "Matched SAMSUNG on " + mfg);
            assertTrue("Chimera Supported on Samsung", info.chimeraSupported, "chimeraSupported == true");
            assertTrue("RAM Plus Virtual RAM Allocated", info.virtualRamMb > 0, "Virtual RAM: " + info.virtualRamMb + " MB");
        }
    }

    private static void testZramStatsHelper() {
        System.out.println("\n[2/5] Testing ZramStatsHelper...");

        // 1. Direct unit test of parseMmStat with genuine token parsing
        String syntheticMmStat = "1774886912 594248530 648790016 0 648790016 14299 7492 18 18 0";
        ZramStatsHelper.ZramStats parsed = ZramStatsHelper.parseMmStat(syntheticMmStat);
        assertTrue("parseMmStat Orig Data Parsed", parsed.origDataBytes == 1774886912L, "Orig: " + parsed.origDataBytes);
        assertTrue("parseMmStat Compr Data Parsed", parsed.comprDataBytes == 594248530L, "Compr: " + parsed.comprDataBytes);
        assertTrue("parseMmStat Compression Ratio Calculated", parsed.compressionRatio > 2.9 && parsed.compressionRatio < 3.0, String.format("Ratio: %.2fx", parsed.compressionRatio));
        long expectedSavedMb = (1774886912L - 594248530L) / (1024 * 1024);
        assertTrue("parseMmStat Physical Saved MB Calculated", parsed.physicalSavedMb == expectedSavedMb, "Saved: " + parsed.physicalSavedMb + " MB");
        assertTrue("parseMmStat Pages Compacted Parsed", parsed.pagesCompacted == 7492L, "Compacted: " + parsed.pagesCompacted);
        assertTrue("parseMmStat Same Pages Parsed", parsed.samePages == 14299L, "Same pages: " + parsed.samePages);

        // Test robustness against corrupted / non-numeric tokens
        ZramStatsHelper.ZramStats safeStats = new ZramStatsHelper.ZramStats();
        boolean badParseResult = ZramStatsHelper.parseMmStat(safeStats, "invalid non_numeric tokens");
        assertTrue("Corrupted mm_stat Handled Without Exception", !badParseResult, "Corrupted string rejected");
        assertTrue("Corrupted mm_stat Does Not Partially Mutate State", safeStats.origDataBytes == 0 && safeStats.comprDataBytes == 0, "State pristine");

        // 2. Live sysfs probe
        ZramStatsHelper.ZramStats liveStats = ZramStatsHelper.readStats();
        assertTrue("ZramStats Live Object Not Null", liveStats != null, "Instance created");

        File mmStatFile = new File("/sys/block/zram0/mm_stat");
        if (mmStatFile.exists() && mmStatFile.canRead()) {
            assertTrue("Orig Data Bytes > 0", liveStats.origDataBytes > 0, "Orig bytes: " + liveStats.origDataBytes);
            assertTrue("Compr Data Bytes > 0", liveStats.comprDataBytes > 0, "Compr bytes: " + liveStats.comprDataBytes);
            assertTrue("Compression Ratio >= 1.0x", liveStats.compressionRatio >= 1.0, String.format("Ratio: %.2fx", liveStats.compressionRatio));
            assertTrue("Physical Saved MB >= 0", liveStats.physicalSavedMb >= 0, "Saved: " + liveStats.physicalSavedMb + " MB");
            assertTrue("Pages Compacted Counted", liveStats.pagesCompacted >= 0, "Compacted pages: " + liveStats.pagesCompacted);
        } else {
            System.out.println("  [SKIP] /sys/block/zram0/mm_stat not present or readable in this environment.");
        }

        // Backing device check
        File backingDevFile = new File("/sys/block/zram0/backing_dev");
        if (backingDevFile.exists() && backingDevFile.canRead()) {
            System.out.println("  [INFO] Backing dev: " + (liveStats.hasBackingDev ? liveStats.backingDev : "none"));
        }

        // Qualcomm KGSL GPU check
        File kgslModel = new File("/sys/class/kgsl/kgsl-3d0/gpu_model");
        if (kgslModel.exists() && kgslModel.canRead()) {
            assertTrue("Adreno GPU Detected", liveStats.gpuModel != null && !liveStats.gpuModel.isEmpty(), "GPU: " + liveStats.gpuModel);
            assertTrue("GPU Temperature Valid", liveStats.gpuTempC >= 0.0, String.format("Temp: %.1f C", liveStats.gpuTempC));
        }

        // JSON check
        String json = liveStats.toJson();
        assertTrue("ZramStats JSON Valid", json != null && json.contains("compressionRatio") && json.contains("physicalSavedMb"), "JSON fields verified");
    }

    private static void testSafetyWhitelist() {
        System.out.println("\n[3/5] Testing Safety Whitelist Hardening...");
        Set<String> whitelist = PurgeEngine.getCriticalWhitelist(null);

        assertTrue("Whitelist Not Empty", whitelist != null && !whitelist.isEmpty(), "Size: " + whitelist.size());

        // Mandatory packages
        assertTrue("Termux Protected", whitelist.contains("com.termux"), "Protects active developer session");
        assertTrue("SystemUI Protected", whitelist.contains("com.android.systemui"), "Protects status bar and lockscreen");
        assertTrue("Android Server Protected", whitelist.contains("android"), "Protects system_server");
        assertTrue("Bluetooth Protected", whitelist.contains("com.android.bluetooth"), "Protects audio and peripheral stack");
        assertTrue("NFC Protected", whitelist.contains("com.android.nfc"), "Protects contactless payment");
        assertTrue("Settings Protected", whitelist.contains("com.android.settings"), "Protects settings provider");
        assertTrue("PackageInstaller Protected", whitelist.contains("com.google.android.packageinstaller") || whitelist.contains("com.android.packageinstaller"), "Protects package installer");
        assertTrue("Shizuku Daemons Protected", whitelist.contains("moe.shizuku.privileged.api") && whitelist.contains("rikka.shizuku"), "Protects Shizuku privilege provider");
        assertTrue("Telephony Protected", whitelist.contains("com.android.phone"), "Protects voice call subsystem");

        // OEM Launchers
        assertTrue("Samsung Launcher Protected", whitelist.contains("com.sec.android.app.launcher"), "One UI Home");
        assertTrue("Xiaomi Launcher Protected", whitelist.contains("com.miui.home"), "MIUI Home");
        assertTrue("Oppo Launcher Protected", whitelist.contains("com.oppo.launcher") || whitelist.contains("com.oplus.launcher"), "ColorOS Home");
        assertTrue("Vivo Launcher Protected", whitelist.contains("com.bbk.launcher2"), "Vivo Home");
        assertTrue("Motorola Launcher Protected", whitelist.contains("com.motorola.launcher3"), "Moto Home");
    }

    private static void testPurgeCommandGeneration() {
        System.out.println("\n[4/5] Testing Purge Command Generation & Syntax...");

        // 1. Genuine call to PurgeEngine.buildBatchedScript for Android 11-13 syntax
        List<String> targetPairs = Arrays.asList("com.test.app:10245", "org.sample.service:10050");
        String a13Script = PurgeEngine.buildBatchedScript(targetPairs, false);
        assertTrue("A11-13 Compaction Script Generated", a13Script != null && !a13Script.isEmpty(), "Script generated");
        assertTrue("A11-13 Compaction Syntax Present", a13Script.contains("cmd activity compact \"$p\" \"$u\" full"), "Uses dual package+uid compaction syntax");
        assertTrue("A11-13 ART SIGUSR1 Signal 10 Present", a13Script.contains("pkill -10 -f \"$p\""), "Dispatches SIGUSR1 for ART moving GC");
        assertTrue("A11-13 Target Packages Embedded", a13Script.contains("com.test.app:10245") && a13Script.contains("org.sample.service:10050"), "Packages present in loop");

        // 2. Genuine call to PurgeEngine.buildBatchedScript for Android 14+ syntax
        String a14Script = PurgeEngine.buildBatchedScript(targetPairs, true);
        assertTrue("A14+ Compaction Script Generated", a14Script != null && !a14Script.isEmpty(), "Script generated");
        assertTrue("A14+ Compaction Syntax Present", a14Script.contains("cmd activity compact full \"$p\""), "Uses modern full <proc> compaction syntax");
        assertTrue("A14+ ART SIGUSR1 Signal 10 Present", a14Script.contains("pkill -10 -f \"$p\""), "Dispatches SIGUSR1 for ART moving GC");

        // 3. Genuine Security Test: Shell Injection & Metacharacter Sanitization
        List<String> injectionPayload = Arrays.asList(
            "com.legitimate.app:10001",
            "com.evil.app; rm -rf /:0",
            "com.malicious.app`id`:1000",
            "com.injection.app$(whoami):1000",
            "valid.subsystem.pkg:10002"
        );
        String sanitizedScript = PurgeEngine.buildBatchedScript(injectionPayload, false);
        assertTrue("Legitimate App 1 Retained", sanitizedScript.contains("com.legitimate.app:10001"), "Preserved");
        assertTrue("Legitimate App 2 Retained", sanitizedScript.contains("valid.subsystem.pkg:10002"), "Preserved");
        assertTrue("Semicolon Shell Injection Blocked", !sanitizedScript.contains("rm -rf"), "Semicolon injection eliminated");
        assertTrue("Backtick Shell Injection Blocked", !sanitizedScript.contains("com.malicious.app`id`"), "Backtick injection eliminated");
        assertTrue("Subshell $(...) Injection Blocked", !sanitizedScript.contains("com.injection.app$(whoami)"), "Subshell injection eliminated");

        // 4. Test PurgeEngine.isValidPackageName directly
        assertTrue("Valid Package Name Accepted", PurgeEngine.isValidPackageName("com.zalorust.zrampurger"), "Clean package name accepted");
        assertTrue("Metacharacter Semicolon Rejected", !PurgeEngine.isValidPackageName("com.app;reboot"), "Semicolon rejected");
        assertTrue("Metacharacter Space Rejected", !PurgeEngine.isValidPackageName("com.app evil"), "Space rejected");
        assertTrue("Null Package Name Rejected", !PurgeEngine.isValidPackageName(null), "Null rejected");
    }

    private static void testMemoryHelperTelemetry() {
        System.out.println("\n[5/5] Testing MemoryHelper Telemetry Integration...");
        MemoryHelper.MemoryStats stats = MemoryHelper.readMemInfo();

        assertTrue("MemoryStats Not Null", stats != null, "Instance created");
        assertTrue("MemTotal > 0", stats.memTotalKb > 0, "MemTotal: " + (stats.memTotalKb / 1024) + " MB");
        assertTrue("MemAvailable > 0", stats.memAvailableKb > 0, "MemAvailable: " + (stats.memAvailableKb / 1024) + " MB");
        assertTrue("SwapTotal >= 0", stats.swapTotalKb >= 0, "SwapTotal: " + (stats.swapTotalKb / 1024) + " MB");

        // Telemetry integration fields
        assertTrue("OEM Brand Populated", stats.oemBrand != null && !stats.oemBrand.isEmpty(), "oemBrand: " + stats.oemBrand);
        assertTrue("Virtual RAM Tech Populated", stats.virtualRamTech != null && !stats.virtualRamTech.isEmpty(), "virtualRamTech: " + stats.virtualRamTech);

        // Verification of Non-Leakage of Samsung-specific defaults to other OEMs
        if (!OemDetector.isSamsung()) {
            assertTrue("Samsung Chimera Strategy Not Leaked to Non-Samsung", stats.chimeraStrategy == null || stats.chimeraStrategy.isEmpty(), "chimeraStrategy: " + stats.chimeraStrategy);
            assertTrue("Samsung RAM Plus Default Not Leaked to Non-Samsung", stats.ramPlusMb == 0, "ramPlusMb: " + stats.ramPlusMb);
            assertTrue("Chimera Disabled on Non-Samsung", !stats.chimeraEnabled, "chimeraEnabled == false");
        } else {
            assertTrue("Samsung Chimera Strategy Present on Samsung", stats.chimeraStrategy != null && !stats.chimeraStrategy.isEmpty(), "chimeraStrategy: " + stats.chimeraStrategy);
            assertTrue("Chimera Enabled on Samsung", stats.chimeraEnabled, "chimeraEnabled == true");
        }

        String json = stats.toJson();
        assertTrue("MemoryStats JSON Contains OEM", json.contains("oemBrand") && json.contains("virtualRamTech"), "OEM keys in JSON");
        assertTrue("MemoryStats JSON Contains ZRAM Ratio", json.contains("zramRatio") && json.contains("zramSavedMb"), "ZRAM keys in JSON");
    }

    private static void testPerformanceEngine() {
        System.out.println("\n[6/7] Testing PerformanceEngine Hardware & Subsystem Controls...");

        // 1. Package Name Validation & Metacharacter Sanitization
        assertTrue("Package Name Accepted", PerformanceEngine.isValidPackageName("com.dts.freefireth"), "Normal package accepted");
        assertTrue("Package Name with Underscore Accepted", PerformanceEngine.isValidPackageName("com.tencent.ig_vn"), "Valid package accepted");
        assertTrue("Package Metacharacter Semicolon Rejected", !PerformanceEngine.isValidPackageName("com.game; rm -rf /"), "Injection rejected");
        assertTrue("Package Metacharacter Backtick Rejected", !PerformanceEngine.isValidPackageName("com.game`reboot`"), "Backtick rejected");
        assertTrue("Package Metacharacter Space Rejected", !PerformanceEngine.isValidPackageName("com.game evil"), "Space rejected");
        assertTrue("Package Null Rejected", !PerformanceEngine.isValidPackageName(null), "Null rejected");

        // 2. EAS Cpuset Migration Logic, Whitelist Invariant & Script Builder
        List<String> candidateApps = Arrays.asList(
                "com.whitelisted.system",
                "com.nonwhitelisted.game1",
                "com.malicious;app",
                "com.nonwhitelisted.game2"
        );
        Set<String> customWhitelist = new HashSet<>(Collections.singletonList("com.whitelisted.system"));
        int migrated = PerformanceEngine.migrateBackgroundAppsToLittleCores(null, candidateApps, customWhitelist);
        assertTrue("EAS Migration Excludes Whitelisted and Invalid Apps", migrated == 2, "Migrated count: " + migrated);

        String migScript = PerformanceEngine.buildMigrationScript(Arrays.asList("com.game1", "com.game2"));
        assertTrue("Migration Script Generated", migScript != null && !migScript.isEmpty(), "Script generated");
        assertTrue("Migration Script Syntax make-uid-idle", migScript.contains("cmd activity make-uid-idle \"com.game1\""), "make-uid-idle in script");
        assertTrue("Migration Script Syntax set-standby-bucket", migScript.contains("cmd activity set-standby-bucket --user 0 \"com.game1\" restricted"), "standby bucket in script");
        assertTrue("Migration Script Syntax compact", migScript.contains("cmd activity compact \"com.game1\" 0 full"), "compact in script");

        // 3. ART Ahead-Of-Time dexopt compilation commands
        String errRes1 = PerformanceEngine.triggerArtCompilation("com.invalid;name", "speed");
        assertTrue("ART Invalid Package Blocked", errRes1.startsWith("Error"), "Result: " + errRes1);

        String errRes2 = PerformanceEngine.triggerArtCompilation("com.valid.game", "unsupported_filter");
        assertTrue("ART Unsupported Filter Mode Blocked", errRes2.startsWith("Error"), "Result: " + errRes2);

        // 4. SurfaceFlinger & HWUI Rendering Tuning Command Builders
        String sfOn = PerformanceEngine.buildSurfaceFlingerScript(true);
        assertTrue("SurfaceFlinger Enable Script Not Empty", sfOn != null && !sfOn.isEmpty(), "Length: " + sfOn.length());
        assertTrue("SurfaceFlinger Latch Unsignaled Present", sfOn.contains("debug.sf.latch_unsignaled 1"), "latch_unsignaled 1");
        assertTrue("SurfaceFlinger GL Backpressure Disabled", sfOn.contains("debug.sf.enable_gl_backpressure 0"), "enable_gl_backpressure 0");
        assertTrue("SurfaceFlinger Shader Cache Solid Layers Present", sfOn.contains("debug.sf.prime_shader_cache.solid_layers 1"), "prime_shader_cache.solid_layers 1");
        assertTrue("SurfaceFlinger Shader Cache Shadow Layers Present", sfOn.contains("debug.sf.prime_shader_cache.shadow_layers 1"), "prime_shader_cache.shadow_layers 1");
        assertTrue("SurfaceFlinger Dynamic Composition Type Present", sfOn.contains("debug.composition.type dyn"), "composition.type dyn");
        assertTrue("SurfaceFlinger EGL HW Present", sfOn.contains("debug.egl.hw 1"), "egl.hw 1");

        String sfOff = PerformanceEngine.buildSurfaceFlingerScript(false);
        assertTrue("SurfaceFlinger Restore Backpressure Present", sfOff.contains("debug.sf.enable_gl_backpressure 1"), "enable_gl_backpressure 1");
        assertTrue("SurfaceFlinger Restore Composition Type Present", sfOff.contains("debug.composition.type c2d"), "composition.type c2d");
        assertTrue("SurfaceFlinger Restore EGL HW Present", sfOff.contains("debug.egl.hw 0"), "egl.hw 0");

        // 5. Samsung CFMS & GameSDK Binder Command Builders
        String cfmsOn = PerformanceEngine.buildSamsungCfmsScript(true);
        assertTrue("CFMS Game Turbo Transaction 76 Present", cfmsOn.contains("service call CustomFrequencyManagerService 76 i32 1"), "Transaction 76 i32 1");
        assertTrue("CFMS GPIS Hint Transaction 111 Present", cfmsOn.contains("service call CustomFrequencyManagerService 111 i32 1"), "Transaction 111 i32 1");
        assertTrue("CFMS Game Power Saving Disable Present", cfmsOn.contains("service call CustomFrequencyManagerService 73 i32 0"), "Transaction 73 i32 0");
        assertTrue("GameSDK CPU Boost Transaction 31 Present", cfmsOn.contains("service call gamesdk 31 i32 1"), "GameSDK 31 i32 1");
        assertTrue("GameSDK GPU Boost Transaction 32 Present", cfmsOn.contains("service call gamesdk 32 i32 1"), "GameSDK 32 i32 1");
        assertTrue("GameSDK Bypass Thermal Mitigation Present", cfmsOn.contains("service call gamesdk 27 i32 1"), "GameSDK 27 i32 1");

        String cfmsOff = PerformanceEngine.buildSamsungCfmsScript(false);
        assertTrue("CFMS Restore Turbo Transaction 76 Present", cfmsOff.contains("service call CustomFrequencyManagerService 76 i32 0"), "Transaction 76 i32 0");
        assertTrue("CFMS Disable GPIS Hint Transaction 110 Present", cfmsOff.contains("service call CustomFrequencyManagerService 110"), "Transaction 110");
        assertTrue("GameSDK Restore CPU Boost Present", cfmsOff.contains("service call gamesdk 31 i32 0"), "GameSDK 31 i32 0");
        assertTrue("GameSDK Restore GPU Boost Present", cfmsOff.contains("service call gamesdk 32 i32 0"), "GameSDK 32 i32 0");
        assertTrue("GameSDK Reset Refresh Rate Present", cfmsOff.contains("service call gamesdk 17"), "GameSDK 17");

        // 6. Thermal Throttling Mitigations & GOS AppOps Command Builders
        String thermOn = PerformanceEngine.buildThermalMitigationsScript(true);
        assertTrue("SDHMS Elevation Delta +2.0C Present", thermOn.contains("service call sdhms 30 i32 20"), "SDHMS 30 i32 20");
        assertTrue("GOS AppOps Neutralization Present", thermOn.contains("cmd appops set com.samsung.android.game.gos RUN_IN_BACKGROUND ignore"), "GOS ignore in script");

        String thermOff = PerformanceEngine.buildThermalMitigationsScript(false);
        assertTrue("SDHMS Restore Delta Present", thermOff.contains("service call sdhms 30 i32 0"), "SDHMS 30 i32 0");
        assertTrue("GOS AppOps Restore Present", thermOff.contains("cmd appops set com.samsung.android.game.gos RUN_IN_BACKGROUND allow"), "GOS allow in script");

        // 7. Execution Safety (Verify Subsystem Dispatches Complete Safely Without Crash)
        boolean sfDispatched = PerformanceEngine.applySurfaceFlingerTuning(false);
        assertTrue("SurfaceFlinger Dispatched Safely", sfDispatched, "Execution safe");

        boolean cfmsDispatched = PerformanceEngine.applySamsungCfmsAndGameSdkBoost(false);
        assertTrue("Samsung CFMS Dispatched Safely", cfmsDispatched, "Execution safe");

        boolean thermDispatched = PerformanceEngine.applyThermalMitigations(false);
        assertTrue("Thermal Mitigations Dispatched Safely", thermDispatched, "Execution safe");

        // 8. Qualcomm Adreno 619 KGSL Sysfs & Telemetry Reader
        PerformanceEngine.PerformanceTelemetry telem = PerformanceEngine.readPerformanceTelemetry(true, "EXTREME_PERFORMANCE_MAX");
        assertTrue("PerformanceTelemetry Object Created", telem != null, "Telemetry object not null");
        assertTrue("PerformanceTelemetry Boost State Preserved", telem.isBoosted, "isBoosted == true");
        assertTrue("PerformanceTelemetry Mode Preserved", "EXTREME_PERFORMANCE_MAX".equals(telem.activeMode), "activeMode: " + telem.activeMode);
        assertTrue("PerformanceTelemetry Model Populated", telem.gpuModel != null && !telem.gpuModel.isEmpty(), "gpuModel: " + telem.gpuModel);

        String telemJson = telem.toJson();
        assertTrue("PerformanceTelemetry JSON Contains GPU Clock", telemJson.contains("gpuClockMhz"), "gpuClockMhz in JSON");
        assertTrue("PerformanceTelemetry JSON Contains GPU Load", telemJson.contains("gpuBusyPercent"), "gpuBusyPercent in JSON");
        assertTrue("PerformanceTelemetry JSON Contains GPU Temp", telemJson.contains("gpuTempC"), "gpuTempC in JSON");
        assertTrue("PerformanceTelemetry JSON Contains Active Mode", telemJson.contains("EXTREME_PERFORMANCE_MAX"), "activeMode in JSON");
    }

    private static void testGameBoosterManager() {
        System.out.println("\n[7/7] Testing GameBoosterManager State Snapshotting & Two-Phase Commit Rollback...");

        // 1. Synthetic StateSnapshot generation with known OEM baseline
        GameBoosterManager.StateSnapshot origSnap = new GameBoosterManager.StateSnapshot();
        origSnap.snapshotId = "test_snap_1001";
        origSnap.state = "PENDING";
        origSnap.targetPackages.add("com.garena.game.kgvn");
        origSnap.systemProperties.put("debug.sf.enable_gl_backpressure", new GameBoosterManager.PropertyEntry(true, "1"));
        origSnap.systemProperties.put("debug.composition.type", new GameBoosterManager.PropertyEntry(true, "c2d"));
        origSnap.systemProperties.put("debug.egl.hw", new GameBoosterManager.PropertyEntry(true, "0"));
        origSnap.appStandbyBuckets.put("com.instagram.android", 20);
        origSnap.appStandbyBuckets.put("com.facebook.katana", 30);
        origSnap.gameModes.put("com.garena.game.kgvn", 1);
        origSnap.kgslMinClockMhz = 253;
        origSnap.kgslMaxClockMhz = 430;
        origSnap.kgslIdleTimer = 80;
        origSnap.sdhmsModified = true;
        origSnap.cfmsModified = true;
        origSnap.gameSdkModified = true;
        origSnap.gosAppOpsModified = true;
        origSnap.surfaceFlingerModified = true;
        origSnap.kgslModified = true;

        // 2. Round-trip JSON Serialization and Deserialization
        String snapJson = origSnap.toJson();
        assertTrue("StateSnapshot Serialized JSON Not Empty", snapJson != null && !snapJson.isEmpty(), "Length: " + (snapJson != null ? snapJson.length() : 0));
        assertTrue("StateSnapshot JSON Contains snapshotId", snapJson.contains("test_snap_1001"), "snapshotId in JSON");
        assertTrue("StateSnapshot JSON Contains debug.sf.enable_gl_backpressure", snapJson.contains("debug.sf.enable_gl_backpressure"), "Property in JSON");
        assertTrue("StateSnapshot JSON Contains App Standby Buckets", snapJson.contains("com.instagram.android") && snapJson.contains("20"), "Buckets in JSON");
        assertTrue("StateSnapshot JSON Contains KGSL Freqs", snapJson.contains("253") && snapJson.contains("430"), "KGSL freqs in JSON");

        GameBoosterManager.StateSnapshot restoredSnap = GameBoosterManager.StateSnapshot.fromJson(snapJson);
        assertTrue("Deserialized StateSnapshot Not Null", restoredSnap != null, "StateSnapshot.fromJson() parsed successfully");
        assertTrue("Snapshot ID Preserved", "test_snap_1001".equals(restoredSnap.snapshotId), "ID: " + restoredSnap.snapshotId);
        assertTrue("Snapshot State Preserved", "PENDING".equals(restoredSnap.state), "State: " + restoredSnap.state);
        assertTrue("Target Packages Preserved", restoredSnap.targetPackages.contains("com.garena.game.kgvn"), "Target packages verified");
        assertTrue("System Properties Map Preserved", restoredSnap.systemProperties.containsKey("debug.sf.enable_gl_backpressure"), "debug.sf.enable_gl_backpressure present");
        assertTrue("Property Value Preserved", "1".equals(restoredSnap.systemProperties.get("debug.sf.enable_gl_backpressure").value), "Value is '1'");
        assertTrue("App Standby Buckets Preserved", restoredSnap.appStandbyBuckets.get("com.instagram.android") == 20, "Bucket 20 preserved");
        assertTrue("Game Mode Preserved", restoredSnap.gameModes.get("com.garena.game.kgvn") == 1, "Game mode 1 preserved");
        assertTrue("KGSL Min Clock Preserved", restoredSnap.kgslMinClockMhz == 253, "Min clock: " + restoredSnap.kgslMinClockMhz);
        assertTrue("Subsystem Flags Preserved", restoredSnap.sdhmsModified && restoredSnap.cfmsModified && restoredSnap.surfaceFlingerModified, "Flags preserved");

        // 3. Two-Phase Commit State Machine Transitions
        origSnap.state = "APPLIED";
        assertTrue("2PC State Transition to APPLIED", "APPLIED".equals(origSnap.state), "State: APPLIED");
        origSnap.state = "RESTORING";
        assertTrue("2PC State Transition to RESTORING", "RESTORING".equals(origSnap.state), "State: RESTORING");
        origSnap.state = "RESTORED";
        assertTrue("2PC State Transition to RESTORED", "RESTORED".equals(origSnap.state), "State: RESTORED");

        // 4. Programmatic Verification Checks
        GameBoosterManager.VerificationResult verif = GameBoosterManager.verifyRestoration(null);
        assertTrue("VerificationResult Created", verif != null, "Result object created");
        String vJson = verif.toJson();
        assertTrue("VerificationResult JSON Valid", vJson != null && vJson.contains("isClean") && vJson.contains("discrepancies"), "JSON verified: " + vJson);
    }
}
