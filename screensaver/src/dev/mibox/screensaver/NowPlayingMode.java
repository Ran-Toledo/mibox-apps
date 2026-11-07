package dev.mibox.screensaver;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The track playing in a music app, in one of two layouts:
 * "ribbon" (the Text mode text, with a slim track ribbon along the bottom) or "album" (large cover,
 * title, artist, progress and up next on a background tinted from the cover).
 * When nothing is playing, or notification access is missing, only the Text mode text shows.
 * <p>
 * Sessions are read through MediaSessionManager, which requires the approved {@link MediaListener}.
 * Listening happens only while the screensaver runs.
 */
final class NowPlayingMode implements ScreenMode {
    /** Music apps whose sessions are shown; anything else (live TV, other video apps) is ignored. */
    static final Set<String> APPS = new HashSet<>(Arrays.asList(
            "com.soundcloud.android",
            "com.google.android.youtube.tv",
            "com.google.android.youtube.tvmusic",
            "com.spotify.tv.android"));
    static final String ACCESS_COMMAND =
            "adb shell cmd notification allow_listener dev.mibox.screensaver/dev.mibox.screensaver.MediaListener";

    /** Metadata keys are logged with: adb shell setprop log.tag.NowPlaying DEBUG */
    private static final String TAG = "NowPlaying";
    private static final long PROGRESS_MS = 1000;
    /** After this long on one track the cover and progress bar dim, against burn-in. */
    private static final long DIM_AFTER_MS = 5 * 60_000L;
    private static final float DIMMED = 0.55f;
    private static final int TRACK = 0x33FFFFFF;
    private static final int ART_PX = 480;

    private final Context context;
    private final boolean album;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName listener;
    private final MediaSessionManager sessions;
    private final View root;
    /** Album layout: full-screen color taken from the cover; not drifted. */
    private final View tint;

    private Typeface typeface;
    private int fg;
    private int dim;
    private String template = "";

    private boolean started;
    private boolean noAccess;
    private final List<MediaController> watched = new ArrayList<>();
    private MediaController playing;
    private String trackKey;
    private long trackSince;
    private ExecutorService io;
    /** The cover downloaded for an artwork URI, and that URI. */
    private Bitmap fetchedArt;
    private String fetchedUri;
    private int generation;

    // Views
    private TextView idle;
    private View card;
    private ImageView art;
    private TextView title;
    private TextView subtitle;
    private TextView albumName;
    private TextView source;
    private TextView clock;
    private Progress progress;
    private TextView position;
    private TextView duration;
    private TextView upNext;
    private TextView hint;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    watch(controllers);
                }
            };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            choose();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            choose();
        }

        @Override
        public void onQueueChanged(List<MediaSession.QueueItem> queue) {
            render();
        }

        @Override
        public void onSessionDestroyed() {
            if (started) watch(query());
        }
    };

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (playing != null) handler.postDelayed(this, PROGRESS_MS);
        }
    };

    NowPlayingMode(Context context, SharedPreferences p) {
        this.context = context;
        album = Prefs.NP_ALBUM.equals(p.getString(Prefs.NP_LAYOUT, Prefs.NP_RIBBON));
        listener = new ComponentName(context, MediaListener.class);
        sessions = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        readStyle(p);
        tint = album ? new View(context) : null;
        if (tint != null) tint.setVisibility(View.INVISIBLE);
        root = album ? buildAlbum() : buildRibbon();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public boolean fillsScreen() {
        return true;
    }

    @Override
    public View background() {
        return tint;
    }

    @Override
    public void applyPrefs(SharedPreferences p) {
        render();
    }

    @Override
    public void start() {
        if (started) return;
        started = true;
        generation++;
        io = Executors.newSingleThreadExecutor();
        noAccess = false;
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler);
        } catch (SecurityException e) {
            noAccess = true;
        }
        watch(query());
    }

    @Override
    public void tick() {
        render();
    }

    @Override
    public void stop() {
        started = false;
        generation++;
        handler.removeCallbacks(progressTick);
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
        } catch (RuntimeException ignored) {
            // Never registered (no access).
        }
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        playing = null;
        trackKey = null;
        if (io != null) io.shutdownNow();
        io = null;
        fetchedArt = null;
        fetchedUri = null;
        art.setImageDrawable(null);
        render();
    }

    // ---- Sessions ----

    private List<MediaController> query() {
        try {
            List<MediaController> list = sessions.getActiveSessions(listener);
            noAccess = false;
            return list;
        } catch (SecurityException e) {
            noAccess = true;
            return new ArrayList<>();
        }
    }

    /** Follows the allowed apps' sessions among {@code controllers}, then picks the one to show. */
    private void watch(List<MediaController> controllers) {
        if (!started) return;
        for (MediaController c : watched) c.unregisterCallback(callback);
        watched.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (!APPS.contains(c.getPackageName())) continue;
                c.registerCallback(callback, handler);
                watched.add(c);
            }
        }
        choose();
    }

    /** Shows the first allowed session that is playing (the system lists the most recent first). */
    private void choose() {
        MediaController pick = null;
        for (MediaController c : watched) {
            if (isPlaying(c.getPlaybackState()) && c.getMetadata() != null) {
                pick = c;
                break;
            }
        }
        playing = pick;
        handler.removeCallbacks(progressTick);
        if (playing != null) handler.post(progressTick);
        render();
    }

    private static boolean isPlaying(PlaybackState s) {
        if (s == null) return false;
        int st = s.getState();
        return st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
                || st == PlaybackState.STATE_FAST_FORWARDING || st == PlaybackState.STATE_REWINDING;
    }

    // ---- Rendering ----

    private void render() {
        String now = DateFormat.getTimeFormat(context).format(new Date());
        idle.setText(TextMode.expand(context, template));
        if (clock != null) clock.setText(now);
        hint.setVisibility(noAccess && started ? View.VISIBLE : View.GONE);

        MediaMetadata m = playing == null ? null : playing.getMetadata();
        if (m == null) {
            card.setVisibility(View.GONE);
            if (album) idle.setVisibility(View.VISIBLE);
            if (tint != null) tint.setVisibility(View.INVISIBLE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        if (album) idle.setVisibility(View.GONE);

        String t = first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String artist = first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        String alb = first(m, MediaMetadata.METADATA_KEY_ALBUM);
        title.setText(t);
        if (album) {
            subtitle.setText(artist);
            albumName.setText(alb);
            albumName.setVisibility(alb.isEmpty() ? View.GONE : View.VISIBLE);
            source.setText(("Now playing · " + appName(playing.getPackageName())).toUpperCase(Locale.getDefault()));
            String next = upNext();
            upNext.setText(next == null ? "" : "Up next: " + next);
            upNext.setVisibility(next == null ? View.GONE : View.VISIBLE);
        } else {
            subtitle.setText(alb.isEmpty() || alb.equals(t) ? artist : artist.isEmpty() ? alb : artist + " · " + alb);
        }

        String key = playing.getPackageName() + "|" + t + "|" + artist;
        if (!key.equals(trackKey)) {
            trackKey = key;
            trackSince = System.currentTimeMillis();
            showArt(m);
        }
        float a = System.currentTimeMillis() - trackSince > DIM_AFTER_MS ? DIMMED : 1f;
        art.setAlpha(a);
        progress.setAlpha(a);
        updateProgress();
    }

    private void updateProgress() {
        MediaController c = playing;
        MediaMetadata m = c == null ? null : c.getMetadata();
        PlaybackState s = c == null ? null : c.getPlaybackState();
        long total = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        boolean show = s != null && total > 0 && s.getPosition() >= 0;
        progress.setVisibility(show ? View.VISIBLE : View.GONE);
        position.setVisibility(show ? View.VISIBLE : View.GONE);
        if (duration != null) duration.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        pos = Math.max(0, Math.min(total, pos));
        progress.set(pos / (float) total);
        if (duration != null) {
            position.setText(time(pos));
            duration.setText(time(total));
        } else {
            position.setText(time(pos) + " / " + time(total));
        }
    }

    /** The queue item after the active one, as "Title — Artist"; null if the app shares no queue. */
    private String upNext() {
        List<MediaSession.QueueItem> queue = playing.getQueue();
        PlaybackState s = playing.getPlaybackState();
        if (queue == null || s == null) return null;
        long active = s.getActiveQueueItemId();
        for (int i = 0; i < queue.size() - 1; i++) {
            if (queue.get(i).getQueueId() == active) {
                CharSequence nt = queue.get(i + 1).getDescription().getTitle();
                CharSequence na = queue.get(i + 1).getDescription().getSubtitle();
                if (TextUtils.isEmpty(nt)) return null;
                return TextUtils.isEmpty(na) ? nt.toString() : nt + " — " + na;
            }
        }
        return null;
    }

    /** The cover from the metadata bitmap, or downloaded from its URI in the background. */
    private void showArt(MediaMetadata m) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            for (String k : m.keySet()) {
                Object v = m.getBitmap(k) != null ? m.getBitmap(k).getWidth() + "px bitmap" : m.getText(k);
                Log.d(TAG, k + " = " + v);
            }
        }
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (b != null) {
            setArt(b);
            return;
        }
        final String uri = first(m, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        if (uri.isEmpty()) {
            setArt(null);
            return;
        }
        if (uri.equals(fetchedUri) && fetchedArt != null) {
            setArt(fetchedArt);
            return;
        }
        setArt(null);
        if (io == null) return;
        final int gen = ++generation;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap loaded = load(uri);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || loaded == null) return;
                        fetchedUri = uri;
                        fetchedArt = loaded;
                        setArt(loaded);
                    }
                });
            }
        });
    }

    private void setArt(Bitmap b) {
        if (b == null) {
            art.setImageDrawable(null);
        } else {
            art.setImageBitmap(b);
        }
        if (tint != null) {
            tint.setBackgroundColor(b == null ? 0xFF1A1A1A : tintOf(b));
            tint.setVisibility(View.VISIBLE);
        }
    }

    /** The cover's average color, darkened so white text stays readable. */
    private static int tintOf(Bitmap b) {
        try {
            Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
            int c = one.getPixel(0, 0);
            if (one != b) one.recycle();
            float[] hsv = new float[3];
            Color.colorToHSV(c, hsv);
            hsv[1] = Math.min(hsv[1], 0.6f);
            hsv[2] = Math.min(hsv[2], 0.2f);
            return Color.HSVToColor(hsv);
        } catch (RuntimeException e) {
            return 0xFF1A1A1A;
        }
    }

    /**
     * Downloads (http/https) or opens (content/file) an artwork URI, scaled to at most ART_PX.
     * Apps' content providers usually refuse other apps; SoundCloud's URIs carry the image's real host
     * (content://…/artworks-x.jpg?_HOST_=i1.sndcdn.com&_PROTOCOL_=https), so that address is tried next.
     */
    private Bitmap load(String uri) {
        Bitmap b = decode(uri);
        if (b != null || uri.startsWith("http")) return b;
        Uri u = Uri.parse(uri);
        String host = u.getQueryParameter("_HOST_");
        String scheme = u.getQueryParameter("_PROTOCOL_");
        if (host == null || u.getPath() == null) return null;
        return decode(("http".equals(scheme) ? "http" : "https") + "://" + host + u.getPath());
    }

    private Bitmap decode(String uri) {
        try {
            byte[] data;
            InputStream in = uri.startsWith("http")
                    ? WeatherClient.open(uri)
                    : context.getContentResolver().openInputStream(Uri.parse(uri));
            if (in == null) return null;
            try {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                data = out.toByteArray();
            } finally {
                in.close();
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= ART_PX) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = context.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (!TextUtils.isEmpty(v)) return v.toString().trim();
        }
        return "";
    }

    private static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---- Layouts ----

    private void readStyle(SharedPreferences p) {
        typeface = Prefs.typeface(p);
        fg = Prefs.color(p.getString(Prefs.FG, Prefs.DEFAULT_FG), Prefs.DEFAULT_FG);
        dim = Color.argb(0xA6, Color.red(fg), Color.green(fg), Color.blue(fg));
        template = Prefs.text(context).replace("\\n", "\n");
        idle = text(p.getInt(Prefs.SIZE, Prefs.DEFAULT_SIZE), fg);
        idle.setGravity(Gravity.CENTER);
    }

    /** The text centered in the space above a slim ribbon: cover, title, artist · album, progress. */
    private View buildRibbon() {
        LinearLayout col = vertical(Gravity.CENTER_HORIZONTAL);
        FrameLayout top = new FrameLayout(context);
        top.addView(idle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        col.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout ribbon = horizontal(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(0x1F, Color.red(fg), Color.green(fg), Color.blue(fg)));
        bg.setCornerRadius(dp(14));
        ribbon.setBackground(bg);
        ribbon.setPadding(dp(14), dp(14), dp(24), dp(14));
        art = cover(dp(64));
        ribbon.addView(art, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout words = vertical(Gravity.START);
        words.setPadding(dp(18), 0, dp(24), 0);
        title = oneLine(text(22, fg));
        subtitle = oneLine(text(15, dim));
        words.addView(title);
        words.addView(subtitle);
        ribbon.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout right = vertical(Gravity.END);
        progress = new Progress(context, fg);
        right.addView(progress, new LinearLayout.LayoutParams(dp(220), dp(4)));
        position = text(14, dim);
        position.setPadding(0, dp(8), 0, 0);
        right.addView(position);
        ribbon.addView(right);
        card = ribbon;

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(96), 0, dp(96), dp(40));
        col.addView(ribbon, lp);
        hint = hint();
        col.addView(hint);
        return col;
    }

    /** Source and clock on top; large cover left; title, artist, album, progress and up next right. */
    private View buildAlbum() {
        FrameLayout frame = new FrameLayout(context);
        frame.addView(idle, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hint = hint();
        frame.addView(hint, hp);

        // Over the dark cover tint the text is always white, like Weather's photo backgrounds.
        int white = Color.WHITE;
        int soft = 0xA6FFFFFF;
        LinearLayout page = vertical(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(64), dp(40), dp(64), dp(48));
        FrameLayout bar = new FrameLayout(context);
        source = text(13, soft);
        source.setLetterSpacing(0.15f);
        bar.addView(source, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));
        clock = text(22, white);
        bar.addView(clock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        page.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
        art = cover(dp(300));
        row.addView(art, new LinearLayout.LayoutParams(dp(300), dp(300)));
        LinearLayout words = vertical(Gravity.START);
        words.setPadding(dp(48), 0, 0, 0);
        title = text(40, white);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(title);
        subtitle = oneLine(text(26, white));
        words.addView(subtitle, gap(dp(6)));
        albumName = oneLine(text(18, soft));
        words.addView(albumName, gap(dp(4)));
        progress = new Progress(context, white);
        words.addView(progress, wide(dp(5), dp(32)));
        FrameLayout times = new FrameLayout(context);
        position = text(15, soft);
        duration = text(15, soft);
        times.addView(position, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START));
        times.addView(duration, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        words.addView(times, wide(ViewGroup.LayoutParams.WRAP_CONTENT, dp(8)));
        upNext = oneLine(text(16, soft));
        words.addView(upNext, gap(dp(28)));
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        card = page;
        frame.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private TextView hint() {
        TextView t = text(13, dim);
        t.setGravity(Gravity.CENTER);
        t.setText("Now Playing needs notification access, granted once over adb:\n" + ACCESS_COMMAND);
        t.setPadding(0, 0, 0, dp(24));
        t.setVisibility(View.GONE);
        return t;
    }

    /** Rounded square cover with a faint placeholder behind it for tracks without artwork. */
    private ImageView cover(int size) {
        ImageView v = new ImageView(context);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(TRACK);
        ph.setCornerRadius(size / 16f);
        v.setBackground(ph);
        v.setClipToOutline(true);
        return v;
    }

    private static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(typeface);
        return t;
    }

    private LinearLayout vertical(int gravity) {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(gravity);
        return l;
    }

    private LinearLayout horizontal(int gravity) {
        LinearLayout l = new LinearLayout(context);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(gravity);
        return l;
    }

    private static LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    /** Full width in a vertical LinearLayout, with the given height and top margin. */
    private static LinearLayout.LayoutParams wide(int height, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    /** A thin rounded progress bar: a faint track with the played part in the text color. */
    static final class Progress extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int color;
        private float fraction;

        Progress(Context context, int color) {
            super(context);
            this.color = color;
        }

        void set(float f) {
            if (Math.abs(f - fraction) < 0.001f) return;
            fraction = f;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            paint.setColor(TRACK);
            canvas.drawRoundRect(rect, r, r, paint);
            rect.set(0, 0, Math.max(getHeight(), fraction * getWidth()), getHeight());
            paint.setColor(color);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }
}
