package com.maiya.realtimedebug.internal;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.maiya.realtimedebug.RealtimeMockInterceptor;
import com.maiya.realtimedebug.RealtimeNetworkInterceptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Handles PC console commands over WebSocket.
 */
final class RealtimeCommandHandler {

    private static final String TAG = "RealtimeCmd";
    private static final ExecutorService EXEC = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "realtime-cmd");
        t.setDaemon(true);
        return t;
    });

    private static volatile OkHttpClient replayClient;

    private RealtimeCommandHandler() {
    }

    static void handle(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        try {
            JSONObject obj = new JSONObject(message);
            if (!"cmd".equals(obj.optString("type"))) {
                return;
            }
            String action = obj.optString("action");
            switch (action) {
                case "set_mock_rules":
                    RealtimeRuleStore.get().setRulesFromJson(obj.optJSONArray("rules"));
                    ack(action, true, "rules=" + RealtimeRuleStore.get().snapshotRules().size());
                    pushConfig();
                    break;
                case "set_throttle":
                    RealtimeRuleStore.get().setThrottleFromJson(obj.optJSONObject("throttle"));
                    ack(action, true, RealtimeRuleStore.get().getThrottle().profile);
                    pushConfig();
                    break;
                case "get_config":
                    pushConfig();
                    break;
                case "clear_mock_rules":
                    RealtimeRuleStore.get().setRulesFromJson(new JSONArray());
                    ack(action, true, "cleared");
                    pushConfig();
                    break;
                case "replay":
                    EXEC.execute(() -> replay(obj));
                    break;
                default:
                    ack(action, false, "unknown_action");
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "handle cmd failed", e);
            ack("unknown", false, e.getMessage());
        }
    }

    private static void replay(JSONObject obj) {
        try {
            String method = obj.optString("method", "GET").toUpperCase();
            String url = obj.optString("url");
            if (url == null || url.isEmpty()) {
                ack("replay", false, "missing_url");
                return;
            }
            Request.Builder rb = new Request.Builder().url(url);
            JSONObject headers = obj.optJSONObject("headers");
            if (headers != null) {
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    // skip hop-by-hop
                    if ("content-length".equalsIgnoreCase(k) || "host".equalsIgnoreCase(k)) {
                        continue;
                    }
                    rb.header(k, headers.optString(k));
                }
            }
            String body = obj.optString("body", obj.optString("requestBody", ""));
            String contentType = obj.optString("contentType", "application/json; charset=utf-8");
            if ("GET".equals(method) || "HEAD".equals(method)) {
                rb.method(method, null);
            } else {
                MediaType mt = MediaType.parse(contentType);
                rb.method(method, RequestBody.create(mt, body == null ? "" : body));
            }
            Request request = rb.build();
            try (Response response = replayClient().newCall(request).execute()) {
                ack("replay", true, "code=" + response.code());
            }
        } catch (Exception e) {
            Log.w(TAG, "replay failed", e);
            ack("replay", false, e.getMessage());
        }
    }

    private static OkHttpClient replayClient() {
        OkHttpClient c = replayClient;
        if (c != null) {
            return c;
        }
        synchronized (RealtimeCommandHandler.class) {
            if (replayClient == null) {
                replayClient = new OkHttpClient.Builder()
                        .connectTimeout(30, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .addInterceptor(new RealtimeNetworkInterceptor())
                        .addInterceptor(new RealtimeMockInterceptor())
                        .build();
            }
            return replayClient;
        }
    }

    private static void pushConfig() {
        RealtimeDebugBridge.getInstance().broadcast(RealtimeRuleStore.get().configSnapshot().toString());
    }

    private static void ack(String action, boolean ok, String message) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "cmd_ack");
            o.put("action", action);
            o.put("ok", ok);
            o.put("message", message == null ? "" : message);
            o.put("ts", System.currentTimeMillis());
            RealtimeDebugBridge.getInstance().broadcast(o.toString());
        } catch (Exception ignored) {
        }
    }

    static void onClientConnected() {
        // delay slightly so hello is sent first
        new Handler(Looper.getMainLooper()).postDelayed(RealtimeCommandHandler::pushConfig, 200);
    }
}
