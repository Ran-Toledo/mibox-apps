package dev.mibox.screensaver;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen photo matching the weather, picked at random from a pool per weather kind and rotated.
 * <ul>
 *   <li>Pools come from res/raw/backgrounds.json, refreshed from GitHub at most once a day.</li>
 *   <li>Photos are downloaded on demand into a cache of at most {@link #CACHE_FILES} files; the next
 *       photo is downloaded while the current one shows, so a rotation never waits.</li>
 *   <li>Only the shown photo is in memory (two for the length of a cross-fade), decoded as RGB_565.</li>
 * </ul>
 * Until a photo is ready, or with no network and nothing cached, the solid background shows through.
 */
final class PhotoBackground {
    static final String LIST_URL =
            "https://raw.githubusercontent.com/Ran-Toledo/mibox-apps/main/screensaver/res/raw/backgrounds.json";
    private static final long LIST_REFRESH_MS = 24 * 3_600_000L;
    private static final int CACHE_FILES = 8;
    private static final long FADE_MS = 1200;
    /** 40% black over the photo so white text stays readable. */
    private static final int SCRIM = 0x66000000;

    private final Context context;
    private final long rotateMs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final FrameLayout root;
    private final ImageView base;
    private final ImageView top;
    private final File cacheDir;
    private final File listFile;

    private Map<String, List<String>> pools = new HashMap<>();
    private ExecutorService io;
    private boolean started;
    private boolean loading;
    private String kind;
    private String current;
    private String next;
    private long shownAt;
    /** Incremented on stop() and on kind changes, so stale background work is dropped. */
    private int generation;

    PhotoBackground(Context context, int rotateMinutes) {
        this.context = context;
        this.rotateMs = Math.max(1, rotateMinutes) * 60_000L;
        cacheDir = new File(context.getCacheDir(), "bg");
        listFile = new File(context.getFilesDir(), "backgrounds.json");
        root = new FrameLayout(context);
        base = image();
        top = image();
        top.setAlpha(0f);
        root.addView(base);
        root.addView(top);
        View scrim = new View(context);
        scrim.setBackgroundColor(SCRIM);
        root.addView(scrim);
        root.setVisibility(View.INVISIBLE);
    }

    View view() {
        return root;
    }

    void start() {
        if (started) return;
        started = true;
        generation++;
        pools = loadPools();
        io = Executors.newSingleThreadExecutor();
        io.execute(new Runnable() {
            @Override
            public void run() {
                refreshList();
            }
        });
        if (kind != null) showNext();
    }

    void stop() {
        started = false;
        generation++;
        loading = false;
        if (io != null) io.shutdownNow();
        io = null;
        top.animate().cancel();
        base.setImageDrawable(null);
        top.setImageDrawable(null);
        root.setVisibility(View.INVISIBLE);
        current = null;
        next = null;
    }

    /** The weather kind to match (see WeatherMode.backgroundKind); switches photo when it changes. */
    void setKind(String newKind) {
        if (newKind == null || newKind.equals(kind)) return;
        kind = newKind;
        next = null;
        generation++;
        loading = false;
        if (started) showNext();
    }

    /** Called every 15 s; rotates once the current photo has been up for the rotation interval. */
    void tick() {
        if (started && kind != null && !loading && System.currentTimeMillis() - shownAt >= rotateMs) showNext();
    }

    // ---- Choosing and showing ----

    private void showNext() {
        final List<String> pool = pools.get(kind);
        if (pool == null || pool.isEmpty() || io == null) return;
        final String url = next != null && pool.contains(next) ? next : pick(pool, current, false);
        if (url == null) return;
        loading = true;
        final int gen = generation;
        final String forKind = kind;
        io.execute(new Runnable() {
            @Override
            public void run() {
                String shown = url;
                Bitmap bitmap = load(url);
                if (bitmap == null) {
                    // Offline or a dead link: fall back to another photo already in the cache.
                    shown = pick(pool, current, true);
                    bitmap = shown == null ? null : load(shown);
                }
                final Bitmap result = bitmap;
                final String resultUrl = shown;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation) return;
                        loading = false;
                        shownAt = System.currentTimeMillis();
                        if (result == null) return; // Try again at the next rotation.
                        current = resultUrl;
                        crossFade(result);
                        prefetch(pool, forKind);
                    }
                });
            }
        });
    }

    /** Downloads the photo that will be shown next, so the rotation does not wait for the network. */
    private void prefetch(List<String> pool, String forKind) {
        final String url = pick(pool, current, false);
        next = url;
        if (url == null || io == null || !forKind.equals(kind)) return;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    cached(url);
                } catch (IOException ignored) {
                    // showNext() falls back to the cache.
                }
            }
        });
    }

    /** A random URL from the pool other than {@code exclude}; with {@code cachedOnly}, only downloaded ones. */
    private String pick(List<String> pool, String exclude, boolean cachedOnly) {
        List<String> choices = new ArrayList<>();
        for (String u : pool) {
            if (u.equals(exclude) && pool.size() > 1) continue;
            if (cachedOnly && !fileFor(u).exists()) continue;
            choices.add(u);
        }
        return choices.isEmpty() ? null : choices.get(random.nextInt(choices.size()));
    }

    private void crossFade(Bitmap bitmap) {
        root.setVisibility(View.VISIBLE);
        top.animate().cancel();
        top.setImageBitmap(bitmap);
        top.setAlpha(0f);
        final Bitmap shown = bitmap;
        top.animate().alpha(1f).setDuration(FADE_MS).withEndAction(new Runnable() {
            @Override
            public void run() {
                // Keep only one decoded photo: the base takes the new one, the old one is dropped.
                base.setImageBitmap(shown);
                top.setImageDrawable(null);
                top.setAlpha(0f);
            }
        }).start();
    }

    // ---- Files (background thread) ----

    /** Decoded photo, downloading it first if needed; null on any failure. */
    private Bitmap load(String url) {
        try {
            File f = cached(url);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            // Photos are requested at 1280 px; scale down anything much larger (custom lists).
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= 1280) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b == null) f.delete(); // Not an image: download again next time.
            return b;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private synchronized File cached(String url) throws IOException {
        File f = fileFor(url);
        if (f.exists()) {
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        cacheDir.mkdirs();
        File tmp = new File(cacheDir, f.getName() + ".part");
        InputStream in = WeatherClient.open(url);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        } finally {
            in.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
        trimCache(f);
        return f;
    }

    /** Keeps the newest CACHE_FILES photos (plus the one just added). */
    private void trimCache(File keep) {
        File[] files = cacheDir.listFiles();
        if (files == null || files.length <= CACHE_FILES) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        String cur = current == null ? "" : fileFor(current).getName();
        for (int i = CACHE_FILES; i < files.length; i++) {
            if (!files[i].equals(keep) && !files[i].getName().equals(cur)) files[i].delete();
        }
    }

    private File fileFor(String url) {
        return new File(cacheDir, Integer.toHexString(url.hashCode()) + ".jpg");
    }

    // ---- Pool list ----

    /** The downloaded list if there is a valid one, else the copy built into the app. */
    private Map<String, List<String>> loadPools() {
        if (listFile.exists()) {
            try {
                Map<String, List<String>> m = parse(readAll(new java.io.FileInputStream(listFile)));
                if (!m.isEmpty()) return m;
            } catch (Exception ignored) {
                // Fall through to the built-in list.
            }
        }
        try {
            return parse(readAll(context.getResources().openRawResource(R.raw.backgrounds)));
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /** Fetches the list from GitHub at most once a day; a bad or missing file keeps the current list. */
    private void refreshList() {
        if (listFile.exists() && System.currentTimeMillis() - listFile.lastModified() < LIST_REFRESH_MS) return;
        try {
            String json = readAll(WeatherClient.open(LIST_URL));
            final Map<String, List<String>> m = parse(json);
            if (m.isEmpty()) return;
            try (FileOutputStream out = new FileOutputStream(listFile)) {
                out.write(json.getBytes("UTF-8"));
            }
            handler.post(new Runnable() {
                @Override
                public void run() {
                    pools = m;
                }
            });
        } catch (Exception ignored) {
            // Offline, or not on GitHub yet: keep using the current list; retry next time.
            listFile.setLastModified(System.currentTimeMillis() - LIST_REFRESH_MS + 3_600_000L);
        }
    }

    private static Map<String, List<String>> parse(String json) throws Exception {
        JSONObject pools = new JSONObject(json).getJSONObject("pools");
        Map<String, List<String>> m = new HashMap<>();
        JSONArray names = pools.names();
        if (names == null) return m;
        for (int i = 0; i < names.length(); i++) {
            String k = names.getString(i);
            JSONArray arr = pools.getJSONArray(k);
            List<String> urls = new ArrayList<>();
            for (int j = 0; j < arr.length(); j++) {
                String u = arr.getJSONObject(j).optString("url", "");
                if (u.startsWith("https://")) urls.add(u);
            }
            if (!urls.isEmpty()) m.put(k, Collections.unmodifiableList(urls));
        }
        return m;
    }

    private static String readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private ImageView image() {
        ImageView v = new ImageView(context);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return v;
    }
}
