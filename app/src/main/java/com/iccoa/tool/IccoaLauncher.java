package com.iccoa.tool;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Launcher for the target ICCOA app. Reuses the verified ComponentName. */
public final class IccoaLauncher {

    private static final ComponentName ICCOA = new ComponentName(
            "com.ucarhu.demo", "com.ucarhu.demo.UCarDemoActivity");

    public interface Callback {
        void onResult(boolean success, String message);
    }

    private IccoaLauncher() {}

    public static void launch(Context context, Callback callback) {
        try {
            Intent intent = new Intent()
                    .setComponent(ICCOA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            if (callback != null) callback.onResult(true, "start request sent");
        } catch (Exception e) {
            if (callback != null) {
                callback.onResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }
}