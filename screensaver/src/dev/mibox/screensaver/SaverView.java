package dev.mibox.screensaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import java.util.Random;

/**
 * Hosts the active ScreenMode on a solid background, ticks it every 15 s and, if enabled,
 * drifts it slowly to avoid burn-in.
 */
final class SaverView extends FrameLayout {
    private static final long TICK_MS = 15_000;
    private static final int DRIFT_EVERY_TICKS = 4;
    /** Drift range for full-screen modes, as a fraction of the screen size. */
    private static final float FULL_SCREEN_DRIFT = 0.02f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ScreenMode mode;
    private boolean drift;
    private int ticks;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            mode.tick();
            if (drift && ++ticks % DRIFT_EVERY_TICKS == 0) moveContent();
            handler.postDelayed(this, TICK_MS);
        }
    };

    SaverView(Context context) {
        super(context);
        SharedPreferences p = Prefs.get(context);
        mode = createMode(context, p);
        View background = mode.background();
        if (background != null) {
            addView(background, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        LayoutParams lp = mode.fillsScreen()
                ? new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                : new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(mode.view(), lp);
        drift = p.getBoolean(Prefs.DRIFT, true);
        setBackgroundColor(Prefs.color(p.getString(Prefs.BG, Prefs.DEFAULT_BG), Prefs.DEFAULT_BG));
        mode.applyPrefs(p);
    }

    private static ScreenMode createMode(Context context, SharedPreferences p) {
        if (Prefs.MODE_WEATHER.equals(p.getString(Prefs.MODE, Prefs.MODE_CLOCK))) {
            return new WeatherMode(context, p);
        }
        return new ClockMode(context);
    }

    void start() {
        mode.start();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    void stop() {
        handler.removeCallbacks(tick);
        mode.stop();
    }

    /** Moves the mode's view to a random offset that keeps it on screen. */
    private void moveContent() {
        View content = mode.view();
        float maxX;
        float maxY;
        if (mode.fillsScreen()) {
            maxX = getWidth() * FULL_SCREEN_DRIFT;
            maxY = getHeight() * FULL_SCREEN_DRIFT;
        } else {
            maxX = Math.max(0, (getWidth() - content.getWidth()) / 2f) * 0.9f;
            maxY = Math.max(0, (getHeight() - content.getHeight()) / 2f) * 0.9f;
        }
        content.animate()
                .translationX((random.nextFloat() * 2 - 1) * maxX)
                .translationY((random.nextFloat() * 2 - 1) * maxY)
                .setDuration(3000)
                .start();
    }
}
