package dev.mibox.screensaver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Open-Meteo (https://open-meteo.com, no API key, CC BY 4.0). Blocking calls: run off the main thread.
 */
final class WeatherClient {
    private static final int TIMEOUT_MS = 10_000;

    private WeatherClient() {}

    /** A geocoding match. */
    static final class Place {
        final String label;
        final double lat;
        final double lon;

        Place(String label, double lat, double lon) {
            this.label = label;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** Raw forecast JSON: current conditions, the next 12 hours and 4 days, in the location's time zone. */
    static String forecast(double lat, double lon, boolean fahrenheit) throws IOException {
        String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
                        + "&hourly=temperature_2m,precipitation_probability,weather_code"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                        + "&timezone=auto&forecast_days=4&forecast_hours=12%s",
                lat, lon, fahrenheit ? "&temperature_unit=fahrenheit" : "");
        return get(url);
    }

    /** City search; up to 8 matches, best first. */
    static List<Place> search(String name) throws IOException, JSONException {
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name="
                + URLEncoder.encode(name.trim(), "UTF-8");
        JSONArray results = new JSONObject(get(url)).optJSONArray("results");
        List<Place> places = new ArrayList<>();
        if (results == null) return places;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            StringBuilder label = new StringBuilder(r.getString("name"));
            String admin = r.optString("admin1", "");
            if (!admin.isEmpty() && !admin.equals(r.getString("name"))) label.append(", ").append(admin);
            String country = r.optString("country", "");
            if (!country.isEmpty()) label.append(", ").append(country);
            places.add(new Place(label.toString(), r.getDouble("latitude"), r.getDouble("longitude")));
        }
        return places;
    }

    private static String get(String url) throws IOException {
        InputStream in = open(url);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    /** GET with timeouts; the caller must close the stream. Also used for background photos. */
    static InputStream open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "SimpleScreensaver");
        int code = c.getResponseCode();
        if (code != 200) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c.getInputStream();
    }
}
