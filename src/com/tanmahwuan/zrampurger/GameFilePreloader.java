package com.tanmahwuan.zrampurger;

import android.util.Log;

/**
 * GameFilePreloader - Preloads APK and OBB files into Linux kernel page cache.
 * Eliminates match-loading stuttering and flash memory read latency during gaming.
 */
public final class GameFilePreloader {

    private static final String TAG = "QanHAdb:Preloader";

    private GameFilePreloader() {}

    /**
     * Preloads game binaries into RAM safely if available memory is sufficient.
     */
    public static boolean preloadGame(String packageName) {
        if (!AospAdbMaster.isValidPackageName(packageName)) return false;
        Log.i(TAG, "Preloading APK and OBB assets into Page Cache for: " + packageName);

        String cmd = "F=\"$(pm path " + packageName + " 2>/dev/null | cut -d: -f2) $(ls /sdcard/Android/obb/" + packageName + "/* 2>/dev/null)\"; " +
                     "S=$(du -ck $F 2>/dev/null | tail -1 | cut -f1); " +
                     "A=$(awk '/MemAvailable/{print $2}' /proc/meminfo 2>/dev/null); " +
                     "[ -n \"$S\" ] && [ -n \"$A\" ] && [ $((S*2)) -lt $A ] && [ $S -lt 1572864 ] && cat $F > /dev/null 2>&1; echo \"DONE\"";

        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0 && res.stdout != null && res.stdout.contains("DONE");
    }
}
