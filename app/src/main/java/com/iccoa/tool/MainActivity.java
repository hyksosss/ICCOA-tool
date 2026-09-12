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
import android.widget.CompoundButton;
import android.widget.Switch;
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
    private TextView tvMachineState;
    private TextView tvStateBDuration;
    private TextView tvTotalSamples;
    private TextView tvCount360;
    private TextView tvCountNon360;
    private TextView tvLast360Appear;
    private TextView tvLast360Disappear;
    private TextView tvAutoLaunchCount;
    private TextView tvLastAutoLaunchAt;
    private TextView tvLastAutoLaunchResult;
    private TextView tvLaunchResult;
    private TextView tvLogs;
    private Switch swAuto;

    private final SimpleDateFormat tsFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final WindowObserver.Listener listener = this::refreshUI;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        WindowObserver.getInstance().init(this);

        tvDumpStatus         = findViewById(R.id.tv_dump_status);
        tvSawStatus          = findViewById(R.id.tv_saw_status);
        tvGrantHint          = findViewById(R.id.tv_grant_hint);
        tvListenStatus       = findViewById(R.id.tv_listen_status);
        tvCurrentFocus       = findViewById(R.id.tv_current_focus);
        tvDumpsysCost        = findViewById(R.id.tv_dumpsys_cost);
        tvMachineState       = findViewById(R.id.tv_machine_state);
        tvStateBDuration     = findViewById(R.id.tv_state_b_duration);
        tvTotalSamples       = findViewById(R.id.tv_total_samples);
        tvCount360           = findViewById(R.id.tv_count_360);
        tvCountNon360        = findViewById(R.id.tv_count_non360);
        tvLast360Appear      = findViewById(R.id.tv_last_360_appear);
        tvLast360Disappear   = findViewById(R.id.tv_last_360_disappear);
        tvAutoLaunchCount    = findViewById(R.id.tv_auto_launch_count);
        tvLastAutoLaunchAt   = findViewById(R.id.tv_last_auto_launch_at);
        tvLastAutoLaunchResult = findViewById(R.id.tv_last_auto_launch_result);
        tvLaunchResult       = findViewById(R.id.tv_launch_result);
        tvLogs               = findViewById(R.id.tv_logs);
        swAuto               = findViewById(R.id.sw_auto);

        swAuto.setChecked(WindowObserver.getInstance().isAutoMode());
        swAuto.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
                WindowObserver.getInstance().setAutoMode(checked));

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
        if (swAuto != null) swAuto.setChecked(WindowObserver.getInstance().isAutoMode());
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
            tvGrantHint.setText("adb shell pm grant com.iccoa.tool android.permission.DUMP");
        }

        WindowObserver o = WindowObserver.getInstance();
        tvListenStatus.setText(o.isRunning() ? "running" : "stopped");
        tvCurrentFocus.setText(o.getCurrentFocus().isEmpty() ? "(none)" : o.getCurrentFocus());
        tvDumpsysCost.setText(o.getLastDumpsysCostMs() + " ms");

        String stateText;
        switch (o.getCurrentState()) {
            case A: stateText = "A (cruise 1000ms)"; break;
            case B: stateText = "B (track 200ms)"; break;
            case C: stateText = "C (slow 1000ms)"; break;
            default: stateText = "stopped"; break;
        }
        tvMachineState.setText(stateText);

        long bEnter = o.getStateBEnteredAt();
        if (o.getCurrentState() == WindowObserver.State.B && bEnter > 0) {
            long elapsed = System.currentTimeMillis() - bEnter;
            tvStateBDuration.setText((elapsed / 1000) + "." + ((elapsed % 1000) / 100) + " s / 10 s");
        } else {
            tvStateBDuration.setText("-");
        }

        tvTotalSamples.setText(String.valueOf(o.getTotalSamples()));
        tvCount360.setText(String.valueOf(o.getCount360()));
        tvCountNon360.setText(String.valueOf(o.getCountNon360()));
        tvLast360Appear.setText(fmt(o.getLast360AppearAt()));
        tvLast360Disappear.setText(fmt(o.getLast360DisappearAt()));
        tvAutoLaunchCount.setText(String.valueOf(o.getAutoLaunchCount()));
        tvLastAutoLaunchAt.setText(fmt(o.getLastAutoLaunchAt()));
        tvLastAutoLaunchResult.setText(o.getLastAutoLaunchResult().isEmpty()
                ? "-" : o.getLastAutoLaunchResult());

        List<String> logs = o.getLogs();
        if (logs.isEmpty()) {
            tvLogs.setText("(none)");
        } else {
            StringBuilder sb = new StringBuilder();
            int max = Math.min(logs.size(), 20);
            for (int i = 0; i < max; i++) sb.append(logs.get(i)).append('\n');
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
