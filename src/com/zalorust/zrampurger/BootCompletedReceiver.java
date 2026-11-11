package com.zalorust.zrampurger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * BootCompletedReceiver - Failsafe boot receiver for Extreme Performance Max.
 *
 * Ensures that if a device rebooted or crashed while booster optimizations were active,
 * the OEM baseline settings and standby states are cleanly recovered without user intervention.
 */
public class BootCompletedReceiver extends BroadcastReceiver {

    public static final String TAG = "ZRamPurger:BoosterEngine";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.i(TAG, "BootCompletedReceiver triggered with action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {

            // Asynchronously verify and recover dangling snapshot
            new Thread(() -> {
                try {
                    Log.i(TAG, "Inspecting system for dangling booster snapshot post-boot...");
                    GameBoosterManager.checkAndRecoverColdStart(context);
                } catch (Throwable t) {
                    Log.e(TAG, "Error executing cold-start recovery on boot", t);
                }
            }).start();
        }
    }
}
