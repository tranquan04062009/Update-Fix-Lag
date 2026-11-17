package com.tanmahwuan.zrampurger;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * OemBloatwareFreezer - Freezes OEM background telemetry, analytics, and cloud sync bloatware
 * during gameplay, safely whitelisting all essential services.
 */
public final class OemBloatwareFreezer {

    private static final String TAG = "QanHAdb:BloatFreezer";
    private static final String PREF_FROZEN_BLOAT = "frozen_oem_bloat";

    public static final String[] OEM_BLOAT_CANDIDATES = {
            // Xiaomi / MIUI / HyperOS
            "com.miui.cloudservice", "com.miui.cloudbackup", "com.miui.weather2", "com.miui.msa.global",
            "com.miui.analytics", "com.miui.hybrid", "com.miui.yellowpage", "com.miui.personalassistant",
            "com.mi.globalminusscreen", "com.xiaomi.mipicks", "com.miui.miservice", "com.xiaomi.glgm",
            "com.miui.bugreport", "com.miui.videoplayer", "com.miui.player",

            // Samsung One UI
            "com.samsung.android.app.spage", "com.samsung.android.bixby.agent", "com.samsung.android.bixby.wakeup",
            "com.samsung.android.scloud", "com.samsung.android.smartsuggestions", "com.samsung.android.app.tips",
            "com.samsung.android.mobileservice", "com.samsung.android.app.routines", "com.sec.android.app.sbrowser",
            "com.samsung.android.voc",

            // Oppo / Realme / OnePlus (ColorOS)
            "com.heytap.cloud", "com.coloros.weather.service", "com.heytap.pictorial", "com.heytap.browser",
            "com.oplus.metis", "com.coloros.assistantscreen", "com.heytap.usercenter", "com.oneplus.membership",

            // Vivo / iQOO (Funtouch / OriginOS)
            "com.vivo.weather", "com.bbk.cloud", "com.vivo.assistant", "com.vivo.globalsearch", "com.vivo.browser",

            // Motorola, Huawei, Transsion
            "com.motorola.moto", "com.motorola.ccc.ota", "com.huawei.hicloud", "com.huawei.intelligent",
            "com.hihonor.cloudservice", "com.transsion.phonemaster", "com.transsion.hilauncher.desktopsearch"
    };

    public static final Set<String> SAFETY_WHITELIST = new HashSet<>(Arrays.asList(
            "moe.shizuku.privilege.api", "moe.shizuku.manager",
            "com.google.android.gms", "com.android.vending", "com.google.android.play.games",
            "com.android.systemui", "android",
            "com.google.android.inputmethod.latin", "com.touchtype.swiftkey", "com.samsung.android.honeyboard",
            "com.zing.zalo", "org.telegram.messenger", "com.whatsapp", "com.facebook.orca", "com.discord"
    ));

    private OemBloatwareFreezer() {}

    public static int freezeBrandBloat(Context context) {
        if (context == null) return 0;
        PackageManager pm = context.getPackageManager();
        List<String> targetPkgs = new ArrayList<>();

        for (String pkg : OEM_BLOAT_CANDIDATES) {
            if (SAFETY_WHITELIST.contains(pkg)) continue;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                if (info != null) targetPkgs.add(pkg);
            } catch (Throwable ignored) {}
        }

        if (targetPkgs.isEmpty()) return 0;

        SharedPreferences sp = context.getSharedPreferences("qanh_bloat_prefs", Context.MODE_PRIVATE);
        Set<String> frozen = new HashSet<>();

        int count = 0;
        for (String pkg : targetPkgs) {
            String cmd = "cmd app_hibernation set-state --user 0 " + pkg + " true 2>/dev/null || " +
                         "cmd appops set --user 0 " + pkg + " RUN_IN_BACKGROUND ignore 2>/dev/null; " +
                         "am force-stop " + pkg + " 2>/dev/null;";
            CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
            if (res.exitCode == 0) {
                frozen.add(pkg);
                count++;
            }
        }

        sp.edit().putStringSet(PREF_FROZEN_BLOAT, frozen).apply();
        Log.i(TAG, "Frozen " + count + " OEM background bloatware services");
        return count;
    }

    public static int unfreezeBrandBloat(Context context) {
        if (context == null) return 0;
        SharedPreferences sp = context.getSharedPreferences("qanh_bloat_prefs", Context.MODE_PRIVATE);
        Set<String> frozen = sp.getStringSet(PREF_FROZEN_BLOAT, null);
        if (frozen == null || frozen.isEmpty()) return 0;

        int restored = 0;
        for (String pkg : frozen) {
            String cmd = "cmd app_hibernation set-state --user 0 " + pkg + " false 2>/dev/null; " +
                         "cmd appops set --user 0 " + pkg + " RUN_IN_BACKGROUND allow 2>/dev/null;";
            CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
            if (res.exitCode == 0) restored++;
        }

        sp.edit().remove(PREF_FROZEN_BLOAT).apply();
        Log.i(TAG, "Unfrozen " + restored + " OEM background bloatware services");
        return restored;
    }
}
