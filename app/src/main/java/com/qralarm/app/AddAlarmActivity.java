package com.qralarm.app;

import android.app.TimePickerDialog;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AddAlarmActivity extends AppCompatActivity {

    public static final String EXTRA_ALARM_ID = "extra_alarm_id";

    // Stage URI state (null = use synth)
    private String uriCalm, uriMedium, uriLoud;

    private int editAlarmId = -1;
    private int selectedHour, selectedMinute;

    private TextView tvTime, tvSoundCalm, tvSoundMedium, tvSoundLoud, tvQrStatus, etLabel;
    private CheckBox[] dayCheckboxes = new CheckBox[7];
    private boolean[] selectedDays   = new boolean[7];
    private String qrCodeValue, qrCodeLabel;

    private final ExecutorService dbExec = Executors.newSingleThreadExecutor();

    // ── Sound pickers (one per stage) ────────────────────────────────────────

    private int pickingStage = -1; // which stage's picker is active

    private final ActivityResultLauncher<String> filePicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null || pickingStage < 0) return;
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {}
            setStageUri(pickingStage, uri.toString(), getFileName(uri));
        });

    // Ringtone picker result (API 19 startActivityForResult)
    private static final int REQ_RINGTONE_CALM   = 1001;
    private static final int REQ_RINGTONE_MEDIUM = 1002;
    private static final int REQ_RINGTONE_LOUD   = 1003;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_alarm);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        tvTime        = findViewById(R.id.tv_selected_time);
        etLabel       = findViewById(R.id.et_label);
        tvSoundCalm   = findViewById(R.id.tv_sound_calm);
        tvSoundMedium = findViewById(R.id.tv_sound_medium);
        tvSoundLoud   = findViewById(R.id.tv_sound_loud);
        tvQrStatus    = findViewById(R.id.tv_qr_status);

        int[] dayIds = { R.id.cb_mon, R.id.cb_tue, R.id.cb_wed,
                         R.id.cb_thu, R.id.cb_fri, R.id.cb_sat, R.id.cb_sun };
        for (int i = 0; i < 7; i++) {
            final int idx = i;
            dayCheckboxes[i] = findViewById(dayIds[i]);
            dayCheckboxes[i].setOnCheckedChangeListener((v, c) -> selectedDays[idx] = c);
        }

        // Default: now + 1 hour
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.add(java.util.Calendar.HOUR_OF_DAY, 1);
        selectedHour   = cal.get(java.util.Calendar.HOUR_OF_DAY);
        selectedMinute = cal.get(java.util.Calendar.MINUTE);
        updateTimeDisplay();

        editAlarmId = getIntent().getIntExtra(EXTRA_ALARM_ID, -1);
        if (editAlarmId != -1) {
            ((TextView) findViewById(R.id.tv_toolbar_title)).setText(R.string.edit_alarm);
            loadExistingAlarm();
        }

        // Listeners
        findViewById(R.id.btn_pick_time).setOnClickListener(v -> showTimePicker());
        tvTime.setOnClickListener(v -> showTimePicker());

        // Stage pickers
        findViewById(R.id.btn_pick_calm).setOnClickListener(v ->
                showSoundSourceDialog(0, REQ_RINGTONE_CALM));
        findViewById(R.id.btn_pick_medium).setOnClickListener(v ->
                showSoundSourceDialog(1, REQ_RINGTONE_MEDIUM));
        findViewById(R.id.btn_pick_loud).setOnClickListener(v ->
                showSoundSourceDialog(2, REQ_RINGTONE_LOUD));

        // Clear buttons (revert to synth)
        findViewById(R.id.btn_clear_calm).setOnClickListener(v ->
                clearStageUri(0));
        findViewById(R.id.btn_clear_medium).setOnClickListener(v ->
                clearStageUri(1));
        findViewById(R.id.btn_clear_loud).setOnClickListener(v ->
                clearStageUri(2));

        // QR setup
        findViewById(R.id.btn_setup_qr).setOnClickListener(v -> setupQr());

        // Save
        findViewById(R.id.btn_save_alarm).setOnClickListener(v -> saveAlarm());
    }

    // ── Time picker ───────────────────────────────────────────────────────────

    private void showTimePicker() {
        new TimePickerDialog(this,
                (view, h, m) -> { selectedHour = h; selectedMinute = m; updateTimeDisplay(); },
                selectedHour, selectedMinute, true).show();
    }

    private void updateTimeDisplay() {
        tvTime.setText(String.format("%02d:%02d", selectedHour, selectedMinute));
    }

    // ── Sound pickers ─────────────────────────────────────────────────────────

    private void showSoundSourceDialog(int stage, int ringtoneReqCode) {
        pickingStage = stage;
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_sound_source)
                .setItems(new CharSequence[]{
                        getString(R.string.pick_from_ringtones),
                        getString(R.string.pick_from_files),
                        getString(R.string.use_synthesized_sound)
                }, (d, which) -> {
                    if (which == 0) openRingtonePicker(stage, ringtoneReqCode);
                    else if (which == 1) filePicker.launch("audio/*");
                    else clearStageUri(stage);
                }).show();
    }

    private void openRingtonePicker(int stage, int reqCode) {
        pickingStage = stage;
        Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        String cur = getStageUri(stage);
        if (cur != null) i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur));
        startActivityForResult(i, reqCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Handle ringtone picker results
        if (resultCode == RESULT_OK && data != null) {
            if (requestCode == REQ_RINGTONE_CALM   ||
                requestCode == REQ_RINGTONE_MEDIUM ||
                requestCode == REQ_RINGTONE_LOUD) {
                Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                if (uri != null) {
                    int stage = (requestCode == REQ_RINGTONE_CALM) ? 0
                              : (requestCode == REQ_RINGTONE_MEDIUM) ? 1 : 2;
                    android.media.Ringtone rt = RingtoneManager.getRingtone(this, uri);
                    String name = (rt != null) ? rt.getTitle(this) : uri.getLastPathSegment();
                    setStageUri(stage, uri.toString(), name);
                }
                return;
            }
        }
        // QR setup result
        if (requestCode == QRSetupActivity.REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            qrCodeValue = data.getStringExtra(QRSetupActivity.RESULT_QR_VALUE);
            qrCodeLabel = data.getStringExtra(QRSetupActivity.RESULT_QR_LABEL);
            updateQrStatus();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ── Stage URI helpers ─────────────────────────────────────────────────────

    private void setStageUri(int stage, String uri, String displayName) {
        switch (stage) {
            case 0: uriCalm   = uri; tvSoundCalm.setText(displayName);   break;
            case 1: uriMedium = uri; tvSoundMedium.setText(displayName); break;
            case 2: uriLoud   = uri; tvSoundLoud.setText(displayName);   break;
        }
    }

    private void clearStageUri(int stage) {
        String synthLabel = getString(R.string.use_synthesized_sound);
        switch (stage) {
            case 0: uriCalm   = null; tvSoundCalm.setText(synthLabel);   break;
            case 1: uriMedium = null; tvSoundMedium.setText(synthLabel); break;
            case 2: uriLoud   = null; tvSoundLoud.setText(synthLabel);   break;
        }
    }

    private String getStageUri(int stage) {
        switch (stage) {
            case 0: return uriCalm;
            case 1: return uriMedium;
            default: return uriLoud;
        }
    }

    // ── QR ───────────────────────────────────────────────────────────────────

    private void setupQr() {
        Intent intent = new Intent(this, QRSetupActivity.class);
        if (qrCodeValue != null) {
            intent.putExtra(QRSetupActivity.EXTRA_CURRENT_VALUE, qrCodeValue);
            intent.putExtra(QRSetupActivity.EXTRA_CURRENT_LABEL, qrCodeLabel);
        }
        startActivityForResult(intent, QRSetupActivity.REQUEST_CODE);
    }

    private void updateQrStatus() {
        if (qrCodeValue != null && !qrCodeValue.isEmpty()) {
            String lbl = qrCodeLabel != null ? qrCodeLabel : qrCodeValue;
            tvQrStatus.setText(getString(R.string.qr_set, lbl));
            tvQrStatus.setTextColor(getColor(R.color.primary));
        } else {
            tvQrStatus.setText(R.string.qr_not_set);
            tvQrStatus.setTextColor(getColor(R.color.danger));
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private void saveAlarm() {
        String label = etLabel.getText().toString().trim();
        AlarmRepository repo = new AlarmRepository(this);

        if (editAlarmId != -1) {
            repo.getAlarmById(editAlarmId, existing -> {
                if (existing == null) return;
                fillFields(existing, label);
                repo.update(existing);
                AlarmScheduler.cancel(this, existing);
                if (existing.isEnabled) AlarmScheduler.schedule(this, existing);
                runOnUiThread(() -> { Toast.makeText(this, R.string.alarm_updated, Toast.LENGTH_SHORT).show(); finish(); });
            });
        } else {
            Alarm alarm = new Alarm(selectedHour, selectedMinute, label);
            fillFields(alarm, label);
            repo.insert(alarm, id -> {
                alarm.id = id;
                AlarmScheduler.schedule(this, alarm);
                runOnUiThread(() -> { Toast.makeText(this, R.string.alarm_saved, Toast.LENGTH_SHORT).show(); finish(); });
            });
        }
    }

    private void fillFields(Alarm a, String label) {
        a.hour        = selectedHour;
        a.minute      = selectedMinute;
        a.label       = label;
        a.qrCodeValue = qrCodeValue;
        a.qrCodeLabel = qrCodeLabel;
        a.uriCalm     = uriCalm;
        a.uriMedium   = uriMedium;
        a.uriLoud     = uriLoud;
        a.setRepeatDaysArray(selectedDays);
    }

    // ── Load existing ─────────────────────────────────────────────────────────

    private void loadExistingAlarm() {
        new AlarmRepository(this).getAlarmById(editAlarmId, alarm -> {
            if (alarm == null) return;
            runOnUiThread(() -> {
                selectedHour   = alarm.hour;
                selectedMinute = alarm.minute;
                updateTimeDisplay();
                etLabel.setText(alarm.label);
                qrCodeValue = alarm.qrCodeValue;
                qrCodeLabel = alarm.qrCodeLabel;
                uriCalm     = alarm.uriCalm;
                uriMedium   = alarm.uriMedium;
                uriLoud     = alarm.uriLoud;

                boolean[] days = alarm.getRepeatDaysArray();
                System.arraycopy(days, 0, selectedDays, 0, 7);
                for (int i = 0; i < 7; i++) dayCheckboxes[i].setChecked(selectedDays[i]);

                tvSoundCalm.setText(  labelFor(uriCalm,   R.string.use_synthesized_sound));
                tvSoundMedium.setText(labelFor(uriMedium, R.string.use_synthesized_sound));
                tvSoundLoud.setText(  labelFor(uriLoud,   R.string.use_synthesized_sound));
                updateQrStatus();
            });
        });
    }

    private String labelFor(String uri, int fallbackRes) {
        if (uri == null || uri.isEmpty()) return getString(fallbackRes);
        try {
            android.media.Ringtone rt = RingtoneManager.getRingtone(this, Uri.parse(uri));
            if (rt != null) return rt.getTitle(this);
        } catch (Exception ignored) {}
        // Try content resolver display name
        try (android.database.Cursor c = getContentResolver().query(
                Uri.parse(uri), new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null) return name;
            }
        } catch (Exception ignored) {}
        return Uri.parse(uri).getLastPathSegment();
    }

    private String getFileName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        dbExec.shutdown();
    }
}
