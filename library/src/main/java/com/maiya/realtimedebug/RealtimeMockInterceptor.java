package com.maiya.realtimedebug;

import android.util.Log;

import com.maiya.realtimedebug.internal.RealtimeRuleStore;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Applies PC-pushed mock / abort / delay / weak-network throttle before real I/O.
 */
public final class RealtimeMockInterceptor implements Interceptor {

    private static final String TAG = "RealtimeMockInterceptor";
    private static final Random RANDOM = new Random();

    public static final String HEADER_MOCKED = "X-Realtime-Mocked";
    public static final String HEADER_RULE_ID = "X-Realtime-Rule-Id";
    public static final String HEADER_THROTTLE_MS = "X-Realtime-Throttle-Ms";

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        String method = request.method();
        String url = request.url().toString();

        RealtimeRuleStore.MockRule rule = RealtimeRuleStore.get().match(method, url);
        RealtimeRuleStore.ThrottleConfig throttle = RealtimeRuleStore.get().getThrottle();

        int delayMs = throttle.delayMs;
        if (rule != null) {
            delayMs = Math.max(delayMs, rule.delayMs);
        }

        if (throttle.failPercent > 0 && RANDOM.nextInt(100) < throttle.failPercent) {
            sleepQuietly(delayMs);
            throw new IOException("RealtimeDebug simulated network failure ("
                    + throttle.profile + ", failPercent=" + throttle.failPercent + ")");
        }

        if (rule != null && "abort".equalsIgnoreCase(rule.action)) {
            sleepQuietly(delayMs);
            throw new IOException("RealtimeDebug abort by rule " + rule.id);
        }

        if (rule != null && ("mock".equalsIgnoreCase(rule.action)
                || "modify".equalsIgnoreCase(rule.action))) {
            sleepQuietly(delayMs);
            MediaType mediaType = MediaType.parse(rule.contentType);
            ResponseBody body = ResponseBody.create(
                    mediaType,
                    rule.responseBody == null ? "" : rule.responseBody
            );
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(rule.statusCode)
                    .message(messageFor(rule.statusCode))
                    .header(HEADER_MOCKED, "1")
                    .header(HEADER_RULE_ID, rule.id)
                    .header(HEADER_THROTTLE_MS, String.valueOf(delayMs))
                    .header("Content-Type", rule.contentType)
                    .body(body)
                    .build();
        }

        sleepQuietly(delayMs);
        long start = System.nanoTime();
        Response response = chain.proceed(request);
        long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (delayMs > 0) {
            return response.newBuilder()
                    .header(HEADER_THROTTLE_MS, String.valueOf(delayMs))
                    .header("X-Realtime-Upstream-Ms", String.valueOf(cost))
                    .build();
        }
        return response;
    }

    private static void sleepQuietly(int delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "throttle interrupted");
        }
    }

    private static String messageFor(int code) {
        if (code >= 500) {
            return "Server Error";
        }
        if (code >= 400) {
            return "Client Error";
        }
        if (code >= 300) {
            return "Redirect";
        }
        return "OK";
    }
}
