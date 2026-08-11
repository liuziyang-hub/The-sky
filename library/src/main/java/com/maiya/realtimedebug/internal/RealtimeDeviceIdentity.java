package com.maiya.realtimedebug.internal;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import com.maiya.realtimedebug.RealtimeUidProvider;

/**
 * Debug identity for LAN discovery.
 * Prefer {@link RealtimeUidProvider}; fallback to stable device code.
 */
final class RealtimeDeviceIdentity {

    private static final String PREF = "realtime_debug_identity";
    private static final String KEY_DEVICE = "device_uid";

    private static volatile RealtimeUidProvider uidProvider;

    private RealtimeDeviceIdentity() {
    }

    static void setUidProvider(RealtimeUidProvider provider) {
        uidProvider = provider;
    }

    static String getUid(Context context) {
        RealtimeUidProvider provider = uidProvider;
        if (provider != null) {
            try {
                String userId = provider.getUid();
                if (!TextUtils.isEmpty(userId)) {
                    return userId.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return getOrCreateDeviceUid(context);
    }

    static String getLabel(Context context) {
        String uid = getUid(context);
        RealtimeUidProvider provider = uidProvider;
        if (provider != null) {
            try {
                String userId = provider.getUid();
                if (!TextUtils.isEmpty(userId) && uid.equals(userId.trim())) {
                    return "user";
                }
            } catch (Exception ignored) {
            }
        }
        return "device";
    }

    private static String getOrCreateDeviceUid(Context context) {
        Context app = context.getApplicationContext();
        String cached = app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_DEVICE, null);
        if (!TextUtils.isEmpty(cached)) {
            return cached;
        }
        String androidId = "";
        try {
            androidId = Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {
        }
        String raw = TextUtils.isEmpty(androidId) ? ("r" + System.currentTimeMillis()) : androidId;
        String code = "d-" + Integer.toHexString(raw.hashCode());
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DEVICE, code)
                .apply();
        return code;
    }
}
