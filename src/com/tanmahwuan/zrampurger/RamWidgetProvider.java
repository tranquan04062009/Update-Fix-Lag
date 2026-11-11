package com.tanmahwuan.zrampurger;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.RemoteViews;
import android.widget.Toast;

public class RamWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_BOOST = "com.tanmahwuan.zrampurger.ACTION_BOOST";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId);
        }
    }

    public static void updateAppWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        MemoryHelper.MemoryStats stats = MemoryHelper.readMemInfo();
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);

        views.setTextViewText(R.id.widget_ram_percent, stats.getMemPercent() + "% RAM");
        views.setTextViewText(R.id.widget_ram_avail, "Khả dụng: " + (stats.memAvailableKb / 1024) + " MB");

        // Click on widget body opens MainActivity
        Intent openAppIntent = new Intent(context, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openAppPending = PendingIntent.getActivity(
                context, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_info_container, openAppPending);

        // Click on Boost button triggers ACTION_BOOST broadcast
        Intent boostIntent = new Intent(context, RamWidgetProvider.class);
        boostIntent.setAction(ACTION_BOOST);
        PendingIntent boostPending = PendingIntent.getBroadcast(
                context, 1, boostIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_boost_btn, boostPending);

        appWidgetManager.updateAppWidget(appWidgetId, views);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (ACTION_BOOST.equals(intent.getAction())) {
            new Thread(() -> {
                PurgeEngine.PurgeResult result = PurgeEngine.executePurge(context, 2, null);

                // Haptic feedback
                try {
                    Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                    if (v != null && v.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            v.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            v.vibrate(80);
                        }
                    }
                } catch (Throwable ignored) {}

                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(context, "⚡ Z-RAM Purged: +" + result.freedMb + " MB RAM!", Toast.LENGTH_SHORT).show();

                    AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
                    ComponentName componentName = new ComponentName(context, RamWidgetProvider.class);
                    int[] appWidgetIds = appWidgetManager.getAppWidgetIds(componentName);
                    for (int appWidgetId : appWidgetIds) {
                        updateAppWidget(context, appWidgetManager, appWidgetId);
                    }
                });
            }).start();
        }
    }
}
