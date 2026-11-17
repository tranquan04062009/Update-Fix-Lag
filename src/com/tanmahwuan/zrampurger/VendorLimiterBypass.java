package com.tanmahwuan.zrampurger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * VendorLimiterBypass - High-performance engine to detect and neutralize OEM game limiters.
 * Supports Samsung GOS, Xiaomi Joyose, Oppo/Realme Cosa, Vivo GameCube, Transsion GameMode.
 */
public final class VendorLimiterBypass {

    private static final String TAG = "QanHAdb:VendorLimiter";
    private static final String PREF_DISABLED_LIMITERS = "bypassed_oem_limiters";

    public static final String XIAOMI_JOYOSE = "com.xiaomi.joyose";
    public static final String SAMSUNG_GOS = "com.samsung.android.game.gos";
    public static final String SAMSUNG_GAMETOOLS = "com.samsung.android.game.gametools";
    public static final String SAMSUNG_GAMEHOME = "com.samsung.android.game.gamehome";
    public static final String OPLUS_COSA = "com.oplus.cosa";
    public static final String OPLUS_GAMESPACE = "com.coloros.gamespaceui";
    public static final String VIVO_GAMECUBE = "com.vivo.gamecube";
    public static final String TRANSSION_GAMEMODE = "com.transsion.gamemode";
    public static final String HONOR_GAMEASSISTANT = "com.hihonor.gameassistant";
    public static final String HUAWEI_GAMEASSISTANT = "com.huawei.gameassistant";
    public static final String MOTO_GAMEMODE = "com.motorola.gamemode";

    public static final String[] ALL_LIMITERS = {
            XIAOMI_JOYOSE,
            SAMSUNG_GOS,
            OPLUS_COSA,
            VIVO_GAMECUBE,
            TRANSSION_GAMEMODE,
            HONOR_GAMEASSISTANT,
            HUAWEI_GAMEASSISTANT,
            MOTO_GAMEMODE
    };

    private VendorLimiterBypass() {}

    public static boolean isPackageInstalled(Context context, String pkg) {
        if (context == null || pkg == null) return false;
        try {
            context.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static List<String> detectInstalledLimiters(Context context) {
        List<String> list = new ArrayList<>();
        if (context == null) return list;
        for (String pkg : ALL_LIMITERS) {
            if (isPackageInstalled(context, pkg)) {
                list.add(pkg);
            }
        }
        return list;
    }

    public static int bypassAllLimiters(Context context) {
        if (context == null) return 0;
        List<String> detected = detectInstalledLimiters(context);
        if (detected.isEmpty()) return 0;

        SharedPreferences sp = context.getSharedPreferences("qanh_vendor_prefs", Context.MODE_PRIVATE);
        Set<String> alreadyDisabled = sp.getStringSet(PREF_DISABLED_LIMITERS, new HashSet<>());
        Set<String> newDisabled = new HashSet<>(alreadyDisabled);

        int count = 0;
        for (String pkg : detected) {
            Log.i(TAG, "Neutralizing OEM throttling limiter: " + pkg);
            CommandExecutor.CommandResult res = CommandExecutor.execute(
                    "pm disable-user --user 0 " + pkg + " 2>/dev/null || cmd package disable-user --user 0 " + pkg + " 2>/dev/null; " +
                    "am force-stop " + pkg + " 2>/dev/null;"
            );
            if (res.exitCode == 0) {
                newDisabled.add(pkg);
                count++;
            }
        }

        sp.edit().putStringSet(PREF_DISABLED_LIMITERS, newDisabled).apply();
        return count;
    }

    public static int restoreLimiters(Context context) {
        if (context == null) return 0;
        SharedPreferences sp = context.getSharedPreferences("qanh_vendor_prefs", Context.MODE_PRIVATE);
        Set<String> disabled = sp.getStringSet(PREF_DISABLED_LIMITERS, null);
        if (disabled == null || disabled.isEmpty()) return 0;

        int restored = 0;
        for (String pkg : disabled) {
            Log.i(TAG, "Restoring OEM limiter: " + pkg);
            CommandExecutor.CommandResult res = CommandExecutor.execute(
                    "pm enable " + pkg + " 2>/dev/null || cmd package enable " + pkg + " 2>/dev/null;"
            );
            if (res.exitCode == 0) restored++;
        }

        sp.edit().remove(PREF_DISABLED_LIMITERS).apply();
        return restored;
    }

    public static String getLimiterSummary(Context context) {
        List<String> detected = detectInstalledLimiters(context);
        if (detected.isEmpty()) return "Không phát hiện bộ giới hạn hãng";
        StringBuilder sb = new StringBuilder("Đã phát hiện: ");
        for (int i = 0; i < detected.size(); i++) {
            String p = detected.get(i);
            if (p.contains("joyose")) sb.append("Xiaomi Joyose");
            else if (p.contains("gos")) sb.append("Samsung GOS");
            else if (p.contains("cosa")) sb.append("Oppo Cosa");
            else if (p.contains("vivo")) sb.append("Vivo GameCube");
            else if (p.contains("transsion")) sb.append("Infinix/Tecno GameMode");
            else sb.append(p);
            if (i < detected.size() - 1) sb.append(", ");
        }
        return sb.toString();
    }
}
