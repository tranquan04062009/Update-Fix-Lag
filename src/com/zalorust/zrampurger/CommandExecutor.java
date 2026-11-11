package com.zalorust.zrampurger;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IRemoteProcess;

public class CommandExecutor {

    public static class CommandResult {
        public int exitCode;
        public String stdout;
        public String stderr;
        public long durationMs;
        public String provider; // "shizuku", "root", "shell"

        public CommandResult(int exitCode, String stdout, String stderr, long durationMs, String provider) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.durationMs = durationMs;
            this.provider = provider;
        }

        public String toJson() {
            String safeOut = stdout.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            String safeErr = stderr.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
            return String.format(
                "{\"exitCode\":%d,\"stdout\":\"%s\",\"stderr\":\"%s\",\"durationMs\":%d,\"provider\":\"%s\"}",
                exitCode, safeOut, safeErr, durationMs, provider
            );
        }
    }

    public static boolean isShizukuAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasShizukuPermission() {
        try {
            if (!isShizukuAvailable()) return false;
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isRootAvailable() {
        try {
            File su1 = new File("/system/bin/su");
            File su2 = new File("/system/xbin/su");
            File su3 = new File("/sbin/su");
            if (su1.exists() || su2.exists() || su3.exists()) return true;

            Process p = Runtime.getRuntime().exec(new String[]{"which", "su"});
            int code = p.waitFor();
            return code == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static String getPrivilegeLevel() {
        if (hasShizukuPermission()) {
            return "shizuku";
        } else if (isRootAvailable()) {
            return "root";
        } else {
            return "shell";
        }
    }

    public static CommandResult execute(String command) {
        long startTime = System.currentTimeMillis();

        if (hasShizukuPermission()) {
            try {
                IBinder binder = Shizuku.getBinder();
                if (binder != null) {
                    IShizukuService service = IShizukuService.Stub.asInterface(binder);
                    IRemoteProcess remoteProcess = service.newProcess(new String[]{"sh", "-c", command}, null, null);

                    StringBuilder out = new StringBuilder();
                    StringBuilder err = new StringBuilder();

                    ParcelFileDescriptor pfdOut = remoteProcess.getInputStream();
                    if (pfdOut != null) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                new FileInputStream(pfdOut.getFileDescriptor()), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                out.append(line).append("\n");
                            }
                        }
                        pfdOut.close();
                    }

                    ParcelFileDescriptor pfdErr = remoteProcess.getErrorStream();
                    if (pfdErr != null) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                new FileInputStream(pfdErr.getFileDescriptor()), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                err.append(line).append("\n");
                            }
                        }
                        pfdErr.close();
                    }

                    int exitCode = remoteProcess.waitFor();
                    remoteProcess.destroy();
                    long duration = System.currentTimeMillis() - startTime;
                    return new CommandResult(exitCode, out.toString().trim(), err.toString().trim(), duration, "shizuku");
                }
            } catch (Throwable t) {
                // fallback to local shell
            }
        }

        if (isRootAvailable()) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
                StringBuilder out = new StringBuilder();
                StringBuilder err = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        out.append(line).append("\n");
                    }
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        err.append(line).append("\n");
                    }
                }

                int code = p.waitFor();
                long duration = System.currentTimeMillis() - startTime;
                return new CommandResult(code, out.toString().trim(), err.toString().trim(), duration, "root");
            } catch (Throwable ignored) {}
        }

        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append("\n");
                }
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    err.append(line).append("\n");
                }
            }

            int code = p.waitFor();
            long duration = System.currentTimeMillis() - startTime;
            return new CommandResult(code, out.toString().trim(), err.toString().trim(), duration, "shell");
        } catch (Throwable t) {
            long duration = System.currentTimeMillis() - startTime;
            return new CommandResult(-1, "", t.getMessage() != null ? t.getMessage() : "Execution error", duration, "shell");
        }
    }
}
