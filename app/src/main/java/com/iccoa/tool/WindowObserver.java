package com.iccoa.tool;

import android.content.Context;
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
 * Singleton observer with a three-phase state machine.
 *
 * State A (cruise, 1000ms): look for 360 appearing.
 * State B (track,  200ms):  360 is on screen; watch until it disappears.
 * State C (slow,   1000ms): 360 has been up more than 10s; slow down.
 *
 * Action X: whenever 360 disappears (in B or C), wait 50ms, then launch ICCOA.
 * Auto mode must be ON for Action X to actually fire.
 */
public final class WindowObserver {

    public enum State { STOPPED, A, B, C }

    public interface Listener {
        void onStatsUpdated();
    }

    public static final long INTERVAL_A_MS = 1000L;
    public static final long INTERVAL_B_MS = 200L;
    public static final long INTERVAL_C_MS = 1000L;
    public static final long STATE_B_TIMEOUT_MS = 10_000L;
    public static final long ACTION_X_DELAY_MS = 50L;
    private static final int LOG_CAPACITY = 100;
    private static final String KEY_360 = "avm360";
    private static final long EXEC_TIMEOUT_MS = 2000L;

    private static volatile WindowObserver instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat tsFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Thread worker;
    private volatile boolean running = false;
    private volatile Listener listener;
    private volatile Context appContext;
    private volatile boolean autoMode = false;

    private volatile State currentState = State.STOPPED;
    private volatile long stateBEnteredAt = 0L;
    private volatile boolean lastWas360 = false;

    private volatile String currentFocus = "";
    private volatile long lastDumpsysCostMs = 0L;
    private volatile long totalSamples = 0L;
    private volatile long count360 = 0L;
    private volatile long countNon360 = 0L;
    private volatile long last360AppearAt = 0L;
    private volatile long last360DisappearAt = 0L;
    private volatile long autoLaunchCount = 0L;
    private volatile long lastAutoLaunchAt = 0L;
    private volatile String lastAutoLaunchResult = "";

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

    public void init(Context context) { this.appContext = context.getApplicationContext(); }

    public void setListener(Listener l) { this.listener = l; }

    public boolean isRunning() { return running; }
    public State getCurrentState() { return currentState; }
    public boolean isAutoMode() { return autoMode; }

    public void setAutoMode(boolean on) {
        autoMode = on;
        appendLog("auto mode " + (on ? "ON" : "OFF"));
        notifyListener();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        currentState = State.A;
        stateBEnteredAt = 0L;
        lastWas360 = false;
        worker = new Thread(this::loop, "window-observer");
        worker.setDaemon(true);
        worker.start();
        appendLog("listen started (state A)");
        notifyListener();
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        currentState = State.STOPPED;
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
                    onSample360(now);
                } else {
                    countNon360++;
                    boolean was360 = lastWas360;
                    if (was360) last360DisappearAt = now;
                    lastWas360 = false;
                    appendLog("[non360] " + shortFocus(focus) + " " + cost + "ms");
                    onSampleNon360(was360);
                }
            } else {
                appendLog("[empty] " + cost + "ms");
            }

            notifyListener();

            long interval = intervalForState(currentState);
            long sleep = interval - cost;
            if (sleep < 0) sleep = 0;
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private long intervalForState(State s) {
        switch (s) {
            case B: return INTERVAL_B_MS;
            case C: return INTERVAL_C_MS;
            case A:
            default: return INTERVAL_A_MS;
        }
    }

    private void onSample360(long now) {
        switch (currentState) {
            case A:
                currentState = State.B;
                stateBEnteredAt = now;
                appendLog("A -> B");
                break;
            case B:
                if (now - stateBEnteredAt >= STATE_B_TIMEOUT_MS) {
                    currentState = State.C;
                    appendLog("B -> C (10s)");
                }
                break;
            case C:
            default:
                break;
        }
    }

    private void onSampleNon360(boolean was360) {
        if (!was360) return;
        if (currentState == State.B || currentState == State.C) {
            appendLog("360 gone in " + currentState + " -> action X");
            currentState = State.A;
            stateBEnteredAt = 0L;
            if (autoMode) {
                scheduleActionX();
            } else {
                appendLog("auto off, skip action X");
            }
        }
    }

    private void scheduleActionX() {
        final Context ctx = appContext;
        if (ctx == null) return;
        mainHandler.postDelayed(() -> IccoaLauncher.launch(ctx, (ok, msg) -> {
            autoLaunchCount++;
            lastAutoLaunchAt = System.currentTimeMillis();
            lastAutoLaunchResult = (ok ? "OK " : "FAIL ") + msg;
            appendLog("action X " + lastAutoLaunchResult);
            notifyListener();
        }), ACTION_X_DELAY_MS);
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
    public long getAutoLaunchCount() { return autoLaunchCount; }
    public long getLastAutoLaunchAt() { return lastAutoLaunchAt; }
    public String getLastAutoLaunchResult() { return lastAutoLaunchResult; }
    public long getStateBEnteredAt() { return stateBEnteredAt; }

    public List<String> getLogs() {
        synchronized (logs) { return new ArrayList<>(logs); }
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
