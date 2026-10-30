package dev.flexy.dynamicisland.island;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.flexy.dynamicisland.xposed.XLog;

public final class AssistantApp {
    static final int STYLE_DOTS = 0;
    static final int STYLE_PULSE = 1;
    private static final String ACTION = "dev.flexy.dynamicisland.CLAUDE";
    private static final String ACTION_DISMISS = "dev.flexy.dynamicisland.ASSISTANT_DISMISS";
    private static final String CHANNEL = "dynamic_island_assistants_nowbar";
    private static final String[] OLD_CHANNELS = {"dynamic_island_assistants", "dynamic_island_assistants_live", "dynamic_island_assistants_shade"};
    private static final String TAG = "dynamic_island_assistant";
    private static final long DONE_MS = 5 * 60_000;
    private static final long STALE_MS = 20 * 60_000;

    static final AssistantApp CLAUDE = new AssistantApp("claude", "com.anthropic.claude", "Claude", 0xFFD97757, STYLE_DOTS,
            "claude_spark_icon", true, 0x0C1A,
            "androidx.health.platform.client.impl.sdkservice.HealthDataSdkService",
            "androidx.health.platform.client.ACTION_BIND_SDK_SERVICE",
            new String[] {"cmd", "cds_icon_code", "read", "cds_icon_file", "edit", "cds_icon_file", "search", "cds_icon_search",
                    "web", "cds_icon_globe", "agent", "cds_icon_dispatch", "artifact", "cds_icon_artifacts", "text", "cds_icon_chat",
                    "user", "cds_icon_chat", "ask", "cds_icon_chat", "think", "claude_spark_icon", "tool", "cds_icon_tasks"});
    static final AssistantApp CHATGPT = new AssistantApp("chatgpt", "com.openai.chatgpt", "ChatGPT", 0xFFFFFFFF, STYLE_PULSE,
            "ds_openai_blossom", false, 0x0C1B, null, null,
            new String[] {"cmd", "ds_terminal_regular_24", "read", "ds_folder_regular_24", "edit", "ds_pencil_regular_24",
                    "search", "ic_search_black_24", "web", "ds_globe_regular_24", "agent", "ds_codex_regular_24",
                    "artifact", "ds_braces_regular_24", "text", "ds_chat_bubble_regular_24", "user", "ds_chat_bubble_regular_24",
                    "ask", "ds_chat_bubble_regular_24", "think", "ds_brain_regular_24", "tool", "ds_wrench_regular_24"});
    private static final AssistantApp[] ALL = {CLAUDE, CHATGPT};

    final String id;
    final String pkg;
    final String title;
    final int accent;
    final int style;
    private final String iconName;
    private final boolean iconColors;
    private final int requestCode;
    private final String keepAliveService;
    private final String keepAliveAction;
    private final Map<String, String> kindNames = new HashMap<>();
    private final Map<String, Drawable> kindIcons = new HashMap<>();

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean registered;
    private static Runnable onChange;
    private static Context appContext;

    private String state = "idle";
    private String[] stages = new String[0];
    private String[] kinds = new String[0];
    private String question;
    private int tasks;
    private long updatedAt;
    private boolean bound;
    private Drawable icon;
    private PendingIntent open;
    private String posted;
    private String dismissed;
    private PendingIntent dismiss;
    private final android.content.ServiceConnection keepAlive = new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) {
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName name) {
        }
    };

    private AssistantApp(String id, String pkg, String title, int accent, int style, String iconName, boolean iconColors,
                         int requestCode, String keepAliveService, String keepAliveAction, String[] kindMap) {
        this.id = id;
        this.pkg = pkg;
        this.title = title;
        this.accent = accent;
        this.style = style;
        this.iconName = iconName;
        this.iconColors = iconColors;
        this.requestCode = requestCode;
        this.keepAliveService = keepAliveService;
        this.keepAliveAction = keepAliveAction;
        for (int i = 0; i + 1 < kindMap.length; i += 2) kindNames.put(kindMap[i], kindMap[i + 1]);
    }

    static void register(Context ctx, Runnable changed) {
        onChange = changed;
        appContext = ctx;
        if (registered) return;
        registered = true;
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            for (AssistantApp a : ALL) nm.cancel(TAG + "_" + a.id, a.requestCode);
        } catch (Throwable ignored) {
        }
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String app = i.getStringExtra("app");
                AssistantApp a = "chatgpt".equals(app) ? CHATGPT : CLAUDE;
                if (ACTION_DISMISS.equals(i.getAction())) a.dismiss();
                else a.accept(i);
            }
        }, filter(), Context.RECEIVER_EXPORTED);
    }

    static void collectAll(List<IslandItem> out, Context ctx) {
        out.removeIf(i -> i.key != null && i.key.contains(TAG));
        for (AssistantApp a : ALL) {
            IslandItem it = a.item(ctx);
            a.syncNotification(ctx, it);
            if (it != null) out.add(it);
        }
    }

    static IslandItem itemForKey(String key) {
        if (key == null || !key.contains(TAG)) return null;
        for (AssistantApp a : ALL) {
            if (key.contains("|" + TAG + "_" + a.id + "|") || key.endsWith("|" + TAG + "_" + a.id)) {
                return appContext == null ? null : a.item(appContext);
            }
        }
        return null;
    }

    private static IntentFilter filter() {
        IntentFilter f = new IntentFilter(ACTION);
        f.addAction(ACTION_DISMISS);
        return f;
    }

    private void dismiss() {
        dismissed = content();
        posted = null;
        if (onChange != null) onChange.run();
    }

    private String content() {
        return state + "|" + String.join("/", stages) + "|" + question;
    }

    private void accept(Intent i) {
        String next = i.getStringExtra("state");
        if (next == null) next = "idle";
        boolean keepTime = "done".equals(next) && "done".equals(state);
        long keep = updatedAt;
        state = next;
        String[] s = i.getStringArrayExtra("stages");
        stages = s == null ? new String[0] : s;
        String[] k = i.getStringArrayExtra("kinds");
        kinds = k == null ? new String[0] : k;
        question = i.getStringExtra("question");
        tasks = i.getIntExtra("tasks", 0);
        updatedAt = keepTime ? keep : SystemClock.uptimeMillis();
        main.removeCallbacks(expire);
        if ("done".equals(state)) main.postDelayed(expire, Math.max(50, DONE_MS + 50 - (SystemClock.uptimeMillis() - updatedAt)));
        else if (!"idle".equals(state) || tasks > 0) main.postDelayed(expire, STALE_MS + 50);
        updateKeepAlive();
        if (onChange != null) onChange.run();
    }

    private final Runnable expire = () -> {
        updateKeepAlive();
        if (onChange != null) onChange.run();
    };

    private void updateKeepAlive() {
        if (keepAliveService == null || appContext == null) return;
        long now = SystemClock.uptimeMillis();
        boolean busy = updatedAt != 0 && now - updatedAt <= STALE_MS && (working(state) || waiting(state) || tasks > 0);
        if (busy == bound) return;
        try {
            if (busy) {
                Intent i = new Intent(keepAliveAction).setClassName(pkg, keepAliveService);
                bound = appContext.bindService(i, keepAlive, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
            } else {
                appContext.unbindService(keepAlive);
                bound = false;
            }
        } catch (Throwable t) {
            bound = false;
            XLog.e(id + " keep alive", t);
        }
    }

    static boolean waiting(String state) {
        return "question".equals(state) || "permission".equals(state);
    }

    static boolean working(String state) {
        return "working".equals(state);
    }

    private IslandItem item(Context ctx) {
        long now = SystemClock.uptimeMillis();
        if (updatedAt == 0 || now - updatedAt > STALE_MS) return null;
        boolean finished = "idle".equals(state) || ("done".equals(state) && now - updatedAt > DONE_MS);
        if (finished && tasks <= 0) return null;
        if (dismissed != null) {
            if (dismissed.equals(content())) return null;
            dismissed = null;
        }
        String shown = finished || ("done".equals(state) && tasks > 0) ? "background" : state;
        int running = tasks;
        load(ctx);
        IslandItem it = new IslandItem("assistant:" + id);
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = pkg;
        it.claude = true;
        it.assistantStyle = style;
        it.claudeState = shown;
        it.claudeStages = stages;
        it.claudeQuestion = question;
        it.claudeTasks = running;
        Drawable[] icons = new Drawable[stages.length];
        for (int n = 0; n < icons.length; n++) icons[n] = kindIcon(ctx, n < kinds.length ? kinds[n] : null);
        it.claudeIcons = icons;
        it.claudeTaskIcon = kindIcon(ctx, "cmd");
        it.title = title;
        it.subtitle = stages.length > 0 ? stages[stages.length - 1] : null;
        it.icon = icon;
        it.cardImage = icon;
        it.iconIsImage = iconColors;
        it.cardImageIsImage = iconColors;
        it.iconColor = true;
        it.iconTint = iconColors ? 0 : 0xFFFFFFFF;
        it.accent = accent;
        it.trailing = IslandItem.Trailing.CLAUDE;
        it.contentIntent = open;
        it.postTime = updatedAt;
        return it;
    }

    private static final String OA = "android.ongoingActivityNoti.";
    private static final int SEM_ONGOING_ACTIVITY = 0x8000;

    private void syncNotification(Context ctx, IslandItem it) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return;
            String tag = TAG + "_" + id;
            if (it == null) {
                if (posted != null) nm.cancel(tag, requestCode);
                posted = null;
                return;
            }
            String text = it.claudeQuestion != null && waiting(it.claudeState) ? it.claudeQuestion
                    : it.subtitle != null ? it.subtitle.toString() : stateLabel(it.claudeState);
            String sig = it.claudeState + "|" + String.join("/", it.claudeStages) + "|" + text + "|" + tasks;
            if (sig.equals(posted)) return;
            if (nm.getNotificationChannel(CHANNEL) == null) {
                for (String old : OLD_CHANNELS) {
                    if (nm.getNotificationChannel(old) != null) nm.deleteNotificationChannel(old);
                }
                NotificationChannel ch = new NotificationChannel(CHANNEL, "AI assistants", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
            int iconId = 0;
            try {
                iconId = ctx.createPackageContext(pkg, 0).getResources().getIdentifier(iconName, "drawable", pkg);
            } catch (Throwable ignored) {
            }
            Icon small = iconId != 0 ? Icon.createWithResource(pkg, iconId) : Icon.createWithResource(ctx, android.R.drawable.stat_notify_sync);
            String header = title + " · " + stateLabel(it.claudeState);
            Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(small)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setCategory(Notification.CATEGORY_PROGRESS)
                    .setContentIntent(open)
                    .setDeleteIntent(dismissIntent(ctx))
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setColor(0xFF000000)
                    .setColorized(false)
                    .setCustomBigContentView(expandedView(ctx, it, false))
                    .setStyle(new Notification.DecoratedCustomViewStyle());
            try {
                Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class).invoke(b, true);
            } catch (Throwable ignored) {
            }
            android.os.Bundle extras = new android.os.Bundle();
            extras.putString("android.substName", title);
            extras.putInt(OA + "style", 1);
            extras.putCharSequence(OA + "primaryInfo", title);
            extras.putCharSequence(OA + "secondaryInfo", text);
            extras.putCharSequence(OA + "chipExpandedText", title);
            extras.putCharSequence(OA + "nowbarPrimaryInfo", header);
            extras.putCharSequence(OA + "nowbarSecondaryInfo", text);
            extras.putInt(OA + "chipBgColor", 0xFF000000);
            extras.putBoolean("android.showSmallIcon", true);
            extras.putParcelable(OA + "chipIcon", small);
            extras.putParcelable(OA + "nowbarIcon", small);
            extras.putParcelable(OA + "firstIcon", small);
            extras.putParcelable(OA + "secondaryInfoIcon", small);
            android.widget.RemoteViews card = expandedView(ctx, it, true);
            extras.putParcelable(OA + "nowbarExpandRemoteView", card);
            extras.putParcelable(OA + "expandedRemoteView", card);
            b.addExtras(extras);
            Notification n = b.build();
            try {
                java.lang.reflect.Field f = Notification.class.getDeclaredField("semFlags");
                f.setAccessible(true);
                f.setInt(n, f.getInt(n) | SEM_ONGOING_ACTIVITY);
            } catch (Throwable ignored) {
            }
            nm.notify(tag, requestCode, n);
            posted = sig;
        } catch (Throwable t) {
            XLog.e(id + " shade notification", t);
        }
    }

    private android.widget.RemoteViews expandedView(Context ctx, IslandItem it, boolean nowBar) {
        android.widget.RemoteViews rv = new android.widget.RemoteViews("dev.flexy.dynamicisland",
                dev.flexy.dynamicisland.R.layout.assistant_expanded);
        rv.setTextViewText(dev.flexy.dynamicisland.R.id.assistant_state, nowBar ? title + " · " + stateLabel(it.claudeState) : stateLabel(it.claudeState));
        if (nowBar) {
            int pad = Math.round(20 * ctx.getResources().getDisplayMetrics().density);
            rv.setViewPadding(dev.flexy.dynamicisland.R.id.assistant_root, pad, pad, pad, pad);
            rv.setTextColor(dev.flexy.dynamicisland.R.id.assistant_state, 0xFFFFFFFF);
        }
        int[] rows = {dev.flexy.dynamicisland.R.id.assistant_row0, dev.flexy.dynamicisland.R.id.assistant_row1,
                dev.flexy.dynamicisland.R.id.assistant_row2};
        int[] texts = {dev.flexy.dynamicisland.R.id.assistant_text0, dev.flexy.dynamicisland.R.id.assistant_text1,
                dev.flexy.dynamicisland.R.id.assistant_text2};
        int[] icons = {dev.flexy.dynamicisland.R.id.assistant_icon0, dev.flexy.dynamicisland.R.id.assistant_icon1,
                dev.flexy.dynamicisland.R.id.assistant_icon2};
        float[] alpha = {0.5f, 0.75f, 1f};
        String[] lines = stages;
        String[] lineKinds = kinds;
        if (question != null && waiting(it.claudeState)) {
            int keep = Math.min(2, stages.length);
            lines = new String[keep + 1];
            lineKinds = new String[keep + 1];
            for (int n = 0; n < keep; n++) {
                lines[n] = stages[stages.length - keep + n];
                lineKinds[n] = stages.length - keep + n < kinds.length ? kinds[stages.length - keep + n] : null;
            }
            lines[keep] = question;
            lineKinds[keep] = "ask";
        }
        int first = rows.length - lines.length;
        for (int n = 0; n < rows.length; n++) {
            int r = n - first;
            if (r < 0 || r >= lines.length) {
                rv.setViewVisibility(rows[n], android.view.View.GONE);
                continue;
            }
            rv.setViewVisibility(rows[n], android.view.View.VISIBLE);
            rv.setTextViewText(texts[n], lines[r]);
            rv.setFloat(rows[n], "setAlpha", alpha[n]);
            Icon ic = kindResIcon(ctx, r < lineKinds.length ? lineKinds[r] : null);
            if (ic != null) rv.setImageViewIcon(icons[n], ic);
            else rv.setViewVisibility(icons[n], android.view.View.GONE);
            if (nowBar) {
                rv.setTextColor(texts[n], 0xFFFFFFFF);
                rv.setInt(icons[n], "setColorFilter", 0xFFFFFFFF);
            }
            if (n == rows.length - 1 && iconColors && working(it.claudeState)) rv.setInt(icons[n], "setColorFilter", accent);
        }
        if (it.claudeTasks > 0) {
            rv.setViewVisibility(dev.flexy.dynamicisland.R.id.assistant_tasks, android.view.View.VISIBLE);
            rv.setTextViewText(dev.flexy.dynamicisland.R.id.assistant_tasks_text, tasksLabel(it.claudeTasks));
            Icon ic = kindResIcon(ctx, "cmd");
            if (ic != null) rv.setImageViewIcon(dev.flexy.dynamicisland.R.id.assistant_tasks_icon, ic);
            if (nowBar) {
                rv.setTextColor(dev.flexy.dynamicisland.R.id.assistant_tasks_text, 0x99FFFFFF);
                rv.setInt(dev.flexy.dynamicisland.R.id.assistant_tasks_icon, "setColorFilter", 0x99FFFFFF);
            }
        } else {
            rv.setViewVisibility(dev.flexy.dynamicisland.R.id.assistant_tasks, android.view.View.GONE);
        }
        return rv;
    }

    private PendingIntent dismissIntent(Context ctx) {
        if (dismiss == null) {
            Intent i = new Intent(ACTION_DISMISS).setPackage(ctx.getPackageName()).putExtra("app", id);
            dismiss = PendingIntent.getBroadcast(ctx, requestCode, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        }
        return dismiss;
    }

    private Icon kindResIcon(Context ctx, String kind) {
        String name = kindNames.get(kind == null ? "tool" : kind);
        if (name == null) name = kindNames.get("tool");
        try {
            int id = ctx.createPackageContext(pkg, 0).getResources().getIdentifier(name, "drawable", pkg);
            return id == 0 ? null : Icon.createWithResource(pkg, id);
        } catch (Throwable t) {
            return null;
        }
    }

    private Drawable kindIcon(Context ctx, String kind) {
        String name = kindNames.get(kind == null ? "tool" : kind);
        if (name == null) name = kindNames.get("tool");
        if (kindIcons.containsKey(name)) return kindIcons.get(name);
        Drawable d = null;
        try {
            Context c = ctx.createPackageContext(pkg, 0);
            int id = c.getResources().getIdentifier(name, "drawable", pkg);
            if (id != 0) d = c.getDrawable(id);
        } catch (Throwable ignored) {
        }
        kindIcons.put(name, d);
        return d;
    }

    private void load(Context ctx) {
        if (icon != null && open != null) return;
        try {
            Context c = ctx.createPackageContext(pkg, 0);
            int id = c.getResources().getIdentifier(iconName, "drawable", pkg);
            if (id != 0 && icon == null) icon = c.getDrawable(id);
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null && open == null) {
                open = PendingIntent.getActivity(ctx, requestCode, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            }
        } catch (Throwable t) {
            XLog.e(id + " resources", t);
        }
    }

    static String text(String en, String ru) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? ru : en;
    }

    static String tasksLabel(int n) {
        if ("ru".equals(Locale.getDefault().getLanguage())) {
            int m10 = n % 10, m100 = n % 100;
            String word = m10 == 1 && m100 != 11 ? "фоновая задача"
                    : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? "фоновые задачи" : "фоновых задач";
            return n + " " + word;
        }
        return n + (n == 1 ? " running task" : " running tasks");
    }

    static String stateLabel(String state) {
        switch (state) {
            case "question":
                return text("Question for you", "Вопрос к вам");
            case "permission":
                return text("Needs permission", "Нужно разрешение");
            case "done":
                return text("Done", "Готово");
            case "background":
                return text("Running in background", "Работает в фоне");
            default:
                return text("Working", "Работает");
        }
    }
}
