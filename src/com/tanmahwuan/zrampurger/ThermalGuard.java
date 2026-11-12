package com.tanmahwuan.zrampurger;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

/**
 * Safety net for the performance booster: never start (or keep) boosting on a device that is already hot.
 * Uses the public thermal API plus the battery temperature, so it works on every brand.
 */
public class ThermalGuard {

    public static final double START_LIMIT_C = 42.0;  // refuse to start at/above this battery temperature
    public static final double STOP_LIMIT_C = 45.0;   // force-stop an active boost at/above this

    static boolean tooHot(double batteryC, int thermalStatus, double limitC, int statusLimit) {
        return (batteryC > 0 && batteryC >= limitC) || thermalStatus >= statusLimit;
    }

    private static int thermalStatus(Context ctx) {
        if (ctx == null || Build.VERSION.SDK_INT < 29) return 0;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm.getCurrentThermalStatus();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Returns a user-readable reason when boosting must NOT start, or null when it is safe. */
    public static String blockReason(Context ctx) {
        double t = ThermalHelper.readBatteryTemperature(ctx);
        int st = thermalStatus(ctx);
        if (tooHot(t, st, START_LIMIT_C, PowerManager.THERMAL_STATUS_MODERATE)) {
            return String.format(java.util.Locale.US,
                    "Máy đang nóng (%.1f°C). Hãy để máy nguội rồi bật lại để tránh quá nhiệt.", t);
        }
        return null;
    }

    /** True when an active boost must be rolled back immediately. */
    public static boolean mustStop(Context ctx) {
        return tooHot(ThermalHelper.readBatteryTemperature(ctx), thermalStatus(ctx),
                STOP_LIMIT_C, PowerManager.THERMAL_STATUS_SEVERE);
    }
}
