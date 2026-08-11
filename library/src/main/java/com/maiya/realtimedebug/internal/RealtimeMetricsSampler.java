package com.maiya.realtimedebug.internal;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.RandomAccessFile;

/**
 * Periodically samples CPU / memory / threads and broadcasts {@code type:"metrics"}.
 */
final class RealtimeMetricsSampler {

    private static final String TAG = "RealtimeMetricsSampler";
    private static final long INTERVAL_MS = 2000L;

    private final Context appContext;
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean started;

    private long prevProcJiffies = -1;
    private long prevTotalJiffies = -1;

    RealtimeMetricsSampler(Context context) {
        this.appContext = context.getApplicationContext();
    }

    synchronized void start() {
        if (started) {
            return;
        }
        thread = new HandlerThread("realtime-metrics");
        thread.start();
        handler = new Handler(thread.getLooper());
        started = true;
        handler.post(sampleRunnable);
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
    }

    private final Runnable sampleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!started) {
                return;
            }
            try {
                broadcastSample();
            } catch (Exception e) {
                Log.w(TAG, "sample failed", e);
            }
            Handler h = handler;
            if (h != null && started) {
                h.postDelayed(this, INTERVAL_MS);
            }
        }
    };

    private void broadcastSample() throws Exception {
        Runtime rt = Runtime.getRuntime();
        long javaUsed = (rt.totalMemory() - rt.freeMemory()) / 1024L;
        long javaMax = rt.maxMemory() / 1024L;
        long javaTotal = rt.totalMemory() / 1024L;
        long pssKb = Debug.getPss();
        long nativeHeap = Debug.getNativeHeapAllocatedSize() / 1024L;

        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        long availMb = -1;
        long totalMb = -1;
        boolean lowMem = false;
        if (am != null) {
            am.getMemoryInfo(memInfo);
            availMb = memInfo.availMem / (1024L * 1024L);
            totalMb = memInfo.totalMem / (1024L * 1024L);
            lowMem = memInfo.lowMemory;
        }

        double cpuPercent = sampleCpuPercent();
        int threads = readThreadCount();

        JSONObject event = new JSONObject();
        event.put("type", "metrics");
        event.put("ts", System.currentTimeMillis());
        event.put("pid", Process.myPid());
        event.put("cpuPercent", Math.round(cpuPercent * 10.0) / 10.0);
        event.put("javaHeapUsedKb", javaUsed);
        event.put("javaHeapTotalKb", javaTotal);
        event.put("javaHeapMaxKb", javaMax);
        event.put("nativeHeapAllocKb", nativeHeap);
        event.put("pssKb", pssKb);
        event.put("availMemMb", availMb);
        event.put("totalMemMb", totalMb);
        event.put("lowMemory", lowMem);
        event.put("threads", threads);
        RealtimeDebugBridge.getInstance().broadcast(event.toString());
    }

    private double sampleCpuPercent() {
        long proc = readProcJiffies();
        long total = readTotalJiffies();
        if (proc < 0 || total < 0) {
            return 0;
        }
        double pct = 0;
        if (prevProcJiffies >= 0 && prevTotalJiffies >= 0) {
            long dProc = proc - prevProcJiffies;
            long dTotal = total - prevTotalJiffies;
            if (dTotal > 0 && dProc >= 0) {
                pct = (dProc * 100.0) / dTotal;
            }
        }
        prevProcJiffies = proc;
        prevTotalJiffies = total;
        return Math.min(pct, 100.0 * Runtime.getRuntime().availableProcessors());
    }

    private static long readProcJiffies() {
        try (RandomAccessFile raf = new RandomAccessFile("/proc/self/stat", "r")) {
            String line = raf.readLine();
            if (line == null) {
                return -1;
            }
            // utime(14) stime(15) — fields after comm
            int closeParen = line.lastIndexOf(')');
            if (closeParen < 0) {
                return -1;
            }
            String[] parts = line.substring(closeParen + 1).trim().split("\\s+");
            // index 11=utime, 12=stime in parts (0-based after comm)
            long utime = Long.parseLong(parts[11]);
            long stime = Long.parseLong(parts[12]);
            return utime + stime;
        } catch (Exception e) {
            return -1;
        }
    }

    private static long readTotalJiffies() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = br.readLine();
            if (line == null || !line.startsWith("cpu ")) {
                return -1;
            }
            String[] parts = line.trim().split("\\s+");
            long sum = 0;
            for (int i = 1; i < parts.length; i++) {
                sum += Long.parseLong(parts[i]);
            }
            return sum;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int readThreadCount() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Threads:")) {
                    return Integer.parseInt(line.substring(8).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return Thread.activeCount();
    }
}
