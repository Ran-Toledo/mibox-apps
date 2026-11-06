package dev.mibox.screensaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.Random;

/**
 * Solid background with one large, centered text block.
 * Supports {time} and {date} tokens and an optional slow drift to avoid burn-in.
 */
final class SaverView extends FrameLayout {
    private static final long TICK_MS = 15_000;
    private static final int DRIFT_EVERY_TICKS = 4;

    private final TextView text;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private String template;
    private boolean drift;
    private int ticks;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshText();
            if (drift && ++ticks % DRIFT_EVERY_TICKS == 0) moveText();
            handler.postDelayed(this, TICK_MS);
        }
    };

    SaverView(Context context) {
        super(context);
        text = new TextView(context);
        text.setGravity(Gravity.CENTER);
        addView(text, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        applyPrefs(Prefs.get(context));
    }

    void applyPrefs(SharedPreferences p) {
        template = Prefs.text(getContext()).replace("\\n", "\n");
        drift = p.getBoolean(Prefs.DRIFT, true);
        setBackgroundColor(Prefs.color(p.getString(Prefs.BG, Prefs.DEFAULT_BG), Prefs.DEFAULT_BG));
        text.setTextColor(Prefs.color(p.getString(Prefs.FG, Prefs.DEFAULT_FG), Prefs.DEFAULT_FG));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, p.getInt(Prefs.SIZE, Prefs.DEFAULT_SIZE));
        text.setTypeface(Prefs.typeface(p));
        refreshText();
    }

    void start() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void stop() {
        handler.removeCallbacks(tick);
    }

    private void refreshText() {
        Date now = new Date();
        text.setText(template
                .replace("{time}", DateFormat.getTimeFormat(getContext()).format(now))
                .replace("{date}", DateFormat.getLongDateFormat(getContext()).format(now)));
    }

    /** Moves the text to a random spot that keeps it fully on screen. */
    private void moveText() {
        float maxX = Math.max(0, (getWidth() - text.getWidth()) / 2f) * 0.9f;
        float maxY = Math.max(0, (getHeight() - text.getHeight()) / 2f) * 0.9f;
        text.animate()
                .translationX((random.nextFloat() * 2 - 1) * maxX)
                .translationY((random.nextFloat() * 2 - 1) * maxY)
                .setDuration(3000)
                .start();
    }
}
