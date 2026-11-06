package dev.mibox.screensaver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
 *       --es mode weather --ef lat 32.81 --ef lon 35.0 --es place Haifa --es units c --es wx_layout glance
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

    private static final String[] MODE_NAMES = {"Clock (text above)", "Weather"};
    private static final String[] MODE_VALUES = {Prefs.MODE_CLOCK, Prefs.MODE_WEATHER};
    private static final String[] UNIT_NAMES = {"Celsius (°C)", "Fahrenheit (°F)"};
    private static final String[] UNIT_VALUES = {Prefs.UNITS_C, Prefs.UNITS_F};
    private static final String[] LAYOUT_NAMES = {"Clock first, one weather line", "Full forecast board"};
    private static final String[] LAYOUT_VALUES = {Prefs.LAYOUT_GLANCE, Prefs.LAYOUT_BOARD};

    private SharedPreferences prefs;
    private Spinner mode;
    private Spinner units;
    private Spinner wxLayout;
    private TextView placeLabel;
    private EditText city;
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
        if (e.containsKey(Prefs.MODE)) { ed.putString(Prefs.MODE, e.getString(Prefs.MODE)); any = true; }
        if (e.containsKey(Prefs.WX_LAT) && e.containsKey(Prefs.WX_LON)) {
            ed.putFloat(Prefs.WX_LAT, e.getFloat(Prefs.WX_LAT)).putFloat(Prefs.WX_LON, e.getFloat(Prefs.WX_LON));
            ed.putString(Prefs.WX_PLACE, e.containsKey(Prefs.WX_PLACE) ? e.getString(Prefs.WX_PLACE) : "");
            any = true;
        }
        if (e.containsKey(Prefs.WX_UNITS)) { ed.putString(Prefs.WX_UNITS, e.getString(Prefs.WX_UNITS)); any = true; }
        if (e.containsKey(Prefs.WX_LAYOUT)) { ed.putString(Prefs.WX_LAYOUT, e.getString(Prefs.WX_LAYOUT)); any = true; }
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

        col.addView(label("Show"));
        mode = choice(MODE_NAMES, MODE_VALUES, prefs.getString(Prefs.MODE, Prefs.MODE_CLOCK));
        col.addView(mode, wide());

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

        TextView wxTitle = new TextView(this);
        wxTitle.setText("Weather");
        wxTitle.setTextSize(22);
        wxTitle.setPadding(0, dp(28), 0, 0);
        col.addView(wxTitle);
        placeLabel = label("");
        showPlace();
        col.addView(placeLabel);
        LinearLayout search = new LinearLayout(this);
        city = new EditText(this);
        city.setHint("City name");
        city.setSingleLine(true);
        search.addView(city, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button find = new Button(this);
        find.setText("Find");
        find.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findCity(city.getText().toString());
            }
        });
        search.addView(find);
        col.addView(search, wide());
        col.addView(label("Units"));
        units = choice(UNIT_NAMES, UNIT_VALUES, prefs.getString(Prefs.WX_UNITS, Prefs.UNITS_C));
        col.addView(units, wide());
        col.addView(label("Weather layout"));
        wxLayout = choice(LAYOUT_NAMES, LAYOUT_VALUES, prefs.getString(Prefs.WX_LAYOUT, Prefs.LAYOUT_GLANCE));
        col.addView(wxLayout, wide());

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
                .putString(Prefs.MODE, MODE_VALUES[mode.getSelectedItemPosition()])
                .putString(Prefs.WX_UNITS, UNIT_VALUES[units.getSelectedItemPosition()])
                .putString(Prefs.WX_LAYOUT, LAYOUT_VALUES[wxLayout.getSelectedItemPosition()])
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

    /** A fixed list of options; an unknown saved value selects the first. */
    private Spinner choice(String[] names, String[] values, String current) {
        Spinner s = new Spinner(this, Spinner.MODE_DIALOG);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        s.setSelection(Math.max(0, Arrays.asList(values).indexOf(current)));
        return s;
    }

    private void showPlace() {
        String place = prefs.getString(Prefs.WX_PLACE, "");
        if (!Prefs.hasLocation(prefs)) {
            placeLabel.setText("Location: not set. Type a city and press Find.");
        } else {
            placeLabel.setText("Location: " + (place.isEmpty()
                    ? prefs.getFloat(Prefs.WX_LAT, 0) + ", " + prefs.getFloat(Prefs.WX_LON, 0) : place));
        }
    }

    /** Looks the city up on a background thread, then lets the user pick a match; the pick is saved at once. */
    private void findCity(final String name) {
        if (name.trim().isEmpty()) return;
        placeLabel.setText("Searching…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<WeatherClient.Place> found = null;
                try {
                    found = WeatherClient.search(name);
                } catch (Exception ignored) {
                    // Reported as a failed search below.
                }
                final List<WeatherClient.Place> result = found;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        showPlace();
                        if (result == null) {
                            Toast.makeText(SettingsActivity.this, "Search failed (no connection?)", Toast.LENGTH_LONG).show();
                        } else if (result.isEmpty()) {
                            Toast.makeText(SettingsActivity.this, "No place found for: " + name, Toast.LENGTH_LONG).show();
                        } else {
                            pickPlace(result);
                        }
                    }
                });
            }
        }, "city-search").start();
    }

    private void pickPlace(final List<WeatherClient.Place> places) {
        String[] labels = new String[places.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = places.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("Choose the location")
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        WeatherClient.Place p = places.get(which);
                        prefs.edit()
                                .putFloat(Prefs.WX_LAT, (float) p.lat)
                                .putFloat(Prefs.WX_LON, (float) p.lon)
                                .putString(Prefs.WX_PLACE, p.label.split(",")[0])
                                .apply();
                        showPlace();
                        Toast.makeText(SettingsActivity.this, "Location saved: " + p.label, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
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
