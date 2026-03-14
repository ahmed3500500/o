package com.example.telegramcallnotifier;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("AlarmReceiver", "Alarm fired");

        SharedPreferences prefs = context.getSharedPreferences("alarm_prefs", Context.MODE_PRIVATE);
        int alarmCounter = prefs.getInt("alarm_counter", 0);

        alarmCounter++;

        boolean sendTelegram = false;

        if (alarmCounter >= 30) {
            sendTelegram = true;
            alarmCounter = 0;
        }

        prefs.edit().putInt("alarm_counter", alarmCounter).apply();

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;

        try {
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "TelegramCallNotifier:AlarmWakeLock"
                );
                wakeLock.acquire(30 * 1000L);
            }

            Intent wakeIntent = new Intent(context, WakeActivity.class);
            wakeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(wakeIntent);

            Intent serviceIntent = new Intent(context, ReportService.class);
            serviceIntent.setAction("ALARM_TRIGGER");
            serviceIntent.putExtra("sendTelegram", sendTelegram);

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } catch (Exception e) {
                Log.e("AlarmReceiver", "Failed to start ReportService", e);
            }

        } catch (Exception e) {
            Log.e("AlarmReceiver", "Error in alarm receiver", e);
        } finally {
            AlarmScheduler.scheduleNext(context, AlarmScheduler.TEST_INTERVAL_MS);

            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
