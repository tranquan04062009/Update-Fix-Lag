package com.tanmahwuan.zrampurger;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * PowerHalDozeGovernor - Controls PowerHAL execution modes, complete Doze exemption for games,
 * adaptive power saver suppression, and hardware bypass charging (direct board power).
 */
public final class PowerHalDozeGovernor {

    private static final String TAG = "QanHAdb:PowerGovernor";

    private static final String[] BYPASS_CHARGING_NODES = {
            "/sys/class/power_supply/battery/charging_enabled",
            "/sys/class/power_supply/battery/bypass_enable",
            "/sys/class/power_supply/battery/input_suspend",
            "/sys/class/power_supply/usb/current_max"
    };

    private PowerHalDozeGovernor() {}

    /**
     * Locks PowerHAL in fixed maximum performance state and triggers interactive boost hint.
     */
    public static boolean setFixedPerformanceMode(boolean enable) {
        Log.i(TAG, "Setting PowerHAL Fixed Performance: " + enable);
        String cmd = enable
                ? "cmd power set-fixed-performance-mode-enabled true 2>/dev/null; " +
                  "cmd power set-power-boost 0 0 2>/dev/null; " +
                  "cmd power set-mode 0 2>/dev/null; " +
                  "cmd power set-adaptive-power-saver-enabled false 2>/dev/null;"
                : "cmd power set-fixed-performance-mode-enabled false 2>/dev/null; " +
                  "cmd power set-adaptive-power-saver-enabled true 2>/dev/null;";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Completely exempts target game from Doze Mode and network restrictions.
     */
    public static boolean exemptGameFromDoze(String packageName, boolean exempt) {
        if (!AospAdbMaster.isValidPackageName(packageName)) return false;
        String op = exempt ? "+" : "-";
        String cmd = "dumpsys deviceidle whitelist " + op + packageName + " 2>/dev/null; " +
                     "cmd deviceidle except-idle-whitelist " + op + packageName + " 2>/dev/null;";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Temporarily disables Adaptive Battery management to prevent aggressive CPU throttling.
     */
    public static boolean setAdaptiveBatteryDisabled(boolean disable) {
        String val = disable ? "0" : "1";
        String cmd = "settings put global adaptive_battery_management_enabled " + val + " 2>/dev/null; " +
                     "settings put global app_standby_enabled " + val + " 2>/dev/null;";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Checks if device supports Smart Bypass Charging via hardware sysfs node.
     */
    public static boolean isBypassChargingSupported() {
        for (String node : BYPASS_CHARGING_NODES) {
            File f = new File(node);
            if (f.exists()) return true;
        }
        return false;
    }

    /**
     * Toggles hardware bypass charging (powers phone directly from cable without charging battery).
     */
    public static boolean setBypassCharging(boolean enable) {
        Log.i(TAG, "Setting hardware bypass charging: " + enable);
        String val = enable ? "0" : "1";
        StringBuilder sb = new StringBuilder();
        for (String node : BYPASS_CHARGING_NODES) {
            sb.append("echo ").append(val).append(" > ").append(node).append(" 2>/dev/null; ");
        }
        CommandExecutor.CommandResult res = CommandExecutor.execute(sb.toString());
        return res.exitCode == 0;
    }
}
