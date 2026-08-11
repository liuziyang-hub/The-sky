package com.maiya.realtimedebug.internal;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.maiya.realtimedebug.RealtimeUidProvider;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dev-only WebSocket server that broadcasts debug events to LAN / USB clients.
 */
public final class RealtimeDebugBridge {

    public static final int PORT = 17890;
    private static final String TAG = "RealtimeDebugBridge";

    private static volatile RealtimeDebugBridge instance;

    private WebSocketServer server;
    private volatile boolean started;
    private Application application;
    private final AtomicInteger clientCount = new AtomicInteger(0);

    private RealtimeLogStreamer logStreamer;
    private RealtimeMetricsSampler metricsSampler;
    private RealtimeScreenPreview screenPreview;
    private RealtimeLanBeacon lanBeacon;

    private RealtimeDebugBridge() {
    }

    public static RealtimeDebugBridge getInstance() {
        if (instance == null) {
            synchronized (RealtimeDebugBridge.class) {
                if (instance == null) {
                    instance = new RealtimeDebugBridge();
                }
            }
        }
        return instance;
    }

    public static void setUidProvider(RealtimeUidProvider provider) {
        RealtimeDeviceIdentity.setUidProvider(provider);
    }

    public Application getApplication() {
        return application;
    }

    public synchronized void start() {
        start(application);
    }

    public synchronized void start(Context context) {
        if (context != null) {
            Context appCtx = context.getApplicationContext();
            if (appCtx instanceof Application) {
                application = (Application) appCtx;
            }
        }
        if (started) {
            ensureStreamers();
            return;
        }
        try {
            server = new WebSocketServer(new InetSocketAddress(PORT)) {
                @Override
                public void onOpen(WebSocket conn, ClientHandshake handshake) {
                    clientCount.incrementAndGet();
                    Log.i(TAG, "client connected: " + conn.getRemoteSocketAddress()
                            + " count=" + clientCount.get());
                    conn.send(buildHelloJson());
                    RealtimeCommandHandler.onClientConnected();
                }

                @Override
                public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                    clientCount.updateAndGet(c -> Math.max(0, c - 1));
                    Log.i(TAG, "client closed: " + reason + " count=" + clientCount.get());
                }

                @Override
                public void onMessage(WebSocket conn, String message) {
                    RealtimeCommandHandler.handle(message);
                }

                @Override
                public void onError(WebSocket conn, Exception ex) {
                    Log.w(TAG, "server error", ex);
                }

                @Override
                public void onStart() {
                    Log.i(TAG, "WebSocket server started on port " + PORT);
                }
            };
            server.setReuseAddr(true);
            server.start();
            started = true;
            ensureStreamers();
        } catch (Exception e) {
            Log.e(TAG, "failed to start WebSocket server", e);
            started = false;
            server = null;
        }
    }

    private String buildHelloJson() {
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("msg", "RealtimeDebug ready");
            hello.put("port", PORT);
            hello.put("ip", getLocalIpAddress());
            if (application != null) {
                hello.put("uid", RealtimeDeviceIdentity.getUid(application));
                hello.put("kind", RealtimeDeviceIdentity.getLabel(application));
            }
            hello.put("model", Build.MODEL != null ? Build.MODEL : "");
            hello.put("features", new org.json.JSONArray()
                    .put("http").put("log").put("metrics").put("screen").put("uid")
                    .put("mock").put("throttle").put("replay").put("assert"));
            return hello.toString();
        } catch (Exception e) {
            return "{\"type\":\"hello\",\"msg\":\"RealtimeDebug ready\",\"port\":" + PORT + "}";
        }
    }

    private void ensureStreamers() {
        if (application == null) {
            return;
        }
        if (logStreamer == null) {
            logStreamer = new RealtimeLogStreamer();
            logStreamer.start();
        }
        if (metricsSampler == null) {
            metricsSampler = new RealtimeMetricsSampler(application);
            metricsSampler.start();
        }
        if (screenPreview == null) {
            screenPreview = new RealtimeScreenPreview(application);
            screenPreview.start();
        }
        if (lanBeacon == null) {
            lanBeacon = new RealtimeLanBeacon(application);
            lanBeacon.start();
        }
    }

    public synchronized void stop() {
        if (lanBeacon != null) {
            lanBeacon.stop();
            lanBeacon = null;
        }
        if (logStreamer != null) {
            logStreamer.stop();
            logStreamer = null;
        }
        if (metricsSampler != null) {
            metricsSampler.stop();
            metricsSampler = null;
        }
        if (screenPreview != null) {
            screenPreview.stop();
            screenPreview = null;
        }
        if (server == null) {
            started = false;
            clientCount.set(0);
            return;
        }
        try {
            server.stop(1000);
        } catch (Exception e) {
            Log.w(TAG, "stop failed", e);
        } finally {
            server = null;
            started = false;
            clientCount.set(0);
        }
    }

    public boolean isStarted() {
        return started;
    }

    public int getClientCount() {
        return clientCount.get();
    }

    public String getUid() {
        if (application == null) {
            return "";
        }
        return RealtimeDeviceIdentity.getUid(application);
    }

    public void broadcast(String json) {
        WebSocketServer s = server;
        if (s == null || !started || json == null) {
            return;
        }
        if (clientCount.get() <= 0) {
            return;
        }
        try {
            s.broadcast(json);
        } catch (Exception e) {
            Log.w(TAG, "broadcast failed", e);
        }
    }

    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return "0.0.0.0";
            }
            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String host = addr.getHostAddress();
                        if (host != null && !host.startsWith("169.254.")) {
                            return host;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getLocalIpAddress failed", e);
        }
        return "0.0.0.0";
    }

    public String getWsUrl() {
        return "ws://" + getLocalIpAddress() + ":" + PORT;
    }
}
