package dev.flexy.dynamicisland.island;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import dev.flexy.dynamicisland.xposed.XLog;

final class SpotifyRecents {
    static final String PACKAGE = "com.spotify.music";
    private static final ComponentName SERVICE = new ComponentName(PACKAGE,
            "com.spotify.mediabrowserservice.mediabrowserservice.SpotifyMediaBrowserService");
    private static final int COUNT = 4;
    private static final long REFRESH_MS = 90_000;
    private static final long RETRY_MS = 20_000;

    static final class Entry {
        final String id;
        final CharSequence title;
        final Bitmap art;

        Entry(String id, CharSequence title, Bitmap art) {
            this.id = id;
            this.title = title;
            this.art = art;
        }
    }

    private static final SpotifyRecents INSTANCE = new SpotifyRecents();

    private final Handler main = new Handler(Looper.getMainLooper());
    private Handler io;
    private MediaBrowser browser;
    private boolean connecting;
    private long fetchedAt;
    private long attemptAt;
    private List<Entry> entries = Collections.emptyList();
    private Runnable onChange;
    private String pendingPlay;

    static SpotifyRecents get() {
        return INSTANCE;
    }

    List<Entry> entries() {
        return entries;
    }

    void request(Context ctx, Runnable changed) {
        onChange = changed;
        long now = SystemClock.uptimeMillis();
        if (connecting) return;
        if (fetchedAt != 0 && now - fetchedAt < REFRESH_MS) return;
        if (attemptAt != 0 && now - attemptAt < RETRY_MS && entries.isEmpty()) return;
        attemptAt = now;
        main.post(() -> refresh(ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx));
    }

    void play(Context ctx, String id) {
        if (browser != null && browser.isConnected()) {
            playNow(ctx, id);
            return;
        }
        pendingPlay = id;
        fetchedAt = 0;
        attemptAt = 0;
        request(ctx, onChange);
    }

    private void playNow(Context ctx, String id) {
        try {
            MediaController mc = new MediaController(ctx, browser.getSessionToken());
            mc.getTransportControls().playFromMediaId(id, null);
        } catch (Throwable t) {
            XLog.e("spotify play failed", t);
        }
    }

    private void refresh(Context ctx) {
        if (browser != null && browser.isConnected()) {
            browse(ctx);
            return;
        }
        connecting = true;
        try {
            if (browser != null) browser.disconnect();
        } catch (Throwable ignored) {
        }
        try {
            browser = new MediaBrowser(ctx, SERVICE, new MediaBrowser.ConnectionCallback() {
                @Override
                public void onConnected() {
                    connecting = false;
                    browse(ctx);
                }

                @Override
                public void onConnectionFailed() {
                    connecting = false;
                }

                @Override
                public void onConnectionSuspended() {
                    connecting = false;
                }
            }, null);
            browser.connect();
        } catch (Throwable t) {
            connecting = false;
            XLog.e("spotify browser", t);
        }
    }

    private void browse(Context ctx) {
        subscribeOnce(browser.getRoot(), children -> {
            MediaBrowser.MediaItem section = findRecent(children);
            if (section == null) {
                for (MediaBrowser.MediaItem m : children) {
                    if (m.isBrowsable()) {
                        section = m;
                        break;
                    }
                }
            }
            if (section != null) fetchRecent(ctx, section.getMediaId());
        });
    }

    private static boolean isRecent(MediaBrowser.MediaItem m) {
        String id = String.valueOf(m.getMediaId()).toLowerCase(Locale.ROOT);
        CharSequence t = m.getDescription().getTitle();
        String title = t == null ? "" : t.toString().toLowerCase(Locale.ROOT);
        return id.contains("recent") || title.contains("recent") || title.contains("недавн");
    }

    private static MediaBrowser.MediaItem findRecent(List<MediaBrowser.MediaItem> items) {
        for (MediaBrowser.MediaItem m : items) if (m.isBrowsable() && isRecent(m)) return m;
        return null;
    }

    private void fetchRecent(Context ctx, String id) {
        subscribeOnce(id, children -> {
            List<MediaBrowser.MediaItem> picked = new ArrayList<>();
            for (MediaBrowser.MediaItem m : children) {
                if (picked.size() < COUNT) picked.add(m);
            }
            fetchedAt = SystemClock.uptimeMillis();
            loadArt(ctx, picked);
        });
    }

    private void loadArt(Context ctx, List<MediaBrowser.MediaItem> picked) {
        if (io == null) {
            HandlerThread t = new HandlerThread("SpotifyRecents");
            t.start();
            io = new Handler(t.getLooper());
        }
        io.post(() -> {
            List<Entry> out = new ArrayList<>();
            for (MediaBrowser.MediaItem m : picked) {
                MediaDescription d = m.getDescription();
                Bitmap art = d.getIconBitmap();
                if (art == null && d.getIconUri() != null) art = decode(ctx, square(d.getIconUri()));
                out.add(new Entry(m.getMediaId(), d.getTitle(), art));
            }
            main.post(() -> {
                entries = out;
                String pending = pendingPlay;
                pendingPlay = null;
                if (pending != null && browser != null && browser.isConnected()) playNow(ctx, pending);
                if (onChange != null) onChange.run();
            });
        });
    }

    private static Uri square(Uri uri) {
        if (uri.getQueryParameter("transformation") == null) return uri;
        Uri.Builder b = uri.buildUpon().clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            if (name.equals("transformation")) continue;
            for (String v : uri.getQueryParameters(name)) b.appendQueryParameter(name, v);
        }
        return b.build();
    }

    private static Bitmap decode(Context ctx, Uri uri) {
        try {
            InputStream in;
            String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                HttpURLConnection c = (HttpURLConnection) new URL(uri.toString()).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                in = c.getInputStream();
            } else {
                in = ctx.getContentResolver().openInputStream(uri);
            }
            try (InputStream s = in) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                Bitmap b = BitmapFactory.decodeStream(s, null, o);
                if (b != null && b.getWidth() > 240) b = Bitmap.createScaledBitmap(b, 240, Math.max(1, 240 * b.getHeight() / b.getWidth()), true);
                return b;
            }
        } catch (Throwable t) {
            XLog.e("spotify art " + uri, t);
            return null;
        }
    }

    private interface Children {
        void on(List<MediaBrowser.MediaItem> children);
    }

    private void subscribeOnce(String id, Children cb) {
        browser.subscribe(id, new MediaBrowser.SubscriptionCallback() {
            @Override
            public void onChildrenLoaded(String parentId, List<MediaBrowser.MediaItem> children) {
                try {
                    browser.unsubscribe(parentId, this);
                } catch (Throwable ignored) {
                }
                cb.on(children);
            }

            @Override
            public void onError(String parentId) {
                connecting = false;
            }
        });
    }
}
