package com.tanmahwuan.zrampurger;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Builds the "Diagnosis" and "AOSP tweaks" cards programmatically inside the Settings tab and
 * fills the device-info text with the REAL profile of the current device.
 */
public class PerfPanel {

    private static final int TEXT = Color.parseColor("#F3F5F9");
    private static final int DIM = Color.parseColor("#A3ABBB");
    private static final int MUTED = Color.parseColor("#6B7385");
    private static final int ACCENT = Color.parseColor("#2DD4B0");
    private static final int WARN = Color.parseColor("#F5A524");

    private final Activity act;
    private final Handler ui;
    private DeviceProfiler.Profile profile;
    private List<TweakEngine.Tweak> tweaks;

    private PerfPanel(Activity act, Handler ui) {
        this.act = act;
        this.ui = ui;
    }

    public static void attach(Activity act, LinearLayout container, TextView deviceInfo, Handler ui) {
        if (container == null) return;
        PerfPanel p = new PerfPanel(act, ui);
        deviceInfo.setText("Đang đọc thông tin thiết bị…");
        new Thread(() -> {
            DeviceProfiler.Profile prof = DeviceProfiler.read(act);
            ui.post(() -> {
                p.profile = prof;
                p.tweaks = TweakEngine.catalog(prof);
                deviceInfo.setText(prof.toDisplayText());
                p.build(container, prof);
            });
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(act);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        c.setBackgroundResource(R.drawable.bg_card_bento);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView text(String s, int color, float sp, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String label, int color) {
        Button b = new Button(act);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTextSize(13);
        b.setStateListAnimator(null);
        b.setBackgroundResource(R.drawable.bg_btn_secondary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(12);
        b.setLayoutParams(lp);
        return b;
    }

    private void build(LinearLayout root, DeviceProfiler.Profile prof) {
        root.removeAllViews();
        root.addView(diagnosisCard());
        root.addView(tweakCard());
        root.addView(copyCard());
    }

    // ---------- Diagnosis ----------
    private View diagnosisCard() {
        LinearLayout c = card();
        c.addView(text("Chẩn đoán lag", TEXT, 16, true));
        c.addView(text("Kiểm tra RAM, áp lực bộ nhớ, ổ lưu trữ, nhiệt độ và các cài đặt làm máy chậm.", MUTED, 12, false));
        TextView result = text("", DIM, 13, false);
        result.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        result.setLayoutParams(rlp);
        result.setVisibility(View.GONE);
        Button run = button("Chạy chẩn đoán", ACCENT);
        run.setOnClickListener(v -> {
            run.setEnabled(false);
            run.setText("Đang kiểm tra…");
            new Thread(() -> {
                DiagnosticsEngine.Report rep = DiagnosticsEngine.run(act, profile);
                ui.post(() -> {
                    result.setText(rep.toText());
                    result.setTextColor(rep.score >= 65 ? TEXT : WARN);
                    result.setVisibility(View.VISIBLE);
                    run.setEnabled(true);
                    run.setText("Chạy lại");
                });
            }).start();
        });
        c.addView(run);
        c.addView(result);
        return c;
    }

    // ---------- Tweaks ----------
    private View tweakCard() {
        LinearLayout c = card();
        c.addView(text("Tinh chỉnh hệ thống (AOSP)", TEXT, 16, true));
        c.addView(text("Mỗi mục lưu lại giá trị cũ và đọc lại để xác minh. Chỉ hiện khả dụng khi máy hỗ trợ.", MUTED, 12, false));

        for (TweakEngine.Tweak t : tweaks) c.addView(tweakRow(t));

        Button art = button("Tối ưu ART nền (bg-dexopt)", Color.parseColor("#F3F5F9"));
        art.setOnClickListener(v -> new Thread(() -> {
            TweakEngine.Result r = TweakEngine.runArtBackgroundDexopt();
            ui.post(() -> Toast.makeText(act, r.message, Toast.LENGTH_LONG).show());
        }).start());
        c.addView(art);

        Button restore = button("Khôi phục tất cả về ban đầu", Color.parseColor("#F4566A"));
        restore.setOnClickListener(v -> new Thread(() -> {
            TweakEngine.restoreAll(act, tweaks);
            ui.post(() -> {
                Toast.makeText(act, "Đã khôi phục các tinh chỉnh", Toast.LENGTH_SHORT).show();
                build((LinearLayout) c.getParent(), profile);
            });
        }).start());
        c.addView(restore);
        return c;
    }

    private View tweakRow(TweakEngine.Tweak t) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        row.setLayoutParams(rlp);

        LinearLayout left = new LinearLayout(act);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(text(t.title, TEXT, 14, true));
        TextView desc = text(t.desc, MUTED, 12, false);
        left.addView(desc);
        TextView status = text(t.unsupportedReason != null ? "Không khả dụng: " + t.unsupportedReason : "", WARN, 11.5f, false);
        status.setVisibility(t.unsupportedReason != null ? View.VISIBLE : View.GONE);
        left.addView(status);

        Switch sw = new Switch(act);
        sw.setShowText(false);
        sw.setSplitTrack(false);
        sw.setTrackDrawable(act.getDrawable(R.drawable.switch_track));
        sw.setThumbDrawable(act.getDrawable(R.drawable.switch_thumb));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(12);
        sw.setLayoutParams(slp);

        boolean supported = t.unsupportedReason == null;
        sw.setEnabled(supported);
        row.setAlpha(supported ? 1f : 0.55f);
        sw.setChecked(TweakEngine.isApplied(act, t.id));

        bindSwitch(sw, t);

        row.addView(left);
        row.addView(sw);
        return row;
    }

    /** Installs the toggle listener; re-installed after every programmatic state change. */
    private void bindSwitch(Switch sw, TweakEngine.Tweak t) {
        sw.setOnCheckedChangeListener((btn, on) -> {
            sw.setEnabled(false);
            new Thread(() -> {
                TweakEngine.Result r = on ? TweakEngine.apply(act, t) : TweakEngine.restore(act, t);
                ui.post(() -> {
                    sw.setOnCheckedChangeListener(null);
                    sw.setChecked(TweakEngine.isApplied(act, t.id));
                    sw.setEnabled(true);
                    Toast.makeText(act, r.message, Toast.LENGTH_LONG).show();
                    bindSwitch(sw, t);
                });
            }).start();
        });
    }

    // ---------- Copy device info ----------
    private View copyCard() {
        Button b = button("Sao chép thông tin thiết bị", DIM);
        b.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("device", profile.toDisplayText()));
            Toast.makeText(act, "Đã sao chép", Toast.LENGTH_SHORT).show();
        });
        return b;
    }
}
