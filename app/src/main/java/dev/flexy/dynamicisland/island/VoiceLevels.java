package dev.flexy.dynamicisland.island;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;

public final class VoiceLevels {
    static final String PACKAGE = "com.sec.android.app.voicenote";
    static final String ACTION = "dev.flexy.dynamicisland.VOICE_LEVEL";
    static final long STEP_MS = 110;
    static final long DELAY_MS = 140;
    static final long SAVED_MS = 3000;
    private static final int SIZE = 160;
    private static final int STATE_RECORDING = 2;
    private static final int STATE_PAUSED = 3;

    private static final VoiceLevels INSTANCE = new VoiceLevels();

    private final long[] times = new long[SIZE];
    private final float[] levels = new float[SIZE];
    private int head;
    private int filled;
    private long lastBatchAt;
    private long lastSampleAt;
    private int state = -1;
    private long savedAt;
    private long frozenAt;
    private boolean registered;
    private Runnable onChange;

    public static VoiceLevels get() {
        return INSTANCE;
    }

    synchronized void register(Context ctx, Runnable changed) {
        onChange = changed;
        if (registered) return;
        registered = true;
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                accept(i.getIntArrayExtra("levels"), i.getIntExtra("state", -1), i.getBooleanExtra("saved", false));
            }
        }, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
    }

    private void accept(int[] amps, int newState, boolean saved) {
        Runnable notify;
        synchronized (this) {
            long now = SystemClock.uptimeMillis();
            int prev = state;
            if (newState >= 0) state = newState;
            if (amps != null && amps.length > 0) {
                long span = lastBatchAt == 0 ? amps.length * STEP_MS : now - lastBatchAt;
                long dt = Math.max(15, Math.min(150, span / amps.length));
                for (int k = 0; k < amps.length; k++) {
                    long t = now - (amps.length - 1 - k) * dt;
                    if (t <= lastSampleAt) t = lastSampleAt + 1;
                    times[head] = t;
                    levels[head] = level(amps[k]);
                    head = (head + 1) % SIZE;
                    filled = Math.min(SIZE, filled + 1);
                    lastSampleAt = t;
                }
                lastBatchAt = now;
            }
            if (state == STATE_PAUSED && prev != STATE_PAUSED) frozenAt = lastSampleAt;
            if (saved) {
                savedAt = now;
                frozenAt = lastSampleAt;
            }
            notify = (saved || prev != state) ? onChange : null;
        }
        if (notify != null) notify.run();
    }

    private static float level(int amp) {
        if (amp <= 0) return 0f;
        double db = 20.0 * Math.log10(amp / 32767.0);
        float v = (float) ((db + 46.0) / 40.0);
        v = Math.max(0f, Math.min(1f, v));
        return (float) Math.pow(v, 1.6);
    }

    public synchronized boolean recording() {
        return state == STATE_RECORDING;
    }

    public synchronized boolean live(long now) {
        return state == STATE_RECORDING && now - lastBatchAt < 1500;
    }

    public synchronized boolean savedRecently(long now) {
        return savedAt > 0 && now - savedAt < SAVED_MS;
    }

    public synchronized long savedUntil() {
        return savedAt + SAVED_MS;
    }

    public synchronized long renderTime(long now, boolean saved) {
        if (saved || state != STATE_RECORDING) return frozenAt > 0 ? frozenAt : lastSampleAt;
        return now - DELAY_MS;
    }

    public synchronized float levelAt(long t) {
        if (filled == 0) return 0f;
        int newest = (head - 1 + SIZE) % SIZE;
        if (t >= times[newest]) return levels[newest];
        int prev = newest;
        for (int n = 1; n < filled; n++) {
            int i = (newest - n + SIZE) % SIZE;
            if (times[i] <= t) {
                long span = times[prev] - times[i];
                float f = span <= 0 ? 1f : (t - times[i]) / (float) span;
                return levels[i] + (levels[prev] - levels[i]) * f;
            }
            prev = i;
        }
        return 0f;
    }

    public static float synthetic(long t) {
        double s = t / 1000.0;
        double burst = 0.5 + 0.5 * Math.sin(s * 2.1 + Math.sin(s * 0.7) * 2.0);
        double syll = Math.abs(Math.sin(s * 4.6 + Math.sin(s * 1.7) * 1.4));
        double grain = 0.5 + 0.5 * Math.sin(s * 11.0 + Math.cos(s * 2.9) * 2.0);
        double v = 0.12 + burst * (0.55 * syll + 0.2 * grain);
        return (float) Math.pow(Math.max(0.0, Math.min(1.0, v)), 1.5);
    }

    synchronized void clearSaved() {
        savedAt = 0;
    }
}
