package com.zalorust.zrampurger;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class WebAppInterface {

    private final MainActivity activity;
    private final SharedPreferences prefs;
    private volatile String cachedAppsJson = "[]";
    private volatile long lastAppScanTime = 0;

    public WebAppInterface(MainActivity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("zram_prefs", Context.MODE_PRIVATE);
        preloadAppsInBackground();
    }

    private void preloadAppsInBackground() {
        new Thread(() -> {
            try {
                loadInstalledAppsInternal();
            } catch (Throwable ignored) {}
        }).start();
    }

    private synchronized String loadInstalledAppsInternal() {
        if (!"[]".equals(cachedAppsJson) && (System.currentTimeMillis() - lastAppScanTime < 60000)) {
            return cachedAppsJson;
        }

        JSONArray arr = new JSONArray();
        PackageManager pm = activity.getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        Set<String> whitelist = getWhitelistSet();
        Set<String> systemProtected = PurgeEngine.getCriticalWhitelist(activity);

        for (ApplicationInfo app : apps) {
            boolean isSystem = (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (isSystem && !app.packageName.contains("google") && !app.packageName.contains("samsung") && !app.packageName.contains("xiaomi")) {
                continue;
            }

            try {
                JSONObject obj = new JSONObject();
                CharSequence label = pm.getApplicationLabel(app);
                obj.put("name", label != null ? label.toString() : app.packageName);
                obj.put("package", app.packageName);
                obj.put("uid", app.uid);
                obj.put("isSystem", isSystem);
                obj.put("isProtected", systemProtected.contains(app.packageName));
                obj.put("isWhitelisted", whitelist.contains(app.packageName));
                arr.put(obj);
            } catch (Exception ignored) {}
        }

        cachedAppsJson = arr.toString();
        lastAppScanTime = System.currentTimeMillis();
        return cachedAppsJson;
    }

    @JavascriptInterface
    public String getInstalledApps() {
        if ("[]".equals(cachedAppsJson)) {
            return loadInstalledAppsInternal();
        }
        return cachedAppsJson;
    }

    @JavascriptInterface
    public String getMemoryInfo() {
        return MemoryHelper.readMemInfo().toJson();
    }

    @JavascriptInterface
    public String getOemInfo() {
        return OemDetector.getOemInfo().toJson();
    }

    @JavascriptInterface
    public String getZramStats() {
        return ZramStatsHelper.readStats().toJson();
    }

    @JavascriptInterface
    public String getPrivilegeStatus() {
        boolean shizukuAvail = CommandExecutor.isShizukuAvailable();
        boolean shizukuPerm = CommandExecutor.hasShizukuPermission();
        boolean rootAvail = CommandExecutor.isRootAvailable();
        String provider = CommandExecutor.getPrivilegeLevel();
        int uid = -1;
        try {
            if (shizukuPerm) {
                uid = Shizuku.getUid();
            }
        } catch (Throwable ignored) {}

        return String.format(
            "{\"provider\":\"%s\",\"shizukuAvailable\":%b,\"shizukuPermission\":%b,\"rootAvailable\":%b,\"uid\":%d}",
            provider, shizukuAvail, shizukuPerm, rootAvail, uid
        );
    }

    @JavascriptInterface
    public void requestShizukuPermission() {
        activity.runOnUiThread(activity::requestShizukuPermission);
    }

    @JavascriptInterface
    public String executePurge(int tier, String userWhitelistJson) {
        Set<String> whitelist = new HashSet<>();
        if (userWhitelistJson != null && !userWhitelistJson.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(userWhitelistJson);
                for (int i = 0; i < arr.length(); i++) {
                    whitelist.add(arr.getString(i));
                }
            } catch (Exception ignored) {}
        }

        PurgeEngine.PurgeResult result = PurgeEngine.executePurge(activity, tier, whitelist);
        updateWidget();
        return result.toJson();
    }

    @JavascriptInterface
    public String triggerChimeraReclaim() {
        OemDetector.OemInfo oem = OemDetector.getOemInfo();
        return executeOemReclaim(oem.brand);
    }

    @JavascriptInterface
    public String triggerXiaomiReclaim() {
        return executeOemReclaim(OemDetector.OemBrand.XIAOMI);
    }

    @JavascriptInterface
    public String triggerColorOsReclaim() {
        return executeOemReclaim(OemDetector.OemBrand.OPPO_ONEPLUS);
    }

    @JavascriptInterface
    public String triggerOriginOsReclaim() {
        return executeOemReclaim(OemDetector.OemBrand.VIVO);
    }

    @JavascriptInterface
    public String triggerOemReclaim() {
        OemDetector.OemInfo oem = OemDetector.getOemInfo();
        return executeOemReclaim(oem.brand);
    }

    private String executeOemReclaim(OemDetector.OemBrand targetBrand) {
        long start = System.currentTimeMillis();
        CommandExecutor.execute("cmd activity memory-factor set CRITICAL 2>/dev/null");
        CommandExecutor.execute("cmd activity compact system 2>/dev/null");
        CommandExecutor.execute("cmd activity compact-idle 2>/dev/null");

        // Dynamic OEM hooks
        if (targetBrand == OemDetector.OemBrand.SAMSUNG) {
            CommandExecutor.execute("am broadcast -a com.samsung.KPM_CRITICAL_MEMORY_STATUS 2>/dev/null");
            CommandExecutor.execute("am broadcast -a ACTION_BOTTLENECK_HINT_FOR_CHIMERA 2>/dev/null");
        } else if (targetBrand == OemDetector.OemBrand.XIAOMI) {
            CommandExecutor.execute("cmd miui.process kill-all 2>/dev/null");
            CommandExecutor.execute("am broadcast -a miui.intent.action.MEMORY_TRIM 2>/dev/null");
            CommandExecutor.execute("echo 1 > /sys/kernel/mi_reclaim/event 2>/dev/null");
        } else if (targetBrand == OemDetector.OemBrand.OPPO_ONEPLUS) {
            CommandExecutor.execute("cmd oplus.athena compact 2>/dev/null");
            CommandExecutor.execute("cmd oplus.athena kill-cached 2>/dev/null");
            CommandExecutor.execute("echo 1 > /proc/oplus_mem/reclaim 2>/dev/null");
        } else if (targetBrand == OemDetector.OemBrand.VIVO) {
            CommandExecutor.execute("cmd vivo.process kill-bg 2>/dev/null");
            CommandExecutor.execute("cmd vivo.process trim-all 2>/dev/null");
        } else if (targetBrand == OemDetector.OemBrand.MOTOROLA) {
            CommandExecutor.execute("dumpsys moto_process 2>/dev/null");
        }

        // In-memory Z-RAM compaction and writeback
        CommandExecutor.execute("echo all > /sys/block/zram0/compact 2>/dev/null || echo 1 > /sys/block/zram0/compact 2>/dev/null");
        CommandExecutor.execute("echo all > /sys/block/zram0/idle 2>/dev/null || echo 1 > /sys/block/zram0/idle 2>/dev/null");
        CommandExecutor.execute("echo idle > /sys/block/zram0/writeback 2>/dev/null");
        CommandExecutor.execute("echo huge > /sys/block/zram0/writeback 2>/dev/null");

        try {
            Thread.sleep(1200);
        } catch (InterruptedException ignored) {}

        CommandExecutor.execute("cmd activity memory-factor reset 2>/dev/null");
        long duration = System.currentTimeMillis() - start;
        updateWidget();
        OemDetector.OemInfo oem = OemDetector.getOemInfo();
        return String.format(java.util.Locale.US, "{\"status\":\"ok\",\"durationMs\":%d,\"brand\":\"%s\"}", duration, OemDetector.escapeJson(oem.brandName));
    }

    @JavascriptInterface
    public String executeCommand(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return "{\"exitCode\":-1,\"stdout\":\"\",\"stderr\":\"Empty command\",\"durationMs\":0,\"provider\":\"none\"}";
        }
        return CommandExecutor.execute(cmd.trim()).toJson();
    }

    @JavascriptInterface
    public void saveWhitelist(String jsonArrayStr) {
        prefs.edit().putString("custom_whitelist", jsonArrayStr).apply();
    }

    @JavascriptInterface
    public String getWhitelist() {
        return prefs.getString("custom_whitelist", "[]");
    }

    private Set<String> getWhitelistSet() {
        Set<String> set = new HashSet<>();
        try {
            String json = prefs.getString("custom_whitelist", "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                set.add(arr.getString(i));
            }
        } catch (Exception ignored) {}
        return set;
    }

    @JavascriptInterface
    public void vibrate(int ms) {
        try {
            Vibrator v = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(ms);
                }
            }
        } catch (Throwable ignored) {}
    }

    @JavascriptInterface
    public void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        );
    }

    @JavascriptInterface
    public void updateWidget() {
        activity.runOnUiThread(() -> {
            try {
                AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(activity);
                ComponentName componentName = new ComponentName(activity, RamWidgetProvider.class);
                int[] appWidgetIds = appWidgetManager.getAppWidgetIds(componentName);
                for (int appWidgetId : appWidgetIds) {
                    RamWidgetProvider.updateAppWidget(activity, appWidgetManager, appWidgetId);
                }
            } catch (Throwable ignored) {}
        });
    }

    // --- Extreme Performance Max & Game Booster Subsystem ---

    @JavascriptInterface
    public boolean toggleGameBooster(boolean enable, String targetPackage) {
        if (enable) {
            boolean success = GameBoosterManager.applyPerformanceBoost(activity, targetPackage);
            updateWidget();
            return success;
        } else {
            boolean success = GameBoosterManager.restoreOemState(activity);
            updateWidget();
            return success;
        }
    }

    @JavascriptInterface
    public boolean isGameBoosterActive() {
        return GameBoosterManager.isBoosterActive(activity);
    }

    @JavascriptInterface
    public String getPerformanceStatusJson() {
        boolean active = GameBoosterManager.isBoosterActive(activity);
        String mode = active ? "EXTREME_PERFORMANCE_MAX" : "OEM_BALANCED";
        PerformanceEngine.PerformanceTelemetry telemetry = PerformanceEngine.readPerformanceTelemetry(active, mode);
        return telemetry.toJson();
    }

    @JavascriptInterface
    public String triggerArtOptimization(String packageName, String mode) {
        return PerformanceEngine.triggerArtCompilation(packageName, mode);
    }

    @JavascriptInterface
    public boolean emergencyRestoreDefaults() {
        boolean success = GameBoosterManager.emergencyReset(activity);
        updateWidget();
        return success;
    }

    @JavascriptInterface
    public String verifyBoosterRestoration() {
        return GameBoosterManager.verifyRestoration(activity).toJson();
    }

    @JavascriptInterface
    public void checkForUpdates() {
        UpdateManager.checkForUpdates(activity, new UpdateManager.UpdateCheckCallback() {
            @Override
            public void onUpdateFound(String updateJson) {
                activity.runOnUiThread(() -> {
                    String escaped = updateJson.replace("\\", "\\\\").replace("'", "\\'");
                    if (activity.getWebView() != null) {
                        activity.getWebView().evaluateJavascript("if (window.onUpdateAvailable) { window.onUpdateAvailable('" + escaped + "'); }", null);
                    }
                });
            }

            @Override
            public void onNoUpdate(String currentVersion) {
                activity.runOnUiThread(() -> {
                    if (activity.getWebView() != null) {
                        activity.getWebView().evaluateJavascript("if (window.onNoUpdate) { window.onNoUpdate('" + currentVersion + "'); }", null);
                    }
                });
            }

            @Override
            public void onError(String message) {
                activity.runOnUiThread(() -> {
                    String escaped = message.replace("'", "\\'");
                    if (activity.getWebView() != null) {
                        activity.getWebView().evaluateJavascript("if (window.onUpdateError) { window.onUpdateError('" + escaped + "'); }", null);
                    }
                });
            }
        });
    }

    @JavascriptInterface
    public void startUpdateDownload(String downloadUrl, String versionName) {
        activity.runOnUiThread(() -> {
            UpdateManager.startDownloadAndInstall(activity, downloadUrl, versionName);
        });
    }

    @JavascriptInterface
    public String getAppVersionInfo() {
        int code = UpdateManager.getCurrentVersionCode(activity);
        String name = UpdateManager.getCurrentVersionName(activity);
        return String.format("{\"versionCode\":%d,\"versionName\":\"%s\"}", code, name);
    }
}
