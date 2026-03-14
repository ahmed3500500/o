package com.example.telegramcallnotifier;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        String action = intent.getAction();
        Log.d(TAG, "Received action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) {

            AlarmScheduler.scheduleNext(context, AlarmScheduler.TEST_INTERVAL_MS);

            try {
                Intent reportIntent = new Intent(context, ReportService.class);
                reportIntent.setAction("START_FOREGROUND_SERVICE");

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(reportIntent);
                } else {
                    context.startService(reportIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start service after boot", e);
            }

            try {
                Intent callMonitorIntent = new Intent(context, CallMonitorService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(callMonitorIntent);
                } else {
                    context.startService(callMonitorIntent);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start CallMonitorService after boot", e);
            }

            Log.d(TAG, "Foreground service + alarm started after boot");
        }
    }
}
