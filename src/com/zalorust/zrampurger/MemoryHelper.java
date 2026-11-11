package com.zalorust.zrampurger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.Locale;

public class MemoryHelper {

    public static class MemoryStats {
        public long memTotalKb = 0;
        public long memFreeKb = 0;
        public long memAvailableKb = 0;
        public long buffersKb = 0;
        public long cachedKb = 0;
        public long swapTotalKb = 0;
        public long swapFreeKb = 0;
        public long activeKb = 0;
        public long inactiveKb = 0;

        // OEM & Dynamic Virtual RAM Info
        public String oemBrand = "Universal Android";
        public String oemVersion = "";
        public String virtualRamTech = "AOSP Compactor";
        public int virtualRamMb = 0;
        public int ramPlusMb = 0;
        public boolean chimeraEnabled = false;
        public String chimeraStrategy = "";
        public int dhaCachedMax = 0;

        // Real-time Z-RAM Kernel Telemetry
        public double zramCompressionRatio = 1.0;
        public long zramPhysicalSavedMb = 0;
        public double zramSavingsPercent = 0.0;
        public long zramCompactedPages = 0;
        public long zramHugePages = 0;
        public boolean hasBackingDev = false;
        public String backingDev = "";

        // Qualcomm Adreno KGSL GPU Telemetry
        public String gpuModel = "";
        public int gpuBusyPercent = 0;
        public double gpuTempC = 0.0;
        public long gpuPageAllocMb = 0;

        public long getMemUsedKb() {
            return Math.max(0, memTotalKb - memAvailableKb);
        }

        public int getMemPercent() {
            if (memTotalKb <= 0) return 0;
            return (int) Math.min(100, Math.max(0, Math.round((getMemUsedKb() * 100.0) / memTotalKb)));
        }

        public long getSwapUsedKb() {
            return Math.max(0, swapTotalKb - swapFreeKb);
        }

        public int getSwapPercent() {
            if (swapTotalKb <= 0) return 0;
            return (int) Math.min(100, Math.max(0, Math.round((getSwapUsedKb() * 100.0) / swapTotalKb)));
        }

        public String toJson() {
            long usedKb = getMemUsedKb();
            long swapUsedKb = getSwapUsedKb();
            return String.format(
                Locale.US,
                "{\"totalMb\":%d,\"freeMb\":%d,\"availableMb\":%d,\"usedMb\":%d,\"cachedMb\":%d,\"buffersMb\":%d,\"percent\":%d," +
                "\"swapTotalMb\":%d,\"swapFreeMb\":%d,\"swapUsedMb\":%d,\"swapPercent\":%d," +
                "\"oemBrand\":\"%s\",\"oemVersion\":\"%s\",\"virtualRamTech\":\"%s\",\"virtualRamMb\":%d," +
                "\"ramPlusMb\":%d,\"chimeraEnabled\":%b,\"chimeraStrategy\":\"%s\",\"dhaCachedMax\":%d," +
                "\"zramRatio\":%.2f,\"zramSavedMb\":%d,\"zramSavingsPercent\":%.1f,\"zramCompactedPages\":%d,\"zramHugePages\":%d," +
                "\"hasBackingDev\":%b,\"backingDev\":\"%s\",\"gpuModel\":\"%s\",\"gpuBusy\":%d,\"gpuTemp\":%.1f,\"gpuAllocMb\":%d}",
                memTotalKb / 1024,
                memFreeKb / 1024,
                memAvailableKb / 1024,
                usedKb / 1024,
                cachedKb / 1024,
                buffersKb / 1024,
                getMemPercent(),
                swapTotalKb / 1024,
                swapFreeKb / 1024,
                swapUsedKb / 1024,
                getSwapPercent(),
                escape(oemBrand),
                escape(oemVersion),
                escape(virtualRamTech),
                virtualRamMb,
                ramPlusMb,
                chimeraEnabled,
                escape(chimeraStrategy),
                dhaCachedMax,
                zramCompressionRatio,
                zramPhysicalSavedMb,
                zramSavingsPercent,
                zramCompactedPages,
                zramHugePages,
                hasBackingDev,
                escape(backingDev),
                escape(gpuModel),
                gpuBusyPercent,
                gpuTempC,
                gpuPageAllocMb
            );
        }

        private String escape(String s) {
            return OemDetector.escapeJson(s);
        }
    }

    public static String getProp(String key, String def) {
        return OemDetector.getSystemProperty(key, def);
    }

    public static MemoryStats readMemInfo() {
        MemoryStats stats = new MemoryStats();

        // 1. Dynamic OEM Capability Integration
        OemDetector.OemInfo oem = OemDetector.getOemInfo();
        stats.oemBrand = oem.brandName;
        stats.oemVersion = oem.oemVersion;
        stats.virtualRamTech = oem.virtualRamTech;
        stats.virtualRamMb = oem.virtualRamMb;

        if (OemDetector.isSamsung()) {
            stats.ramPlusMb = oem.virtualRamMb > 0 ? oem.virtualRamMb : 4096;
            stats.chimeraEnabled = oem.chimeraSupported;
            stats.chimeraStrategy = getProp("ro.slmk.chimera_strategy_4gb", "870,11,9,1406");
            try {
                stats.dhaCachedMax = Integer.parseInt(getProp("ro.slmk.dha_cached_max", "14"));
            } catch (Throwable ignored) {}
        } else {
            stats.ramPlusMb = 0;
            stats.chimeraEnabled = false;
            stats.chimeraStrategy = "";
            stats.dhaCachedMax = 0;
        }

        // 2. Real-time Z-RAM & Qualcomm GPU Telemetry Integration
        ZramStatsHelper.ZramStats zram = ZramStatsHelper.readStats();
        stats.zramCompressionRatio = zram.compressionRatio;
        stats.zramPhysicalSavedMb = zram.physicalSavedMb;
        stats.zramSavingsPercent = zram.savingsPercent;
        stats.zramCompactedPages = zram.pagesCompacted;
        stats.zramHugePages = zram.hugePages;
        stats.hasBackingDev = zram.hasBackingDev;
        stats.backingDev = zram.backingDev;
        stats.gpuModel = zram.gpuModel;
        stats.gpuBusyPercent = zram.gpuBusyPercent;
        stats.gpuTempC = zram.gpuTempC;
        stats.gpuPageAllocMb = zram.gpuPageAllocMb;

        // 3. Proc/meminfo parsing
        File file = new File("/proc/meminfo");
        if (!file.exists() || !file.canRead()) {
            return stats;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts.length < 2) continue;
                String key = parts[0].trim();
                String valStr = parts[1].trim().split("\\s+")[0];
                long val = 0;
                try {
                    val = Long.parseLong(valStr);
                } catch (NumberFormatException ignored) {}

                switch (key) {
                    case "MemTotal": stats.memTotalKb = val; break;
                    case "MemFree": stats.memFreeKb = val; break;
                    case "MemAvailable": stats.memAvailableKb = val; break;
                    case "Buffers": stats.buffersKb = val; break;
                    case "Cached": stats.cachedKb = val; break;
                    case "SwapTotal": stats.swapTotalKb = val; break;
                    case "SwapFree": stats.swapFreeKb = val; break;
                    case "Active": stats.activeKb = val; break;
                    case "Inactive": stats.inactiveKb = val; break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (stats.memAvailableKb == 0 && stats.memFreeKb > 0) {
            stats.memAvailableKb = stats.memFreeKb + stats.cachedKb + stats.buffersKb;
        }

        return stats;
    }
}
