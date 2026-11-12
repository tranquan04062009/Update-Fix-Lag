package com.tanmahwuan.zrampurger;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class UpdateManager {

    public static final String GITHUB_RELEASE_API_URL = 
        "https://api.github.com/repos/tranquan04062009/Update-Fix-Lag/releases/latest";
    public static final String GITHUB_RAW_FALLBACK_URL = 
        "https://raw.githubusercontent.com/tranquan04062009/Update-Fix-Lag/master/version.json";

    public interface UpdateCheckCallback {
        void onUpdateFound(String updateJson);
        void onNoUpdate(String currentVersion);
        void onError(String message);
    }

    public static void checkForUpdates(Context context, UpdateCheckCallback callback) {
        new Thread(() -> {
            String currentName = getCurrentVersionName(context);
            JSONObject result = fetchLatestReleaseFromGitHub(currentName);

            // If GitHub API failed, try raw fallback
            if (result == null) {
                result = fetchRawFallback(currentName);
            }

            final JSONObject finalResult = result;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (finalResult != null) {
                    if (finalResult.optBoolean("hasUpdate", false)) {
                        callback.onUpdateFound(finalResult.toString());
                    } else {
                        callback.onNoUpdate(currentName);
                    }
                } else {
                    callback.onError("Không thể kiểm tra máy chủ cập nhật.");
                }
            });
        }).start();
    }

    private static JSONObject fetchLatestReleaseFromGitHub(String currentVersion) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(GITHUB_RELEASE_API_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "ZRamPurgerExtreme-Client");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            conn.connect();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject release = new JSONObject(sb.toString());
                String tagName = release.optString("tag_name", "");
                String cleanRemoteVer = tagName.replaceAll("[^0-9.]", "");
                String releaseTitle = release.optString("name", "Bản cập nhật v" + cleanRemoteVer);
                String changelog = release.optString("body", "Tối ưu hóa hệ thống và sửa lỗi.");

                String downloadUrl = "";
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null && assets.length() > 0) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String assetName = asset.optString("name", "");
                        if (assetName.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url", "");
                            break;
                        }
                    }
                    if (downloadUrl.isEmpty()) {
                        downloadUrl = assets.getJSONObject(0).optString("browser_download_url", "");
                    }
                }

                boolean isNewer = isVersionNewer(cleanRemoteVer, currentVersion);

                JSONObject out = new JSONObject();
                out.put("hasUpdate", isNewer);
                out.put("versionName", cleanRemoteVer);
                out.put("title", releaseTitle);
                out.put("changelog", changelog);
                out.put("downloadUrl", downloadUrl);
                return out;
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static JSONObject fetchRawFallback(String currentVersion) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(GITHUB_RAW_FALLBACK_URL + "?t=" + System.currentTimeMillis());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "ZRamPurgerExtreme-Client");
            conn.connect();

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject raw = new JSONObject(sb.toString());
                String remoteVer = raw.optString("versionName", "1.0.0").replaceAll("[^0-9.]", "");
                boolean isNewer = isVersionNewer(remoteVer, currentVersion);

                JSONObject out = new JSONObject();
                out.put("hasUpdate", isNewer);
                out.put("versionName", remoteVer);
                out.put("title", raw.optString("title", "Cập nhật mới"));
                out.put("changelog", raw.optString("changelog", ""));
                out.put("downloadUrl", raw.optString("downloadUrl", ""));
                return out;
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    public static boolean isVersionNewer(String remoteVer, String currentVer) {
        if (remoteVer == null || remoteVer.isEmpty()) return false;
        if (currentVer == null || currentVer.isEmpty()) return true;

        try {
            String[] rParts = remoteVer.split("\\.");
            String[] cParts = currentVer.split("\\.");
            int maxLen = Math.max(rParts.length, cParts.length);

            for (int i = 0; i < maxLen; i++) {
                int r = i < rParts.length ? Integer.parseInt(rParts[i].replaceAll("[^0-9]", "")) : 0;
                int c = i < cParts.length ? Integer.parseInt(cParts[i].replaceAll("[^0-9]", "")) : 0;
                if (r > c) return true;
                if (r < c) return false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static void startDownloadAndInstall(Context context, String downloadUrl, String versionName) {
        try {
            if (downloadUrl == null || downloadUrl.isEmpty()) {
                Toast.makeText(context, "⚠️ Link tải cập nhật chưa sẵn sàng!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.getPackageManager().canRequestPackageInstalls()) {
                    Toast.makeText(context, "Vui lòng cho phép quyền cài đặt file APK từ ứng dụng", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
            }

            DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager == null) {
                Toast.makeText(context, "Dịch vụ tải về không khả dụng!", Toast.LENGTH_SHORT).show();
                return;
            }

            String fileName = "ZRamPurgerExtreme_v" + versionName + ".apk";
            Uri uri = Uri.parse(downloadUrl);

            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle("Z-RAM Extreme v" + versionName);
            request.setDescription("Đang tải bản cập nhật...");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setMimeType("application/vnd.android.package-archive");

            long downloadId = downloadManager.enqueue(request);
            Toast.makeText(context, "⚡ Đang tải bản nâng cấp v" + versionName + "...", Toast.LENGTH_SHORT).show();

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
                            Toast.makeText(ctx, "Lỗi cài đặt: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
                context.registerReceiver(receiver, filter, 2);
            } else {
                context.registerReceiver(receiver, filter);
            }

        } catch (Exception e) {
            Toast.makeText(context, "Lỗi tải về: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
            return pInfo.versionName != null ? pInfo.versionName : "1.0.2";
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0.2";
        }
    }
}
