package com.example.telegramcallnotifier;

import android.content.Context;
import android.net.Uri;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TelegramSender {

    private static final String TAG = "TelegramSender";
    private static final String HTTPS_URL = "https://37.49.226.139.sslip.io/p5002/send";
    private static final String FALLBACK_URL = "http://37.49.226.139:5002/send";
    private static final String SERVER_URL = HTTPS_URL;
    private static final String SERVER_API_KEY = "A7f9xP22sKp90ZqLm";

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public TelegramSender(Context context) {
        this.context = context;
    }

    public static String getServerUrl() {
        return SERVER_URL;
    }

    private void logToFile(String message) {
        DebugLogger.log(context, TAG, message);
    }

    public void sendMessage(String message) {
        DebugLogger.log(context, TAG, "CALL sendMessage() called. msg=" + truncate(message, 500));
        sendToServer("call", message);
    }

    public void sendStatusMessage(String message) {
        DebugLogger.log(context, TAG, "REPORT sendStatusMessage() called. msg=" + truncate(message, 500));
        sendToServer("report", message);
    }

    public void sendPing() {
        DebugLogger.log(context, TAG, "PING sendPing() called");
        sendToServer("ping", "alive");
    }

    public boolean sendMessageSync(String message) {
        DebugLogger.log(context, TAG, "CALL sendMessageSync() called. msg=" + truncate(message, 500));
        return sendToServerSync("call", message);
    }

    public void sendToServer(String type, String text) {
        if (text == null || text.isEmpty()) {
            DebugLogger.log(context, TAG, "sendToServer skipped: empty text. type=" + type);
            return;
        }
        final String finalType = (type == null || type.isEmpty()) ? "unknown" : type;
        final String finalText = text;

        DebugLogger.log(context, TAG, "sendToServer start. type=" + finalType + " text=" + truncate(finalText, 500));
        DebugLogger.logState(context, TAG, "before http request");

        executor.execute(() -> {
            try {
                logToFile("sendToServer ASYNC type=" + finalType);
                String json = buildJson(finalType, finalText);
                boolean ok = sendWithRetryAndFallback(json);
                logToFile("sendToServer ASYNC done ok=" + ok);
            } catch (Exception e) {
                DebugLogger.log(context, TAG, "sendToServer exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                Log.e(TAG, "Error sending to server", e);
                DebugLogger.logError(context, TAG, e);
            }
        });
    }

    public boolean sendToServerSync(String type, String text) {
        if (text == null || text.isEmpty()) {
            DebugLogger.log(context, TAG, "sendToServerSync skipped: empty text. type=" + type);
            return false;
        }
        String finalType = (type == null || type.isEmpty()) ? "unknown" : type;

        PowerManager.WakeLock wl = null;
        try {
            logToFile("sendToServerSync ENTER");
            logToFile("APP VERSION MARK = BUILD_5002_TEST_V1");
            logToFile("sendToServerSync type=" + type);
            logToFile("sendToServerSync text=" + text);
            logToFile("sendToServerSync SERVER_URL const=" + SERVER_URL);
            logToFile("sendToServerSync HTTPS_URL const=" + HTTPS_URL);
            logToFile("sendToServerSync FALLBACK_URL const=" + FALLBACK_URL);

            Uri uri = Uri.parse(SERVER_URL);
            logToFile("SERVER HOST = " + uri.getHost());
            int port = uri.getPort();
            if (port == -1) {
                String scheme = uri.getScheme();
                if ("https".equalsIgnoreCase(scheme)) port = 443;
                else if ("http".equalsIgnoreCase(scheme)) port = 80;
            }
            logToFile("SERVER PORT = " + port);
            logToFile("SERVER PATH = " + uri.getPath());

            logToFile("sendToServerSync CALLED FROM HERE");
            for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
                logToFile("STACK " + el.toString());
            }

            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TelegramCallNotifier:SendLock");
                wl.acquire(15000);
            }

            String json = buildJson(finalType, text);
            logToFile("JSON payload len=" + json.length());

            boolean ok = sendWithRetryAndFallback(json);
            logToFile("sendToServerSync RESULT ok=" + ok);
            return ok;
        } catch (Exception e) {
            DebugLogger.log(context, TAG, "sendToServerSync exception: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            DebugLogger.logError(context, TAG, e);
            logToFile("sendToServerSync EXCEPTION = " + Log.getStackTraceString(e));
            return false;
        } finally {
            if (wl != null) {
                try {
                    if (wl.isHeld()) wl.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean sendWithRetryAndFallback(String json) {
        String[] urls = {HTTPS_URL, HTTPS_URL, FALLBACK_URL};
        int[] connectTimeouts = {8000, 12000, 15000};
        int[] readTimeouts = {10000, 15000, 20000};
        long[] delaysMs = {0, 2000, 5000};

        for (int i = 0; i < urls.length; i++) {
            try {
                if (delaysMs[i] > 0) {
                    Thread.sleep(delaysMs[i]);
                }

                logToFile("Send attempt " + (i + 1) + " url=" + urls[i]
                        + " connectTimeout=" + connectTimeouts[i]
                        + " readTimeout=" + readTimeouts[i]);

                boolean ok = sendRequest(urls[i], json, connectTimeouts[i], readTimeouts[i]);
                if (ok) {
                    logToFile("Send success on attempt " + (i + 1));
                    return true;
                } else {
                    logToFile("Send failed on attempt " + (i + 1));
                }
            } catch (Exception e) {
                logToFile("Send exception on attempt " + (i + 1) + ": " + Log.getStackTraceString(e));
            }
        }

        return false;
    }

    private boolean sendRequest(String urlString, String json, int connectTimeout, int readTimeout) throws Exception {
        HttpURLConnection conn = null;
        OutputStream os = null;
        InputStream is = null;

        try {
            Uri uri = Uri.parse(urlString);
            logToFile("REQUEST URL = " + urlString);
            logToFile("REQUEST HOST = " + uri.getHost());
            logToFile("REQUEST SCHEME = " + uri.getScheme());
            logToFile("REQUEST PATH = " + uri.getPath());

            int port = uri.getPort();
            if (port == -1) {
                String scheme = uri.getScheme();
                if ("https".equalsIgnoreCase(scheme)) port = 443;
                else if ("http".equalsIgnoreCase(scheme)) port = 80;
            }
            logToFile("REQUEST PORT = " + port);

            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            logToFile("URLConnection class = " + conn.getClass().getName());

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setDoOutput(true);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            byte[] out = json.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(out.length);
            os = conn.getOutputStream();
            os.write(out);
            os.flush();

            int code = conn.getResponseCode();
            logToFile("Response code (" + urlString + ") = " + code);
            logToFile("Response message (" + urlString + ") = " + conn.getResponseMessage());

            Map<String, List<String>> headers = conn.getHeaderFields();
            if (headers != null) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    logToFile("HEADER " + entry.getKey() + " = " + entry.getValue());
                }
            }

            if (code >= 200 && code < 300) {
                is = conn.getInputStream();
                String body = readStream(is);
                logToFile("Response body (" + urlString + ") = " + body);
                return isOkResponse(code, body);
            } else {
                is = conn.getErrorStream();
                String body = readStream(is);
                logToFile("Error body (" + urlString + ") = " + body);
                return false;
            }
        } finally {
            try {
                if (os != null) os.close();
            } catch (Exception ignored) {
            }
            try {
                if (is != null) is.close();
            } catch (Exception ignored) {
            }
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String buildJson(String type, String text) {
        String finalType = (type == null || type.isEmpty()) ? "unknown" : type;
        String finalText = (text == null) ? "" : text;
        return "{"
                + "\"api_key\":\"" + escapeJson(SERVER_API_KEY) + "\","
                + "\"type\":\"" + escapeJson(finalType) + "\","
                + "\"text\":\"" + escapeJson(finalText) + "\""
                + "}";
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return "readStream error: " + e.getMessage();
        }
    }

    private static boolean isOkResponse(int code, String body) {
        if (code != 200 || body == null) return false;
        String compact = body.replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "");
        return compact.contains("\"ok\":true");
    }

    private static String readBody(HttpURLConnection conn, boolean successStream) {
        InputStream is = null;
        try {
            is = successStream ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "";
            BufferedReader in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line).append('\n');
            }
            return response.toString().trim();
        } catch (Exception e) {
            return "";
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String escapeJson(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c <= 0x1F) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }
}
