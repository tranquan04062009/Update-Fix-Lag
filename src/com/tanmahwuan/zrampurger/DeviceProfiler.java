package com.tanmahwuan.zrampurger;

import android.app.ActivityManager;
import android.content.Context;
import android.app.admin.DevicePolicyManager;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.os.Build;
import android.view.Display;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the REAL hardware/software profile of the current device (no hard-coded model names).
 * Every value that cannot be read is reported as unknown instead of being guessed.
 */
public class DeviceProfiler {

    public static final String UNKNOWN = "Không đọc được";

    public static class Cluster {
        public String cpus = "";
        public int coreCount;
        public long maxKHz = -1;
        public long minKHz = -1;
        public String governor = "";
        public String role = "";
    }

    public static class Profile {
        public String manufacturer, brand, model, device, board, hardware;
        public String androidRelease, securityPatch, skin, kernel;
        public int sdk;
        public String socVendor = UNKNOWN, socModel = UNKNOWN, platformCode = UNKNOWN;
        public int cores;
        public List<Cluster> clusters = new ArrayList<>();
        public String gpu = UNKNOWN;
        public long ramMb, swapMb;
        public String zramAlgo = "";
        public boolean hasZram, hasPsi;
        public String storage = UNKNOWN;
        public String resolution = UNKNOWN;
        public int densityDpi;
        public float maxRefreshHz;
        public List<Integer> refreshRates = new ArrayList<>();
        public String privilege = "none";

        // Capability flags: features are only offered when the OS really supports them.
        public boolean supportsDeviceConfig() { return sdk >= 29; }
        public boolean supportsGameManager() { return sdk >= 31; }
        public boolean supportsPhantomControl() { return sdk >= 31; }
        public boolean supportsAdaptiveBattery() { return sdk >= 28; }
        public boolean supportsHighRefresh() { return maxRefreshHz >= 90f; }

        public String toDisplayText() {
            StringBuilder sb = new StringBuilder();
            sb.append("Thiết bị: ").append(manufacturer).append(' ').append(model)
              .append(" (").append(device).append(")\n");
            sb.append("Android ").append(androidRelease).append(" (API ").append(sdk).append(")");
            if (securityPatch != null && !securityPatch.isEmpty()) sb.append(" · Bản vá ").append(securityPatch);
            sb.append('\n');
            sb.append("Giao diện: ").append(skin).append('\n');
            sb.append("Chip: ").append(socVendor).append(" · ").append(socModel)
              .append(" (").append(platformCode).append(")\n");
            sb.append("CPU: ").append(cores).append(" nhân");
            for (Cluster c : clusters) {
                sb.append("\n   • ").append(c.role).append(": ").append(c.coreCount).append("× lõi ").append(c.cpus);
                if (c.maxKHz > 0) sb.append(String.format(Locale.US, " · %.2f GHz", c.maxKHz / 1000000.0));
                if (!c.governor.isEmpty()) sb.append(" · ").append(c.governor);
            }
            sb.append("\nGPU: ").append(gpu).append('\n');
            sb.append("RAM: ").append(String.format(Locale.US, "%.1f GB", ramMb / 1024.0));
            if (swapMb > 0) {
                sb.append(" · Swap/ZRAM: ").append(String.format(Locale.US, "%.1f GB", swapMb / 1024.0));
                if (!zramAlgo.isEmpty()) sb.append(" (").append(zramAlgo).append(')');
            } else {
                sb.append(" · Không có swap");
            }
            sb.append("\nMàn hình: ").append(resolution);
            if (maxRefreshHz > 0) sb.append(" · tối đa ").append(Math.round(maxRefreshHz)).append(" Hz");
            sb.append("\nLưu trữ: ").append(storage).append('\n');
            sb.append("Kernel: ").append(kernel).append('\n');
            sb.append("PSI bộ nhớ: ").append(hasPsi ? "có" : "không").append(" · Quyền: ").append(privilege);
            return sb.toString();
        }
    }

    // ---------- pure helpers (unit-testable, no Android calls) ----------

    static final Pattern PROP_LINE = Pattern.compile("^\\[(.+?)\\]: \\[(.*)\\]$");

    public static Map<String, String> parseGetprop(String text) {
        Map<String, String> map = new HashMap<>();
        if (text == null) return map;
        for (String line : text.split("\n")) {
            Matcher m = PROP_LINE.matcher(line.trim());
            if (m.matches()) map.put(m.group(1), m.group(2));
        }
        return map;
    }

    /** "lzo [lz4] zstd" -> "lz4" (the bracketed entry is the active algorithm). */
    public static String selectedZramAlgo(String file) {
        if (file == null) return "";
        Matcher m = Pattern.compile("\\[([^\\]]+)\\]").matcher(file);
        return m.find() ? m.group(1) : file.trim();
    }

    /** [0,1,2,3,6] -> "0-3,6" */
    public static String cpuRange(List<Integer> cpus) {
        if (cpus.isEmpty()) return "";
        List<Integer> s = new ArrayList<>(new TreeSet<>(cpus));
        StringBuilder sb = new StringBuilder();
        int start = s.get(0), prev = start;
        for (int i = 1; i <= s.size(); i++) {
            Integer cur = i < s.size() ? s.get(i) : null;
            if (cur == null || cur != prev + 1) {
                if (sb.length() > 0) sb.append(',');
                sb.append(start == prev ? String.valueOf(start) : start + "-" + prev);
                if (cur != null) start = cur;
            }
            if (cur != null) prev = cur;
        }
        return sb.toString();
    }

    public static List<Integer> parseCpuList(String raw) {
        List<Integer> out = new ArrayList<>();
        if (raw == null) return out;
        for (String tok : raw.trim().split("[ ,]+")) {
            if (tok.isEmpty()) continue;
            try {
                if (tok.contains("-")) {
                    String[] ab = tok.split("-");
                    for (int i = Integer.parseInt(ab[0]); i <= Integer.parseInt(ab[1]); i++) out.add(i);
                } else out.add(Integer.parseInt(tok));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    public static String guessSocVendor(String socMfr, String platform, String hardware) {
        String m = socMfr == null ? "" : socMfr.toLowerCase(Locale.US);
        if (m.equals("qti") || m.contains("qualcomm")) return "Qualcomm";
        if (m.contains("mediatek")) return "MediaTek";
        if (m.contains("samsung")) return "Samsung Exynos";
        if (m.contains("google")) return "Google Tensor";
        if (m.contains("hisilicon") || m.contains("huawei")) return "HiSilicon Kirin";
        if (m.contains("unisoc") || m.contains("spreadtrum")) return "Unisoc";
        String p = ((platform == null ? "" : platform) + " " + (hardware == null ? "" : hardware)).toLowerCase(Locale.US);
        if (p.contains("qcom") || p.matches(".*\\b(msm|sdm|sm\\d).*")) return "Qualcomm";
        if (p.matches(".*\\bmt\\d.*")) return "MediaTek";
        if (p.matches(".*\\b(exynos|s5e|universal).*")) return "Samsung Exynos";
        if (p.matches(".*\\b(gs\\d|zuma|laguna).*")) return "Google Tensor";
        if (p.matches(".*\\b(kirin|hi\\d).*")) return "HiSilicon Kirin";
        if (p.matches(".*\\b(ums|sc\\d|sp\\d).*")) return "Unisoc";
        return UNKNOWN;
    }

    public static String detectSkin(Map<String, String> p) {
        String v;
        if (!isEmpty(v = p.get("ro.mi.os.version.name"))) return "HyperOS " + nz(p.get("ro.mi.os.version.incremental"));
        if (!isEmpty(v = p.get("ro.miui.ui.version.name"))) return "MIUI " + v;
        if (!isEmpty(v = p.get("ro.build.version.oneui"))) return "One UI " + oneUiVersion(v);
        if (!isEmpty(v = p.get("ro.build.version.oplusrom"))) return "ColorOS/OxygenOS " + v;
        if (!isEmpty(v = p.get("ro.build.version.opporom"))) return "ColorOS " + v;
        if (!isEmpty(v = p.get("ro.vivo.os.version"))) return nz(p.get("ro.vivo.os.name")) + " " + v;
        if (!isEmpty(v = p.get("ro.build.version.emui"))) return v;
        if (!isEmpty(v = p.get("ro.build.version.magic"))) return "MagicOS " + v;
        if (!isEmpty(v = p.get("ro.com.google.clientidbase")) && v.contains("transsion")) return "Transsion (HiOS/XOS)";
        return "AOSP / Stock";
    }

    /** One UI encodes version as an integer: 50100 -> 5.1.0 */
    static String oneUiVersion(String raw) {
        try {
            int n = Integer.parseInt(raw.trim());
            return (n / 10000) + "." + ((n / 100) % 100) + (n % 100 != 0 ? "." + (n % 100) : "");
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    /** Extracts avg10 from "some avg10=1.23 avg60=..." (PSI). Returns -1 when not parsable. */
    public static double parsePsiSomeAvg10(String content) {
        if (content == null) return -1;
        Matcher m = Pattern.compile("some\\s+avg10=([0-9.]+)").matcher(content);
        return m.find() ? Double.parseDouble(m.group(1)) : -1;
    }

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    private static String nz(String s) { return s == null ? "" : s; }

    // ---------- file helpers ----------

    static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            return l == null ? null : l.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    static String readAll(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    static long readLong(String path) {
        String s = readFirstLine(path);
        try { return s == null ? -1 : Long.parseLong(s); } catch (NumberFormatException e) { return -1; }
    }

    private static Map<String, String> getprop() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop"});
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
            }
            p.waitFor();
            return parseGetprop(sb.toString());
        } catch (Throwable t) {
            return new HashMap<>();
        }
    }

    // ---------- main entry ----------

    /** Blocking (file + process I/O). Call from a background thread. */
    public static Profile read(Context ctx) {
        Profile p = new Profile();
        Map<String, String> props = getprop();

        p.manufacturer = cap(Build.MANUFACTURER);
        p.brand = Build.BRAND;
        p.model = Build.MODEL;
        p.device = Build.DEVICE;
        p.board = Build.BOARD;
        p.hardware = Build.HARDWARE;
        p.androidRelease = Build.VERSION.RELEASE;
        p.sdk = Build.VERSION.SDK_INT;
        p.securityPatch = Build.VERSION.SECURITY_PATCH;
        p.kernel = nzOr(System.getProperty("os.version"), UNKNOWN);
        p.skin = detectSkin(props);
        p.privilege = CommandExecutor.getPrivilegeLevel();

        // SoC
        String socMfr = null, socModel = null;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                socMfr = (String) Build.class.getField("SOC_MANUFACTURER").get(null);
                socModel = (String) Build.class.getField("SOC_MODEL").get(null);
            } catch (Throwable ignored) {}
        }
        if (isEmpty(socMfr)) socMfr = props.get("ro.soc.manufacturer");
        if (isEmpty(socModel)) socModel = props.get("ro.soc.model");
        String platform = props.get("ro.board.platform");
        if (!isEmpty(platform)) p.platformCode = platform;
        if (!isEmpty(socModel) && !"unknown".equalsIgnoreCase(socModel)) p.socModel = socModel;
        else if (!isEmpty(platform)) p.socModel = platform;
        p.socVendor = guessSocVendor(socMfr, platform, Build.HARDWARE);

        readCpu(p);
        readGpu(p, props);
        readMemory(ctx, p);
        readDisplay(ctx, p);

        if (new File("/sys/block/mmcblk0").exists()) p.storage = "eMMC";
        if (new File("/sys/block/sda").exists()) p.storage = "UFS / SCSI";
        if (new File("/sys/block/nvme0n1").exists()) p.storage = "NVMe";
        p.hasPsi = new File("/proc/pressure/memory").exists();
        return p;
    }

    private static void readCpu(Profile p) {
        p.cores = Runtime.getRuntime().availableProcessors();
        File[] pols = new File("/sys/devices/system/cpu/cpufreq").listFiles((d, n) -> n.startsWith("policy"));
        if (pols != null && pols.length > 0) {
            Arrays.sort(pols);
            for (File f : pols) {
                String base = f.getPath();
                String rel = readFirstLine(base + "/related_cpus");
                if (rel == null) rel = readFirstLine(base + "/affected_cpus");
                List<Integer> cpus = parseCpuList(rel);
                if (cpus.isEmpty()) continue;
                Cluster c = new Cluster();
                c.cpus = cpuRange(cpus);
                c.coreCount = cpus.size();
                c.maxKHz = readLong(base + "/cpuinfo_max_freq");
                c.minKHz = readLong(base + "/cpuinfo_min_freq");
                String gov = readFirstLine(base + "/scaling_governor");
                c.governor = gov == null ? "" : gov;
                p.clusters.add(c);
            }
        }
        if (p.clusters.isEmpty()) {
            Map<Long, List<Integer>> byFreq = new java.util.TreeMap<>();
            for (int i = 0; i < p.cores; i++) {
                long mx = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
                if (mx > 0) {
                    List<Integer> l = byFreq.get(mx);
                    if (l == null) { l = new ArrayList<>(); byFreq.put(mx, l); }
                    l.add(i);
                }
            }
            for (Map.Entry<Long, List<Integer>> e : byFreq.entrySet()) {
                Cluster c = new Cluster();
                c.maxKHz = e.getKey();
                c.coreCount = e.getValue().size();
                c.cpus = cpuRange(e.getValue());
                p.clusters.add(c);
            }
        }
        Collections.sort(p.clusters, (a, b) -> Long.compare(a.maxKHz, b.maxKHz));
        p.clusters = assignRoles(p.clusters);
    }

    static String[] rolesFor(int n) {
        switch (n) {
            case 1: return new String[]{"Đơn cụm"};
            case 2: return new String[]{"Tiết kiệm", "Hiệu năng"};
            case 3: return new String[]{"Tiết kiệm", "Hiệu năng", "Prime"};
            default: {
                String[] r = new String[n];
                String[] base = {"Tiết kiệm", "Cân bằng", "Hiệu năng", "Prime"};
                for (int i = 0; i < n; i++) r[i] = i < 4 ? base[i] : "Cụm " + (i + 1);
                if (n > 4) r[n - 1] = "Prime";
                return r;
            }
        }
    }

    static List<Cluster> assignRoles(List<Cluster> list) {
        if (list.isEmpty()) return list;
        String[] roles = rolesFor(list.size());
        for (int i = 0; i < list.size(); i++) list.get(i).role = roles[i];
        return list;
    }


    // ================= CPU topology / GPU telemetry (device-independent) =================

    public static class Topology {
        public int littleCpu = 0;
        public int bigCpu = 0;
    }

    private static Topology cachedTopology;

    /** First CPU of the slowest and fastest cluster of THIS device (no hard-coded core numbers). */
    public static synchronized Topology topology() {
        if (cachedTopology != null) return cachedTopology;
        Profile tmp = new Profile();
        readCpu(tmp);
        Topology t = new Topology();
        if (!tmp.clusters.isEmpty()) {
            t.littleCpu = firstCpu(tmp.clusters.get(0).cpus, 0);
            t.bigCpu = firstCpu(tmp.clusters.get(tmp.clusters.size() - 1).cpus, 0);
        } else {
            t.littleCpu = 0;
            t.bigCpu = Math.max(0, tmp.cores - 1);
        }
        cachedTopology = t;
        return t;
    }

    static int firstCpu(String range, int def) {
        if (range == null || range.isEmpty()) return def;
        try { return Integer.parseInt(range.split("[-,]")[0]); } catch (NumberFormatException e) { return def; }
    }

    public static class Gpu {
        public String kind = "none";   // kgsl | devfreq | none
        public String model = "";
        public int clockMhz = -1;      // -1 = unknown
        public int busyPct = -1;       // -1 = unknown
    }

    /** Reads GPU clock/load from whichever interface this device exposes. Unknown values stay -1. */
    public static Gpu readGpu() {
        Gpu g = new Gpu();
        String kgsl = "/sys/class/kgsl/kgsl-3d0/";
        if (new File(kgsl).exists()) {
            g.kind = "kgsl";
            String m = readFirstLine(kgsl + "gpu_model");
            if (m != null) g.model = m;
            long clk = readLong(kgsl + "clock_mhz");
            if (clk > 0) g.clockMhz = (int) clk;
            String busy = readFirstLine(kgsl + "gpu_busy_percentage");
            if (busy != null) {
                try { g.busyPct = clampPct(Integer.parseInt(busy.replace("%", "").trim())); } catch (NumberFormatException ignored) {}
            } else {
                g.busyPct = parseBusyRatio(readFirstLine(kgsl + "gpubusy"));
            }
            return g;
        }
        File[] dev = new File("/sys/class/devfreq").listFiles();
        if (dev != null) {
            for (File d : dev) {
                String n = d.getName().toLowerCase(Locale.US);
                if (!(n.contains("mali") || n.contains("gpu") || n.contains("kgsl"))) continue;
                g.kind = "devfreq";
                g.model = d.getName();
                long hz = readLong(d.getPath() + "/cur_freq");
                if (hz > 0) g.clockMhz = (int) (hz / 1000000L);
                g.busyPct = parseDevfreqLoad(readFirstLine(d.getPath() + "/load"));
                return g;
            }
        }
        return g;
    }

    static int clampPct(int v) { return Math.max(0, Math.min(100, v)); }

    /** kgsl "gpubusy": "<busy ticks> <total ticks>" -> percent, -1 if unusable. */
    public static int parseBusyRatio(String raw) {
        if (raw == null) return -1;
        String[] parts = raw.trim().split("\\s+");
        if (parts.length < 2) return -1;
        try {
            long busy = Long.parseLong(parts[0]), total = Long.parseLong(parts[1]);
            return total > 0 ? clampPct((int) (busy * 100 / total)) : -1;
        } catch (NumberFormatException e) { return -1; }
    }

    /** devfreq "load": "37@800000000Hz" -> 37, -1 if unusable. */
    public static int parseDevfreqLoad(String raw) {
        if (raw == null) return -1;
        Matcher m = Pattern.compile("^(\\d+)").matcher(raw.trim());
        return m.find() ? clampPct(Integer.parseInt(m.group(1))) : -1;
    }

    /**
     * Chooses the GPU "min clock" to hold while gaming: the second-highest step of THIS GPU's own table
     * (never a fixed number such as 800 MHz). Returns -1 when the table is unreadable.
     */
    public static int pickBoostFreq(String table) {
        if (table == null) return -1;
        TreeSet<Integer> set = new TreeSet<>();
        for (String tok : table.trim().split("\\s+")) {
            try { int v = Integer.parseInt(tok); if (v > 0) set.add(v); } catch (NumberFormatException ignored) {}
        }
        if (set.isEmpty()) return -1;
        if (set.size() == 1) return set.first();
        return set.lower(set.last());
    }

    // ================= Packages that must never be killed/restricted =================

    /** "pkg/cls:pkg2/cls2" -> {pkg, pkg2} */
    public static Set<String> packagesOfComponents(String colonList) {
        Set<String> out = new HashSet<>();
        if (colonList == null) return out;
        for (String c : colonList.split(":")) {
            int i = c.indexOf('/');
            String pkg = i > 0 ? c.substring(0, i) : c;
            if (!pkg.isEmpty() && pkg.matches("[A-Za-z0-9_.]+")) out.add(pkg);
        }
        return out;
    }

    /**
     * Packages that are critical on THIS device: every launcher, enabled keyboard, default dialer/SMS,
     * enabled accessibility services, live wallpaper, notification listeners, assistant and device admins.
     * Works for any brand, so no static per-OEM list is needed for these.
     */
    public static Set<String> dynamicProtectedPackages(Context ctx) {
        Set<String> s = new HashSet<>();
        if (ctx == null) return s;
        s.add(ctx.getPackageName());
        try {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(home, 0))
                if (ri.activityInfo != null) s.add(ri.activityInfo.packageName);
        } catch (Throwable ignored) {}
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            for (InputMethodInfo i : imm.getEnabledInputMethodList()) s.add(i.getPackageName());
        } catch (Throwable ignored) {}
        try {
            TelecomManager tm = (TelecomManager) ctx.getSystemService(Context.TELECOM_SERVICE);
            String d = tm.getDefaultDialerPackage();
            if (d != null) s.add(d);
        } catch (Throwable ignored) {}
        try {
            String sms = Telephony.Sms.getDefaultSmsPackage(ctx);
            if (sms != null) s.add(sms);
        } catch (Throwable ignored) {}
        try {
            AccessibilityManager am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            for (AccessibilityServiceInfo i : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
                if (i.getResolveInfo() != null && i.getResolveInfo().serviceInfo != null)
                    s.add(i.getResolveInfo().serviceInfo.packageName);
        } catch (Throwable ignored) {}
        try {
            android.app.WallpaperInfo wi = WallpaperManager.getInstance(ctx).getWallpaperInfo();
            if (wi != null) s.add(wi.getPackageName());
        } catch (Throwable ignored) {}
        try {
            s.addAll(packagesOfComponents(Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners")));
            s.addAll(packagesOfComponents(Settings.Secure.getString(ctx.getContentResolver(), "assistant")));
        } catch (Throwable ignored) {}
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            List<ComponentName> admins = dpm.getActiveAdmins();
            if (admins != null) for (ComponentName c : admins) s.add(c.getPackageName());
        } catch (Throwable ignored) {}
        return s;
    }

    private static void readGpu(Profile p, Map<String, String> props) {
        String model = readFirstLine("/sys/class/kgsl/kgsl-3d0/gpu_model");
        if (!isEmpty(model)) {
            p.gpu = model.startsWith("Adreno") ? model.replaceFirst("Adreno\\s*", "Adreno ") : "Adreno " + model;
            return;
        }
        String egl = props.get("ro.hardware.egl");
        if (isEmpty(egl)) egl = props.get("ro.hardware.vulkan");
        if (!isEmpty(egl)) {
            String e = egl.toLowerCase(Locale.US);
            if (e.contains("adreno")) p.gpu = "Adreno";
            else if (e.contains("mali")) p.gpu = "Arm Mali";
            else if (e.contains("powervr") || e.contains("img")) p.gpu = "PowerVR";
            else if (e.contains("emulation") || e.contains("swiftshader")) p.gpu = "Ảo hóa (" + egl + ")";
            else p.gpu = egl;
        } else if (new File("/sys/class/misc/mali0").exists()) {
            p.gpu = "Arm Mali";
        }
    }

    private static void readMemory(Context ctx, Profile p) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            p.ramMb = mi.totalMem / (1024 * 1024);
        } catch (Throwable ignored) {}
        String mem = readAll("/proc/meminfo");
        if (mem != null) {
            Matcher m = Pattern.compile("SwapTotal:\\s+(\\d+) kB").matcher(mem);
            if (m.find()) p.swapMb = Long.parseLong(m.group(1)) / 1024;
            if (p.ramMb <= 0) {
                Matcher t = Pattern.compile("MemTotal:\\s+(\\d+) kB").matcher(mem);
                if (t.find()) p.ramMb = Long.parseLong(t.group(1)) / 1024;
            }
        }
        p.hasZram = new File("/sys/block/zram0").exists();
        if (p.hasZram) p.zramAlgo = selectedZramAlgo(readFirstLine("/sys/block/zram0/comp_algorithm"));
    }

    private static void readDisplay(Context ctx, Profile p) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (d == null) return;
            Display.Mode cur = d.getMode();
            p.resolution = cur.getPhysicalWidth() + "×" + cur.getPhysicalHeight();
            TreeSet<Integer> rates = new TreeSet<>();
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() == cur.getPhysicalWidth() && m.getPhysicalHeight() == cur.getPhysicalHeight()) {
                    rates.add(Math.round(m.getRefreshRate()));
                }
            }
            p.refreshRates.addAll(rates);
            if (!rates.isEmpty()) p.maxRefreshHz = rates.last();
            p.densityDpi = ctx.getResources().getDisplayMetrics().densityDpi;
        } catch (Throwable ignored) {}
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String nzOr(String s, String d) { return isEmpty(s) ? d : s; }
}
