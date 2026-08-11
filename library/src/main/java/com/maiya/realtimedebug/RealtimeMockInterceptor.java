package com.maiya.realtimedebug;

import android.util.Log;

import com.maiya.realtimedebug.internal.RealtimeRuleStore;

import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

/**
 * Applies PC-pushed mock / abort / rewrite / delay / weak-network throttle before real I/O.
 */
public final class RealtimeMockInterceptor implements Interceptor {

    private static final String TAG = "RealtimeMockInterceptor";
    private static final Random RANDOM = new Random();

    public static final String HEADER_MOCKED = "X-Realtime-Mocked";
    public static final String HEADER_RULE_ID = "X-Realtime-Rule-Id";
    public static final String HEADER_THROTTLE_MS = "X-Realtime-Throttle-Ms";
    public static final String HEADER_REWRITTEN = "X-Realtime-Rewritten";

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

        if (rule != null && "mock".equalsIgnoreCase(rule.action)) {
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

        if (rule != null && "rewrite".equalsIgnoreCase(rule.action)) {
            request = applyRewrite(request, rule);
        }

        sleepQuietly(delayMs);
        long start = System.nanoTime();
        Response response = chain.proceed(request);
        long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        Response.Builder rb = response.newBuilder();
        if (rule != null && "rewrite".equalsIgnoreCase(rule.action)) {
            rb.header(HEADER_REWRITTEN, "1");
            rb.header(HEADER_RULE_ID, rule.id);
        }
        if (delayMs > 0) {
            rb.header(HEADER_THROTTLE_MS, String.valueOf(delayMs));
            rb.header("X-Realtime-Upstream-Ms", String.valueOf(cost));
        }
        return rb.build();
    }

    private static Request applyRewrite(Request original, RealtimeRuleStore.MockRule rule)
            throws IOException {
        Request.Builder builder = original.newBuilder();
        String method = original.method();
        if (rule.rewriteMethod != null && !rule.rewriteMethod.isEmpty()) {
            method = rule.rewriteMethod;
        }
        if (rule.rewriteUrl != null && !rule.rewriteUrl.isEmpty()) {
            builder.url(rule.rewriteUrl);
        }

        Headers.Builder headers = original.headers().newBuilder();
        for (String name : rule.removeHeaders) {
            headers.removeAll(name);
        }
        for (Map.Entry<String, String> e : rule.setHeaders.entrySet()) {
            headers.set(e.getKey(), e.getValue());
        }
        builder.headers(headers.build());

        RequestBody body = original.body();
        if (rule.rewriteBodySet) {
            String contentType = headers.get("Content-Type");
            if (contentType == null || contentType.isEmpty()) {
                contentType = rule.contentType;
            }
            MediaType mediaType = MediaType.parse(contentType);
            body = RequestBody.create(mediaType, rule.rewriteBody == null ? "" : rule.rewriteBody);
        } else if (body != null) {
            // OkHttp requires re-attaching body when method changes
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            MediaType mediaType = body.contentType();
            body = RequestBody.create(mediaType, buffer.readByteArray());
        }

        if ("GET".equals(method) || "HEAD".equals(method)) {
            builder.method(method, null);
        } else {
            if (body == null) {
                body = RequestBody.create(null, new byte[0]);
            }
            builder.method(method, body);
        }
        return builder
                .header(HEADER_REWRITTEN, "1")
                .header(HEADER_RULE_ID, rule.id)
                .build();
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
