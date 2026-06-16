package com.qralarm.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * Procedural audio composer using AudioTrack.
 *
 * Three looping tones — no external files needed:
 *
 *   STAGE_CALM   (0) — 432 Hz sine wave, gentle fade-in each loop cycle
 *   STAGE_MEDIUM (1) — 600 Hz triangle wave, 4 Hz amplitude pulse
 *   STAGE_LOUD   (2) — 1000+1200 Hz sawtooth/square siren, hard attack
 *
 * Usage:
 *   SoundSynthesizer synth = new SoundSynthesizer();
 *   synth.play(SoundSynthesizer.STAGE_CALM, 0.15f);   // start quiet
 *   synth.setVolume(0.8f);                             // later, ramp up
 *   synth.stop();
 */
public class SoundSynthesizer {

    private static final String TAG       = "SoundSynthesizer";
    private static final int    SAMPLE_HZ = 44100;

    public static final int STAGE_CALM   = 0;
    public static final int STAGE_MEDIUM = 1;
    public static final int STAGE_LOUD   = 2;

    private AudioTrack       track;
    private HandlerThread    writeThread;
    private Handler          writeHandler;
    private volatile boolean running = false;
    private volatile float   volume  = 1.0f;

    // ── Public API ────────────────────────────────────────────────────────────

    public void play(int stage, float initialVolume) {
        stop(); // release any previous track
        volume = initialVolume;

        short[] samples = buildSamples(stage);

        int minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_HZ,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, samples.length * 2);

        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setLegacyStreamType(AudioManager.STREAM_ALARM)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        track.setStereoVolume(volume, volume);
        track.play();

        running = true;
        writeThread = new HandlerThread("SynthWriter");
        writeThread.start();
        writeHandler = new Handler(writeThread.getLooper());
        writeHandler.post(() -> streamLoop(samples));
    }

    public void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        if (track != null) {
            try { track.setStereoVolume(volume, volume); }
            catch (IllegalStateException ignored) {}
        }
    }

    public void stop() {
        running = false;
        if (writeThread != null) {
            writeThread.quitSafely();
            writeThread = null;
        }
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.release();
            } catch (IllegalStateException ignored) {}
            track = null;
        }
    }

    public boolean isPlaying() {
        return running && track != null;
    }

    // ── Sample generators ─────────────────────────────────────────────────────

    /** Builds one full loop cycle of PCM samples for the given stage. */
    private short[] buildSamples(int stage) {
        switch (stage) {
            case STAGE_CALM:   return buildCalm();
            case STAGE_MEDIUM: return buildMedium();
            default:           return buildLoud();
        }
    }

    /**
     * Stage 0 — CALM: 432 Hz pure sine, 4-second loop with a gentle
     * linear fade-in for the first second, then sustain.
     */
    private short[] buildCalm() {
        final double FREQ      = 432.0;
        final int    LOOP_SECS = 4;
        final int    N         = SAMPLE_HZ * LOOP_SECS;
        final int    FADE_SAMP = SAMPLE_HZ; // 1-second fade-in

        short[] buf = new short[N * 2]; // stereo
        for (int i = 0; i < N; i++) {
            double t        = (double) i / SAMPLE_HZ;
            double sine     = Math.sin(2.0 * Math.PI * FREQ * t);
            double envelope = (i < FADE_SAMP) ? (double) i / FADE_SAMP : 1.0;
            short  sample   = (short) (sine * envelope * Short.MAX_VALUE * 0.6);
            buf[i * 2]     = sample; // L
            buf[i * 2 + 1] = sample; // R
        }
        return buf;
    }

    /**
     * Stage 1 — MEDIUM: 600 Hz triangle wave with a 4 Hz amplitude pulse
     * (tremolo), 2-second loop.
     */
    private short[] buildMedium() {
        final double FREQ      = 600.0;
        final double TREMOLO   = 4.0;   // Hz
        final int    LOOP_SECS = 2;
        final int    N         = SAMPLE_HZ * LOOP_SECS;

        short[] buf = new short[N * 2];
        for (int i = 0; i < N; i++) {
            double t        = (double) i / SAMPLE_HZ;
            // Triangle: 2/π * arcsin(sin(2πft))
            double tri      = (2.0 / Math.PI) * Math.asin(Math.sin(2.0 * Math.PI * FREQ * t));
            double tremolo  = 0.5 + 0.5 * Math.sin(2.0 * Math.PI * TREMOLO * t);
            short  sample   = (short) (tri * tremolo * Short.MAX_VALUE * 0.75);
            buf[i * 2]     = sample;
            buf[i * 2 + 1] = sample;
        }
        return buf;
    }

    /**
     * Stage 2 — LOUD: dual-frequency siren alternating between a 1000 Hz
     * sawtooth and 1200 Hz square wave every half-second, full amplitude.
     */
    private short[] buildLoud() {
        final int    LOOP_SECS  = 2;
        final int    N          = SAMPLE_HZ * LOOP_SECS;
        final int    HALF       = SAMPLE_HZ / 2; // 0.5-second phase length
        final double FREQ_SAW   = 1000.0;
        final double FREQ_SQ    = 1200.0;

        short[] buf = new short[N * 2];
        for (int i = 0; i < N; i++) {
            double t      = (double) i / SAMPLE_HZ;
            int    phase  = (i / HALF) % 2; // alternates 0 / 1
            double sample;
            if (phase == 0) {
                // Sawtooth: 2*(t*f - floor(t*f + 0.5))
                double p  = t * FREQ_SAW;
                sample    = 2.0 * (p - Math.floor(p + 0.5));
            } else {
                // Square
                sample    = Math.sin(2.0 * Math.PI * FREQ_SQ * t) >= 0 ? 1.0 : -1.0;
            }
            short s        = (short) (sample * Short.MAX_VALUE * 0.90);
            buf[i * 2]    = s;
            buf[i * 2+1]  = s;
        }
        return buf;
    }

    // ── Stream loop (runs on writeThread) ────────────────────────────────────

    private void streamLoop(short[] samples) {
        final int CHUNK = 2048; // shorts per write
        int pos = 0;
        while (running) {
            int toWrite = Math.min(CHUNK, samples.length - pos);
            if (toWrite <= 0) { pos = 0; continue; }
            if (track == null) break;
            try {
                int written = track.write(samples, pos, toWrite);
                if (written < 0) {
                    Log.e(TAG, "AudioTrack.write error: " + written);
                    break;
                }
                pos += written;
                if (pos >= samples.length) pos = 0; // loop
            } catch (IllegalStateException e) {
                break;
            }
        }
    }
}
