package dev.mibox.screensaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Weather from Open-Meteo, in one of two layouts:
 * "glance" (the Text mode text with one weather line under it) or "board" (full-screen forecast).
 * The last forecast is cached in prefs; a new one is fetched only while the screensaver runs.
 */
final class WeatherMode implements ScreenMode {
    private static final long REFRESH_MS = 30 * 60_000L;
    private static final long RETRY_MS = 5 * 60_000L;
    private static final long STALE_MS = 3 * 3_600_000L;
    private static final long MAX_AGE_MS = 12 * 3_600_000L;
    private static final int ACCENT = 0xFF7FB2E5;
    private static final int TRACK = 0x33FFFFFF;
    private static final String CREDIT = "Weather: Open-Meteo.com";

    private final Context context;
    private final SharedPreferences prefs;
    private final boolean board;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View root;
    /** Null unless the background style is weather photos. */
    private final PhotoBackground photos;

    private Typeface typeface;
    private int fg;
    private int dim;
    private String template = "";

    private Weather weather;
    private long fetchedAt;
    private boolean fetching;
    private boolean lastFetchFailed;
    private long lastAttempt;
    /** Incremented on stop(), so a fetch finishing afterwards does not touch the views. */
    private int generation;

    // Shared
    private TextView clock;
    private ImageView icon;
    private TextView temp;
    private TextView condition;
    private TextView detail;
    private TextView message;
    // Glance only
    private View weatherBox;
    private TextView place;
    // Board only
    private TextView updated;
    private TextView date;
    private View forecast;
    private TextView hoursLabel;
    private LinearLayout hoursRow;
    private LinearLayout daysCol;

    WeatherMode(Context context, SharedPreferences p) {
        this.context = context;
        this.prefs = p;
        board = Prefs.LAYOUT_BOARD.equals(p.getString(Prefs.WX_LAYOUT, Prefs.LAYOUT_GLANCE));
        photos = Prefs.BG_PHOTO.equals(p.getString(Prefs.BG_STYLE, Prefs.BG_SOLID))
                ? new PhotoBackground(context, p.getInt(Prefs.BG_MINUTES, Prefs.DEFAULT_BG_MINUTES)) : null;
        readStyle(p);
        root = board ? buildBoard() : buildGlance();
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public boolean fillsScreen() {
        return board;
    }

    @Override
    public View background() {
        return photos == null ? null : photos.view();
    }

    @Override
    public void applyPrefs(SharedPreferences p) {
        if (Prefs.weatherKey(p).equals(p.getString(Prefs.WX_KEY, ""))) {
            long at = p.getLong(Prefs.WX_AT, 0);
            if (System.currentTimeMillis() - at < MAX_AGE_MS) {
                weather = Weather.parse(p.getString(Prefs.WX_JSON, null));
                fetchedAt = at;
            }
        }
        render();
    }

    @Override
    public void start() {
        if (photos != null) photos.start();
        maybeFetch();
        render();
    }

    @Override
    public void tick() {
        maybeFetch();
        render();
        if (photos != null) photos.tick();
    }

    @Override
    public void stop() {
        generation++;
        if (photos != null) photos.stop();
    }

    // ---- Fetching ----

    private void maybeFetch() {
        if (!Prefs.hasLocation(prefs) || fetching) return;
        long now = System.currentTimeMillis();
        final String key = Prefs.weatherKey(prefs);
        boolean fresh = weather != null && key.equals(prefs.getString(Prefs.WX_KEY, ""))
                && now - fetchedAt < REFRESH_MS;
        if (fresh || now - lastAttempt < RETRY_MS) return;
        fetching = true;
        lastAttempt = now;
        final int gen = generation;
        final double lat = prefs.getFloat(Prefs.WX_LAT, 0);
        final double lon = prefs.getFloat(Prefs.WX_LON, 0);
        final boolean fahrenheit = Prefs.UNITS_F.equals(prefs.getString(Prefs.WX_UNITS, Prefs.UNITS_C));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String json = null;
                try {
                    json = WeatherClient.forecast(lat, lon, fahrenheit);
                } catch (IOException | RuntimeException ignored) {
                    // Offline or server error: keep the cached forecast and retry later.
                }
                final String result = json;
                final Weather parsed = Weather.parse(result);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        fetching = false;
                        lastFetchFailed = parsed == null;
                        if (parsed != null) {
                            long at = System.currentTimeMillis();
                            prefs.edit().putString(Prefs.WX_JSON, result).putString(Prefs.WX_KEY, key)
                                    .putLong(Prefs.WX_AT, at).apply();
                            weather = parsed;
                            fetchedAt = at;
                        }
                        if (gen == generation) render();
                    }
                });
            }
        }, "weather-fetch");
        t.setDaemon(true);
        t.start();
    }

    // ---- Rendering ----

    private void render() {
        long now = System.currentTimeMillis();
        Date nowDate = new Date(now);
        if (board) {
            clock.setText(DateFormat.getTimeFormat(context).format(nowDate));
            date.setText(DateFormat.getLongDateFormat(context).format(nowDate));
        } else {
            clock.setText(TextMode.expand(context, template));
        }

        String problem = problem();
        message.setText(problem);
        message.setVisibility(problem == null ? View.GONE : View.VISIBLE);
        if (board) {
            forecast.setVisibility(problem == null ? View.VISIBLE : View.INVISIBLE);
            updated.setText(weather == null ? "" : updatedText(now));
        } else {
            weatherBox.setVisibility(problem == null ? View.VISIBLE : View.GONE);
        }
        if (problem != null) return;

        Weather w = weather;
        Weather.Day today = w.days.isEmpty() ? null : w.days.get(0);
        icon.setImageResource(iconFor(w.code, w.isDay));
        if (photos != null) photos.setKind(backgroundKind(w.code, w.isDay));
        String placeName = prefs.getString(Prefs.WX_PLACE, "");
        place.setText(placeName);
        ((View) place.getParent()).setVisibility(placeName.isEmpty() ? View.GONE : View.VISIBLE);
        temp.setText(w.temp + "°");
        condition.setText(Weather.label(w.code));
        String hiLo = today == null ? "" : "H " + today.hi + "°  ·  L " + today.lo + "°";
        String precip = precipSummary(w, now);

        if (!board) {
            StringBuilder s = new StringBuilder();
            if (precip != null) s.append(precip).append("  ·  ");
            s.append(hiLo);
            if (now - fetchedAt > STALE_MS) s.append("  ·  ").append(updatedText(now).toLowerCase(Locale.US));
            detail.setText(s);
            return;
        }

        detail.setText(hiLo + "  ·  Feels like " + w.feels + "°");
        hoursLabel.setText(precip == null ? "NEXT HOURS" : "NEXT HOURS  ·  " + precip);
        List<Weather.Hour> hours = w.nextHours(now, hoursRow.getChildCount());
        java.text.DateFormat hourFmt = DateFormat.getTimeFormat(context);
        for (int i = 0; i < hoursRow.getChildCount(); i++) {
            ViewGroup cell = (ViewGroup) hoursRow.getChildAt(i);
            if (i >= hours.size()) {
                cell.setVisibility(View.INVISIBLE);
                continue;
            }
            Weather.Hour h = hours.get(i);
            cell.setVisibility(View.VISIBLE);
            ((TextView) cell.getChildAt(0)).setText(hourFmt.format(new Date(h.time)));
            ((TextView) cell.getChildAt(1)).setText(h.temp + "°");
            ((TextView) cell.getChildAt(2)).setText(h.rain >= 10 ? h.rain + "%" : "");
        }

        // Days after today; one shared scale so the range bars compare.
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 1; i < w.days.size(); i++) {
            min = Math.min(min, w.days.get(i).lo);
            max = Math.max(max, w.days.get(i).hi);
        }
        SimpleDateFormat dayFmt = new SimpleDateFormat("EEE", Locale.getDefault());
        for (int i = 0; i < daysCol.getChildCount(); i++) {
            ViewGroup row = (ViewGroup) daysCol.getChildAt(i);
            if (i + 1 >= w.days.size()) {
                row.setVisibility(View.INVISIBLE);
                continue;
            }
            Weather.Day d = w.days.get(i + 1);
            row.setVisibility(View.VISIBLE);
            ((TextView) row.getChildAt(0)).setText(dayFmt.format(new Date(d.noon)));
            ((ImageView) row.getChildAt(1)).setImageResource(iconFor(d.code, true));
            ((TextView) row.getChildAt(2)).setText(d.lo + "°");
            ((RangeBar) row.getChildAt(3)).set(d.lo, d.hi, min, max);
            ((TextView) row.getChildAt(4)).setText(d.hi + "°");
        }
    }

    /** Why there is no forecast to show, or null if there is one. */
    private String problem() {
        if (!Prefs.hasLocation(prefs)) return "Set a weather location in Simple Screensaver settings";
        if (weather != null) return null;
        if (lastFetchFailed) return "Weather unavailable (no connection?)";
        return "Loading weather…";
    }

    private String updatedText(long now) {
        long age = now - fetchedAt;
        if (age > STALE_MS) return "Updated " + (age / 3_600_000L) + " h ago";
        return "Updated " + DateFormat.getTimeFormat(context).format(new Date(fetchedAt));
    }

    /** The photo pool for the current weather; keys as in res/raw/backgrounds.json. */
    static String backgroundKind(int code, boolean day) {
        switch (Weather.kind(code)) {
            case CLEAR: return day ? "clear_day" : "clear_night";
            case PARTLY: return day ? "partly_day" : "partly_night";
            case CLOUDY: return "cloudy";
            case FOG: return "fog";
            case SNOW: return "snow";
            case STORM: return "storm";
            default: return "rain";
        }
    }

    private String precipSummary(Weather w, long now) {
        if (Weather.isWet(Weather.kind(w.code))) return Weather.precipWord(w.code) + " now";
        Weather.Hour h = w.nextWetHour(now);
        if (h == null) return null;
        return Weather.precipWord(h.code) + " likely from "
                + DateFormat.getTimeFormat(context).format(new Date(h.time));
    }

    static int iconFor(int code, boolean day) {
        switch (Weather.kind(code)) {
            case CLEAR: return day ? R.drawable.wx_clear_day : R.drawable.wx_clear_night;
            case PARTLY: return day ? R.drawable.wx_partly_day : R.drawable.wx_partly_night;
            case CLOUDY: return R.drawable.wx_cloudy;
            case FOG: return R.drawable.wx_fog;
            case SNOW: return R.drawable.wx_snow;
            case STORM: return R.drawable.wx_storm;
            default: return R.drawable.wx_rain;
        }
    }

    // ---- Layouts ----

    private void readStyle(SharedPreferences p) {
        typeface = Prefs.typeface(p);
        // Over a photo (under a dark scrim) only white text is reliably readable.
        fg = photos != null ? Color.WHITE : Prefs.color(p.getString(Prefs.FG, Prefs.DEFAULT_FG), Prefs.DEFAULT_FG);
        dim = Color.argb(0xA6, Color.red(fg), Color.green(fg), Color.blue(fg));
        template = Prefs.text(context).replace("\\n", "\n");
    }

    /** The text as in TextMode, then a divider and one weather line. */
    private View buildGlance() {
        LinearLayout col = vertical(Gravity.CENTER_HORIZONTAL);
        clock = text(prefs.getInt(Prefs.SIZE, Prefs.DEFAULT_SIZE), fg);
        clock.setGravity(Gravity.CENTER);
        // WRAP_CONTENT, not the vertical default MATCH_PARENT: a MATCH_PARENT child doesn't count
        // toward a wrap-content column's width, so the column would shrink to the weather row and
        // clip the text.
        col.addView(clock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The divider is drawn by the row itself: a MATCH_PARENT divider view would widen the
        // wrap-content column to the whole screen and push the row off center.
        LinearLayout row = new LinearLayout(context) {
            private final Paint rulePaint = new Paint();

            @Override
            protected void dispatchDraw(Canvas canvas) {
                rulePaint.setColor(TRACK);
                canvas.drawRect(0, 0, getWidth(), Math.max(1, dp(1) / 2), rulePaint);
                super.dispatchDraw(canvas);
            }
        };
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(18), dp(8), 0);
        icon = new ImageView(context);
        row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        temp = text(42, fg);
        temp.setPadding(dp(16), 0, dp(16), 0);
        row.addView(temp);
        LinearLayout lines = vertical(Gravity.START);
        condition = text(18, fg);
        detail = text(15, ACCENT);
        TextView credit = text(10, dim);
        credit.setText(CREDIT);
        credit.setPadding(0, dp(4), 0, 0);
        lines.addView(condition);
        lines.addView(detail);
        lines.addView(placeRow(15, 16));
        lines.addView(credit);
        row.addView(lines);
        weatherBox = row;
        col.addView(row, marginTop(dp(21)));

        message = text(16, dim);
        col.addView(message, marginTop(dp(21)));
        return col;
    }

    /** Full-screen forecast: header, current conditions + 3 days, next 6 hours. */
    private View buildBoard() {
        LinearLayout root = vertical(Gravity.NO_GRAVITY);
        root.setPadding(dp(54), dp(40), dp(54), dp(36));

        LinearLayout header = horizontal(Gravity.TOP);
        LinearLayout left = vertical(Gravity.START);
        updated = text(14, dim);
        left.addView(updated);
        header.addView(left, fillWidth());
        LinearLayout right = vertical(Gravity.END);
        clock = text(33, fg);
        date = text(14, dim);
        right.addView(clock);
        right.addView(date);
        header.addView(right);
        root.addView(header);

        // Middle and bottom are one block so a missing forecast can hide them together.
        LinearLayout body = vertical(Gravity.NO_GRAVITY);
        LinearLayout middle = horizontal(Gravity.CENTER_VERTICAL);
        icon = new ImageView(context);
        middle.addView(icon, new LinearLayout.LayoutParams(dp(112), dp(112)));
        LinearLayout now = vertical(Gravity.START);
        now.setPadding(dp(30), 0, 0, 0);
        temp = text(140, fg);
        temp.setIncludeFontPadding(false);
        condition = text(22, fg);
        detail = text(15, dim);
        now.addView(temp);
        now.addView(condition);
        now.addView(detail);
        LinearLayout pin = placeRow(17, 19);
        pin.setPadding(0, dp(6), 0, 0);
        now.addView(pin);
        middle.addView(now, fillWidth());

        daysCol = vertical(Gravity.NO_GRAVITY);
        TextView daysLabel = label("NEXT 3 DAYS");
        LinearLayout daysBox = vertical(Gravity.NO_GRAVITY);
        daysBox.addView(daysLabel);
        for (int i = 0; i < 3; i++) {
            LinearLayout r = horizontal(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(12), 0, 0);
            TextView day = text(18, fg);
            r.addView(day, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
            r.addView(new ImageView(context), new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView lo = text(18, dim);
            lo.setGravity(Gravity.END);
            r.addView(lo, new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(0, dp(5), 1);
            barLp.setMargins(dp(12), 0, dp(12), 0);
            r.addView(new RangeBar(context, fg), barLp);
            TextView hi = text(18, fg);
            r.addView(hi, new LinearLayout.LayoutParams(dp(40), ViewGroup.LayoutParams.WRAP_CONTENT));
            daysCol.addView(r);
        }
        daysBox.addView(daysCol);
        middle.addView(daysBox, new LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(middle, fillHeight());

        hoursLabel = label("NEXT HOURS");
        body.addView(hoursLabel);
        View rule = new View(context);
        rule.setBackgroundColor(TRACK);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        ruleLp.setMargins(0, dp(10), 0, dp(12));
        body.addView(rule, ruleLp);
        hoursRow = horizontal(Gravity.NO_GRAVITY);
        for (int i = 0; i < 6; i++) {
            LinearLayout cell = horizontal(Gravity.BOTTOM);
            TextView t = text(15, dim);
            TextView v = text(21, fg);
            v.setPadding(dp(10), 0, dp(8), 0);
            TextView r = text(13, ACCENT);
            cell.addView(t);
            cell.addView(v);
            cell.addView(r);
            hoursRow.addView(cell, fillWidth());
        }
        body.addView(hoursRow);
        TextView credit = text(10, dim);
        credit.setText(CREDIT);
        credit.setGravity(Gravity.END);
        credit.setPadding(0, dp(10), 0, 0);
        body.addView(credit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        forecast = body;

        android.widget.FrameLayout stack = new android.widget.FrameLayout(context);
        stack.addView(body, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        message = text(22, dim);
        stack.addView(message, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addView(stack, fillHeight());
        return root;
    }

    /** Location pin (outline, in the text color) followed by the place name; sets {@link #place}. */
    private LinearLayout placeRow(float textSp, int pinDp) {
        LinearLayout row = horizontal(Gravity.CENTER_VERTICAL);
        ImageView pin = new ImageView(context);
        pin.setImageResource(R.drawable.ic_pin);
        pin.setColorFilter(fg);
        row.addView(pin, new LinearLayout.LayoutParams(dp(pinDp), dp(pinDp)));
        place = text(textSp, fg);
        place.setPadding(dp(6), 0, 0, 0);
        row.addView(place);
        return row;
    }

    private TextView label(String s) {
        TextView t = text(12, dim);
        t.setText(s);
        t.setLetterSpacing(0.15f);
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

    /** Takes the remaining width in a horizontal LinearLayout. */
    private static LinearLayout.LayoutParams fillWidth() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    /** Takes the remaining height in a vertical LinearLayout. */
    private static LinearLayout.LayoutParams fillHeight() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
    }

    private static LinearLayout.LayoutParams marginTop(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = px;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    /** A day's low-high range drawn on a track spanning the shown days' overall range. */
    static final class RangeBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int color;
        private float from;
        private float to = 1;

        RangeBar(Context context, int color) {
            super(context);
            this.color = color;
        }

        void set(int lo, int hi, int min, int max) {
            float span = Math.max(1, max - min);
            from = (lo - min) / span;
            to = (hi - min) / span;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            rect.set(0, 0, getWidth(), getHeight());
            paint.setColor(TRACK);
            canvas.drawRoundRect(rect, r, r, paint);
            rect.set(from * getWidth(), 0, Math.max(from * getWidth() + getHeight(), to * getWidth()), getHeight());
            paint.setColor(color);
            canvas.drawRoundRect(rect, r, r, paint);
        }
    }
}
