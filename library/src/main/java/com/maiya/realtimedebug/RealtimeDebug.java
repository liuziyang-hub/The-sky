package com.maiya.realtimedebug;

import android.app.Application;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.maiya.realtimedebug.internal.RealtimeDebugBridge;
import com.maiya.realtimedebug.internal.RealtimeRuleStore;

import org.json.JSONArray;
import org.json.JSONObject;

import okhttp3.Interceptor;
import okhttp3.OkHttpClient;

/**
 * Third-party entry for LAN / USB realtime debug (network / log / metrics / screen / mock / throttle).
 *
 * <pre>
 * RealtimeDebug.install(this);
 * RealtimeDebug.setUidProvider(() -&gt; userId);
 * RealtimeDebug.installOkHttp(builder); // mock + capture
 * </pre>
 */
public final class RealtimeDebug {

    public static final int WS_PORT = RealtimeDebugBridge.PORT;
    public static final int BEACON_PORT = 17891;

    private RealtimeDebug() {
    }

    public static void install(@NonNull Context context) {
        install(context, null);
    }

    public static void install(@NonNull Context context, @Nullable RealtimeUidProvider uidProvider) {
        if (uidProvider != null) {
            RealtimeDebugBridge.setUidProvider(uidProvider);
        }
        RealtimeDebugBridge.getInstance().start(context);
    }

    public static void setUidProvider(@Nullable RealtimeUidProvider uidProvider) {
        RealtimeDebugBridge.setUidProvider(uidProvider);
    }

    public static void start(@Nullable Context context) {
        RealtimeDebugBridge.getInstance().start(context);
    }

    public static void start() {
        RealtimeDebugBridge.getInstance().start();
    }

    public static void stop() {
        RealtimeDebugBridge.getInstance().stop();
    }

    public static boolean isStarted() {
        return RealtimeDebugBridge.getInstance().isStarted();
    }

    @NonNull
    public static String getUid() {
        return RealtimeDebugBridge.getInstance().getUid();
    }

    @NonNull
    public static String getWsUrl() {
        return RealtimeDebugBridge.getInstance().getWsUrl();
    }

    public static int getClientCount() {
        return RealtimeDebugBridge.getInstance().getClientCount();
    }

    /**
     * Capture-only interceptor. Prefer {@link #installOkHttp(OkHttpClient.Builder)}.
     */
    @NonNull
    public static Interceptor networkInterceptor() {
        return new RealtimeNetworkInterceptor();
    }

    /**
     * Mock / throttle / abort interceptor (must be inner relative to capture).
     */
    @NonNull
    public static Interceptor mockInterceptor() {
        return new RealtimeMockInterceptor();
    }

    /**
     * Install capture + mock interceptors in the correct order.
     * Capture is outer so mocked responses are still visible in the console.
     */
    public static void installOkHttp(@NonNull OkHttpClient.Builder builder) {
        builder.addInterceptor(networkInterceptor());
        builder.addInterceptor(mockInterceptor());
    }

    public static void setMockRulesJson(@Nullable String jsonArray) {
        try {
            RealtimeRuleStore.get().setRulesFromJson(
                    jsonArray == null || jsonArray.isEmpty() ? new JSONArray() : new JSONArray(jsonArray)
            );
        } catch (Exception ignored) {
        }
    }

    public static void setThrottle(@Nullable String profile, int delayMs, int failPercent) {
        RealtimeRuleStore.get().setThrottle(new RealtimeRuleStore.ThrottleConfig(
                profile == null ? "none" : profile, delayMs, failPercent
        ));
    }

    @NonNull
    public static JSONObject currentConfig() {
        return RealtimeRuleStore.get().configSnapshot();
    }

    public static void broadcast(@Nullable String json) {
        RealtimeDebugBridge.getInstance().broadcast(json);
    }

    @Nullable
    public static Application getApplication() {
        return RealtimeDebugBridge.getInstance().getApplication();
    }
}
