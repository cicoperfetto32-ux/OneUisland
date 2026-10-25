package dev.flexy.dynamicisland.xposed;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.Arrays;

final class VoiceRecorderHook {
    static final String PACKAGE = "com.sec.android.app.voicenote";
    private static final String RECORDER = "com.sec.android.app.voicenote.engine.Recorder";
    private static final String ACTION = "dev.flexy.dynamicisland.VOICE_LEVEL";
    private static final int WHAT_STATE = 1010;
    private static final int WHAT_PROGRESS = 1011;
    private static final long BATCH_MS = 90;

    private static final int[] pending = new int[48];
    private static int count;
    private static int state = -1;
    private static long lastSent;

    private VoiceRecorderHook() {
    }

    static void install(ClassLoader cl) {
        int n = Refl.hookAll(cl, RECORDER, "notifyObservers", SafeHook.before("voice level", p -> {
            if (p.args.length != 3 || !(p.args[0] instanceof Integer)) return;
            int what = (Integer) p.args[0];
            if (what == WHAT_PROGRESS) {
                add((Integer) p.args[2]);
            } else if (what == WHAT_STATE) {
                setState((Integer) p.args[1]);
            }
        }));
        n += Refl.hookAll(cl, RECORDER, "saveFile", SafeHook.after("voice saved", p -> {
            if (Boolean.FALSE.equals(p.getResult())) return;
            send(true);
        }));
        XLog.i("voice recorder hooks: " + n);
    }

    private static synchronized void add(int amplitude) {
        if (count < pending.length) pending[count++] = Math.max(0, amplitude);
        if (SystemClock.uptimeMillis() - lastSent >= BATCH_MS) send(false);
    }

    private static synchronized void setState(int s) {
        state = s;
        send(false);
    }

    private static synchronized void send(boolean saved) {
        try {
            Context ctx = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (ctx == null) return;
            Intent i = new Intent(ACTION).setPackage("com.android.systemui");
            i.putExtra("levels", Arrays.copyOf(pending, count));
            i.putExtra("state", state);
            if (saved) i.putExtra("saved", true);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XLog.e("voice level send", t);
        }
        count = 0;
        lastSent = SystemClock.uptimeMillis();
    }
}
