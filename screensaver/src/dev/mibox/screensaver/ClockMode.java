package dev.mibox.screensaver;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import java.util.Date;

/** The original screensaver: one text block with {time} and {date} tokens. */
final class ClockMode implements ScreenMode {
    private final Context context;
    private final TextView text;
    private String template = "";

    ClockMode(Context context) {
        this.context = context;
        text = new TextView(context);
        text.setGravity(Gravity.CENTER);
    }

    @Override
    public View view() {
        return text;
    }

    @Override
    public boolean fillsScreen() {
        return false;
    }

    @Override
    public void applyPrefs(SharedPreferences p) {
        template = Prefs.text(context).replace("\\n", "\n");
        text.setTextColor(Prefs.color(p.getString(Prefs.FG, Prefs.DEFAULT_FG), Prefs.DEFAULT_FG));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, p.getInt(Prefs.SIZE, Prefs.DEFAULT_SIZE));
        text.setTypeface(Prefs.typeface(p));
        tick();
    }

    @Override
    public void start() {
        tick();
    }

    @Override
    public void tick() {
        text.setText(expand(context, template));
    }

    @Override
    public void stop() {}

    /** Replaces {time} and {date} using the device's time and date formats. */
    static String expand(Context context, String template) {
        Date now = new Date();
        return template
                .replace("{time}", DateFormat.getTimeFormat(context).format(now))
                .replace("{date}", DateFormat.getLongDateFormat(context).format(now));
    }
}
