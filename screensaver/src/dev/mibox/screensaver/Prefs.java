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
    /** Weather mode background: BG_SOLID (the color above) or BG_PHOTO (rotating weather photos). */
    static final String BG_STYLE = "bg_style";
    static final String BG_MINUTES = "bg_minutes";
    static final String BG_SOLID = "solid";
    static final String BG_PHOTO = "photo";
    static final int DEFAULT_BG_MINUTES = 10;
    static final String MODE = "mode";
    static final String WX_LAT = "lat";
    static final String WX_LON = "lon";
    static final String WX_PLACE = "place";
    static final String WX_UNITS = "units";
    static final String WX_LAYOUT = "wx_layout";
    /** Last forecast response (JSON), the location/units it was fetched for, and when. */
    static final String WX_JSON = "wx_json";
    static final String WX_KEY = "wx_key";
    static final String WX_AT = "wx_at";

    static final String MODE_CLOCK = "clock";
    static final String MODE_WEATHER = "weather";
    static final String UNITS_C = "c";
    static final String UNITS_F = "f";
    static final String LAYOUT_GLANCE = "glance";
    static final String LAYOUT_BOARD = "board";

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

    static boolean hasLocation(SharedPreferences p) {
        return p.contains(WX_LAT) && p.contains(WX_LON);
    }

    /** Identifies what a cached forecast was fetched for; a mismatch means it must be refetched. */
    static String weatherKey(SharedPreferences p) {
        return p.getFloat(WX_LAT, 0) + "," + p.getFloat(WX_LON, 0) + "," + p.getString(WX_UNITS, UNITS_C);
    }
}
