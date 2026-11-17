package com.tanmahwuan.zrampurger;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UniversalThermalEngine - Multi-zone hardware temperature monitor & adaptive governor.
 * Compatible with Qualcomm, MediaTek Dimensity, Google Tensor, Samsung Exynos, Unisoc.
 */
public final class UniversalThermalEngine {

    private static final String TAG = "QanHAdb:ThermalEngine";

    public static final int STATE_COOL = 0;
    public static final int STATE_WARM = 1;
    public static final int STATE_HOT = 2;

    public static final float THRESHOLD_COOL = 36.5f;
    public static final float THRESHOLD_HOT = 39.5f;

    public static class ThermalSnapshot {
        public float cpuTemp = 0.0f;
        public float gpuTemp = 0.0f;
        public float batteryTemp = 0.0f;
        public float skinTemp = 0.0f;
        public float socTemp = 0.0f;
        public int thermalState = STATE_COOL;
        public String stateDescription = "Mát mẻ (Tối đa hiệu năng)";
    }

    private static final Pattern P_TEMP_DUMPSYS = Pattern.compile(
            "Temperature\\{mValue=([-0-9.eENa]+),\\s*mType=(-?\\d+),\\s*mName=([^,}]*)(?:,\\s*mStatus=(-?\\d+))?"
    );

    private UniversalThermalEngine() {}

    /**
     * Captures real-time temperatures across all hardware zones using dumpsys thermalservice
     * with graceful sysfs and BatteryManager fallbacks.
     */
    public static ThermalSnapshot getThermalSnapshot(Context context) {
        ThermalSnapshot snap = new ThermalSnapshot();

        // 1. Get Battery Temp via BatteryManager (always available on 100% Android devices)
        if (context != null) {
            try {
                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent bIntent = context.registerReceiver(null, ifilter);
                if (bIntent != null) {
                    int raw = bIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
                    snap.batteryTemp = raw / 10.0f;
                }
            } catch (Throwable ignored) {}
        }

        // 2. Query dumpsys thermalservice
        CommandExecutor.CommandResult dump = CommandExecutor.execute("dumpsys thermalservice 2>/dev/null");
        if (dump.exitCode == 0 && dump.stdout != null && dump.stdout.contains("Temperature{")) {
            Matcher m = P_TEMP_DUMPSYS.matcher(dump.stdout);
            while (m.find()) {
                try {
                    float val = Float.parseFloat(m.group(1));
                    int type = Integer.parseInt(m.group(2));
                    String name = m.group(3).toLowerCase(Locale.ROOT);

                    if (val > 150.0f) val = val / 1000.0f; // handle millidegree
                    if (val <= 0.0f || val > 120.0f) continue;

                    switch (type) {
                        case 0: // CPU
                            if (snap.cpuTemp <= 0 || val > snap.cpuTemp) snap.cpuTemp = val;
                            break;
                        case 1: // GPU
                            if (snap.gpuTemp <= 0 || val > snap.gpuTemp) snap.gpuTemp = val;
                            break;
                        case 2: // Battery
                            if (snap.batteryTemp <= 0) snap.batteryTemp = val;
                            break;
                        case 3: // Skin
                            if (snap.skinTemp <= 0 || val > snap.skinTemp) snap.skinTemp = val;
                            break;
                        case 13: // SoC
                            if (snap.socTemp <= 0 || val > snap.socTemp) snap.socTemp = val;
                            break;
                        default:
                            if (name.contains("cpu") && (snap.cpuTemp <= 0 || val > snap.cpuTemp)) snap.cpuTemp = val;
                            else if (name.contains("gpu") && (snap.gpuTemp <= 0 || val > snap.gpuTemp)) snap.gpuTemp = val;
                            else if (name.contains("skin") && snap.skinTemp <= 0) snap.skinTemp = val;
                            break;
                    }
                } catch (Throwable ignored) {}
            }
        }

        // 3. Sysfs & HardwarePropertiesManager fallback for CPU & GPU
        if (snap.cpuTemp <= 0.0f) {
            double c = ThermalHelper.readCpuTemperature(context);
            if (c > 0) snap.cpuTemp = (float) c;
        }
        if (snap.gpuTemp <= 0.0f) {
            double g = ThermalHelper.readGpuTemperature();
            if (g > 0) snap.gpuTemp = (float) g;
        }
        if (snap.socTemp <= 0.0f) {
            double s = ThermalHelper.readSocTemperature();
            if (s > 0) snap.socTemp = (float) s;
        }
        if (snap.cpuTemp <= 0.0f || snap.gpuTemp <= 0.0f) {
            readSysfsSensors(snap);
        }

        // 4. Ensure reasonable defaults if sensors absent
        if (snap.cpuTemp <= 0.0f) snap.cpuTemp = (snap.batteryTemp > 0) ? (snap.batteryTemp + 3.5f) : 34.0f;
        if (snap.gpuTemp <= 0.0f) snap.gpuTemp = snap.cpuTemp;
        if (snap.skinTemp <= 0.0f) snap.skinTemp = (snap.batteryTemp > 0) ? (snap.batteryTemp - 1.5f) : 31.0f;
        if (snap.socTemp <= 0.0f) snap.socTemp = snap.cpuTemp;

        // 5. Evaluate state description
        float ref = Math.max(snap.batteryTemp, snap.skinTemp);
        if (ref < THRESHOLD_COOL) {
            snap.thermalState = STATE_COOL;
            snap.stateDescription = "Mát mẻ (Tối đa hiệu năng)";
        } else if (ref < THRESHOLD_HOT) {
            snap.thermalState = STATE_WARM;
            snap.stateDescription = "Ấm (Cân bằng ổn định)";
        } else {
            snap.thermalState = STATE_HOT;
            snap.stateDescription = "Nóng (Bảo vệ phần cứng)";
        }

        return snap;
    }

    private static void readSysfsSensors(ThermalSnapshot snap) {
        File dir = new File("/sys/class/thermal");
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] zones = dir.listFiles((d, name) -> name.startsWith("thermal_zone"));
        if (zones == null) return;

        for (File zone : zones) {
            try {
                String type = readFileFirstLine(new File(zone, "type")).toLowerCase(Locale.ROOT);
                String tempStr = readFileFirstLine(new File(zone, "temp"));
                if (tempStr.isEmpty()) continue;

                float temp = Float.parseFloat(tempStr);
                if (temp > 1000.0f) temp /= 1000.0f;
                if (temp <= 0.0f || temp > 120.0f) continue;

                if ((type.contains("cpu") || type.contains("tsens") || type.contains("mtktscpu") || type.contains("exynos-therm") || type.contains("soc")) && (snap.cpuTemp <= 0 || temp > snap.cpuTemp)) {
                    snap.cpuTemp = temp;
                } else if ((type.contains("gpu") || type.contains("kgsl") || type.contains("mali") || type.contains("mtktsgpu")) && (snap.gpuTemp <= 0 || temp > snap.gpuTemp)) {
                    snap.gpuTemp = temp;
                }
            } catch (Throwable ignored) {}
        }
    }

    private static String readFileFirstLine(File file) {
        if (!file.exists() || !file.canRead()) return "";
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return (line != null) ? line.trim() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Suppresses premature thermal throttling to keep consistent 120 FPS frame pacing.
     */
    public static boolean setThermalThrottlingSuppressed(boolean suppress) {
        String cmd = suppress
                ? "cmd thermalservice override-status 0 2>/dev/null || true"
                : "cmd thermalservice reset 2>/dev/null || true";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }
}
