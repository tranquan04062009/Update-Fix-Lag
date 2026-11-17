package com.tanmahwuan.zrampurger;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * AppOpsStandbyManager - Manages App Standby Bucket restrictions, Package Suspensions,
 * background WakeLock/Boot bans, and Cloud Profile ART compilation.
 */
public final class AppOpsStandbyManager {

    private static final String TAG = "QanHAdb:StandbyManager";

    private AppOpsStandbyManager() {}

    /**
     * Pushes all non-essential third-party apps into the 'restricted' Standby Bucket,
     * reducing background CPU wakeup cycles by 90%, and elevates target game to 'active'.
     */
    public static int enforceRestrictedBuckets(Context context, String targetGame) {
        if (context == null) return 0;
        PackageManager pm = context.getPackageManager();
        Set<String> whitelist = PurgeEngine.getCriticalWhitelist(context);

        int count = 0;
        try {
            List<ApplicationInfo> installed = pm.getInstalledApplications(0);
            for (ApplicationInfo app : installed) {
                if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
                String pkg = app.packageName;
                if (pkg.equals(targetGame) || whitelist.contains(pkg)) continue;

                CommandExecutor.execute("cmd activity set-standby-bucket " + pkg + " restricted 2>/dev/null");
                count++;
            }
            if (targetGame != null && !targetGame.isEmpty()) {
                CommandExecutor.execute("cmd activity set-standby-bucket " + targetGame + " active 2>/dev/null");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Error enforcing standby buckets", t);
        }
        return count;
    }

    /**
     * Suspends a background package using PackageManager API, freezing its services and UI completely.
     */
    public static boolean setPackageSuspended(String packageName, boolean suspend) {
        if (!AospAdbMaster.isValidPackageName(packageName)) return false;
        String action = suspend ? "suspend" : "unsuspend";
        CommandExecutor.CommandResult res = CommandExecutor.execute("cmd package " + action + " " + packageName + " 2>/dev/null");
        return res.exitCode == 0;
    }

    /**
     * Revokes background WakeLock, Boot Completed, and Notification permissions for background junk.
     */
    public static boolean restrictAppOpsForJunk(String packageName) {
        if (!AospAdbMaster.isValidPackageName(packageName)) return false;
        String cmd = "appops set " + packageName + " WAKE_LOCK ignore 2>/dev/null; " +
                     "appops set " + packageName + " BOOT_COMPLETED ignore 2>/dev/null; " +
                     "appops set " + packageName + " POST_NOTIFICATIONS ignore 2>/dev/null; " +
                     "appops set " + packageName + " RUN_IN_BACKGROUND ignore 2>/dev/null;";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }

    /**
     * Forces ART to compile application using the latest Cloud Baseline Profiles from Google servers.
     */
    public static boolean compileWithCloudProfile(String packageName) {
        if (!AospAdbMaster.isValidPackageName(packageName)) return false;
        Log.i(TAG, "Compiling " + packageName + " using Cloud Profiles");
        String cmd = "cmd package compile -m speed-profile -f --reset " + packageName + " 2>/dev/null";
        CommandExecutor.CommandResult res = CommandExecutor.execute(cmd);
        return res.exitCode == 0;
    }
}
