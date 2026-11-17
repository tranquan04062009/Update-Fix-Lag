package com.tanmahwuan.zrampurger;

import java.util.ArrayList;
import java.util.List;

/** Small pure helpers for the UI (no Android dependency, unit-tested). */
public final class UiFormat {
    private UiFormat() {}

    /** "vừa xong", "5 phút trước", "3 giờ trước", "2 ngày trước". */
    public static String relativeTime(long nowMs, long thenMs) {
        long diff = Math.max(0, nowMs - thenMs);
        long min = diff / 60000L;
        if (min < 1) return "vừa xong";
        if (min < 60) return min + " phút trước";
        long hours = min / 60;
        if (hours < 24) return hours + " giờ trước";
        return (hours / 24) + " ngày trước";
    }

    /** Colour of the health chip: green / amber / red. */
    public static String healthColor(int score) {
        if (score >= 85) return "#2DD4B0";
        if (score >= 65) return "#F5A524";
        return "#F4566A";
    }

    /** Default FPS cap proposed for THIS display: its own maximum, never a fixed 120. */
    public static int defaultFps(float maxHz) {
        if (maxHz >= 115f) return 120;
        if (maxHz >= 85f) return 90;
        return 60;
    }

    /** FPS options the display can actually show: 0 (= system default) plus every step up to its maximum. */
    public static List<Integer> fpsChoices(float maxHz, int[] allSteps) {
        List<Integer> out = new ArrayList<>();
        for (int v : allSteps) if (v == 0 || v <= Math.round(maxHz) + 1) out.add(v);
        return out;
    }

    public static String ramSummary(int freedMb, int apps, long durationMs, String when) {
        return apps + " ứng dụng · " + String.format(java.util.Locale.US, "%.1fs", durationMs / 1000.0) + " · " + when;
    }
}
