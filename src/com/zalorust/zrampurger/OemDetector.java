package com.zalorust.zrampurger;

import android.os.Build;
import java.lang.reflect.Method;

public class OemDetector {

    public enum OemBrand {
        SAMSUNG,
        XIAOMI,
        OPPO_ONEPLUS,
        VIVO,
        PIXEL,
        MOTOROLA,
        AOSP_GENERIC
    }

    public static class OemInfo {
        public OemBrand brand = OemBrand.AOSP_GENERIC;
        public String brandName = "Universal Android";
        public String oemVersion = "";
        public String virtualRamTech = "AOSP Compactor";
        public int virtualRamMb = 0;
        public boolean chimeraSupported = false;
        public boolean hyperOsSupported = false;
        public boolean colorOsSupported = false;
        public boolean originOsSupported = false;
        public String customPurgeBanner = "AOSP Compaction & Memory Factor";

        public String toJson() {
            return String.format(
                "{\"brand\":\"%s\",\"brandName\":\"%s\",\"oemVersion\":\"%s\",\"virtualRamTech\":\"%s\"," +
                "\"virtualRamMb\":%d,\"chimeraSupported\":%b,\"hyperOsSupported\":%b,\"colorOsSupported\":%b," +
                "\"originOsSupported\":%b,\"customPurgeBanner\":\"%s\"}",
                brand.name(),
                escapeJson(brandName),
                escapeJson(oemVersion),
                escapeJson(virtualRamTech),
                virtualRamMb,
                chimeraSupported,
                hyperOsSupported,
                colorOsSupported,
                originOsSupported,
                escapeJson(customPurgeBanner)
            );
        }
    }

    public static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < ' ') {
                        sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    public static OemInfo detect() {
        return getOemInfo();
    }

    public static boolean isSamsung() {
        return getOemInfo().brand == OemBrand.SAMSUNG;
    }

    public static boolean isXiaomi() {
        return getOemInfo().brand == OemBrand.XIAOMI;
    }

    public static boolean isOppoOnePlus() {
        return getOemInfo().brand == OemBrand.OPPO_ONEPLUS;
    }

    public static boolean isVivo() {
        return getOemInfo().brand == OemBrand.VIVO;
    }

    public static boolean isPixel() {
        return getOemInfo().brand == OemBrand.PIXEL;
    }

    public static boolean isMotorola() {
        return getOemInfo().brand == OemBrand.MOTOROLA;
    }

    private static OemInfo cachedInfo = null;

    public static synchronized OemInfo getOemInfo() {
        if (cachedInfo != null) {
            return cachedInfo;
        }

        OemInfo info = new OemInfo();
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase() : "";
        String brand = Build.BRAND != null ? Build.BRAND.toLowerCase() : "";

        // 1. Samsung One UI Detection
        String oneUiVer = getSystemProperty("ro.build.version.oneui", "");
        String semVer = getSystemProperty("ro.build.version.sem", "");
        if (!oneUiVer.isEmpty() || !semVer.isEmpty() || manufacturer.contains("samsung") || brand.contains("samsung")) {
            info.brand = OemBrand.SAMSUNG;
            info.brandName = "Samsung One UI";
            if (!oneUiVer.isEmpty()) {
                try {
                    int verNum = Integer.parseInt(oneUiVer);
                    info.oemVersion = String.format("One UI %d.%d", verNum / 10000, (verNum % 10000) / 100);
                } catch (Throwable t) {
                    info.oemVersion = "One UI (" + oneUiVer + ")";
                }
            } else {
                info.oemVersion = "One UI";
            }
            info.virtualRamTech = "Samsung RAM Plus & Chimera";
            info.chimeraSupported = true;
            info.customPurgeBanner = "Samsung Chimera DHA & RAM Plus";

            String ramPlus = getSystemProperty("persist.sys.zram.ram_expand_size", "4096");
            try {
                info.virtualRamMb = Integer.parseInt(ramPlus);
            } catch (Throwable ignored) {
                info.virtualRamMb = 4096;
            }
            cachedInfo = info;
            return info;
        }

        // 2. Xiaomi / Redmi / POCO HyperOS & MIUI Detection
        String miuiVer = getSystemProperty("ro.miui.ui.version.name", "");
        String hyperOsVer = getSystemProperty("ro.mi.os.version.name", "");
        if (!hyperOsVer.isEmpty() || !miuiVer.isEmpty() || manufacturer.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) {
            info.brand = OemBrand.XIAOMI;
            if (!hyperOsVer.isEmpty()) {
                info.brandName = "Xiaomi HyperOS";
                info.oemVersion = hyperOsVer;
            } else if (!miuiVer.isEmpty()) {
                info.brandName = "Xiaomi MIUI";
                info.oemVersion = miuiVer;
            } else {
                info.brandName = "Xiaomi";
                info.oemVersion = "HyperOS";
            }
            info.virtualRamTech = "HyperOS Memory Extension";
            info.hyperOsSupported = true;
            info.customPurgeBanner = "HyperOS Sentinel & mi_reclaim";

            String extMb = getSystemProperty("persist.miui.extm.size", "3072");
            try {
                info.virtualRamMb = Integer.parseInt(extMb);
            } catch (Throwable ignored) {
                info.virtualRamMb = 3072;
            }
            cachedInfo = info;
            return info;
        }

        // 3. OPPO / OnePlus / Realme ColorOS / OxygenOS Detection
        String oplusRom = getSystemProperty("ro.build.version.oplusrom", "");
        String oppoRom = getSystemProperty("ro.build.version.opporom", "");
        if (!oplusRom.isEmpty() || !oppoRom.isEmpty() || manufacturer.contains("oppo") || manufacturer.contains("oneplus") || manufacturer.contains("realme") || brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme")) {
            info.brand = OemBrand.OPPO_ONEPLUS;
            info.brandName = manufacturer.contains("oneplus") ? "OnePlus OxygenOS" : "OPPO ColorOS";
            info.oemVersion = !oplusRom.isEmpty() ? oplusRom : oppoRom;
            info.virtualRamTech = "ColorOS RAM Expansion";
            info.colorOsSupported = true;
            info.customPurgeBanner = "ColorOS Athena & OKiller";

            String nandswap = getSystemProperty("persist.sys.oplus.nandswap", "4096");
            try {
                info.virtualRamMb = Integer.parseInt(nandswap);
            } catch (Throwable ignored) {
                info.virtualRamMb = 4096;
            }
            cachedInfo = info;
            return info;
        }

        // 4. Vivo / iQOO OriginOS / Funtouch OS Detection
        String vivoVer = getSystemProperty("ro.vivo.os.version", "");
        String vivoDisplay = getSystemProperty("ro.vivo.os.build.display.id", "");
        if (!vivoVer.isEmpty() || !vivoDisplay.isEmpty() || manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo")) {
            info.brand = OemBrand.VIVO;
            info.brandName = "Vivo OriginOS";
            info.oemVersion = !vivoVer.isEmpty() ? vivoVer : vivoDisplay;
            info.virtualRamTech = "OriginOS Memory Fusion";
            info.originOsSupported = true;
            info.customPurgeBanner = "OriginOS Memory Fusion & VGC";

            String vivoFusion = getSystemProperty("persist.sys.vivo.ram_expansion", "4096");
            try {
                info.virtualRamMb = Integer.parseInt(vivoFusion);
            } catch (Throwable ignored) {
                info.virtualRamMb = 4096;
            }
            cachedInfo = info;
            return info;
        }

        // 5. Google Pixel Detection
        if (manufacturer.contains("google") || brand.contains("google")) {
            info.brand = OemBrand.PIXEL;
            info.brandName = "Google Pixel";
            info.oemVersion = "Android " + Build.VERSION.RELEASE;
            info.virtualRamTech = "AOSP CachedAppOptimizer";
            info.customPurgeBanner = "Pixel CachedAppOptimizer";
            info.virtualRamMb = 0;
            cachedInfo = info;
            return info;
        }

        // 6. Motorola / Lenovo Detection
        if (manufacturer.contains("motorola") || manufacturer.contains("lenovo") || brand.contains("motorola")) {
            info.brand = OemBrand.MOTOROLA;
            info.brandName = "Motorola MyUX";
            info.oemVersion = "Android " + Build.VERSION.RELEASE;
            info.virtualRamTech = "Motorola RAM Boost";
            info.customPurgeBanner = "Motorola RAM Boost & Freezer";

            String motoBoost = getSystemProperty("persist.vendor.zram.expand_size", "2048");
            try {
                info.virtualRamMb = Integer.parseInt(motoBoost);
            } catch (Throwable ignored) {
                info.virtualRamMb = 2048;
            }
            cachedInfo = info;
            return info;
        }

        // 7. Universal AOSP Fallback
        info.brand = OemBrand.AOSP_GENERIC;
        info.brandName = "Universal Android";
        info.oemVersion = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")";
        info.virtualRamTech = "AOSP Compactor & ZRAM";
        info.customPurgeBanner = "AOSP Compaction & Memory Factor";
        info.virtualRamMb = 0;
        cachedInfo = info;
        return info;
    }

    public static String getSystemProperty(String key, String def) {
        try {
            Class<?> clazz = Class.forName("android.os.SystemProperties");
            Method getMethod = clazz.getMethod("get", String.class, String.class);
            String val = (String) getMethod.invoke(null, key, def);
            return val != null ? val.trim() : def;
        } catch (Throwable t) {
            return def;
        }
    }
}
