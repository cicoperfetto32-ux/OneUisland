package dev.flexy.dynamicisland.xposed;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class AssistantReporter {
    static final String ACTION = "dev.flexy.dynamicisland.CLAUDE";
    static String app = "claude";
    static final String WORKING = "working";
    static final String QUESTION = "question";
    static final String PERMISSION = "permission";
    static final String DONE = "done";
    static final String IDLE = "idle";
    private static final int MAX_STAGES = 3;
    private static final long SEND_MS = 300;

    private static final List<String> stages = new ArrayList<>();
    private static final List<String> kinds = new ArrayList<>();
    private static final java.util.Set<String> tasks = new java.util.LinkedHashSet<>();
    private static String state = IDLE;
    private static String question;
    private static String sentState = IDLE;
    private static boolean posted;
    private static long sentAt;
    private static Handler handler;

    private AssistantReporter() {
    }

    static synchronized void working() {
        if (!WORKING.equals(state)) question = null;
        state = WORKING;
        schedule();
    }

    static synchronized void stage(String kind, String text) {
        if (text == null) return;
        String t = text.replace('\n', ' ').trim();
        if (t.isEmpty()) return;
        if (t.length() > 90) t = t.substring(0, 89).trim() + "…";
        if (!stages.isEmpty() && stages.get(stages.size() - 1).equals(t)) return;
        stages.add(t);
        kinds.add(kind);
        while (stages.size() > MAX_STAGES) {
            stages.remove(0);
            kinds.remove(0);
        }
        if (!QUESTION.equals(state) && !PERMISSION.equals(state)) state = WORKING;
        schedule();
    }

    static synchronized void ask(String text, boolean permission) {
        if (text == null || text.trim().isEmpty()) return;
        question = text.trim();
        state = permission ? PERMISSION : QUESTION;
        schedule();
    }

    static synchronized void pendingQuestion(String text) {
        if (text != null && !text.trim().isEmpty()) question = text.trim();
    }

    static synchronized void finished(boolean ok) {
        if (question != null && !PERMISSION.equals(state)) state = QUESTION;
        else if (!PERMISSION.equals(state)) state = ok ? DONE : IDLE;
        schedule();
    }

    static synchronized void taskStarted(String id) {
        if (id == null || !tasks.add(id)) return;
        schedule();
    }

    static synchronized void taskEnded(String id) {
        if (id == null || !tasks.remove(id)) return;
        schedule();
    }

    static String label(String en, String ru) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? ru : en;
    }

    private static void schedule() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        if (posted) return;
        posted = true;
        long wait = Math.max(0, SEND_MS - (SystemClock.uptimeMillis() - sentAt));
        handler.postDelayed(AssistantReporter::send, wait);
    }

    private static final Runnable heartbeat = AssistantReporter::schedule;

    private static void send() {
        String out;
        String[] list;
        String[] kindList;
        String q;
        int running;
        synchronized (AssistantReporter.class) {
            running = tasks.size();
            posted = false;
            sentAt = SystemClock.uptimeMillis();
            out = state;
            if (DONE.equals(out) && !WORKING.equals(sentState) && !DONE.equals(sentState)) out = IDLE;
            sentState = out;
            list = stages.toArray(new String[0]);
            kindList = kinds.toArray(new String[0]);
            q = question;
            handler.removeCallbacks(heartbeat);
            if (!IDLE.equals(state) && !DONE.equals(state) || !tasks.isEmpty()) handler.postDelayed(heartbeat, 20_000);
        }
        try {
            Context ctx = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if (ctx == null) return;
            Intent i = new Intent(ACTION).setPackage("com.android.systemui");
            i.putExtra("state", out);
            i.putExtra("app", app);
            i.putExtra("stages", list);
            i.putExtra("kinds", kindList);
            if (q != null) i.putExtra("question", q);
            i.putExtra("tasks", running);
            ctx.sendBroadcast(i);
        } catch (Throwable t) {
            XLog.e("claude send", t);
        }
    }
}
