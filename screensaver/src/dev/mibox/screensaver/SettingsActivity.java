package dev.mibox.screensaver;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * D-pad friendly settings screen. Also accepts adb extras, which are saved without showing the UI:
 *   am start -n dev.mibox.screensaver/.SettingsActivity --es text 'Hello\nWorld' --es fg '#FFFFFF'
 *       --es bg '#000000' --ei size 120 --ez drift true --ez dim false --es font sans-serif-light --ez bold false
 */
public class SettingsActivity extends Activity {
    private static final String[] COLOR_NAMES = {
            "Black", "White", "Dark gray", "Navy", "Midnight blue", "Deep red",
            "Forest green", "Purple", "Orange", "Amber", "Teal", "Light pink"};
    private static final String[] COLOR_VALUES = {
            "#000000", "#FFFFFF", "#202020", "#0B1F4B", "#101830", "#7A0F0F",
            "#0F4D2A", "#3B1A5A", "#E8711A", "#FFB300", "#00796B", "#FFB6C1"};
    private static final Integer[] SIZES = {48, 64, 80, 96, 120, 160, 200, 260};
    /** System font families shipped with Android 8+ (see /system/etc/fonts.xml). */
    private static final String[] FONT_NAMES = {
            "Sans (Roboto)", "Sans thin", "Sans light", "Sans medium", "Sans black",
            "Condensed", "Condensed light", "Serif", "Monospace", "Serif monospace",
            "Casual", "Cursive", "Small caps"};
    private static final String[] FONT_VALUES = {
            "sans-serif", "sans-serif-thin", "sans-serif-light", "sans-serif-medium", "sans-serif-black",
            "sans-serif-condensed", "sans-serif-condensed-light", "serif", "monospace", "serif-monospace",
            "casual", "cursive", "sans-serif-smallcaps"};

    private SharedPreferences prefs;
    private EditText text;
    private Spinner fg;
    private Spinner bg;
    private Spinner size;
    private CheckBox drift;
    private CheckBox dim;
    private Spinner font;
    private CheckBox bold;
    private List<String> fontValues;
    private List<String> fgValues;
    private List<String> bgValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        if (applyExtras(getIntent())) {
            Toast.makeText(this, "Screensaver settings saved", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setContentView(buildUi());
    }

    /** Saves any recognised extras; returns true if there were some. */
    private boolean applyExtras(Intent intent) {
        Bundle e = intent.getExtras();
        if (e == null) return false;
        SharedPreferences.Editor ed = prefs.edit();
        boolean any = false;
        if (e.containsKey(Prefs.TEXT)) { ed.putString(Prefs.TEXT, e.getString(Prefs.TEXT)); any = true; }
        if (e.containsKey(Prefs.FG)) { ed.putString(Prefs.FG, e.getString(Prefs.FG)); any = true; }
        if (e.containsKey(Prefs.BG)) { ed.putString(Prefs.BG, e.getString(Prefs.BG)); any = true; }
        if (e.containsKey(Prefs.SIZE)) { ed.putInt(Prefs.SIZE, e.getInt(Prefs.SIZE, Prefs.DEFAULT_SIZE)); any = true; }
        if (e.containsKey(Prefs.DRIFT)) { ed.putBoolean(Prefs.DRIFT, e.getBoolean(Prefs.DRIFT)); any = true; }
        if (e.containsKey(Prefs.DIM)) { ed.putBoolean(Prefs.DIM, e.getBoolean(Prefs.DIM)); any = true; }
        if (e.containsKey(Prefs.FONT)) { ed.putString(Prefs.FONT, e.getString(Prefs.FONT)); any = true; }
        if (e.containsKey(Prefs.BOLD)) { ed.putBoolean(Prefs.BOLD, e.getBoolean(Prefs.BOLD)); any = true; }
        ed.apply();
        return any;
    }

    private View buildUi() {
        int pad = dp(48);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(32), pad, dp(32));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(28);
        col.addView(title);

        col.addView(label("Text  ({time} and {date} are replaced; new line = Enter)"));
        text = new EditText(this);
        text.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        text.setText(Prefs.text(this).replace("\\n", "\n"));
        col.addView(text, wide());

        fgValues = new ArrayList<>(Arrays.asList(COLOR_VALUES));
        bgValues = new ArrayList<>(Arrays.asList(COLOR_VALUES));
        col.addView(label("Text color"));
        fg = colorSpinner(fgValues, prefs.getString(Prefs.FG, Prefs.DEFAULT_FG));
        col.addView(fg, wide());
        col.addView(label("Background color"));
        bg = colorSpinner(bgValues, prefs.getString(Prefs.BG, Prefs.DEFAULT_BG));
        col.addView(bg, wide());

        col.addView(label("Text size (sp)"));
        size = new Spinner(this, Spinner.MODE_DIALOG);
        size.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, SIZES));
        size.setSelection(Math.max(0, Arrays.asList(SIZES).indexOf(prefs.getInt(Prefs.SIZE, Prefs.DEFAULT_SIZE))));
        col.addView(size, wide());

        col.addView(label("Font"));
        fontValues = new ArrayList<>(Arrays.asList(FONT_VALUES));
        font = fontSpinner(fontValues, prefs.getString(Prefs.FONT, Prefs.DEFAULT_FONT));
        col.addView(font, wide());
        bold = new CheckBox(this);
        bold.setText("Bold");
        bold.setChecked(prefs.getBoolean(Prefs.BOLD, false));
        col.addView(bold);

        drift = new CheckBox(this);
        drift.setText("Move text slowly (prevents burn-in)");
        drift.setChecked(prefs.getBoolean(Prefs.DRIFT, true));
        col.addView(drift);
        dim = new CheckBox(this);
        dim.setText("Dim the screen while active");
        dim.setChecked(prefs.getBoolean(Prefs.DIM, false));
        col.addView(dim);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.START);
        Button save = new Button(this);
        save.setText("Save");
        // Anonymous classes, not lambdas: javac against android.jar has no LambdaMetafactory.
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                finish();
            }
        });
        Button preview = new Button(this);
        preview.setText("Save and preview");
        preview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                startActivity(new Intent(SettingsActivity.this, PreviewActivity.class));
            }
        });
        buttons.addView(save);
        buttons.addView(preview);
        col.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        return scroll;
    }

    private void save() {
        prefs.edit()
                .putString(Prefs.TEXT, text.getText().toString())
                .putString(Prefs.FG, fgValues.get(fg.getSelectedItemPosition()))
                .putString(Prefs.BG, bgValues.get(bg.getSelectedItemPosition()))
                .putInt(Prefs.SIZE, SIZES[size.getSelectedItemPosition()])
                .putBoolean(Prefs.DRIFT, drift.isChecked())
                .putBoolean(Prefs.DIM, dim.isChecked())
                .putString(Prefs.FONT, fontValues.get(font.getSelectedItemPosition()))
                .putBoolean(Prefs.BOLD, bold.isChecked())
                .apply();
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    /** Color picker of presets; a custom value set via adb is kept as an extra entry. */
    private Spinner colorSpinner(List<String> values, String current) {
        List<String> names = new ArrayList<>(Arrays.asList(COLOR_NAMES));
        int index = indexOfIgnoreCase(values, current);
        if (index < 0) {
            values.add(current);
            names.add("Custom (" + current + ")");
            index = values.size() - 1;
        }
        Spinner s = new Spinner(this, Spinner.MODE_DIALOG);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        s.setSelection(index);
        return s;
    }

    /**
     * Font picker; each entry is drawn in its own typeface so it doubles as a preview.
     * A custom family set via adb is kept as an extra entry.
     */
    private Spinner fontSpinner(final List<String> values, String current) {
        List<String> names = new ArrayList<>(Arrays.asList(FONT_NAMES));
        int index = values.indexOf(current);
        if (index < 0) {
            values.add(current);
            names.add("Custom (" + current + ")");
            index = values.size() - 1;
        }
        Spinner s = new Spinner(this, Spinner.MODE_DIALOG);
        s.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return styled(super.getView(position, convertView, parent), position);
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                return styled(super.getDropDownView(position, convertView, parent), position);
            }

            private View styled(View v, int position) {
                if (v instanceof TextView) {
                    ((TextView) v).setTypeface(Typeface.create(values.get(position), Typeface.NORMAL));
                }
                return v;
            }
        });
        s.setSelection(index);
        return s;
    }

    private static int indexOfIgnoreCase(List<String> list, String value) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).equalsIgnoreCase(value)) return i;
        return -1;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    private static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
