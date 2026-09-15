package com.iccoa.tool;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Singleton observer with a three-phase state machine plus a boot fallback.
 *
 * State A (cruise, 1200ms): look for 360 appearing.
 * State B (track,  200ms):  360 is on screen; watch until it disappears.
 * State C (slow,   1500ms): 360 has been up more than 10s; slow down.
 *
 * Action X: whenever 360 disappears (from B or C), wait 50ms, launch ICCOA.
 *
 * Boot fallback: if during the first 15s after the observer starts we never
 * see 360 and the launcher stays in the foreground for 3s continuously, we
 * assume the observer started too late and missed the boot-time 360, so we
 * launch ICCOA once. This fallback fires at most once per session.
 */
public final class WindowObserver {

    private static final long INTERVAL_A_MS = 1200L;
    private static final long INTERVAL_B_MS = 200L;
    private static final long INTERVAL_C_MS = 1500L;
    private static final long STATE_B_TIMEOUT_MS = 10_000L;
    private static final long ACTION_X_DELAY_MS = 50L;
    private static final String KEY_360 = "avm360";
    private static final long EXEC_TIMEOUT_MS = 2000L;

    private static final long FALLBACK_WINDOW_MS = 15_000L;
    private static final long LAUNCHER_STABLE_MS = 3_000L;
    private static final String PKG_LAUNCHER = "com.android.launcher";

    private static volatile WindowObserver instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Thread worker;
    private volatile Context appContext;

    // 0 = A (cruise), 1 = B (track), 2 = C (slow)
    private volatile int currentState = 0;
    private volatile long stateBEnteredAt = 0L;
    private volatile boolean lastWas360 = false;

    // fallback state
    private volatile long observerStartTime = 0L;
    private volatile boolean hasSeen360 = false;
    private volatile boolean fallbackUsed = false;
    private volatile long launcherStartTime = 0L;

    private WindowObserver() {}

    public static WindowObserver getInstance() {
        if (instance == null) {
            synchronized (WindowObserver.class) {
                if (instance == null) instance = new WindowObserver();
            }
        }
        return instance;
    }

    public void init(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public synchronized void start() {
        if (worker != null && worker.isAlive()) return;
        currentState = 0;
        stateBEnteredAt = 0L;
        lastWas360 = false;
        observerStartTime = System.currentTimeMillis();
        hasSeen360 = false;
        fallbackUsed = false;
        launcherStartTime = 0L;
        Thread t = new Thread(this::loop, "window-observer");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    public synchronized void stop() {
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
    }

    /** Stop current worker and start a fresh one. */
    public synchronized void reset() {
        stop();
        start();
    }

    private void loop() {
        final Thread me = Thread.currentThread();
        while (worker == me) {
            long t0 = System.currentTimeMillis();
            String focus = execDumpsys();
            long cost = System.currentTimeMillis() - t0;

            if (focus != null) {
                boolean is360 = focus.contains(KEY_360);
                long now = System.currentTimeMillis();
                handleSample(focus, is360, now);
            }

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

    private long intervalForState(int s) {
        switch (s) {
            case 1:  return INTERVAL_B_MS;
            case 2:  return INTERVAL_C_MS;
            default: return INTERVAL_A_MS;
        }
    }

    private void handleSample(String focus, boolean is360, long now) {
        if (is360) {
            // Entered 360: normal state machine takes over; fallback disabled forever.
            hasSeen360 = true;
            launcherStartTime = 0L;
            lastWas360 = true;
            onSample360(now);
            return;
        }

        boolean was360 = lastWas360;
        lastWas360 = false;

        if (hasSeen360) {
            onSampleNon360(was360);
            return;
        }

        // Never seen 360 in this session: try boot fallback.
        if (fallbackUsed) return;
        if (observerStartTime == 0L) return;
        if (now - observerStartTime > FALLBACK_WINDOW_MS) return;

        if (focus.contains(PKG_LAUNCHER)) {
            if (launcherStartTime == 0L) {
                launcherStartTime = now;
            } else if (now - launcherStartTime >= LAUNCHER_STABLE_MS) {
                fallbackUsed = true;
                launcherStartTime = 0L;
                scheduleActionX();
            }
        } else {
            launcherStartTime = 0L;
        }
    }

    private void onSample360(long now) {
        switch (currentState) {
            case 0:
                currentState = 1;
                stateBEnteredAt = now;
                break;
            case 1:
                if (now - stateBEnteredAt >= STATE_B_TIMEOUT_MS) {
                    currentState = 2;
                }
                break;
            default:
                break;
        }
    }

    private void onSampleNon360(boolean was360) {
        if (!was360) return;
        if (currentState == 1 || currentState == 2) {
            currentState = 0;
            stateBEnteredAt = 0L;
            scheduleActionX();
        }
    }

    private void scheduleActionX() {
        final Context ctx = appContext;
        if (ctx == null) return;
        mainHandler.postDelayed(() -> IccoaLauncher.launch(ctx, null), ACTION_X_DELAY_MS);
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
}
