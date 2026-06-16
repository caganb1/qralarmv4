package com.qralarm.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlarmRingActivity extends AppCompatActivity {

    public static final String EXTRA_ALARM_ID = "extra_alarm_id";

    private int alarmId = -1;
    private Alarm alarm;
    private final ExecutorService db = Executors.newSingleThreadExecutor();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        // Show over keyguard & turn screen on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        // Full brightness while ringing.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1.0f;
        getWindow().setAttributes(lp);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_alarm_ring);

        alarmId = getIntent().getIntExtra(EXTRA_ALARM_ID, -1);
        if (alarmId == -1) { finish(); return; }

        loadAndBind();
        findViewById(R.id.btn_scan_qr).setOnClickListener(v -> startScan());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        int id = intent.getIntExtra(EXTRA_ALARM_ID, -1);
        if (id != -1) { alarmId = id; loadAndBind(); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Restore system brightness.
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        db.shutdown();
    }

    // Block hardware back so alarm can only be dismissed via QR.
    @Override public void onBackPressed() { /* intentionally empty */ }
    @Override public boolean onKeyDown(int k, KeyEvent e) {
        return k == KeyEvent.KEYCODE_BACK || super.onKeyDown(k, e);
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void loadAndBind() {
        db.execute(() -> {
            alarm = AlarmDatabase.getInstance(this).alarmDao().getAlarmById(alarmId);
            runOnUiThread(() -> {
                if (alarm == null) { finish(); return; }
                ((TextView) findViewById(R.id.tv_ring_time)).setText(alarm.getTimeString());
                String lbl = alarm.label != null ? alarm.label : "";
                ((TextView) findViewById(R.id.tv_ring_label)).setText(lbl);

                TextView tvInstr = findViewById(R.id.tv_ring_instruction);
                TextView tvQr    = findViewById(R.id.tv_ring_qr_label);
                View     btnFree = findViewById(R.id.btn_dismiss_no_qr);

                if (alarm.hasQrCode()) {
                    tvInstr.setText(R.string.scan_to_dismiss);
                    String qLabel = alarm.qrCodeLabel != null
                            ? alarm.qrCodeLabel : getString(R.string.your_qr_code);
                    tvQr.setText(getString(R.string.required_code, qLabel));
                    tvQr.setVisibility(View.VISIBLE);
                    if (btnFree != null) btnFree.setVisibility(View.GONE);
                } else {
                    tvInstr.setText(R.string.no_qr_configured);
                    tvQr.setVisibility(View.GONE);
                    if (btnFree != null) {
                        btnFree.setVisibility(View.VISIBLE);
                        btnFree.setOnClickListener(v -> dismiss());
                    }
                }
            });
        });
    }

    // ── QR scan ───────────────────────────────────────────────────────────────

    private void startScan() {
        IntentIntegrator ii = new IntentIntegrator(this);
        ii.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES);
        ii.setPrompt(getString(R.string.scan_prompt));
        ii.setBeepEnabled(false);
        ii.setOrientationLocked(true);
        ii.initiateScan();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        IntentResult r = IntentIntegrator.parseActivityResult(req, res, data);
        if (r != null) {
            if (r.getContents() == null) {
                Toast.makeText(this, R.string.scan_cancelled, Toast.LENGTH_SHORT).show();
            } else {
                validate(r.getContents());
            }
        } else {
            super.onActivityResult(req, res, data);
        }
    }

    private void validate(String scanned) {
        if (alarm == null) return;
        if (!alarm.hasQrCode() || scanned.trim().equals(alarm.qrCodeValue.trim())) {
            dismiss();
        } else {
            Toast.makeText(this, R.string.wrong_code, Toast.LENGTH_LONG).show();
        }
    }

    private void dismiss() {
        AlarmService.stop(this);
        if (alarm != null) {
            db.execute(() -> {
                if (!alarm.repeats()) {
                    AlarmDatabase.getInstance(this).alarmDao().setEnabled(alarmId, false);
                } else {
                    AlarmScheduler.schedule(this, alarm);
                }
            });
        }
        finish();
    }
}
