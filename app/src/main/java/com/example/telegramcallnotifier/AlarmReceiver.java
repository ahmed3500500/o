package com.example.telegramcallnotifier;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;

public class AlarmReceiver extends BroadcastReceiver {

    private static final long MIN_DUPLICATE_GAP_MS = 3000L;
    private static final long PERIODIC_STATUS_INTERVAL_MS = 60 * 60 * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        DebugLogger.log(context, "AlarmReceiver", "onReceive action=" + (intent != null ? intent.getAction() : "null"));
        DebugLogger.logState(context, "AlarmReceiver", "alarm fired");

        SharedPreferences prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE);

        long now = System.currentTimeMillis();
        long lastHandledAt = prefs.getLong("last_alarm_handled_at", 0L);

        if (now - lastHandledAt < MIN_DUPLICATE_GAP_MS) {
            DebugLogger.log(context, "AlarmReceiver", "Duplicate alarm ignored. gapMs=" + (now - lastHandledAt));
            return;
        }

        prefs.edit().putLong("last_alarm_handled_at", now).apply();

        long lastPeriodicSentAt = prefs.getLong("last_periodic_sent_at", 0L);

        String reportType = "alarm";
        if ((now - lastPeriodicSentAt) >= PERIODIC_STATUS_INTERVAL_MS) {
            reportType = "periodic_status";
            prefs.edit().putLong("last_periodic_sent_at", now).apply();
        }

        DebugLogger.log(context, "AlarmReceiver", "reportType=" + reportType);

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;

        try {
            if (powerManager != null) {
                DebugLogger.log(context, "AlarmReceiver", "Trying to acquire WakeLock for 30 seconds");
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "TelegramCallNotifier:AlarmWakeLock"
                );
                wakeLock.acquire(30 * 1000L);
                DebugLogger.log(context, "AlarmReceiver", "WakeLock acquired");
            } else {
                DebugLogger.log(context, "AlarmReceiver", "powerManager is null");
            }

            Intent wakeIntent = new Intent(context, WakeActivity.class);
            wakeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            DebugLogger.log(context, "AlarmReceiver", "Starting WakeActivity");
            context.startActivity(wakeIntent);

            Intent serviceIntent = new Intent(context, ReportService.class);
            serviceIntent.setAction("ALARM_TRIGGER");
            serviceIntent.putExtra("reportType", reportType);

            if ("periodic_status".equals(reportType)) {
                DebugLogger.log(context, "AlarmReceiver", "Periodic report detected, delaying ReportService start by 3 seconds");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
                        DebugLogger.log(context, "AlarmReceiver", "Delayed ReportService start requested for periodic report");
                    } catch (Exception e) {
                        DebugLogger.logError(context, "AlarmReceiver", e);
                    }
                }, 10000);
            } else {
                try {
                    DebugLogger.log(context, "AlarmReceiver", "Starting ReportService for alarm report");
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                    DebugLogger.log(context, "AlarmReceiver", "ReportService start requested successfully");
                } catch (Exception e) {
                    DebugLogger.logError(context, "AlarmReceiver", e);
                }
            }

        } catch (Exception e) {
            DebugLogger.logError(context, "AlarmReceiver", e);
        } finally {
            DebugLogger.log(context, "AlarmReceiver", "Scheduling next alarm");
            AlarmScheduler.scheduleNext(context, AlarmScheduler.TEST_INTERVAL_MS);

            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                    DebugLogger.log(context, "AlarmReceiver", "WakeLock released");
                } catch (Exception e) {
                    DebugLogger.logError(context, "AlarmReceiver", e);
                }
            }
        }
    }
}
