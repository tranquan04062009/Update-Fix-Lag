package com.zalorust.zrampurger;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateManager {

    public static final String UPDATE_MANIFEST_URL = 
        "https://raw.githubusercontent.com/tranquan04062009/Update-Fix-Lag/master/version.json";

    public interface UpdateCheckCallback {
        void onUpdateFound(String updateJson);
        void onNoUpdate(String currentVersion);
        void onError(String message);
    }

    public static void checkForUpdates(Context context, UpdateCheckCallback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                int currentCode = getCurrentVersionCode(context);
                String currentName = getCurrentVersionName(context);

                URL url = new URL(UPDATE_MANIFEST_URL + "?nocache=" + System.currentTimeMillis());
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", "ZRamPurgerExtreme-App/" + currentName);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    int remoteCode = json.optInt("versionCode", 0);
                    String remoteName = json.optString("versionName", "1.0.0");
                    String downloadUrl = json.optString("downloadUrl", "");
                    String changelog = json.optString("changelog", "Bản cập nhật tối ưu hóa hiệu năng.");
                    String title = json.optString("title", "Cập nhật mới");

                    boolean hasUpdate = remoteCode > currentCode;

                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (hasUpdate) {
                            try {
                                json.put("currentVersionCode", currentCode);
                                json.put("currentVersionName", currentName);
                                json.put("hasUpdate", true);
                                callback.onUpdateFound(json.toString());
                            } catch (Exception e) {
                                callback.onError(e.getMessage());
                            }
                        } else {
                            callback.onNoUpdate(currentName);
                        }
                    });
                } else {
                    new Handler(Looper.getMainLooper()).post(() -> 
                        callback.onError("HTTP " + responseCode + " khi tải manifest cập nhật.")
                    );
                }
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> 
                    callback.onError("Không thể kết nối đến máy chủ GitHub: " + e.getMessage())
                );
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }

    public static void startDownloadAndInstall(Context context, String downloadUrl, String versionName) {
        try {
            if (downloadUrl == null || downloadUrl.isEmpty()) {
                Toast.makeText(context, "⚠️ URL tải về không hợp lệ!", Toast.LENGTH_SHORT).show();
                return;
            }

            // Android 8+ check unknown app sources
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.getPackageManager().canRequestPackageInstalls()) {
                    Toast.makeText(context, "Vui lòng cho phép quyền cài đặt file APK từ nguồn ngoài", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
            }

            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager == null) {
                Toast.makeText(context, "Dịch vụ DownloadManager không khả dụng!", Toast.LENGTH_SHORT).show();
                return;
            }

            String fileName = "ZRamPurgerExtreme_" + versionName + ".apk";
            Uri uri = Uri.parse(downloadUrl);

            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle("Z-RAM Purger Extreme v" + versionName);
            request.setDescription("Đang tải bản cập nhật...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setMimeType("application/vnd.android.package-archive");

            long downloadId = downloadManager.enqueue(request);
            Toast.makeText(context, "⚡ Đang tải bản cập nhật v" + versionName + "...", Toast.LENGTH_SHORT).show();

            // Register complete broadcast receiver
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                    if (id == downloadId) {
                        try {
                            Uri downloadedFileUri = downloadManager.getUriForDownloadedFile(downloadId);
                            if (downloadedFileUri != null) {
                                Intent installIntent = new Intent(Intent.ACTION_VIEW);
                                installIntent.setDataAndType(downloadedFileUri, "application/vnd.android.package-archive");
                                installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                ctx.startActivity(installIntent);
                            }
                        } catch (Exception e) {
                            Toast.makeText(ctx, "Lỗi khi mở trình cài đặt: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        } finally {
                            try {
                                ctx.unregisterReceiver(this);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (Build.VERSION.SDK_INT >= 34) {
                // 2 = Context.RECEIVER_EXPORTED (API 34 compatibility without compile-time SDK dependency)
                context.registerReceiver(receiver, filter, 2);
            } else {
                context.registerReceiver(receiver, filter);
            }

        } catch (Exception e) {
            Toast.makeText(context, "Lỗi bắt đầu cập nhật: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public static int getCurrentVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return (int) pInfo.getLongVersionCode();
            } else {
                return pInfo.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            return 1;
        }
    }

    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName != null ? pInfo.versionName : "1.0.0";
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0.0";
        }
    }
}
