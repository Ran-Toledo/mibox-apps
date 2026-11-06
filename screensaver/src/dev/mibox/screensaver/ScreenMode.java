package dev.mibox.screensaver;

import android.content.SharedPreferences;
import android.view.View;

/**
 * One thing the screensaver can show (text, weather, ...). SaverView hosts the active mode,
 * paints the background and drifts the mode's view to prevent burn-in.
 */
interface ScreenMode {
    /** The mode's root view; created once in the constructor. */
    View view();

    /** True if view() should fill the screen; false to center it at its natural size. */
    boolean fillsScreen();

    /** Optional full-screen layer drawn behind view() and not drifted (e.g. a photo); null for none. */
    default View background() {
        return null;
    }

    /** Reads settings; called once before start(). */
    void applyPrefs(SharedPreferences p);

    /** The screensaver became visible. */
    void start();

    /** Called every 15 s while visible (refresh clocks etc.). */
    void tick();

    /** The screensaver stopped: stop timers and threads, drop anything large. */
    void stop();
}
