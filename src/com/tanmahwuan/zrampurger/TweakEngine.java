package com.tanmahwuan.zrampurger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reversible system tweaks built only from documented AOSP settings / DeviceConfig keys.
 *  - Offered only when the device (API level, display, ...) supports them.
 *  - The previous value of every key is stored BEFORE changing it, so it can be restored exactly.
 *  - Every write is read back and verified; a tweak is never reported as "applied" unless it really is.
 */
public class TweakEngine {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_.\\-]+$");
    private static final String PREFS = "tweak_engine";

    public static class Op {
        public final String kind;   // "settings" | "device_config"
        public final String ns;     // global | system | secure | DeviceConfig namespace
        public final String key;
        public final String value;

        public Op(String kind, String ns, String key, String value) {
            this.kind = kind; this.ns = ns; this.key = key; this.value = value;
        }

        boolean valid() {
            return SAFE.matcher(ns).matches() && SAFE.matcher(key).matches() && SAFE.matcher(value).matches()
                    && ("settings".equals(kind) || "device_config".equals(kind));
        }
    }

    public static class Tweak {
        public final String id, title, desc;
        public final List<Op> ops;
        public String unsupportedReason; // null => supported on this device

        Tweak(String id, String title, String desc, Op... ops) {
            this.id = id; this.title = title; this.desc = desc; this.ops = Arrays.asList(ops);
        }
    }

    public static class Result {
        public final boolean ok;
        public final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    /** Touch response toggle (restores the user's previous values, not hard-coded defaults). */
    public static final Tweak TOUCH = new Tweak("touch",
            "Phản hồi cảm ứng",
            "Rút ngắn thời gian nhấn giữ để thao tác nhạy hơn.",
            new Op("settings", "secure", "long_press_timeout", "250"),
            new Op("settings", "secure", "multi_press_timeout", "250"));

    /** Proactive memory management flags (DeviceConfig). Cached-process cap only on devices with <= 8 GB RAM. */
    public static Tweak lowRam(int sdk, long ramMb) {
        List<Op> ops = new ArrayList<>();
        String am = "activity_manager";
        ops.add(new Op("device_config", am, "use_compaction", "true"));
        ops.add(new Op("device_config", am, "compact_action_1", "3"));
        ops.add(new Op("device_config", am, "compact_action_2", "3"));
        ops.add(new Op("device_config", am, "compact_throttle_1", "1000"));
        ops.add(new Op("device_config", am, "compact_throttle_2", "2000"));
        ops.add(new Op("device_config", am, "compact_full_rss_throttle_kb", "8000"));
        ops.add(new Op("device_config", am, "use_oom_re_ranking", "true"));
        if (ramMb > 0 && ramMb <= 8192) ops.add(new Op("device_config", am, "max_cached_processes", "24"));
        ops.add(new Op("device_config", am, "kill_bg_restricted_cached_idle", "true"));
        ops.add(new Op("device_config", "activity_manager_native_boot", "use_freezer", "true"));
        ops.add(new Op("device_config", "activity_manager_native_boot", "freezer_debounce_timeout", "10000"));
        Tweak t = new Tweak("lowram", "Quản lý bộ nhớ chủ động", "Nén và đóng băng tiến trình nền sớm hơn.",
                ops.toArray(new Op[0]));
        if (sdk < 29) t.unsupportedReason = "Cần Android 10 trở lên";
        return t;
    }

    /** Builds the catalog for THIS device; unsupported tweaks carry a human-readable reason. */
    public static List<Tweak> catalog(DeviceProfiler.Profile p) {
        List<Tweak> list = new ArrayList<>();

        Tweak phantom = new Tweak("phantom",
                "Bỏ giới hạn tiến trình con",
                "Ngăn Android 12+ tự kill tiến trình con/phụ của ứng dụng (game, trình duyệt, Termux…) gây giật và tự thoát.",
                new Op("device_config", "activity_manager", "max_phantom_processes", "2147483647"),
                new Op("settings", "global", "settings_enable_monitor_phantom_procs", "false"));
        if (!p.supportsPhantomControl()) phantom.unsupportedReason = "Cần Android 12 trở lên";
        list.add(phantom);

        String hz = p.maxRefreshHz > 0 ? String.format(Locale.US, "%.1f", p.maxRefreshHz) : "60.0";
        Tweak refresh = new Tweak("refresh",
                "Tần số quét tối đa",
                "Cho phép hệ thống dùng tới " + Math.round(p.maxRefreshHz) + " Hz khi cuộn/chơi game (tốn pin hơn).",
                new Op("settings", "system", "peak_refresh_rate", hz));
        if (!p.supportsHighRefresh()) refresh.unsupportedReason = "Màn hình không hỗ trợ từ 90 Hz";
        list.add(refresh);

        list.add(new Tweak("scan",
                "Giảm quét nền Wi-Fi/Bluetooth",
                "Tắt quét Wi-Fi và BLE khi đã tắt kết nối, giảm tải CPU nền và hao pin.",
                new Op("settings", "global", "wifi_scan_always_enabled", "0"),
                new Op("settings", "global", "ble_scan_always_enabled", "0")));

        Tweak standby = new Tweak("standby",
                "Chế độ chờ thông minh",
                "Bật App Standby, Adaptive Battery và tự hạn chế app nền ngốn tài nguyên. Thông báo của app ít dùng có thể chậm hơn.",
                new Op("settings", "global", "app_standby_enabled", "1"),
                new Op("settings", "global", "adaptive_battery_management_enabled", "1"),
                new Op("settings", "global", "app_auto_restriction_enabled", "1"));
        if (!p.supportsAdaptiveBattery()) standby.unsupportedReason = "Cần Android 9 trở lên";
        list.add(standby);

        return list;
    }

    /** Test hook: lets unit tests inject a fake store instead of a real Context. */
    static SharedPreferences testPrefs;

    private static SharedPreferences prefs(Context c) {
        if (testPrefs != null) return testPrefs;
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isApplied(Context c, String id) {
        return prefs(c).getBoolean("on_" + id, false);
    }

    // ---------- shell helpers ----------

    private static class Val {
        boolean ok; String v; String provider = "";
    }

    private static String getCmd(Op op) {
        return "settings".equals(op.kind)
                ? "settings get " + op.ns + " " + op.key
                : "cmd device_config get " + op.ns + " " + op.key;
    }

    private static String putCmd(Op op, String value) {
        return "settings".equals(op.kind)
                ? "settings put " + op.ns + " " + op.key + " " + value
                : "cmd device_config put " + op.ns + " " + op.key + " " + value;
    }

    private static String deleteCmd(Op op) {
        return "settings".equals(op.kind)
                ? "settings delete " + op.ns + " " + op.key
                : "cmd device_config delete " + op.ns + " " + op.key;
    }

    private static Val read(Op op) {
        CommandExecutor.CommandResult r = CommandExecutor.execute(getCmd(op));
        Val v = new Val();
        v.provider = r.provider;
        String out = r.stdout == null ? "" : r.stdout.trim();
        boolean err = r.exitCode != 0 || out.contains("Exception") || out.startsWith("Error");
        v.ok = !err;
        v.v = (out.isEmpty() || out.equals("null")) ? null : out;
        return v;
    }

    private static String needPrivilege(String provider) {
        return "shell".equals(provider)
                ? "Cần cấp quyền Shizuku hoặc Root để thay đổi cài đặt hệ thống"
                : "Hệ thống từ chối thay đổi này";
    }

    // ---------- apply / restore ----------

    public static synchronized Result apply(Context c, Tweak t) {
        if (t.unsupportedReason != null) return new Result(false, t.unsupportedReason);
        if (isApplied(c, t.id)) return new Result(true, "Đã bật từ trước");
        JSONArray snap = new JSONArray();
        try {
            for (int i = 0; i < t.ops.size(); i++) {
                Op op = t.ops.get(i);
                if (!op.valid()) { restoreOps(snap); return new Result(false, "Cấu hình không hợp lệ"); }
                Val before = read(op);
                if (!before.ok) { restoreOps(snap); return new Result(false, needPrivilege(before.provider)); }
                JSONObject o = new JSONObject();
                o.put("kind", op.kind); o.put("ns", op.ns); o.put("key", op.key);
                o.put("prev", before.v == null ? JSONObject.NULL : before.v);
                snap.put(o);

                CommandExecutor.execute(putCmd(op, op.value));
                Val after = read(op);
                if (!after.ok || after.v == null || !op.value.equals(after.v)) {
                    restoreOps(snap);
                    return new Result(false, needPrivilege(before.provider));
                }
            }
        } catch (Throwable e) {
            restoreOps(snap);
            return new Result(false, "Lỗi: " + e.getMessage());
        }
        prefs(c).edit().putString("snap_" + t.id, snap.toString()).putBoolean("on_" + t.id, true).apply();
        return new Result(true, "Đã áp dụng và xác minh");
    }

    public static synchronized Result restore(Context c, Tweak t) {
        String raw = prefs(c).getString("snap_" + t.id, null);
        if (raw == null) {
            prefs(c).edit().putBoolean("on_" + t.id, false).apply();
            return new Result(true, "Không có gì để khôi phục");
        }
        try {
            restoreOps(new JSONArray(raw));
        } catch (Throwable ignored) {}
        prefs(c).edit().remove("snap_" + t.id).putBoolean("on_" + t.id, false).apply();
        return new Result(true, "Đã khôi phục giá trị ban đầu");
    }

    public static void restoreAll(Context c, List<Tweak> all) {
        for (Tweak t : all) if (isApplied(c, t.id)) restore(c, t);
    }

    /** Restores snapshot entries in reverse order. */
    private static void restoreOps(JSONArray snap) {
        for (int i = snap.length() - 1; i >= 0; i--) {
            try {
                JSONObject o = snap.getJSONObject(i);
                Op op = new Op(o.getString("kind"), o.getString("ns"), o.getString("key"), "x");
                if (!SAFE.matcher(op.ns).matches() || !SAFE.matcher(op.key).matches()) continue;
                if (o.isNull("prev")) {
                    CommandExecutor.execute(deleteCmd(op));
                } else {
                    String prev = o.getString("prev");
                    if (SAFE.matcher(prev).matches()) CommandExecutor.execute(putCmd(op, prev));
                }
            } catch (Throwable ignored) {}
        }
    }

    // ---------- one-shot actions ----------

    /** Runs the system's own background dexopt job (ART profile-guided compilation). */
    public static Result runArtBackgroundDexopt() {
        CommandExecutor.CommandResult r = CommandExecutor.execute("cmd package bg-dexopt-job");
        if (r.exitCode == 0) return new Result(true, "Đã yêu cầu hệ thống tối ưu ART nền (chạy ngầm, có thể mất vài phút)");
        return new Result(false, needPrivilege(r.provider));
    }
}
