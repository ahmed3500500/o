package com.example.telegramcallnotifier;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

public class ReportService extends Service {

    private static final String TAG = "ReportService";
    private static final String CHANNEL_ID = "report_service_channel";
    private static final int NOTIFICATION_ID = 2001;
    private TelegramSender telegramSender;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        telegramSender = new TelegramSender(this);
        DebugLogger.log(this, TAG, "onCreate");
        DebugLogger.logState(this, TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "null";
        String reportType = intent != null ? intent.getStringExtra("reportType") : "alarm";

        DebugLogger.log(this, TAG, "onStartCommand action=" + action + " flags=" + flags + " startId=" + startId + " reportType=" + reportType);
        DebugLogger.logState(this, TAG, "onStartCommand");

        try {
            startForeground(NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Telegram Call Notifier")
                    .setContentText("Service running in background")
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setOngoing(true)
                    .build());

            DebugLogger.log(this, TAG, "startForeground success");
        } catch (Exception e) {
            DebugLogger.logError(this, TAG, e);
        }

        if (!"ALARM_TRIGGER".equals(action)) {
            DebugLogger.log(this, TAG, "Background task skipped because action=" + action);
            return START_STICKY;
        }

        final String finalReportType = reportType;

        new Thread(() -> {
            try {
                DebugLogger.log(ReportService.this, TAG, "Background task started. reportType=" + finalReportType);

                if ("periodic_status".equals(finalReportType)) {
                    sendPeriodicStatusReportNow();
                } else {
                    sendReportNow();
                }
            } catch (Exception e) {
                DebugLogger.logError(ReportService.this, TAG, e);
            }
        }, "ReportServiceWorker").start();

        return START_STICKY;
    }

    private void sendReportNow() {
        try {
            String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date());

            String msg =
                    "⏰ Alarm report\n" +
                            "Time: " + time;

            DebugLogger.log(this, TAG, "sendReportNow sending alarm report time=" + time);

            telegramSender.sendStatusMessage(msg);
        } catch (Exception e) {
            DebugLogger.logError(this, TAG, e);
        }
    }

    private void sendPeriodicStatusReportNow() {
        try {
            int battery = DebugLogger.getBatteryPercent(this);
            boolean charging = DebugLogger.isCharging(this);
            String network = DebugLogger.getNetworkSummary(this);
            boolean wifiEnabled = DebugLogger.isWifiEnabled(this);
            boolean screenOn = DebugLogger.isInteractive(this);

            String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                    .format(new java.util.Date());

            String msg =
                    "📊 Periodic Status Report\n" +
                            "🔢 Battery: " + battery + "%\n" +
                            "⚡️ Charging: " + (charging ? "Yes" : "No") + "\n" +
                            "📶 Network: " + network + "\n" +
                            "🌐 Wi-Fi: " + (wifiEnabled ? "On" : "Off") + "\n" +
                            "📱 Screen: " + (screenOn ? "On" : "Off") + "\n" +
                            "⏰ Time: " + time;

            DebugLogger.log(this, TAG, "sendPeriodicStatusReportNow building message time=" + time + " network=" + network + " battery=" + battery);
            DebugLogger.log(this, TAG, "sendPeriodicStatusReportNow sending");

            telegramSender.sendStatusMessage(msg);
        } catch (Exception e) {
            DebugLogger.logError(this, TAG, e);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Report Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DebugLogger.log(this, TAG, "onDestroy");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
