package dev.mibox.taskmanager;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * One screen with Memory, CPU and Storage tabs. Each row is one app; apps that can be closed get
 * a Close button on the right (D-pad right to reach it). OK on the row itself does nothing.
 * Data is only refreshed while the screen is visible.
 */
public class MainActivity extends Activity {

    private static final int BG = 0xFF121212, SURFACE = 0xFF1E1E1E, TEXT = 0xFFFFFFFF,
            SECONDARY = 0xFFA0A0A0, MUTED = 0xFF707070, TRACK = 0xFF333333, ACCENT = 0xFF4C8DF6,
            DANGER = 0xFFE5534B, DANGER_BG = 0xFF3A1A1A;

    private static final String[] TABS = {"Memory", "CPU", "Storage"};
    private static final long[] INTERVAL_MS = {10000, 3000, 60000};
    private static final String[][] LOADING = {
            {"Reading memory usage…", "Android measures every running app; this takes about 10 s"},
            {"Measuring CPU usage…", "Sampling all processes for 2 s"},
            {"Adding up app storage…", "Reading app, data and cache sizes"}};
    private static final String[] FOOTER = {"Updates every 10 s", "Average over 2 s", "App + data + cache"};

    private Device device;
    private HandlerThread workerThread;
    private Handler worker;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final TextView[] tabViews = new TextView[TABS.length];
    private TextView summary, status, footer;
    private Bar totalBar;
    private LinearLayout list;
    private View parking;

    private int tab = 0;
    private final Device.Snapshot[] last = new Device.Snapshot[TABS.length]; // last result per tab
    private int generation = 0;   // bumps on every tab switch so stale results are dropped
    private boolean visible;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        device = new Device(this);
        device.onPrompt = () -> showStatus("On the TV, allow network debugging for Task Manager (tick \"Always allow\").");
        setContentView(buildLayout());
        selectTab(0);
        tabViews[0].requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        workerThread = new HandlerThread("device");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        refreshNow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        worker.removeCallbacksAndMessages(null);
        worker.post(device::disconnect);
        workerThread.quitSafely();
    }

    // --- refresh loop ------------------------------------------------------

    private void refreshNow() {
        if (!visible) return;
        worker.removeCallbacksAndMessages(null);
        final int gen = generation, t = tab;
        worker.post(new Runnable() {
            @Override public void run() {
                if (gen != generation || !visible) return;
                try {
                    device.adb();
                    showStatus("");
                    Device.Snapshot s = t == 0 ? device.memory() : t == 1 ? device.cpu() : device.storage();
                    ui.post(() -> { if (gen == generation) show(s); });
                    worker.postDelayed(this, INTERVAL_MS[t]);
                } catch (AdbClient.AuthPendingException e) {
                    showStatus("The debugging prompt timed out. Trying again…");
                    worker.postDelayed(this, 1000);
                } catch (java.net.ConnectException e) {
                    showStatus("Can't reach adb on this box. Turn on network debugging (adb tcpip 5555).");
                    worker.postDelayed(this, 5000);
                } catch (Exception e) {
                    showStatus("Couldn't read the box: " + e.getMessage());
                    worker.postDelayed(this, 5000);
                }
            }
        });
    }

    private void showStatus(String text) {
        ui.post(() -> {
            status.setText(text);
            status.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void closeApp(Device.Row row) {
        new Thread(() -> {
            try {
                device.forceStop(row.key);
                ui.post(() -> {
                    Toast.makeText(this, "Closed " + row.label, Toast.LENGTH_SHORT).show();
                    removeRow(row.key);
                });
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Couldn't close " + row.label, Toast.LENGTH_SHORT).show());
            }
            ui.post(this::refreshNow);
        }, "close").start();
    }

    /** Drops a closed app's row at once (the next refresh can take 10 s), moving focus to its neighbour. */
    private void removeRow(String key) {
        for (Device.Snapshot snap : last) {
            if (snap == null) continue;
            for (java.util.Iterator<Device.Row> it = snap.rows.iterator(); it.hasNext(); ) {
                if (it.next().key.equals(key)) it.remove();
            }
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            View v = list.getChildAt(i);
            if (v.getTag() instanceof Device.Row && ((Device.Row) v.getTag()).key.equals(key)) {
                View next = i + 1 < list.getChildCount() ? list.getChildAt(i + 1) : i > 0 ? list.getChildAt(i - 1) : tabViews[tab];
                if (v.hasFocus()) next.requestFocus();
                list.removeViewAt(i);
                return;
            }
        }
    }

    // --- rendering ---------------------------------------------------------

    private void selectTab(int t) {
        tab = t;
        generation++;
        for (int i = 0; i < tabViews.length; i++) {
            tabViews[i].setTextColor(i == t ? TEXT : SECONDARY);
            tabViews[i].getPaint().setFakeBoldText(i == t);
            tabViews[i].invalidate();
        }
        footer.setText((t == 2 ? "↑↓ Move    " : "↑↓ Move    ←→ Row / Close button    OK Close    ") + FOOTER[t]);
        summary.setText("");
        totalBar.setFraction(0);
        list.removeAllViews();
        // Show this tab's last result until the next refresh; the spinner only when there is none yet.
        if (last[t] != null) show(last[t]);
        else addLoading(LOADING[t][0], LOADING[t][1]);
        refreshNow();
    }

    private void show(Device.Snapshot s) {
        if (!s.rows.isEmpty()) last[tab] = s;
        summary.setText(s.summary);
        totalBar.setFraction(s.fraction);
        // An occasional empty dump (e.g. two dumpsys meminfo at once) keeps the last list instead of blanking it.
        boolean hasRows = list.getChildCount() > 0 && list.getChildAt(0).getTag() instanceof Device.Row;
        if (s.rows.isEmpty() && hasRows) return;

        // Remember the focused row (and whether its Close button had focus), then rebuild.
        String focusKey = null;
        boolean focusClose = false;
        int focusIndex = -1;
        View focused = getCurrentFocus();
        if (focused != null && isInList(focused)) {
            View rowView = focused.getTag() instanceof Device.Row ? focused : (View) focused.getParent();
            Device.Row r = (Device.Row) rowView.getTag();
            focusKey = r.key;
            focusClose = focused != rowView;
            focusIndex = list.indexOfChild(rowView);
            parking.setFocusable(true);
            parking.requestFocus(); // keep focus off the tabs while the list is rebuilt
        }

        list.removeAllViews();
        double max = 0;
        for (Device.Row r : s.rows) max = Math.max(max, r.value);
        if (s.rows.isEmpty()) addMessage("Nothing to show");
        View restore = null;
        for (int i = 0; i < s.rows.size(); i++) {
            Device.Row r = s.rows.get(i);
            LinearLayout rowView = buildRow(r, max);
            list.addView(rowView);
            if (r.key.equals(focusKey)) {
                restore = focusClose && rowView.getChildAt(4).isFocusable() ? rowView.getChildAt(4) : rowView;
            }
        }
        if (focusIndex >= 0) {
            if (restore == null && list.getChildCount() > 0) {
                restore = list.getChildAt(Math.min(focusIndex, list.getChildCount() - 1));
            }
            if (restore != null && restore.isFocusable()) restore.requestFocus();
            else tabViews[tab].requestFocus();
            parking.setFocusable(false);
        }
        // Up from the first row goes to the current tab, not whichever tab is nearest.
        if (list.getChildAt(0) instanceof LinearLayout) {
            LinearLayout first = (LinearLayout) list.getChildAt(0);
            first.setNextFocusUpId(tabViews[tab].getId());
            first.getChildAt(4).setNextFocusUpId(tabViews[tab].getId());
        }
    }

    private boolean isInList(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == list) return v != list;
        }
        return false;
    }

    private LinearLayout buildRow(Device.Row r, double max) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setTag(r);
        row.setPadding(dp(12), dp(5), dp(12), dp(5));
        row.setBackground(selector(SURFACE, ACCENT, 0));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView label = text(r.label, 15, TEXT);
        // Hebrew labels would otherwise align right and run into the state column.
        label.setTextDirection(View.TEXT_DIRECTION_LTR);
        label.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
        label.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        label.setPadding(0, 0, dp(12), 0);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(text(r.state, 12, SECONDARY), new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView value = text(format(r.value), 15, TEXT);
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT));

        Bar bar = new Bar(this, SECONDARY);
        bar.setFraction(max > 0 ? r.value / max : 0);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(110), dp(5));
        barLp.leftMargin = dp(16);
        row.addView(bar, barLp);

        TextView close = text(r.closable ? "Close" : "", 13, SECONDARY);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(3), 0, dp(3));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = dp(16);
        if (r.closable) {
            close.setFocusable(true);
            close.setBackground(selector(DANGER_BG, DANGER, TRACK));
            close.setOnFocusChangeListener((v, has) -> close.setTextColor(has ? DANGER : SECONDARY));
            close.setOnClickListener(v -> closeApp(r));
            // Focus search ignores views inside the focused one, so move between row and button by hand.
            row.setOnKeyListener((v, code, ev) -> code == KeyEvent.KEYCODE_DPAD_RIGHT
                    && ev.getAction() == KeyEvent.ACTION_DOWN && close.requestFocus());
            close.setOnKeyListener((v, code, ev) -> code == KeyEvent.KEYCODE_DPAD_LEFT
                    && ev.getAction() == KeyEvent.ACTION_DOWN && row.requestFocus());
        }
        row.addView(close, closeLp);
        return row;
    }

    private String format(double v) {
        if (tab == 1) return v < 0.5 ? "<1%" : Math.round(v) + "%";
        if (tab == 2 && v >= 1000) return String.format("%.1f GB", v / 1024);
        return Math.round(v) + " MB";
    }

    /** Spinner with a title and a hint, shown until a tab's first result arrives. */
    private void addLoading(String title, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), dp(16), 0, 0);

        android.widget.ProgressBar spinner = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        box.addView(spinner, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 16, TEXT));
        texts.addView(text(hint, 12, MUTED));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(16);
        box.addView(texts, lp);
        list.addView(box);
    }

    private void addMessage(String msg) {
        TextView t = text(msg, 14, MUTED);
        t.setPadding(dp(12), dp(8), 0, 0);
        list.addView(t);
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(48), dp(27), dp(48), dp(20));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView t = text(TABS[i], 18, SECONDARY);
            t.setFocusable(true);
            t.setId(View.generateViewId());
            t.setPadding(dp(14), dp(4), dp(14), dp(4));
            t.setBackground(selector(0, ACCENT, 0));
            t.setOnFocusChangeListener((v, has) -> { if (has && tab != idx) selectTab(idx); });
            tabViews[i] = t;
            header.addView(t);
        }
        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        summary = text("", 14, SECONDARY);
        header.addView(summary);
        root.addView(header);

        totalBar = new Bar(this, TEXT);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        barLp.topMargin = dp(12);
        barLp.bottomMargin = dp(12);
        root.addView(totalBar, barLp);

        status = text("", 14, DANGER);
        status.setVisibility(View.GONE);
        status.setPadding(dp(12), 0, 0, dp(8));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        footer = text("", 12, MUTED);
        footer.setPadding(dp(12), dp(10), 0, 0);
        root.addView(footer);

        parking = new View(this);
        root.addView(parking, new LinearLayout.LayoutParams(1, 1));
        return root;
    }

    // --- small view helpers ------------------------------------------------

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** Background that shows a colored outline (and fill) only while focused. */
    private StateListDrawable selector(int focusedFill, int focusedStroke, int restingStroke) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, rounded(focusedFill, focusedStroke));
        d.addState(new int[]{}, rounded(0, restingStroke));
        return d;
    }

    private GradientDrawable rounded(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Flat horizontal bar: a track with a filled share. */
    static final class Bar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float fraction;

        Bar(Context c, int color) {
            super(c);
            track.setColor(TRACK);
            fill.setColor(color);
        }

        void setFraction(double f) {
            fraction = (float) Math.max(0, Math.min(1, f));
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float h = getHeight(), r = h / 2;
            rect.set(0, 0, getWidth(), h);
            canvas.drawRoundRect(rect, r, r, track);
            if (fraction > 0) {
                rect.set(0, 0, Math.max(h, getWidth() * fraction), h);
                canvas.drawRoundRect(rect, r, r, fill);
            }
        }
    }
}
