package com.tanmahwuan.zrampurger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * BoosterService - Android Foreground Service maintaining high process priority
 * (oom_score_adj <= 100) while Extreme Performance Max / Game Booster is active.
 *
 * Ensures that 3D game workloads and LMKD pressure do not terminate the booster process,
 * and guarantees clean OEM state restoration upon service destruction.
 */
public class BoosterService extends Service {

    public static final String TAG = "ZRamPurger:BoosterService";
    public static final String CHANNEL_ID = "extreme_booster_channel";
    public static final int NOTIFICATION_ID = 5001;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "BoosterService created. Initializing notification channel...");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "BoosterService starting in foreground...");

        Intent notifyIntent = new Intent(this, MainActivity.class);
        notifyIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notifyIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Z-RAM Purger: Extreme Performance Max")
                .setContentText("Adreno 619 Turbo & Kryo EAS Priority Active")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setColor(Color.parseColor("#00E5FF"))
                .build();

        startForeground(NOTIFICATION_ID, notification);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "BoosterService onDestroy triggered.");
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Extreme Game Booster Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Ongoing status notification for Extreme Performance Max");
                channel.enableLights(true);
                channel.setLightColor(Color.CYAN);
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }
}
