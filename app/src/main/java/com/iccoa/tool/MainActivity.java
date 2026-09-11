package com.iccoa.tool;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {

    private TextView tvDumpStatus;
    private TextView tvSawStatus;
    private TextView tvGrantHint;
    private TextView tvListenStatus;
    private TextView tvCurrentFocus;
    private TextView tvDumpsysCost;
    private TextView tvTotalSamples;
    private TextView tvCount360;
    private TextView tvCountNon360;
    private TextView tvLast360Appear;
    private TextView tvLast360Disappear;
    private TextView tvLaunchResult;
    private TextView tvLogs;

    private final SimpleDateFormat tsFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final WindowObserver.Listener listener = this::refreshUI;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvDumpStatus       = findViewById(R.id.tv_dump_status);
        tvSawStatus        = findViewById(R.id.tv_saw_status);
        tvGrantHint        = findViewById(R.id.tv_grant_hint);
        tvListenStatus     = findViewById(R.id.tv_listen_status);
        tvCurrentFocus     = findViewById(R.id.tv_current_focus);
        tvDumpsysCost      = findViewById(R.id.tv_dumpsys_cost);
        tvTotalSamples     = findViewById(R.id.tv_total_samples);
        tvCount360         = findViewById(R.id.tv_count_360);
        tvCountNon360      = findViewById(R.id.tv_count_non360);
        tvLast360Appear    = findViewById(R.id.tv_last_360_appear);
        tvLast360Disappear = findViewById(R.id.tv_last_360_disappear);
        tvLaunchResult     = findViewById(R.id.tv_launch_result);
        tvLogs             = findViewById(R.id.tv_logs);

        findViewById(R.id.btn_recheck).setOnClickListener(v -> refreshUI());
        findViewById(R.id.btn_goto_overlay).setOnClickListener(v -> gotoOverlaySettings());

        Button btnStart = findViewById(R.id.btn_start);
        Button btnStop  = findViewById(R.id.btn_stop);
        btnStart.setOnClickListener(v -> WindowObserver.getInstance().start());
        btnStop.setOnClickListener(v -> WindowObserver.getInstance().stop());

        findViewById(R.id.btn_clear_stats).setOnClickListener(v ->
                WindowObserver.getInstance().clearStats());
        findViewById(R.id.btn_clear_logs).setOnClickListener(v ->
                WindowObserver.getInstance().clearLogs());

        findViewById(R.id.btn_launch).setOnClickListener(v ->
                IccoaLauncher.launch(this, (ok, msg) -> {
                    String stamp = tsFormat.format(new Date());
                    tvLaunchResult.setText((ok ? "OK " : "FAIL ") + stamp + "  " + msg);
                }));

        WindowObserver.getInstance().setListener(listener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUI();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        WindowObserver.getInstance().setListener(null);
    }

    private void refreshUI() {
        if (isFinishing() || isDestroyed()) return;

        boolean dumpOk = checkSelfPermission(Manifest.permission.DUMP)
                == PackageManager.PERMISSION_GRANTED;
        boolean sawOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);

        tvDumpStatus.setText(dumpOk ? "granted" : "not granted");
        tvSawStatus.setText(sawOk ? "granted" : "not granted");

        if (dumpOk) {
            tvGrantHint.setVisibility(View.GONE);
        } else {
            tvGrantHint.setVisibility(View.VISIBLE);
            tvGrantHint.setText(
                    "adb shell pm grant com.iccoa.tool android.permission.DUMP");
        }

        WindowObserver o = WindowObserver.getInstance();
        tvListenStatus.setText(o.isRunning() ? "running" : "stopped");
        tvCurrentFocus.setText(o.getCurrentFocus().isEmpty()
                ? "(none)" : o.getCurrentFocus());
        tvDumpsysCost.setText(o.getLastDumpsysCostMs() + " ms");

        tvTotalSamples.setText(String.valueOf(o.getTotalSamples()));
        tvCount360.setText(String.valueOf(o.getCount360()));
        tvCountNon360.setText(String.valueOf(o.getCountNon360()));
        tvLast360Appear.setText(fmt(o.getLast360AppearAt()));
        tvLast360Disappear.setText(fmt(o.getLast360DisappearAt()));

        List<String> logs = o.getLogs();
        if (logs.isEmpty()) {
            tvLogs.setText("(none)");
        } else {
            StringBuilder sb = new StringBuilder();
            for (String s : logs) sb.append(s).append('\n');
            tvLogs.setText(sb.toString().trim());
        }
    }

    private String fmt(long ms) {
        return ms <= 0 ? "-" : tsFormat.format(new Date(ms));
    }

    private void gotoOverlaySettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try { startActivity(i); } catch (Exception ignored) {}
    }
}