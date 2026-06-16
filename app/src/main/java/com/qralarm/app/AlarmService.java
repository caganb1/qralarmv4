package com.qralarm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 3-stage escalating alarm service.
 *
 * For each stage, tries to play the user's chosen URI first.
 * Falls back to SoundSynthesizer (procedural) if URI is null/invalid.
 *
 * Stage 0 (0–2 min):  calm   → STAGE_CALM   synth fallback
 * Stage 1 (2–4 min):  medium → STAGE_MEDIUM synth fallback
 * Stage 2 (4–6 min):  loud   → STAGE_LOUD   synth fallback
 * After stage 2: auto-stop.
 */
public class AlarmService extends Service {

    private static final String TAG              = "AlarmService";
    public  static final String EXTRA_ALARM_ID   = "extra_alarm_id";
    public  static final String CHANNEL_ID       = "qralarm_channel";
    public  static final int    NOTIFICATION_ID  = 42;

    private static final long  STAGE_DURATION_MS = 120_000L;
    private static final float MIN_VOL           = 0.07f;
    private static final float MAX_VOL           = 1.00f;

    // Maps stage index → SoundSynthesizer stage constant
    private static final int[] SYNTH_STAGE = {
        SoundSynthesizer.STAGE_CALM,
        SoundSynthesizer.STAGE_MEDIUM,
        SoundSynthesizer.STAGE_LOUD
    };

    private static volatile boolean sRinging   = false;
    private static volatile int     sRingingId = -1;

    private final Handler         handler  = new Handler(Looper.getMainLooper());
    private final ExecutorService dbExec   = Executors.newSingleThreadExecutor();

    private MediaPlayer           player;
    private SoundSynthesizer      synth;
    private PowerManager.WakeLock wakeLock;

    private Alarm currentAlarm;
    private int   currentAlarmId = -1;
    private int   stage          = 0;
    private long  stageStart     = 0;
    private boolean usingSynth   = false;

    // ── Volume ramp tick ─────────────────────────────────────────────────────

    private final Runnable volumeTick = new Runnable() {
        @Override public void run() {
            long elapsed = System.currentTimeMillis() - stageStart;
            if (elapsed >= STAGE_DURATION_MS) {
                advanceStage();
                return;
            }
            float vol = MIN_VOL + (MAX_VOL - MIN_VOL) * ((float) elapsed / STAGE_DURATION_MS);
            applyVolume(vol);
            handler.postDelayed(this, 1_000L);
        }
    };

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        int alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1);
        if (alarmId == -1) { stopSelf(); return START_NOT_STICKY; }

        if (sRinging && sRingingId == alarmId) return START_STICKY;
        if (sRinging) return START_STICKY;

        sRinging       = true;
        sRingingId     = alarmId;
        currentAlarmId = alarmId;
        stage          = 0;

        acquireWakeLock();
        startForeground(NOTIFICATION_ID, buildNotification());
        launchRingScreen();

        // Load alarm from DB, then start first stage on main thread
        dbExec.execute(() -> {
            currentAlarm = AlarmDatabase.getInstance(this).alarmDao().getAlarmById(currentAlarmId);
            handler.post(() -> playStage(0));
        });

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(volumeTick);
        releaseAudio();
        releaseWakeLock();
        sRinging   = false;
        sRingingId = -1;
        dbExec.shutdown();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    // ── Stage logic ───────────────────────────────────────────────────────────

    private void playStage(int s) {
        if (s >= 3) { stopSelf(); return; }
        stage      = s;
        stageStart = System.currentTimeMillis();

        releaseAudio();

        String uriStr = (currentAlarm != null) ? currentAlarm.uriForStage(s) : null;
        boolean uriOk = tryMediaPlayer(uriStr);

        if (!uriOk) {
            // Fallback: procedural synth
            usingSynth = true;
            synth = new SoundSynthesizer();
            synth.play(SYNTH_STAGE[s], MIN_VOL);
        } else {
            usingSynth = false;
        }

        handler.postDelayed(volumeTick, 1_000L);
        Log.d(TAG, "Stage " + s + (usingSynth ? " [synth]" : " [uri]"));
    }

    /**
     * Attempts to start MediaPlayer with the given URI.
     * @return true if playback started successfully, false otherwise.
     */
    private boolean tryMediaPlayer(String uriStr) {
        if (uriStr == null || uriStr.isEmpty()) return false;
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setLegacyStreamType(AudioManager.STREAM_ALARM)
                    .build());
            player.setDataSource(this, Uri.parse(uriStr));
            player.setLooping(true);
            player.prepare();
            player.setVolume(MIN_VOL, MIN_VOL);
            player.start();
            return true;
        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "MediaPlayer failed for stage " + stage + ": " + e.getMessage());
            if (player != null) { player.release(); player = null; }
            return false;
        }
    }

    private void advanceStage() {
        handler.removeCallbacks(volumeTick);
        playStage(stage + 1);
    }

    private void applyVolume(float v) {
        if (!usingSynth && player != null) {
            try { player.setVolume(v, v); } catch (IllegalStateException ignored) {}
        } else if (usingSynth && synth != null) {
            synth.setVolume(v);
        }
    }

    private void releaseAudio() {
        handler.removeCallbacks(volumeTick);
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (IllegalStateException ignored) {}
            player.release();
            player = null;
        }
        if (synth != null) {
            synth.stop();
            synth = null;
        }
    }

    // ── Lock-screen launch ────────────────────────────────────────────────────

    private void launchRingScreen() {
        Intent i = new Intent(this, AlarmRingActivity.class);
        i.putExtra(AlarmRingActivity.EXTRA_ALARM_ID, currentAlarmId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    // ── WakeLock ──────────────────────────────────────────────────────────────

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QRAlarm::WakeLock");
            wakeLock.acquire(7 * 60 * 1_000L);
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private Notification buildNotification() {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent tap  = new Intent(this, AlarmRingActivity.class);
        tap.putExtra(AlarmRingActivity.EXTRA_ALARM_ID, currentAlarmId);
        tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap, piFlags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(getString(R.string.alarm_ringing))
                .setContentText(getString(R.string.scan_qr_to_dismiss))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true).setAutoCancel(false)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.alarm_channel_name),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setBypassDnd(true);
            ch.enableVibration(false);
            ch.setSound(null, null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    public static boolean isAlarmRinging(int alarmId) {
        return sRinging && sRingingId == alarmId;
    }

    public static void stop(android.content.Context ctx) {
        ctx.stopService(new Intent(ctx, AlarmService.class));
    }
}
