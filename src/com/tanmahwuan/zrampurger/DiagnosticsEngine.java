package com.tanmahwuan.zrampurger;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lag diagnosis based only on signals that can be read reliably on any device.
 * A signal that cannot be read is skipped (never guessed) and does not affect the score.
 */
public class DiagnosticsEngine {

    public static class Finding {
        public final int penalty;     // points removed from the score
        public final String text;
        Finding(int penalty, String text) { this.penalty = penalty; this.text = text; }
    }

    public static class Report {
        public int score = 100;
        public final List<Finding> findings = new ArrayList<>();
        public int checked;

        public String verdict() {
            if (score >= 85) return "Tốt";
            if (score >= 65) return "Khá";
            if (score >= 40) return "Cần chú ý";
            return "Kém";
        }

        public String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append("Điểm hiệu năng: ").append(score).append("/100 · ").append(verdict())
              .append("\n(đã kiểm tra ").append(checked).append(" chỉ số đọc được trên máy này)");
            if (findings.isEmpty()) {
                sb.append("\n\nKhông phát hiện vấn đề nào ảnh hưởng đến độ mượt.");
            } else {
                for (Finding f : findings) sb.append("\n\n• ").append(f.text);
            }
            return sb.toString();
        }
    }

    private static void add(Report r, int penalty, String text) {
        r.findings.add(new Finding(penalty, text));
        r.score -= penalty;
    }

    /** Blocking; run on a background thread. */
    public static Report run(Context ctx, DeviceProfiler.Profile profile) {
        Report r = new Report();
        ContentResolver cr = ctx.getContentResolver();

        // 1. RAM available
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            r.checked++;
            double pct = 100.0 * mi.availMem / Math.max(1, mi.totalMem);
            if (mi.lowMemory || pct < 10) {
                add(r, 20, String.format(Locale.US, "RAM trống chỉ còn %.0f%% — hệ thống đang phải kill app liên tục. Hãy dùng \"Giải phóng RAM\".", pct));
            } else if (pct < 18) {
                add(r, 10, String.format(Locale.US, "RAM trống thấp (%.0f%%). Nên dọn RAM trước khi chơi game hoặc dùng app nặng.", pct));
            }
        } catch (Throwable ignored) {}

        // 2. Memory pressure (PSI) — only when readable
        String psi = DeviceProfiler.readAll("/proc/pressure/memory");
        double some = DeviceProfiler.parsePsiSomeAvg10(psi);
        if (some >= 0) {
            r.checked++;
            if (some > 10) add(r, 15, String.format(Locale.US, "Áp lực bộ nhớ cao (PSI %.1f%%): tiến trình đang chờ bộ nhớ, đây là nguyên nhân giật trực tiếp.", some));
            else if (some > 4) add(r, 7, String.format(Locale.US, "Áp lực bộ nhớ trung bình (PSI %.1f%%).", some));
        }

        // 3. Storage free space on /data
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            double free = 100.0 * fs.getAvailableBytes() / Math.max(1, fs.getTotalBytes());
            r.checked++;
            if (free < 10) add(r, 20, String.format(Locale.US, "Bộ nhớ trong chỉ còn %.0f%% trống — ổ đầy làm ghi/đọc chậm và app khởi động lâu. Hãy xóa bớt dữ liệu.", free));
            else if (free < 20) add(r, 8, String.format(Locale.US, "Bộ nhớ trong còn %.0f%% trống, nên giữ trên 20%% để máy chạy mượt.", free));
        } catch (Throwable ignored) {}

        // 4. Thermal throttling (public API, Android 10+)
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= 29) {
                int st = pm.getCurrentThermalStatus();
                r.checked++;
                if (st >= PowerManager.THERMAL_STATUS_SEVERE) add(r, 25, "Máy đang quá nóng và bị giới hạn xung nhịp mạnh. Hãy tháo ốp, ngừng sạc và để máy nguội.");
                else if (st >= PowerManager.THERMAL_STATUS_MODERATE) add(r, 12, "Máy đang nóng và bắt đầu giảm hiệu năng (thermal throttling).");
            }
            // 5. Power saver
            r.checked++;
            if (pm.isPowerSaveMode()) add(r, 10, "Chế độ Tiết kiệm pin đang bật: CPU bị giới hạn nên máy chạy chậm hơn.");
        } catch (Throwable ignored) {}

        // 6. Developer options that hurt smoothness
        try {
            r.checked++;
            if (Settings.Global.getInt(cr, "always_finish_activities", 0) == 1)
                add(r, 15, "\"Không giữ hoạt động\" trong Tùy chọn nhà phát triển đang BẬT: mọi app phải tải lại mỗi lần chuyển qua lại. Nên tắt.");
            float anim = Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            if (anim > 1.0f) add(r, 5, String.format(Locale.US, "Tốc độ hoạt ảnh đang là %.1fx, chậm hơn mặc định. Đặt 1x hoặc 0.5x trong tab Cài đặt.", anim));
        } catch (Throwable ignored) {}

        // 7. Refresh rate limited below display capability
        try {
            if (profile != null && profile.supportsHighRefresh()) {
                float peak = Settings.System.getFloat(cr, "peak_refresh_rate", 0f);
                r.checked++;
                if (peak > 0 && peak < profile.maxRefreshHz - 1)
                    add(r, 4, "Màn hình hỗ trợ " + Math.round(profile.maxRefreshHz) + " Hz nhưng đang bị giới hạn ở " + Math.round(peak) + " Hz. Bật \"Tần số quét tối đa\" để mượt hơn.");
            }
        } catch (Throwable ignored) {}

        // 8. Very long uptime
        long days = SystemClock.elapsedRealtime() / (24L * 3600 * 1000);
        r.checked++;
        if (days >= 7) add(r, 5, "Máy chưa khởi động lại " + days + " ngày. Khởi động lại giúp giải phóng bộ nhớ bị phân mảnh.");

        if (r.score < 0) r.score = 0;
        return r;
    }
}
