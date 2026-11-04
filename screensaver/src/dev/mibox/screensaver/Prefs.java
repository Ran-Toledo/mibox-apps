package dev.mibox.screensaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/** Screensaver configuration, stored in SharedPreferences. */
final class Prefs {
    static final String TEXT = "text";
    static final String FG = "fg";
    static final String BG = "bg";
    static final String SIZE = "size";
    static final String DRIFT = "drift";
    static final String DIM = "dim";

    static final String DEFAULT_FG = "#FFFFFF";
    static final String DEFAULT_BG = "#000000";
    static final int DEFAULT_SIZE = 96;

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
}
