package com.zalorust.zrampurger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;

public class ZramStatsHelper {

    public static class ZramStats {
        public long diskSizeMb = 0;
        public long origDataBytes = 0;
        public long comprDataBytes = 0;
        public long memUsedTotalBytes = 0;
        public long memLimitBytes = 0;
        public long memUsedMaxBytes = 0;
        public long samePages = 0;
        public long pagesCompacted = 0;
        public long hugePages = 0;
        public long hugePagesSince = 0;
        public long idlePages = 0;

        // Derived ZRAM metrics
        public double compressionRatio = 1.0;
        public long physicalSavedMb = 0;
        public double savingsPercent = 0.0;

        // Flash Backing Device (RAM Plus / swap)
        public String backingDev = "";
        public boolean hasBackingDev = false;
        public long bdReads = 0;
        public long bdWrites = 0;

        // Qualcomm Adreno KGSL GPU Telemetry
        public String gpuModel = "";
        public int gpuBusyPercent = 0;
        public double gpuTempC = 0.0;
        public long gpuPageAllocMb = 0;
        public long gpuMappedMb = 0;

        public String toJson() {
            return String.format(
                Locale.US,
                "{\"diskSizeMb\":%d,\"origDataMb\":%d,\"comprDataMb\":%d,\"memUsedTotalMb\":%d," +
                "\"compressionRatio\":%.2f,\"physicalSavedMb\":%d,\"savingsPercent\":%.1f," +
                "\"samePages\":%d,\"pagesCompacted\":%d,\"hugePages\":%d,\"idlePages\":%d," +
                "\"backingDev\":\"%s\",\"hasBackingDev\":%b,\"bdReads\":%d,\"bdWrites\":%d," +
                "\"gpuModel\":\"%s\",\"gpuBusyPercent\":%d,\"gpuTempC\":%.1f,\"gpuPageAllocMb\":%d,\"gpuMappedMb\":%d}",
                diskSizeMb,
                origDataBytes / (1024 * 1024),
                comprDataBytes / (1024 * 1024),
                memUsedTotalBytes / (1024 * 1024),
                compressionRatio,
                physicalSavedMb,
                savingsPercent,
                samePages,
                pagesCompacted,
                hugePages,
                idlePages,
                escapeJson(backingDev),
                hasBackingDev,
                bdReads,
                bdWrites,
                escapeJson(gpuModel),
                gpuBusyPercent,
                gpuTempC,
                gpuPageAllocMb,
                gpuMappedMb
            );
        }

        private String escapeJson(String s) {
            return OemDetector.escapeJson(s);
        }
    }

    public static boolean parseMmStat(ZramStats stats, String mmStatStr) {
        if (stats == null || mmStatStr == null || mmStatStr.trim().isEmpty()) {
            return false;
        }
        String[] tokens = mmStatStr.trim().split("\\s+");
        if (tokens.length < 2) {
            return false;
        }
        try {
            long orig = Long.parseLong(tokens[0]);
            long compr = Long.parseLong(tokens[1]);
            long memUsed = tokens.length > 2 ? Long.parseLong(tokens[2]) : 0;
            long memLimit = tokens.length > 3 ? Long.parseLong(tokens[3]) : 0;
            long memMax = tokens.length > 4 ? Long.parseLong(tokens[4]) : 0;
            long same = tokens.length > 5 ? Long.parseLong(tokens[5]) : 0;
            long compacted = tokens.length > 6 ? Long.parseLong(tokens[6]) : 0;
            long huge = tokens.length > 7 ? Long.parseLong(tokens[7]) : 0;
            long hugeSince = tokens.length > 8 ? Long.parseLong(tokens[8]) : 0;
            long idle = tokens.length > 9 ? Long.parseLong(tokens[9]) : 0;

            // Commit all fields atomically
            stats.origDataBytes = orig;
            stats.comprDataBytes = compr;
            stats.memUsedTotalBytes = memUsed;
            stats.memLimitBytes = memLimit;
            stats.memUsedMaxBytes = memMax;
            stats.samePages = same;
            stats.pagesCompacted = compacted;
            stats.hugePages = huge;
            stats.hugePagesSince = hugeSince;
            stats.idlePages = idle;

            if (compr > 0) {
                stats.compressionRatio = (double) orig / compr;
            } else if (orig > 0) {
                stats.compressionRatio = 1.0;
            } else {
                stats.compressionRatio = 1.0;
            }

            if (orig >= compr) {
                stats.physicalSavedMb = (orig - compr) / (1024 * 1024);
            } else {
                stats.physicalSavedMb = 0;
            }

            if (orig > 0) {
                stats.savingsPercent = (1.0 - ((double) compr / orig)) * 100.0;
            } else {
                stats.savingsPercent = 0.0;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static ZramStats parseMmStat(String mmStatStr) {
        ZramStats stats = new ZramStats();
        parseMmStat(stats, mmStatStr);
        return stats;
    }

    public static ZramStats readStats() {
        ZramStats stats = new ZramStats();

        // 1. Read /sys/block/zram0/disksize
        String diskSizeStr = readLineFirst("/sys/block/zram0/disksize");
        if (diskSizeStr != null && !diskSizeStr.isEmpty()) {
            try {
                stats.diskSizeMb = Long.parseLong(diskSizeStr) / (1024 * 1024);
            } catch (Throwable ignored) {}
        }

        // 2. Read /sys/block/zram0/mm_stat
        String mmStatStr = readLineFirst("/sys/block/zram0/mm_stat");
        if (mmStatStr != null && !mmStatStr.isEmpty()) {
            parseMmStat(stats, mmStatStr);
        }

        // 3. Read /sys/block/zram0/backing_dev
        String bd = readLineFirst("/sys/block/zram0/backing_dev");
        if (bd != null) {
            String trimmedBd = bd.trim();
            if (!trimmedBd.isEmpty() && !trimmedBd.equalsIgnoreCase("none")) {
                stats.backingDev = trimmedBd;
                stats.hasBackingDev = true;
            }
        }

        // 4. Read /sys/block/zram0/bd_stat
        String bdStat = readLineFirst("/sys/block/zram0/bd_stat");
        if (bdStat != null && !bdStat.isEmpty()) {
            String[] bdTokens = bdStat.trim().split("\\s+");
            if (bdTokens.length >= 2) {
                try {
                    stats.bdReads = Long.parseLong(bdTokens[0]);
                    stats.bdWrites = Long.parseLong(bdTokens[1]);
                } catch (Throwable ignored) {}
            }
        }

        // 5. Read Qualcomm Adreno KGSL GPU Telemetry
        readQualcommGpuStats(stats);

        return stats;
    }

    private static void readQualcommGpuStats(ZramStats stats) {
        // GPU Model
        String model = readLineFirst("/sys/class/kgsl/kgsl-3d0/gpu_model");
        if (model != null && !model.isEmpty()) {
            stats.gpuModel = model.trim();
        }

        // GPU Busy Percentage
        String busyStr = readLineFirst("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
        if (busyStr != null && !busyStr.isEmpty()) {
            try {
                String clean = busyStr.replace("%", "").trim();
                stats.gpuBusyPercent = Integer.parseInt(clean);
            } catch (Throwable ignored) {}
        } else {
            // Fallback to /sys/class/kgsl/kgsl-3d0/gpubusy (busy_ticks total_ticks)
            String rawBusy = readLineFirst("/sys/class/kgsl/kgsl-3d0/gpubusy");
            if (rawBusy != null && !rawBusy.isEmpty()) {
                String[] parts = rawBusy.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        long busyTicks = Long.parseLong(parts[0]);
                        long totalTicks = Long.parseLong(parts[1]);
                        if (totalTicks > 0) {
                            stats.gpuBusyPercent = (int) Math.min(100, Math.max(0, (busyTicks * 100) / totalTicks));
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }

        // GPU Temperature (millidegrees Celsius)
        String tempStr = readLineFirst("/sys/class/kgsl/kgsl-3d0/temp");
        if (tempStr != null && !tempStr.isEmpty()) {
            try {
                long tempMilli = Long.parseLong(tempStr.trim());
                stats.gpuTempC = tempMilli / 1000.0;
            } catch (Throwable ignored) {}
        }

        // KGSL Allocated Page Memory
        String pageAlloc = readLineFirst("/sys/class/kgsl/kgsl/page_alloc");
        if (pageAlloc != null && !pageAlloc.isEmpty()) {
            try {
                stats.gpuPageAllocMb = Long.parseLong(pageAlloc.trim()) / (1024 * 1024);
            } catch (Throwable ignored) {}
        }

        // KGSL Mapped Memory
        String mapped = readLineFirst("/sys/class/kgsl/kgsl/mapped");
        if (mapped != null && !mapped.isEmpty()) {
            try {
                stats.gpuMappedMb = Long.parseLong(mapped.trim()) / (1024 * 1024);
            } catch (Throwable ignored) {}
        }
    }

    private static String readLineFirst(String path) {
        File file = new File(path);
        if (!file.exists() || !file.canRead()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
