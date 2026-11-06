package dev.mibox.screensaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;

/** Screensaver configuration, stored in SharedPreferences. */
final class Prefs {
    static final String TEXT = "text";
    static final String FG = "fg";
    static final String BG = "bg";
    static final String SIZE = "size";
    static final String DRIFT = "drift";
    static final String DIM = "dim";
    static final String FONT = "font";
    static final String BOLD = "bold";

    static final String DEFAULT_FG = "#FFFFFF";
    static final String DEFAULT_BG = "#000000";
    static final int DEFAULT_SIZE = 96;
    /** A system font family name, as listed in /system/etc/fonts.xml. */
    static final String DEFAULT_FONT = "sans-serif";

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("screensaver", Context.MODE_PRIVATE);
    }

    /** Saved text, or the default from res/values/strings.xml. */
    static String text(Context context) {
        return get(context).getString(TEXT, context.getString(R.string.default_text));
    }

    /** Parses #RRGGBB / #AARRGGBB / named colors, falling back on bad input. */
    static int color(String value, String fallback) {
        try {
            return Color.parseColor(value);
        } catch (RuntimeException e) {
            return Color.parseColor(fallback);
        }
    }

    /**
     * Saved font family plus bold flag. Unknown family names fall back to the default
     * sans-serif inside Typeface.create, so a bad adb value can't break the screensaver.
     */
    static Typeface typeface(SharedPreferences p) {
        String family = p.getString(FONT, DEFAULT_FONT);
        return Typeface.create(family, p.getBoolean(BOLD, false) ? Typeface.BOLD : Typeface.NORMAL);
    }
}
