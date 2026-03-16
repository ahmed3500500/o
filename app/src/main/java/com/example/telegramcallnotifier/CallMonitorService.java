package com.example.telegramcallnotifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Display;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;

public class CallMonitorService extends Service {

    private static final String CHANNEL_ID = "CallMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    private TelephonyManager telephonyManager;
    private PhoneStateListener phoneStateListener;
    // Keep strong references to listeners to prevent GC
    private java.util.List<PhoneStateListener> activeListeners = new java.util.ArrayList<>();
    private Object telephonyCallback; // For API 31+
    private TelegramSender telegramSender;
    // Removed heartbeat fields
    private long callStartTime = 0;
    private boolean isRinging = false;
    private String lastIncomingNumber = "";
    private PowerManager.WakeLock wakeLock;
    // Removed BroadcastReceiver to prevent conflict with SubscriptionManager

    // Debounce fields
    private Handler debounceHandler = new Handler(Looper.getMainLooper());
    private Runnable sendNotificationRunnable;
    private String pendingNumber = null;
    private int pendingSimSlot = -1;

    private final Handler pingHandler = new Handler(Looper.getMainLooper());
    private Runnable pingRunnable;
    private BroadcastReceiver connectivityReceiver;
    private volatile boolean retryInProgress = false;

    // Battery & Status Monitoring
    private BatteryReceiver batteryReceiver;
    private int lastBatteryLevel = -1;
    private boolean lastChargingState = false;
    private static final long PERIODIC_INTERVAL = 60 * 60 * 1000;
    private static final String ACTION_SEND_PERIODIC_REPORT = "com.example.telegramcallnotifier.ACTION_SEND_PERIODIC_REPORT";
    
    private final Handler periodicHandler = new Handler(Looper.getMainLooper());
    private final Runnable periodicRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                DebugLogger.log(CallMonitorService.this, "CallMonitorService", "periodicRunnable fired");
                sendPeriodicStatusReport();
            } finally {
                periodicHandler.postDelayed(this, PERIODIC_INTERVAL);
                scheduleNextReport();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        DebugLogger.log(this, "CallMonitorService", "onCreate");
        DebugLogger.logState(this, "CallMonitorService", "service create");
        createNotificationChannel();
        telegramSender = new TelegramSender(this);
        retryPendingNotifications();
        registerConnectivityReceiver();
        
        // Log Service Start (Local log only)
        CustomExceptionHandler.log(this, "Service onCreate");
        startPingTask();
        DebugLogger.log(this, "CallMonitorService", "startPingTask scheduled");

        // Acquire WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            try {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "CallMonitorService::WakeLock");
                wakeLock.acquire();
                DebugLogger.log(this, "CallMonitorService", "Main WakeLock acquired");
            } catch (Exception e) {
                Log.e("CallMonitorService", "Error acquiring WakeLock", e);
            }
        }

        // Start Foreground Service
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Call Monitor Active")
                .setContentText("Listening for incoming calls...")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentIntent(pendingIntent)
                .build();

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                DebugLogger.log(this, "CallMonitorService", "Foreground started (type DATA_SYNC)");
            } else {
                startForeground(NOTIFICATION_ID, notification);
                DebugLogger.log(this, "CallMonitorService", "Foreground started");
            }
        } catch (Throwable e) {
            Log.e("CallMonitorService", "Error starting foreground service", e);
            try {
                startForeground(NOTIFICATION_ID, notification);
                DebugLogger.log(this, "CallMonitorService", "Foreground started in fallback path");
            } catch (Throwable t) {
                Log.e("CallMonitorService", "Fatal error starting foreground", t);
            }
        }

        // Register Phone Listener
        telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        registerPhoneListener();
        DebugLogger.log(this, "CallMonitorService", "registerPhoneListener finished");
        
        // Removed registerCallReceiver() to rely solely on SubscriptionManager/PhoneStateListener
        // This prevents the "Unknown SIM" (-1) from overwriting the correct SIM slot.

        // Removed Heartbeat and Start Notification per user request

        // Initialize Battery & Status Monitoring
        DebugLogger.log(this, "CallMonitorService", "Sending service started status message");
        telegramSender.sendStatusMessage("Service started");
        startBatteryMonitoring();
        startPeriodicReporting();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "null";
        DebugLogger.log(this, "CallMonitorService", "onStartCommand action=" + action + " flags=" + flags + " startId=" + startId);
        DebugLogger.logState(this, "CallMonitorService", "onStartCommand");
        if (intent != null && ACTION_SEND_PERIODIC_REPORT.equals(intent.getAction())) {
            DebugLogger.log(this, "CallMonitorService", "ACTION_SEND_PERIODIC_REPORT triggered from onStartCommand");
            sendPeriodicStatusReport();
            restartInProcessPeriodicLoop();
            scheduleNextReport();
            return START_STICKY;
        }

        retryPendingNotifications();
        restartInProcessPeriodicLoop();
        scheduleNextReport();
        return START_STICKY;
    }

    // Removed registerCallReceiver() to avoid conflict with SubscriptionManager


    private void registerPhoneListener() {
        // Multi-SIM Support
        android.telephony.SubscriptionManager subscriptionManager = getSystemService(android.telephony.SubscriptionManager.class);
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            java.util.List<android.telephony.SubscriptionInfo> subList = subscriptionManager.getActiveSubscriptionInfoList();
            if (subList != null && !subList.isEmpty()) {
                // Clear existing listeners to avoid duplicates
                activeListeners.clear();
                for (android.telephony.SubscriptionInfo subInfo : subList) {
                    int subId = subInfo.getSubscriptionId();
                    int slotIndex = subInfo.getSimSlotIndex(); // 0 or 1
                    registerListenerForSub(subId, slotIndex + 1);
                }
            } else {
                // Fallback for single SIM or if list is empty
                DebugLogger.log(this, "CallMonitorService", "No active subscriptions found, fallback listener used");
                if (Build.VERSION.SDK_INT >= 31) {
                    registerTelephonyCallback();
                } else {
                    registerLegacyPhoneListener();
                }
            }
        } else {
            DebugLogger.log(this, "CallMonitorService", "READ_PHONE_STATE permission missing, cannot register multi-sim listeners");
        }
    }

    private void registerListenerForSub(int subId, int simSlot) {
        TelephonyManager subTm = telephonyManager.createForSubscriptionId(subId);
        
        try {
            PhoneStateListener listener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    CustomExceptionHandler.log(CallMonitorService.this,
                            "onCallStateChanged state=" + state + " number=" + phoneNumber + " sim=" + simSlot);
                    if (state == TelephonyManager.CALL_STATE_RINGING) {
                        CustomExceptionHandler.log(CallMonitorService.this,
                                "CALL_STATE_RINGING detected on SIM " + simSlot + " number=" + phoneNumber);

                        handleCallState(state, phoneNumber, simSlot);
                        return;
                    }
                    handleCallState(state, phoneNumber, simSlot);
                }
            };
            subTm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);
            activeListeners.add(listener); // Keep strong reference
            DebugLogger.log(this, "CallMonitorService", "Listener registered for subId=" + subId + " simSlot=" + simSlot);
        } catch (Exception e) {
            Log.e("CallMonitorService", "Error registering listener for SIM " + simSlot, e);
            CustomExceptionHandler.logError(CallMonitorService.this, e);
        }
    }

    private void registerLegacyPhoneListener() {
        try {
            phoneStateListener = new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    CustomExceptionHandler.log(CallMonitorService.this, "onCallStateChanged state=" + state + " number=" + phoneNumber);
                    if (state == TelephonyManager.CALL_STATE_RINGING) {
                        CustomExceptionHandler.log(CallMonitorService.this, "CALL_STATE_RINGING detected");
                        pendingNumber = phoneNumber;
                        if (sendNotificationRunnable != null) {
                            debounceHandler.removeCallbacks(sendNotificationRunnable);
                        }
                        processRingingCall();
                        return;
                    }
                    handleCallState(state, phoneNumber, -1);
                }
            };
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            DebugLogger.log(this, "CallMonitorService", "Legacy phone listener registered");
        } catch (SecurityException e) {
            DebugLogger.logError(this, "CallMonitorService", e);
            Log.e("CallMonitorService", "Permission missing for phone listener", e);
        } catch (Exception e) {
            Log.e("CallMonitorService", "Error registering legacy listener", e);
        }
    }

    private void registerTelephonyCallback() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                telephonyCallback = new CallStateCallback();
                telephonyManager.registerTelephonyCallback(getMainExecutor(), (TelephonyCallback) telephonyCallback);
                DebugLogger.log(this, "CallMonitorService", "TelephonyCallback registered");
            } catch (SecurityException e) {
            DebugLogger.logError(this, "CallMonitorService", e);
                 Log.e("CallMonitorService", "Permission missing for telephony callback", e);
            } catch (Exception e) {
                 Log.e("CallMonitorService", "Error registering telephony callback", e);
            }
        }
    }

    private class CallStateCallback extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int state) {
            CustomExceptionHandler.log(CallMonitorService.this, "onCallStateChanged state=" + state + " number=" + null);
            handleCallState(state, null, -1);
        }
    }
    
    private void handleCallState(int state, String incomingNumber, int simSlot) {
        // Debounce Logic for Ringing
        if (state == TelephonyManager.CALL_STATE_RINGING) {
            CustomExceptionHandler.log(this, "CALL_STATE_RINGING detected. incomingNumber=" + incomingNumber);
            CustomExceptionHandler.log(this, "RINGING on SIM slot=" + simSlot);
            
            // 1. Update pending data if available
            // Priority Logic: Prefer the event that contains a valid incoming number.
            // This helps filter out "Ghost" events where one SIM mirrors the other but without the number.
            boolean isNewInfoBetter = false;
            
            boolean newHasNumber = (incomingNumber != null && !incomingNumber.isEmpty() && !incomingNumber.equals("Unknown"));
            boolean currentHasNumber = (pendingNumber != null && !pendingNumber.equals("Unknown"));

            if (pendingSimSlot == -1) {
                isNewInfoBetter = true;
            } else {
                if (newHasNumber && !currentHasNumber) {
                    isNewInfoBetter = true;
                } else if (newHasNumber == currentHasNumber) {
                    // Both have numbers or both don't.
                    // Priority Logic: FIRST WRITE WINS.
                    // We assume the first event we receive (with a number) is the Real event,
                    // and subsequent events for other SIMs are likely "Ghost" mirrors.
                    // So we do NOT update if we already have a valid slot.
                    isNewInfoBetter = false;
                    CustomExceptionHandler.log(this, "Ignored potential Ghost event from SIM " + simSlot + " because we already have SIM " + pendingSimSlot);
                }
            }

            CustomExceptionHandler.log(this, "Slot Decision: Current=" + pendingSimSlot + " New=" + simSlot + " Better=" + isNewInfoBetter + " NewHasNum=" + newHasNumber);

            if (isNewInfoBetter) {
                if (simSlot != -1) {
                    pendingSimSlot = simSlot;
                }
                if (newHasNumber) {
                    pendingNumber = incomingNumber;
                }
            }

            // 2. If we don't have a runnable scheduled, schedule one
            if (sendNotificationRunnable != null) {
                // If we are already waiting, do not reset the timer just because another SIM event came in
                // UNLESS we want to extend it? 
                // Better to let the original timer finish to be responsive, 
                // as we now have the correct slot in pendingSimSlot.
                // However, ensuring we don't fire multiple runnables is key.
                // Current logic removes and reposts, which resets the timer. 
                // Let's keep resetting to ensure we have the latest stable state after 1000ms.
                debounceHandler.removeCallbacks(sendNotificationRunnable);
            }

            sendNotificationRunnable = new Runnable() {
                @Override
                public void run() {
                    processRingingCall();
                }
            };
            
            // Wait 1000ms to gather data (SIM + Number)
            debounceHandler.postDelayed(sendNotificationRunnable, 1000);
            CustomExceptionHandler.log(this, "Debounce scheduled for ringing call");
            retryPendingNotifications();
            
        } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
            // Cancel pending ringing notification if answered very quickly
            if (sendNotificationRunnable != null) {
                debounceHandler.removeCallbacks(sendNotificationRunnable);
                sendNotificationRunnable.run();
                sendNotificationRunnable = null;
            }
            
            CustomExceptionHandler.log(this, "Call Offhook. Scheduling Hangup in 5s.");
            // Call Answered
            // Start 5 second timer to hang up
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    attemptHangUp();
                }
            }, 5000);
            
            isRinging = false;
            retryPendingNotifications();
        } else if (state == TelephonyManager.CALL_STATE_IDLE) {
            // Only reset if the IDLE comes from the pending slot, or if it's a global IDLE (-1)
            // This prevents SIM 1 (Ghost) sending IDLE and cancelling SIM 2's valid Ringing state.
            if (simSlot == -1 || simSlot == pendingSimSlot) {
                isRinging = false;
                // Reset pending data
                pendingNumber = null;
                pendingSimSlot = -1;
                if (sendNotificationRunnable != null) {
                    debounceHandler.removeCallbacks(sendNotificationRunnable);
                }
            }
            retryPendingNotifications();
        }
    }
    
    private void processRingingCall() {
        try {
            sendNotificationRunnable = null;
            CustomExceptionHandler.log(this, "processRingingCall() START");
            CustomExceptionHandler.log(this, "Incoming number raw = " + pendingNumber);

            String number = pendingNumber;
            if (number == null || number.trim().isEmpty()) {
                number = "Unknown";
            }
            CustomExceptionHandler.log(this, "Incoming number final = " + number);
            CustomExceptionHandler.log(this, "Pending SIM slot = " + pendingSimSlot);

            if (lastIncomingNumber.equals(number) && isRinging) {
                CustomExceptionHandler.log(this, "processRingingCall() skipped: already handled. number=" + number);
                return;
            }

            if (pendingSimSlot == -1) {
                CustomExceptionHandler.log(this, "Pending SIM slot missing. Trying resolveSimSlot()");
                pendingSimSlot = resolveSimSlot();
                CustomExceptionHandler.log(this, "resolveSimSlot() result = " + pendingSimSlot);
            }

            isRinging = true;
            callStartTime = System.currentTimeMillis();
            lastIncomingNumber = number;

            String simInfo;
            if (pendingSimSlot != -1) {
                simInfo = (pendingSimSlot == 1) ? "SIM 1" :
                        (pendingSimSlot == 2) ? "SIM 2" :
                                "SIM " + pendingSimSlot;
            } else {
                simInfo = "Unknown SIM";
                if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    android.telephony.SubscriptionManager subscriptionManager = getSystemService(android.telephony.SubscriptionManager.class);
                    if (subscriptionManager != null) {
                        java.util.List<android.telephony.SubscriptionInfo> subs = subscriptionManager.getActiveSubscriptionInfoList();
                        if (subs != null && subs.size() == 1) {
                            simInfo = "SIM " + (subs.get(0).getSimSlotIndex() + 1);
                        }
                    }
                }
            }

            CustomExceptionHandler.log(this, "Detected line = " + simInfo);

            long ringTimeMillis = System.currentTimeMillis();
            String ringTimeText = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(ringTimeMillis));

            String msg = "📞 Incoming Call Detected!\n" +
                    "🔢 Number: " + number + "\n" +
                    "📱 Line: " + simInfo + "\n" +
                    "⏰ Time: " + ringTimeText;

            CustomExceptionHandler.log(this, "Call message built = " + msg.replace("\n", " | "));

            String notificationId = "call_" + ringTimeMillis;
            PendingNotificationManager.addPending(this, notificationId, msg);

            new Thread(() -> {
                boolean ok = telegramSender.sendMessageSync(msg);
                if (ok) {
                    PendingNotificationManager.markSent(CallMonitorService.this, notificationId);
                } else {
                    PendingNotificationManager.markRetry(CallMonitorService.this, notificationId);
                }
            }).start();

            CustomExceptionHandler.log(this, "Calling attemptAutoAnswer()");
            attemptAutoAnswer();
            retryPendingNotifications();

        } catch (Exception e) {
            CustomExceptionHandler.log(this, "processRingingCall exception: " + e.getMessage());
            CustomExceptionHandler.logError(this, e);
        }
    }

    private void retryPendingNotifications() {
        if (retryInProgress) return;
        retryInProgress = true;
        new Thread(() -> {
            try {
                if (!isNetworkConnected()) return;
                org.json.JSONArray arr = PendingNotificationManager.getAll(this);
                CustomExceptionHandler.log(this, "retryPendingNotifications count=" + arr.length());

                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) continue;

                    String id = obj.optString("id", "");
                    String text = obj.optString("text", "");
                    int retryCount = obj.optInt("retryCount", 0);
                    long lastTry = obj.optLong("lastTry", 0);

                    long now = System.currentTimeMillis();
                    long waitMs;
                    if (retryCount <= 0) waitMs = 5000;
                    else if (retryCount == 1) waitMs = 15000;
                    else if (retryCount == 2) waitMs = 30000;
                    else waitMs = 60000;

                    if (now - lastTry < waitMs) continue;

                    boolean ok = telegramSender.sendMessageSync(text);
                    if (ok) {
                        PendingNotificationManager.markSent(this, id);
                    } else {
                        PendingNotificationManager.markRetry(this, id);
                    }
                }
            } catch (Exception e) {
                CustomExceptionHandler.log(this, "retryPendingNotifications exception: " + e.getMessage());
                CustomExceptionHandler.logError(this, e);
            } finally {
                retryInProgress = false;
            }
        }).start();
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    private void registerConnectivityReceiver() {
        if (connectivityReceiver != null) return;
        try {
            connectivityReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (isNetworkConnected()) {
                        retryPendingNotifications();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(connectivityReceiver, filter);
        } catch (Exception e) {
            CustomExceptionHandler.log(this, "registerConnectivityReceiver error: " + e.getMessage());
            CustomExceptionHandler.logError(this, e);
        }
    }
    
    private int resolveSimSlot() {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return -1;
        }

        android.telephony.SubscriptionManager subscriptionManager = getSystemService(android.telephony.SubscriptionManager.class);
        if (subscriptionManager == null) return -1;

        java.util.List<android.telephony.SubscriptionInfo> subs = subscriptionManager.getActiveSubscriptionInfoList();
        if (subs == null || subs.isEmpty()) return -1;

        // Strategy 1: Check which specific Subscription ID is Ringing
        int bestSlot = -1;
        for (android.telephony.SubscriptionInfo sub : subs) {
            TelephonyManager subTm = telephonyManager.createForSubscriptionId(sub.getSubscriptionId());
            if (subTm.getCallState() == TelephonyManager.CALL_STATE_RINGING) {
                int slot = sub.getSimSlotIndex() + 1;
                CustomExceptionHandler.log(this, "Found Ringing SIM via polling: Slot " + slot);
                // Priority Logic: Pick the first one found if we don't have one yet.
                // If multiple are ringing, this might be ambiguous, but usually Slot 1 is checked first.
                if (bestSlot == -1) {
                    bestSlot = slot;
                }
            }
        }
        
        if (bestSlot != -1) {
            return bestSlot;
        }
        
        // Strategy 2: If only 1 SIM is active, assume it's that one
        if (subs.size() == 1) {
             return subs.get(0).getSimSlotIndex() + 1;
        }

        return -1;
    }

    private void wakeDeviceFor20Seconds() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                    "TelegramCallNotifier:WakeLock"
            );

            wakeLock.acquire(20000);
            CustomExceptionHandler.log(this, "Device wake for 20 seconds");
        } catch (Exception e) {
            CustomExceptionHandler.log(this, "wakeDevice error: " + e.getMessage());
        }
    }

    private void startPingTask() {
        pingRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    CustomExceptionHandler.log(CallMonitorService.this, "Ping server");
                    telegramSender.sendPing();
                    wakeDeviceFor20Seconds();
                    retryPendingNotifications();
                } catch (Exception e) {
                    CustomExceptionHandler.log(CallMonitorService.this, "Ping error: " + e.getMessage());
                }

                pingHandler.postDelayed(this, 10 * 60 * 1000);
            }
        };

        pingHandler.postDelayed(pingRunnable, 10 * 60 * 1000);
    }

    private void attemptAutoAnswer() {
        try {
            CustomExceptionHandler.log(this, "attemptAutoAnswer() START");

            boolean isDefault = isAppDefaultDialer();
            CustomExceptionHandler.log(this, "isAppDefaultDialer = " + isDefault);

            if (!isDefault) {
                CustomExceptionHandler.log(this, "Warning: app is NOT default dialer, trying anyway");
            }

            if (Build.VERSION.SDK_INT >= 26) {
                android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (tm != null) {
                    if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        try {
                            CustomExceptionHandler.log(this, "Calling TelecomManager.acceptRingingCall()");
                            tm.acceptRingingCall();
                            CustomExceptionHandler.log(this, "acceptRingingCall() invoked");
                        } catch (Exception e) {
                            Log.e("CallMonitorService", "Failed to answer call", e);
                            CustomExceptionHandler.log(this, "acceptRingingCall() EXCEPTION: " + e.getMessage());
                            CustomExceptionHandler.logError(this, e);
                        }
                    } else {
                        CustomExceptionHandler.log(this, "ANSWER_PHONE_CALLS permission not granted");
                    }
                } else {
                    CustomExceptionHandler.log(this, "TelecomManager is null");
                }
            } else {
                CustomExceptionHandler.log(this, "Unsupported SDK for TelecomManager.acceptRingingCall()");
            }

            try {
                Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK));
                sendOrderedBroadcast(intent, null);

                intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK));
                sendOrderedBroadcast(intent, null);
                CustomExceptionHandler.log(this, "Fallback headset hook broadcast sent");
            } catch (Exception e) {
                CustomExceptionHandler.log(this, "Fallback headset hook EXCEPTION: " + e.getMessage());
            }
        } catch (Throwable e) {
            CustomExceptionHandler.log(this, "attemptAutoAnswer exception: " + e.getMessage());
            CustomExceptionHandler.logError(this, e);
        }
    }

    private boolean isAppDefaultDialer() {
        try {
            if (Build.VERSION.SDK_INT < 23) return true;
            android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
            if (tm == null) return false;
            String defaultDialer = tm.getDefaultDialerPackage();
            return getPackageName().equals(defaultDialer);
        } catch (Throwable e) {
            CustomExceptionHandler.log(this, "isAppDefaultDialer exception: " + e.getMessage());
            CustomExceptionHandler.logError(this, e);
            return false;
        }
    }

    private void attemptHangUp() {
        if (Build.VERSION.SDK_INT >= 28) {
             android.telecom.TelecomManager tm = (android.telecom.TelecomManager) getSystemService(Context.TELECOM_SERVICE);
             if (tm != null) {
                 if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                     try {
                         tm.endCall();
                         CustomExceptionHandler.log(this, "Auto-ended call via TelecomManager");
                     } catch (Exception e) {
                         Log.e("CallMonitorService", "Failed to end call", e);
                         CustomExceptionHandler.logError(this, e);
                     }
                 }
             }
        }
    }

    private void startBatteryMonitoring() {
        batteryReceiver = new BatteryReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(batteryReceiver, filter);
    }

    private void stopBatteryMonitoring() {
        if (batteryReceiver != null) {
            unregisterReceiver(batteryReceiver);
            batteryReceiver = null;
        }
    }

    private void restartInProcessPeriodicLoop() {
        periodicHandler.removeCallbacks(periodicRunnable);
        periodicHandler.postDelayed(periodicRunnable, PERIODIC_INTERVAL);
    }

    private void startPeriodicReporting() {
        restartInProcessPeriodicLoop();
        scheduleNextReport();
    }

    private void scheduleNextReport() {
        android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, CallMonitorService.class);
        intent.setAction(ACTION_SEND_PERIODIC_REPORT);
        
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        long triggerAtMillis = System.currentTimeMillis() + PERIODIC_INTERVAL;
        DebugLogger.log(this, "CallMonitorService", "scheduleNextReport triggerAt=" + triggerAtMillis + " now=" + System.currentTimeMillis());

        if (alarmManager == null) {
            DebugLogger.log(this, "CallMonitorService", "scheduleNextReport skipped alarmManager is null");
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                );
                DebugLogger.log(this, "CallMonitorService", "scheduleNextReport used setAndAllowWhileIdle because exact alarm permission missing");
                return;
            }

            if (Build.VERSION.SDK_INT >= 23) {
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                DebugLogger.log(this, "CallMonitorService", "scheduleNextReport used setExactAndAllowWhileIdle");
            } else if (Build.VERSION.SDK_INT >= 19) {
                alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                DebugLogger.log(this, "CallMonitorService", "scheduleNextReport used setExact");
            } else {
                alarmManager.set(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
                DebugLogger.log(this, "CallMonitorService", "scheduleNextReport used set");
            }
        } catch (SecurityException e) {
            DebugLogger.logError(this, "CallMonitorService", e);
            try {
                alarmManager.setAndAllowWhileIdle(
                        android.app.AlarmManager.RTC_WAKEUP,
                        triggerAtMillis,
                        pendingIntent
                );
                DebugLogger.log(this, "CallMonitorService", "scheduleNextReport fallback setAndAllowWhileIdle after SecurityException");
            } catch (Exception inner) {
                DebugLogger.logError(this, "CallMonitorService", inner);
                inner.printStackTrace();
            }
        } catch (Exception e) {
            DebugLogger.logError(this, "CallMonitorService", e);
        }
    }

    private void stopPeriodicReporting() {
        periodicHandler.removeCallbacks(periodicRunnable);

        android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(this, CallMonitorService.class);
        intent.setAction(ACTION_SEND_PERIODIC_REPORT);
        PendingIntent pendingIntent = PendingIntent.getService(this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
            DebugLogger.log(this, "CallMonitorService", "Periodic report alarm canceled");
        }
    }

    private class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                handleBatteryChanged(intent);
            } else if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                sendBatteryAlert("🔋 Battery Status", "⚡ Charging: Yes\n🔌 Charger Connected");
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                sendBatteryAlert("🔋 Battery Status", "⚡ Charging: No\n🔌 Charger Disconnected");
            }
        }
    }

    private void handleBatteryChanged(Intent intent) {
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL;
        
        if (level != -1 && scale != -1) {
            int pct = (int) ((level / (float) scale) * 100);
            
            // Check Thresholds (20, 15, 10, 5)
            // We only alert if we drop TO or BELOW a threshold, and we weren't already there/below in the last check (or if it's a fresh start)
            // To avoid spam, we track lastBatteryLevel.
            
            if (lastBatteryLevel != -1) {
                checkThreshold(lastBatteryLevel, pct, 20);
                checkThreshold(lastBatteryLevel, pct, 15);
                checkThreshold(lastBatteryLevel, pct, 10);
                checkThreshold(lastBatteryLevel, pct, 5);
            }
            
            lastBatteryLevel = pct;
            lastChargingState = isCharging;
        }
    }

    private void checkThreshold(int oldLevel, int newLevel, int threshold) {
        if (oldLevel > threshold && newLevel <= threshold) {
            sendBatteryAlert("⚠️ Battery Low!", "📉 Level: " + newLevel + "%");
        }
    }

    private void sendBatteryAlert(String title, String extraInfo) {
        DebugLogger.log(this, "CallMonitorService", "sendPeriodicStatusReport called");
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String batteryStatus = getBatteryInfoString();
        
        StringBuilder msg = new StringBuilder();
        msg.append(title).append("\n");
        msg.append(batteryStatus).append("\n");
        if (extraInfo != null && !extraInfo.isEmpty()) {
            msg.append(extraInfo).append("\n");
        }
        msg.append("⏰ Time: ").append(time);
        
        telegramSender.sendStatusMessage(msg.toString());
        retryPendingNotifications();
    }

    private void sendPeriodicStatusReport() {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        
        StringBuilder msg = new StringBuilder();
        msg.append("📊 Periodic Status Report\n");
        msg.append(getBatteryInfoString()).append("\n");
        msg.append(getNetworkStatusString()).append("\n");
        msg.append(getScreenStatusString()).append("\n");
        msg.append("⏰ Time: ").append(time);
        
        telegramSender.sendStatusMessage(msg.toString());
        retryPendingNotifications();
    }

    private String getBatteryInfoString() {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        
        if (batteryStatus == null) return "🔋 Battery: Unknown";
        
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int pct = (int) ((level / (float) scale) * 100);
        
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL;
        
        int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        String type = "Unknown";
        if (chargePlug == BatteryManager.BATTERY_PLUGGED_USB) type = "USB";
        else if (chargePlug == BatteryManager.BATTERY_PLUGGED_AC) type = "AC";
        else if (chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS) type = "Wireless";
        
        return "🔢 Battery: " + pct + "%\n" +
               "⚡ Charging: " + (isCharging ? "Yes" : "No") + 
               (isCharging ? ("\n🔌 Type: " + type) : "");
    }

    private String getNetworkStatusString() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "📶 Network: Unknown";
        
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        boolean isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        String netType = isConnected ? activeNetwork.getTypeName() : "None";
        
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        boolean isWifiEnabled = (wifiManager != null && wifiManager.isWifiEnabled());
        
        return "📶 Network: " + (isConnected ? "Connected (" + netType + ")" : "Disconnected") + "\n" +
               "🌐 Wi-Fi: " + (isWifiEnabled ? "On" : "Off");
    }

    private String getScreenStatusString() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = false;
        if (Build.VERSION.SDK_INT >= 20) {
            isScreenOn = pm.isInteractive();
        } else {
            isScreenOn = pm.isScreenOn();
        }
        
        return "📱 Screen: " + (isScreenOn ? "On" : "Off");
    }


    private String getNetworkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "Unknown";
            
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            if (activeNetwork != null && activeNetwork.isConnected()) {
                if (activeNetwork.getType() == ConnectivityManager.TYPE_WIFI) return "WiFi";
                if (activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE) return "Mobile Data";
                return "Connected";
            }
            return "No Internet";
        } catch (Exception e) {
            Log.e("CallMonitorService", "Error checking network", e);
            return "Unknown (Error)";
        }
    }

    private String formatDuration(long seconds) {
        long absSeconds = Math.abs(seconds);
        String positive = String.format(
                "%d:%02d:%02d",
                absSeconds / 3600,
                (absSeconds % 3600) / 60,
                absSeconds % 60);
        return positive;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Call Monitor Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        DebugLogger.log(this, "CallMonitorService", "onDestroy");
        DebugLogger.logState(this, "CallMonitorService", "service destroy");
        
        stopBatteryMonitoring();
        stopPeriodicReporting();
        if (pingRunnable != null) {
            pingHandler.removeCallbacks(pingRunnable);
        }
        if (connectivityReceiver != null) {
            try {
                unregisterReceiver(connectivityReceiver);
            } catch (Exception ignored) {
            }
            connectivityReceiver = null;
        }

        // Removed callReceiver unregister
        if (telephonyManager != null) {
            // Unregister all multi-sim listeners
            for (PhoneStateListener listener : activeListeners) {
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
            }
            activeListeners.clear();

            if (Build.VERSION.SDK_INT >= 31 && telephonyCallback != null) {
                telephonyManager.unregisterTelephonyCallback((TelephonyCallback) telephonyCallback);
            } else if (phoneStateListener != null) {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            DebugLogger.log(this, "CallMonitorService", "Main WakeLock released");
        }
        DebugLogger.log(this, "CallMonitorService", "onDestroy cleanup finished");
        // Removed stop notification
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
