package com.zalorust.zrampurger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    public static final int SHIZUKU_PERMISSION_CODE = 4001;

    private WebView webView;
    private WebAppInterface webAppInterface;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        runOnUiThread(() -> {
            notifyWebviewPrivilege();
            checkAndRequestShizuku();
        });
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        runOnUiThread(() -> {
            notifyWebviewPrivilege();
        });
    };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_PERMISSION_CODE) {
            runOnUiThread(() -> {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "⚡ Shizuku: Cấp quyền thành công!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "⚠️ Shizuku: Quyền bị từ chối!", Toast.LENGTH_SHORT).show();
                }
                notifyWebviewPrivilege();
            });
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);

        webAppInterface = new WebAppInterface(this);
        webView.addJavascriptInterface(webAppInterface, "AndroidNative");

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
                new android.app.AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Xác nhận")
                        .setMessage(message)
                        .setPositiveButton("Đồng ý", (dialog, which) -> result.confirm())
                        .setNegativeButton("Hủy", (dialog, which) -> result.cancel())
                        .setOnCancelListener(dialog -> result.cancel())
                        .show();
                return true;
            }
        });

        // Register Shizuku listeners
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permissionResultListener);
        } catch (Throwable ignored) {}

        // Failsafe recovery & crash rollback handlers
        GameBoosterManager.checkAndRecoverColdStart(this);
        GameBoosterManager.installCrashHandler(this);

        webView.loadUrl("file:///android_asset/index.html");
    }

    public WebView getWebView() {
        return webView;
    }

    public void checkAndRequestShizuku() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
                }
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkAndRequestShizuku();
        notifyWebviewPrivilege();
    }

    public void requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_CODE);
                } else {
                    Toast.makeText(this, "⚡ Shizuku đã được cấp quyền từ trước!", Toast.LENGTH_SHORT).show();
                    notifyWebviewPrivilege();
                }
            } else {
                Toast.makeText(this, "⚠️ Không tìm thấy Shizuku server đang chạy!", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "Lỗi kiểm tra Shizuku: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void notifyWebviewPrivilege() {
        if (webView != null) {
            webView.evaluateJavascript("if (window.onPrivilegeChanged) { window.onPrivilegeChanged(); }", null);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permissionResultListener);
        } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
