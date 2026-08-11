package com.maiya.realtimedebug.internal;

import android.os.Process;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streams this process's logcat lines to {@link RealtimeDebugBridge}.
 */
final class RealtimeLogStreamer {

    private static final String TAG = "RealtimeLogStreamer";
    private static final int MAX_MSG = 2000;
    private static final int MAX_LINES_PER_SEC = 30;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private java.lang.Process logcatProcess;

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::loop, "realtime-logcat");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        running.set(false);
        java.lang.Process p = logcatProcess;
        if (p != null) {
            p.destroy();
            logcatProcess = null;
        }
        Thread t = worker;
        if (t != null) {
            t.interrupt();
            worker = null;
        }
    }

    private void loop() {
        int pid = Process.myPid();
        AtomicInteger windowCount = new AtomicInteger(0);
        long[] windowStart = {System.currentTimeMillis()};

        while (running.get()) {
            try {
                logcatProcess = Runtime.getRuntime().exec(new String[]{
                        "logcat",
                        "--pid=" + pid,
                        "-v", "threadtime"
                });
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(logcatProcess.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while (running.get() && (line = reader.readLine()) != null) {
                    long now = System.currentTimeMillis();
                    if (now - windowStart[0] >= 1000L) {
                        windowStart[0] = now;
                        windowCount.set(0);
                    }
                    if (windowCount.incrementAndGet() > MAX_LINES_PER_SEC) {
                        continue;
                    }
                    broadcastLine(line, now);
                }
            } catch (Exception e) {
                if (running.get()) {
                    Log.w(TAG, "logcat stream error, retry", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                java.lang.Process p = logcatProcess;
                if (p != null) {
                    p.destroy();
                    logcatProcess = null;
                }
            }
        }
    }

    private void broadcastLine(String line, long ts) {
        try {
            ParsedLog parsed = parse(line);
            JSONObject event = new JSONObject();
            event.put("type", "log");
            event.put("ts", ts);
            event.put("level", parsed.level);
            event.put("tag", parsed.tag);
            event.put("msg", truncate(parsed.msg));
            event.put("raw", truncate(line));
            RealtimeDebugBridge.getInstance().broadcast(event.toString());
        } catch (Exception ignored) {
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_MSG ? s : s.substring(0, MAX_MSG) + "…";
    }

    /**
     * threadtime: "01-01 12:00:00.000  1234  5678 I Tag: message"
     */
    private static ParsedLog parse(String line) {
        ParsedLog p = new ParsedLog();
        p.level = "I";
        p.tag = "";
        p.msg = line;
        try {
            // find " X Tag: "
            int idx = -1;
            for (int i = 18; i < line.length() - 2; i++) {
                char c = line.charAt(i);
                if ((c == 'V' || c == 'D' || c == 'I' || c == 'W' || c == 'E' || c == 'F' || c == 'S')
                        && i + 1 < line.length() && line.charAt(i + 1) == ' ') {
                    // heuristic: preceded by space and digits
                    if (i > 0 && Character.isWhitespace(line.charAt(i - 1))) {
                        idx = i;
                        break;
                    }
                }
            }
            if (idx >= 0) {
                p.level = String.valueOf(line.charAt(idx));
                int colon = line.indexOf(':', idx + 2);
                if (colon > idx) {
                    p.tag = line.substring(idx + 2, colon).trim();
                    p.msg = colon + 1 < line.length() ? line.substring(colon + 1).trim() : "";
                } else {
                    p.msg = line.substring(idx + 2).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return p;
    }

    private static final class ParsedLog {
        String level;
        String tag;
        String msg;
    }
}
