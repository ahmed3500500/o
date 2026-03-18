package com.example.telegramcallnotifier;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.telephony.TelephonyManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class DebugLogger {

    private static final String LOG_FILE_NAME = "debug_log.txt";
    private static final String LOG_OLD_FILE_NAME = "debug_log_old.txt";
    private static final long MAX_LOG_SIZE = 2L * 1024L * 1024L;

    public static synchronized void log(Context context, String tag, String message) {
        writeLine(context, tag, message, null);
    }

    public static synchronized void logError(Context context, String tag, Throwable throwable) {
        writeLine(context, tag, "ERROR " + throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage()), throwable);
    }

    public static synchronized void logState(Context context, String tag, String reason) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            ActivityManager act = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

            boolean isIgnoringBattery = false;
            boolean isInteractive = false;
            boolean isDeviceIdle = false;
            boolean isPowerSaveMode = false;
            boolean canExactAlarm = true;
            int battery = -1;
            String networkText = "unknown";
            String callState = "unknown";
            long availMemMb = -1;
            boolean lowMem = false;

            if (pm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    isIgnoringBattery = pm.isIgnoringBatteryOptimizations(context.getPackageName());
                    isDeviceIdle = pm.isDeviceIdleMode();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                    isInteractive = pm.isInteractive();
                } else {
                    isInteractive = pm.isScreenOn();
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    isPowerSaveMode = pm.isPowerSaveMode();
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am != null) {
                canExactAlarm = am.canScheduleExactAlarms();
            }

            if (bm != null) {
                battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }

            if (cm != null) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null) {
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            networkText = "WIFI";
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            networkText = "MOBILE";
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                            networkText = "ETHERNET";
                        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                            networkText = "BLUETOOTH";
                        } else {
                            networkText = "OTHER";
                        }
                    } else {
                        networkText = "NO_CAPABILITIES";
                    }
                } else {
                    networkText = "NO_ACTIVE_NETWORK";
                }
            }

            if (act != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                act.getMemoryInfo(mi);
                availMemMb = mi.availMem / (1024L * 1024L);
                lowMem = mi.lowMemory;
            }

            if (tm != null) {
                try {
                    int state = tm.getCallState();
                    if (state == TelephonyManager.CALL_STATE_IDLE) callState = "IDLE";
                    else if (state == TelephonyManager.CALL_STATE_RINGING) callState = "RINGING";
                    else if (state == TelephonyManager.CALL_STATE_OFFHOOK) callState = "OFFHOOK";
                    else callState = String.valueOf(state);
                } catch (SecurityException ignored) {
                    callState = "NO_PERMISSION";
                }
            }

            log(context, tag,
                    "STATE reason=" + reason +
                            " sdk=" + Build.VERSION.SDK_INT +
                            " manufacturer=" + Build.MANUFACTURER +
                            " model=" + Build.MODEL +
                            " battery=" + battery +
                            " interactive=" + isInteractive +
                            " idleMode=" + isDeviceIdle +
                            " powerSave=" + isPowerSaveMode +
                            " ignoringBatteryOpt=" + isIgnoringBattery +
                            " canExactAlarm=" + canExactAlarm +
                            " network=" + networkText +
                            " callState=" + callState +
                            " availMemMb=" + availMemMb +
                            " lowMem=" + lowMem +
                            " appPkg=" + context.getPackageName());
        } catch (Exception e) {
            logError(context, tag, e);
        }
    }

    public static int getBatteryPercent(Context context) {
        try {
            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (pct >= 0) {
                    return pct;
                }
            }

            Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent == null) {
                return 0;
            }
            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level <= 0 || scale <= 0) {
                return 0;
            }
            return Math.round((level * 100f) / scale);
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean isCharging(Context context) {
        try {
            Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent == null) {
                return false;
            }
            int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Exception e) {
            return false;
        }
    }

    public static String getNetworkSummary(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return "unknown";
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) {
                    return "NO_ACTIVE_NETWORK";
                }
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) {
                    return "NO_CAPABILITIES";
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return "WIFI";
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return "MOBILE";
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return "ETHERNET";
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                    return "BLUETOOTH";
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return "VPN";
                }
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return "ONLINE";
                }
                return "OTHER";
            } else {
                android.net.NetworkInfo info = cm.getActiveNetworkInfo();
                if (info != null && info.isConnected()) {
                    return info.getTypeName();
                }
                return "OFFLINE";
            }
        } catch (Exception e) {
            return "unknown";
        }
    }

    public static boolean isWifiEnabled(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wifiManager != null && wifiManager.isWifiEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isInteractive(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                return pm.isInteractive();
            } else {
                return pm.isScreenOn();
            }
        } catch (Exception e) {
            return false;
        }
    }

    public static File getLogFile(Context context) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, LOG_FILE_NAME);
    }

    public static String getLogContent(Context context) {
        try {
            File logFile = getLogFile(context);
            if (!logFile.exists()) return "No logs found.";
            FileInputStream fis = new FileInputStream(logFile);
            byte[] data = new byte[(int) logFile.length()];
            int read = fis.read(data);
            fis.close();
            return new String(data, 0, Math.max(read, 0), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Error reading logs: " + e.getMessage();
        }
    }

    private static void writeLine(Context context, String tag, String message, Throwable throwable) {
        File logFile = getLogFile(context);
        rotateIfNeeded(logFile);
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(time).append("] [").append(tag).append("] ").append(message).append("\n");
        if (throwable != null) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            sb.append(sw).append("\n");
        }
        try {
            FileOutputStream fos = new FileOutputStream(logFile, true);
            fos.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignored) {
        }
    }

    private static void rotateIfNeeded(File logFile) {
        try {
            if (logFile.exists() && logFile.length() > MAX_LOG_SIZE) {
                File backup = new File(logFile.getParentFile(), LOG_OLD_FILE_NAME);
                if (backup.exists()) {
                    backup.delete();
                }
                logFile.renameTo(backup);
            }
        } catch (Exception ignored) {
        }
    }
}
