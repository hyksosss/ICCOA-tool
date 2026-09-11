package com.iccoa.tool;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** Low-importance foreground service. Keeps the process alive. */
public final class KeepAliveService extends Service {

    private static final String CHANNEL_ID = "iccoa_tool";
    private static final int NOTIFICATION_ID = 1001;

    static void startSafely(Context context) {
        try {
            Intent intent = new Intent(context, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("ICCOA-tool")
                .setContentText("debug service running")
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, n);
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "ICCOA-tool", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .createNotificationChannel(ch);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}