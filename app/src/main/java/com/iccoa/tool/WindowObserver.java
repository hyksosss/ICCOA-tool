package com.iccoa.tool;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Singleton. Background thread polls "dumpsys window" and observes whether
 * the current focus contains "avm360". Activity only reads stats.
 */
public final class WindowObserver {

    public interface Listener {
        void onStatsUpdated();
    }

    public static final long POLL_INTERVAL_MS = 500L;
    private static final int LOG_CAPACITY = 10;
    private static final String KEY_360 = "avm360";
    private static final long EXEC_TIMEOUT_MS = 2000L;

    private static volatile WindowObserver instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat tsFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Thread worker;
    private volatile boolean running = false;
    private volatile Listener listener;

    private volatile String currentFocus = "";
    private volatile long lastDumpsysCostMs = 0L;
    private volatile long totalSamples = 0L;
    private volatile long count360 = 0L;
    private volatile long countNon360 = 0L;
    private volatile long last360AppearAt = 0L;
    private volatile long last360DisappearAt = 0L;
    private volatile boolean lastWas360 = false;

    private final Deque<String> logs = new ArrayDeque<>();

    private WindowObserver() {}

    public static WindowObserver getInstance() {
        if (instance == null) {
            synchronized (WindowObserver.class) {
                if (instance == null) instance = new WindowObserver();
            }
        }
        return instance;
    }

    public void setListener(Listener l) { this.listener = l; }

    public boolean isRunning() { return running; }

    public synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "window-observer");
        worker.setDaemon(true);
        worker.start();
        appendLog("listen started");
        notifyListener();
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        appendLog("listen stopped");
        notifyListener();
    }

    private void loop() {
        while (running) {
            long t0 = System.currentTimeMillis();
            String focus = execDumpsys();
            long cost = System.currentTimeMillis() - t0;
            lastDumpsysCostMs = cost;

            if (focus != null) {
                currentFocus = focus;
                boolean is360 = focus.contains(KEY_360);
                totalSamples++;
                long now = System.currentTimeMillis();

                if (is360) {
                    count360++;
                    if (!lastWas360) last360AppearAt = now;
                    lastWas360 = true;
                    appendLog("[360] " + cost + "ms");
                } else {
                    countNon360++;
                    if (lastWas360) last360DisappearAt = now;
                    lastWas360 = false;
                    appendLog("[non360] " + shortFocus(focus) + " " + cost + "ms");
                }
            } else {
                appendLog("[empty] dumpsys returned nothing " + cost + "ms");
            }

            notifyListener();

            long sleep = POLL_INTERVAL_MS - cost;
            if (sleep < 0) sleep = 0;
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private String execDumpsys() {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "dumpsys window 2>/dev/null | grep -i mCurrentFocus"
            });

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append(' ');
                }
            }

            if (!proc.waitFor(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                return null;
            }

            String out = sb.toString().trim();
            return out.isEmpty() ? null : out;
        } catch (Exception e) {
            return null;
        } finally {
            if (proc != null) {
                try { proc.getInputStream().close(); } catch (Exception ignored) {}
                try { proc.getErrorStream().close(); } catch (Exception ignored) {}
                try { proc.getOutputStream().close(); } catch (Exception ignored) {}
                proc.destroy();
            }
        }
    }

    private String shortFocus(String raw) {
        int idx = raw.indexOf(" u0 ");
        if (idx < 0) return raw.length() > 40 ? raw.substring(0, 40) + "..." : raw;
        String tail = raw.substring(idx + 4);
        int sp = tail.indexOf(' ');
        return sp > 0 ? tail.substring(0, sp) : tail;
    }

    private void notifyListener() {
        Listener l = listener;
        if (l != null) mainHandler.post(l::onStatsUpdated);
    }

    private void appendLog(String msg) {
        String entry = tsFormat.format(new Date()) + " " + msg;
        synchronized (logs) {
            logs.addFirst(entry);
            while (logs.size() > LOG_CAPACITY) logs.removeLast();
        }
    }

    public String getCurrentFocus() { return currentFocus; }
    public long getLastDumpsysCostMs() { return lastDumpsysCostMs; }
    public long getTotalSamples() { return totalSamples; }
    public long getCount360() { return count360; }
    public long getCountNon360() { return countNon360; }
    public long getLast360AppearAt() { return last360AppearAt; }
    public long getLast360DisappearAt() { return last360DisappearAt; }

    public List<String> getLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    public void clearStats() {
        totalSamples = 0;
        count360 = 0;
        countNon360 = 0;
        last360AppearAt = 0;
        last360DisappearAt = 0;
        lastWas360 = false;
        notifyListener();
    }

    public void clearLogs() {
        synchronized (logs) { logs.clear(); }
        notifyListener();
    }
}