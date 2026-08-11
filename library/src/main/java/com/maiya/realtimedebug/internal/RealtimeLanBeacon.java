package com.maiya.realtimedebug.internal;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Broadcasts UID + IP on LAN so PC discovery server can map uid -> device.
 */
final class RealtimeLanBeacon {

    static final int BEACON_PORT = 17891;
    private static final String TAG = "RealtimeLanBeacon";
    private static final long INTERVAL_MS = 2000L;

    private final Context appContext;
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean started;
    private DatagramSocket socket;

    RealtimeLanBeacon(Context context) {
        this.appContext = context.getApplicationContext();
    }

    synchronized void start() {
        if (started) {
            return;
        }
        thread = new HandlerThread("realtime-beacon");
        thread.start();
        handler = new Handler(thread.getLooper());
        started = true;
        handler.post(tick);
    }

    synchronized void stop() {
        started = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!started) {
                return;
            }
            try {
                sendOnce();
            } catch (Exception e) {
                Log.w(TAG, "beacon send failed", e);
            }
            Handler h = handler;
            if (h != null && started) {
                h.postDelayed(this, INTERVAL_MS);
            }
        }
    };

    private void sendOnce() throws Exception {
        String ip = RealtimeDebugBridge.getLocalIpAddress();
        if ("0.0.0.0".equals(ip)) {
            return;
        }
        JSONObject payload = new JSONObject();
        payload.put("type", "nsdebug");
        payload.put("uid", RealtimeDeviceIdentity.getUid(appContext));
        payload.put("kind", RealtimeDeviceIdentity.getLabel(appContext));
        payload.put("ip", ip);
        payload.put("port", RealtimeDebugBridge.PORT);
        payload.put("model", Build.MODEL);
        payload.put("ts", System.currentTimeMillis());
        byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);

        if (socket == null || socket.isClosed()) {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setReuseAddress(true);
        }
        for (InetAddress target : broadcastTargets(ip)) {
            DatagramPacket packet = new DatagramPacket(data, data.length, target, BEACON_PORT);
            socket.send(packet);
        }
    }

    private static List<InetAddress> broadcastTargets(String localIp) throws Exception {
        Set<String> hosts = new LinkedHashSet<>();
        hosts.add("255.255.255.255");
        // real interface broadcast (handles /23 correctly, e.g. 172.16.3.255)
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        if (interfaces != null) {
            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b instanceof Inet4Address) {
                        hosts.add(b.getHostAddress());
                    }
                }
            }
        }
        // fallback subnet .255 of current IP
        String[] parts = localIp.split("\\.");
        if (parts.length == 4) {
            hosts.add(parts[0] + "." + parts[1] + "." + parts[2] + ".255");
        }
        List<InetAddress> out = new ArrayList<>();
        for (String h : hosts) {
            out.add(InetAddress.getByName(h));
        }
        return out;
    }
}
