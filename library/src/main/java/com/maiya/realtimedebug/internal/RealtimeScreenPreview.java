package com.maiya.realtimedebug.internal;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;

/**
 * Low-FPS App window preview pushed as JPEG base64 {@code type:"screen"}.
 * Only samples when there is at least one WebSocket client.
 */
final class RealtimeScreenPreview implements Application.ActivityLifecycleCallbacks {

    private static final String TAG = "RealtimeScreenPreview";
    private static final long INTERVAL_MS = 1000L;
    private static final int MAX_WIDTH = 480;
    private static final int JPEG_QUALITY = 40;

    private final Application application;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private volatile boolean started;
    private volatile boolean capturing;

    RealtimeScreenPreview(Application application) {
        this.application = application;
    }

    synchronized void start() {
        if (started) {
            return;
        }
        application.registerActivityLifecycleCallbacks(this);
        started = true;
        mainHandler.post(tick);
    }

    synchronized void stop() {
        started = false;
        mainHandler.removeCallbacks(tick);
        try {
            application.unregisterActivityLifecycleCallbacks(this);
        } catch (Exception ignored) {
        }
        resumedActivity = new WeakReference<>(null);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started) {
                return;
            }
            if (RealtimeDebugBridge.getInstance().getClientCount() > 0) {
                captureOnce();
            }
            mainHandler.postDelayed(this, INTERVAL_MS);
        }
    };

    private void captureOnce() {
        if (capturing) {
            return;
        }
        Activity activity = resumedActivity.get();
        if (activity == null || activity.isFinishing()) {
            return;
        }
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        capturing = true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                PixelCopy.request(window, bitmap, copyResult -> {
                    if (copyResult == PixelCopy.SUCCESS) {
                        publish(bitmap);
                    } else {
                        bitmap.recycle();
                        fallbackDraw(decor);
                    }
                    capturing = false;
                }, mainHandler);
            } else {
                fallbackDraw(decor);
                capturing = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "capture failed", e);
            capturing = false;
        }
    }

    private void fallbackDraw(View decor) {
        try {
            int w = decor.getWidth();
            int h = decor.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            decor.draw(canvas);
            publish(bitmap);
        } catch (Exception e) {
            Log.w(TAG, "fallback draw failed", e);
        }
    }

    private void publish(Bitmap source) {
        try {
            Bitmap scaled = scale(source);
            if (scaled != source) {
                source.recycle();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            scaled.recycle();
            String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            JSONObject event = new JSONObject();
            event.put("type", "screen");
            event.put("ts", System.currentTimeMillis());
            event.put("mime", "image/jpeg");
            event.put("data", b64);
            RealtimeDebugBridge.getInstance().broadcast(event.toString());
        } catch (Exception e) {
            Log.w(TAG, "publish failed", e);
            try {
                source.recycle();
            } catch (Exception ignored) {
            }
        }
    }

    private static Bitmap scale(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= MAX_WIDTH) {
            return src;
        }
        int nh = Math.max(1, (int) (h * (MAX_WIDTH / (float) w)));
        return Bitmap.createScaledBitmap(src, MAX_WIDTH, nh, true);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        resumedActivity = new WeakReference<>(activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
        Activity cur = resumedActivity.get();
        if (cur == activity) {
            resumedActivity = new WeakReference<>(null);
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        Activity cur = resumedActivity.get();
        if (cur == activity) {
            resumedActivity = new WeakReference<>(null);
        }
    }
}
