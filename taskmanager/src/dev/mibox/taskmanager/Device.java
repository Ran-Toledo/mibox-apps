package dev.mibox.taskmanager;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads memory, CPU and storage over the local adb connection. Call only from a worker thread. */
final class Device {

    static final class Row {
        String key;       // package name, or process name for native processes
        String label;
        String state;     // short state or detail shown in the second column
        double value;     // MB or % of the whole CPU
        boolean closable;
    }

    static final class Snapshot {
        String summary;
        double fraction;  // 0..1 for the summary bar
        List<Row> rows = new ArrayList<>();
    }

    private static final String SEP = "@@TASKMANAGER@@";

    // Packages that keep the box usable; never offered for closing (same list as running-apps.ps1).
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "system", "android", "com.android.systemui", "com.android.bluetooth", "com.mi.bluetooth",
            "com.droidlogic", "xiaomi.tvservice", "mitv.service", "com.android.se", "com.android.phone",
            "com.android.providers.media.module", "com.android.providers.tv", "com.android.tv.settings",
            "com.google.android.gms", "com.google.android.gsf", "com.google.android.ext.services",
            "com.google.android.inputmethod.latin", "com.google.android.tv.remote.service",
            "com.google.android.katniss", "com.google.android.tvrecommendations",
            "com.xiaomi.android.tvsetup.partnercustomizer", "com.google.android.permissioncontroller"));

    private static final Map<String, String> NATIVE_NAMES = new HashMap<>();
    static {
        NATIVE_NAMES.put("system", "Android system");
        NATIVE_NAMES.put("system_server", "Android system");
        NATIVE_NAMES.put("surfaceflinger", "Display (surfaceflinger)");
        NATIVE_NAMES.put("mediaserver", "Media server");
        NATIVE_NAMES.put("media.codec", "Media codecs");
        NATIVE_NAMES.put("media.swcodec", "Media codecs");
        NATIVE_NAMES.put("audioserver", "Audio server");
        NATIVE_NAMES.put("cameraserver", "Camera server");
        NATIVE_NAMES.put("adbd", "adb");
        NATIVE_NAMES.put("zygote", "App launcher (zygote)");
        NATIVE_NAMES.put("logd", "Logging");
        NATIVE_NAMES.put("kernel", "Kernel threads");
    }

    private static final Pattern LRU = Pattern.compile(
            "^\\s*#\\s*\\d+:\\s+\\w+(?:\\+\\s*\\d+)?\\s+(\\S+)\\s+\\S+\\s+(\\d+):(\\S+?)/\\S+");
    private static final Pattern PSS = Pattern.compile("^\\s*([\\d,]+)K:\\s+(\\S+)\\s+\\(pid\\s+(\\d+)");
    private static final Pattern CPU_HEADER = Pattern.compile("(\\d+)%cpu.*?(\\d+)%idle");

    private final Context context;
    private final PackageManager pm;
    private final Map<String, String> labels = new HashMap<>();
    private AdbClient adb;
    private boolean usageGranted;
    Runnable onPrompt;

    Device(Context context) {
        this.context = context.getApplicationContext();
        this.pm = this.context.getPackageManager();
    }

    // --- connection --------------------------------------------------------

    /** Connects if needed. Throws AuthPendingException while the TV prompt is open. */
    synchronized AdbClient adb() throws IOException, GeneralSecurityException {
        if (adb == null) adb = new AdbClient("127.0.0.1", 5555, keys(), "taskmanager@mibox", 120000, onPrompt);
        return adb;
    }

    synchronized void disconnect() {
        if (adb != null) adb.close();
        adb = null;
    }

    private String exec(String command) throws IOException, GeneralSecurityException {
        try {
            return adb().exec(command);
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    private KeyPair keys() throws GeneralSecurityException, IOException {
        File priv = new File(context.getFilesDir(), "adbkey");
        File pub = new File(context.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair kp = gen.generateKeyPair();
        writeAll(priv, kp.getPrivate().getEncoded());
        writeAll(pub, kp.getPublic().getEncoded());
        return kp;
    }

    // --- tabs --------------------------------------------------------------

    Snapshot memory() throws IOException, GeneralSecurityException {
        String[] parts = exec("cat /proc/meminfo; echo " + SEP + "; dumpsys activity lru; echo " + SEP
                + "; dumpsys meminfo").split(SEP);
        long total = 0, avail = 0;
        for (String line : lines(part(parts, 0))) {
            if (line.startsWith("MemTotal:")) total = firstNumber(line);
            else if (line.startsWith("MemAvailable:")) avail = firstNumber(line);
        }
        Map<String, String> stateByPkg = states(part(parts, 1));
        String home = homePackage();

        Map<String, Double> mbByKey = new HashMap<>();
        boolean inPss = false;
        for (String line : lines(part(parts, 2))) {
            if (line.startsWith("Total PSS by process:")) { inPss = true; continue; }
            if (!inPss) continue;
            if (line.trim().isEmpty()) break;
            Matcher m = PSS.matcher(line);
            if (m.find()) {
                String key = packageOf(m.group(2));
                double mb = Long.parseLong(m.group(1).replace(",", "")) / 1024.0;
                Double old = mbByKey.get(key);
                mbByKey.put(key, old == null ? mb : old + mb);
            }
        }

        Snapshot s = new Snapshot();
        s.summary = String.format("%,d MB available of %.1f GB", avail / 1024, total / 1024.0 / 1024.0);
        s.fraction = total > 0 ? 1.0 - (double) avail / total : 0;
        for (Map.Entry<String, Double> e : mbByKey.entrySet()) {
            s.rows.add(row(e.getKey(), e.getValue(), stateByPkg.get(e.getKey()), home));
        }
        sortDescending(s.rows);
        return s;
    }

    Snapshot cpu() throws IOException, GeneralSecurityException {
        String[] parts = exec("top -b -n 1 -d 2 -m 20; echo " + SEP
                + "; cat /sys/class/thermal/thermal_zone0/temp; echo " + SEP + "; dumpsys activity lru").split(SEP);
        int cores = 1;
        double busy = 0;
        Map<String, Double> pctByKey = new HashMap<>();
        boolean inTable = false;
        for (String line : lines(part(parts, 0))) {
            Matcher h = CPU_HEADER.matcher(line);
            if (h.find()) {
                int all = Integer.parseInt(h.group(1));
                cores = Math.max(1, all / 100);
                busy = 1.0 - Integer.parseInt(h.group(2)) / (double) all;
                continue;
            }
            String t = line.trim();
            if (t.startsWith("PID")) { inTable = true; continue; }
            if (!inTable || t.isEmpty()) continue;
            String[] f = t.split("\\s+");
            if (f.length < 12) continue;
            String name = f[11];
            if (name.equals("top") || name.equals("sh")) continue; // our own measurement
            if (name.startsWith("[")) name = "kernel";
            double pct;
            try { pct = Double.parseDouble(f[8]) / cores; } catch (NumberFormatException e) { continue; }
            if (pct <= 0) continue;
            String key = packageOf(name);
            Double old = pctByKey.get(key);
            pctByKey.put(key, old == null ? pct : old + pct);
        }

        String temp = "";
        try {
            temp = String.format(" · %d °C", Math.round(Long.parseLong(part(parts, 1).trim()) / 1000.0));
        } catch (NumberFormatException ignored) { }

        Map<String, String> stateByPkg = states(part(parts, 2));
        String home = homePackage();
        Snapshot s = new Snapshot();
        s.summary = String.format("%d%% busy · %d cores%s · up %s",
                Math.round(busy * 100), cores, temp, uptime());
        s.fraction = busy;
        for (Map.Entry<String, Double> e : pctByKey.entrySet()) {
            s.rows.add(row(e.getKey(), e.getValue(), stateByPkg.get(e.getKey()), home));
        }
        sortDescending(s.rows);
        return s;
    }

    Snapshot storage() throws IOException, GeneralSecurityException {
        if (!usageGranted) {
            // Per-app sizes need the usage-stats app op, which only the shell can grant.
            exec("appops set " + context.getPackageName() + " GET_USAGE_STATS allow");
            usageGranted = true;
        }
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes(), free = fs.getAvailableBytes();

        Snapshot s = new Snapshot();
        s.summary = String.format("%.1f GB free of %.1f GB", free / 1e9, total / 1e9);
        s.fraction = total > 0 ? 1.0 - (double) free / total : 0;

        StorageStatsManager ssm = (StorageStatsManager) context.getSystemService(Context.STORAGE_STATS_SERVICE);
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            StorageStats st;
            try {
                st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle());
            } catch (Exception e) {
                continue;
            }
            Row r = new Row();
            r.key = ai.packageName;
            r.label = label(ai.packageName);
            long cache = st.getCacheBytes();
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            r.state = cache >= 1_000_000 ? String.format("%d MB cache", cache / 1_000_000) : (system ? "System" : "App");
            r.value = (st.getAppBytes() + st.getDataBytes()) / 1048576.0;
            if (r.value >= 1) s.rows.add(r);
        }
        sortDescending(s.rows);
        return s;
    }

    void forceStop(String pkg) throws IOException, GeneralSecurityException {
        if (!pkg.matches("[A-Za-z0-9_.]+")) throw new IOException("Bad package name");
        exec("am force-stop " + pkg);
    }

    // --- helpers -----------------------------------------------------------

    private Row row(String key, double value, String lruState, String home) {
        Row r = new Row();
        r.key = key;
        r.label = label(key);
        r.value = value;
        boolean installed = isInstalled(key);
        boolean system = !installed || PROTECTED.contains(key);
        r.state = lruState != null ? lruState : "Background";
        if (system && !r.state.equals("On screen")) r.state = "System";
        r.closable = !system && !key.equals(home) && !key.equals(context.getPackageName())
                && !r.state.equals("On screen") && !r.state.equals("System");
        return r;
    }

    /** Package name to its best (most important) state, in the words the UI shows. */
    private Map<String, String> states(String lru) {
        Map<String, String> state = new HashMap<>();
        Map<String, Integer> rank = new HashMap<>();
        for (String line : lines(lru)) {
            Matcher m = LRU.matcher(line);
            if (!m.find()) continue;
            String code = m.group(1), pkg = packageOf(m.group(3));
            int r = rankOf(code);
            Integer old = rank.get(pkg);
            if (old == null || r < old) {
                rank.put(pkg, r);
                state.put(pkg, wordsFor(code));
            }
        }
        return state;
    }

    private static int rankOf(String code) {
        switch (code) {
            case "PER": case "PERU": return 0;
            case "TOP": return 1;
            case "BTOP": case "FGS": case "BFGS": return 2;
            case "IMPF": case "IMPB": case "TRNB": case "BKUP": case "SVC": case "RCVR": return 3;
            case "LAST": return 4;
            default: return 5;
        }
    }

    private static String wordsFor(String code) {
        switch (code) {
            case "PER": case "PERU": return "System";
            case "TOP": return "On screen";
            case "BTOP": case "FGS": case "BFGS": return "Foreground";
            case "IMPF": case "IMPB": case "TRNB": case "BKUP": case "SVC": case "RCVR": return "Background";
            case "LAST": return "Previous app";
            default: return "Cached";
        }
    }

    private String homePackage() {
        ResolveInfo ri = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return ri != null && ri.activityInfo != null ? ri.activityInfo.packageName : "";
    }

    private static String packageOf(String process) {
        int colon = process.indexOf(':');
        String p = colon > 0 ? process.substring(0, colon) : process;
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    private boolean isInstalled(String pkg) {
        try {
            pm.getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String label(String key) {
        String cached = labels.get(key);
        if (cached != null) return cached;
        String l = NATIVE_NAMES.get(key);
        if (l == null) {
            try {
                l = pm.getApplicationLabel(pm.getApplicationInfo(key, 0)).toString();
            } catch (PackageManager.NameNotFoundException e) {
                l = key.startsWith("android.hardware.") ? "Hardware service (" + key.substring(17).split("@")[0] + ")" : key;
            }
        }
        labels.put(key, l);
        return l;
    }

    private static String uptime() {
        long min = SystemClock.elapsedRealtime() / 60000;
        if (min < 60) return min + " min";
        if (min < 48 * 60) return (min / 60) + " h " + (min % 60) + " min";
        return (min / 1440) + " days";
    }

    private static void sortDescending(List<Row> rows) {
        Collections.sort(rows, (a, b) -> Double.compare(b.value, a.value));
    }

    private static String part(String[] parts, int i) { return i < parts.length ? parts[i] : ""; }

    private static String[] lines(String s) { return s.split("\n"); }

    private static long firstNumber(String line) {
        Matcher m = Pattern.compile("(\\d+)").matcher(line);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        }
        return b;
    }

    private static void writeAll(File f, byte[] b) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(b); }
    }
}
