package com.maiya.realtimedebug;

import androidx.annotation.Nullable;

/**
 * Optional login / business identity for LAN multi-device discovery.
 * Return null or empty to fall back to a stable device code ({@code d-xxxx}).
 */
public interface RealtimeUidProvider {
    @Nullable
    String getUid();
}
