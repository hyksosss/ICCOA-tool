package com.iccoa.tool;

import android.app.Activity;
import android.os.Bundle;

/**
 * NoDisplay launcher endpoint. Opening it performs a clean reset of the
 * observer and ensures the foreground service is running, then finishes.
 */
public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowObserver.getInstance().init(this);
        WindowObserver.getInstance().reset();
        KeepAliveService.startSafely(this);
        finish();
    }
}
