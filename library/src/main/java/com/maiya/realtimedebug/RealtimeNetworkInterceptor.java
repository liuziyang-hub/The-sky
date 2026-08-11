package com.maiya.realtimedebug;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

/**
 * OkHttp interceptor that pushes truncated HTTP events to {@link com.maiya.realtimedebug.RealtimeDebug}.
 */
public final class RealtimeNetworkInterceptor implements Interceptor {

    private static final String TAG = "RealtimeNetInterceptor";
    private static final int MAX_BODY_CHARS = 8 * 1024;
    private static final int MAX_HEADER_CHARS = 2 * 1024;

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();
        long startNs = System.nanoTime();

        BodyPeek requestPeek = peekRequestBody(original);
        Request request = requestPeek.request;
        String requestBodyPreview = requestPeek.preview;

        Response response;
        try {
            response = chain.proceed(request);
        } catch (IOException e) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            broadcastError(request, requestBodyPreview, durationMs, e);
            throw e;
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
        return broadcastAndRebuild(request, requestBodyPreview, response, durationMs);
    }

    private Response broadcastAndRebuild(Request request, String requestBodyPreview,
                                         Response response, long durationMs) {
        ResponseBody body = response.body();
        String responseBodyPreview = "";
        MediaType contentType = body != null ? body.contentType() : null;
        byte[] bytes = null;

        try {
            if (body != null) {
                BufferedSource source = body.source();
                source.request(Long.MAX_VALUE);
                Buffer buffer = source.getBuffer();
                Charset charset = charsetOf(contentType);
                bytes = buffer.clone().readByteArray();
                responseBodyPreview = truncate(new String(bytes, charset));
            }
        } catch (Exception e) {
            Log.w(TAG, "read response body failed", e);
            responseBodyPreview = "<unreadable>";
        }

        try {
            JSONObject event = new JSONObject();
            event.put("type", "http");
            event.put("ts", System.currentTimeMillis());
            event.put("method", request.method());
            event.put("url", request.url().toString());
            event.put("code", response.code());
            event.put("durationMs", durationMs);
            event.put("requestHeaders", headersToJson(request.headers()));
            event.put("responseHeaders", headersToJson(response.headers()));
            event.put("requestBody", requestBodyPreview != null ? requestBodyPreview : "");
            event.put("responseBody", responseBodyPreview);
            boolean mocked = "1".equals(response.header(RealtimeMockInterceptor.HEADER_MOCKED));
            boolean rewritten = "1".equals(response.header(RealtimeMockInterceptor.HEADER_REWRITTEN));
            event.put("mocked", mocked);
            event.put("rewritten", rewritten);
            if (mocked || rewritten) {
                event.put("ruleId", response.header(RealtimeMockInterceptor.HEADER_RULE_ID));
            }
            String throttle = response.header(RealtimeMockInterceptor.HEADER_THROTTLE_MS);
            if (throttle != null && !throttle.isEmpty()) {
                try {
                    event.put("throttledMs", Integer.parseInt(throttle));
                } catch (Exception ignored) {
                }
            }
            com.maiya.realtimedebug.internal.RealtimeDebugBridge.getInstance().broadcast(event.toString());
        } catch (Exception e) {
            Log.w(TAG, "broadcast http event failed", e);
        }

        if (bytes != null && body != null) {
            return response.newBuilder()
                    .body(ResponseBody.create(contentType, bytes))
                    .build();
        }
        return response;
    }

    private void broadcastError(Request request, String requestBodyPreview,
                                long durationMs, IOException error) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", "http");
            event.put("ts", System.currentTimeMillis());
            event.put("method", request.method());
            event.put("url", request.url().toString());
            event.put("code", -1);
            event.put("durationMs", durationMs);
            event.put("requestHeaders", headersToJson(request.headers()));
            event.put("responseHeaders", new JSONObject());
            event.put("requestBody", requestBodyPreview != null ? requestBodyPreview : "");
            event.put("responseBody", "");
            event.put("error", error.getMessage() != null ? error.getMessage() : error.toString());
            com.maiya.realtimedebug.internal.RealtimeDebugBridge.getInstance().broadcast(event.toString());
        } catch (Exception e) {
            Log.w(TAG, "broadcast error event failed", e);
        }
    }

    private static final class BodyPeek {
        final Request request;
        final String preview;

        BodyPeek(Request request, String preview) {
            this.request = request;
            this.preview = preview;
        }
    }

    private static BodyPeek peekRequestBody(Request request) {
        RequestBody body = request.body();
        if (body == null) {
            return new BodyPeek(request, "");
        }
        try {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            byte[] bytes = buffer.readByteArray();
            Charset charset = charsetOf(body.contentType());
            String preview = truncate(new String(bytes, charset));
            RequestBody newBody = RequestBody.create(body.contentType(), bytes);
            Request rebuilt = request.newBuilder()
                    .method(request.method(), newBody)
                    .build();
            return new BodyPeek(rebuilt, preview);
        } catch (Exception e) {
            return new BodyPeek(request, "<unreadable>");
        }
    }

    private static JSONObject headersToJson(Headers headers) {
        JSONObject obj = new JSONObject();
        if (headers == null) {
            return obj;
        }
        try {
            JSONArray names = new JSONArray();
            int total = 0;
            for (int i = 0; i < headers.size(); i++) {
                String name = headers.name(i);
                String value = headers.value(i);
                total += name.length() + value.length();
                if (total > MAX_HEADER_CHARS) {
                    obj.put("_truncated", true);
                    break;
                }
                // last-wins for duplicate header names is acceptable for debug preview
                obj.put(name, value);
                names.put(name);
            }
        } catch (Exception ignored) {
        }
        return obj;
    }

    private static Charset charsetOf(MediaType contentType) {
        if (contentType != null) {
            Charset charset = contentType.charset(StandardCharsets.UTF_8);
            if (charset != null) {
                return charset;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_BODY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_BODY_CHARS) + "\n…(truncated)";
    }
}
