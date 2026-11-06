package dev.mibox.screensaver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** A parsed Open-Meteo forecast (see WeatherClient.forecast). Times are epoch millis. */
final class Weather {
    enum Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

    static final class Hour {
        final long time;
        final int temp;
        /** Precipitation probability in %, or -1 if unknown. */
        final int rain;
        final int code;

        Hour(long time, int temp, int rain, int code) {
            this.time = time;
            this.temp = temp;
            this.rain = rain;
            this.code = code;
        }
    }

    static final class Day {
        /** Noon of that day, so formatting in a nearby time zone gives the same date. */
        final long noon;
        final int code;
        final int hi;
        final int lo;

        Day(long noon, int code, int hi, int lo) {
            this.noon = noon;
            this.code = code;
            this.hi = hi;
            this.lo = lo;
        }
    }

    final int temp;
    final int feels;
    final int code;
    final boolean isDay;
    final List<Hour> hours = new ArrayList<>();
    /** Today first. */
    final List<Day> days = new ArrayList<>();

    private Weather(JSONObject j) throws JSONException, ParseException {
        int offsetMs = j.optInt("utc_offset_seconds", 0) * 1000;
        SimpleDateFormat dateTime = utc("yyyy-MM-dd'T'HH:mm");
        SimpleDateFormat date = utc("yyyy-MM-dd");

        JSONObject cur = j.getJSONObject("current");
        temp = round(cur.getDouble("temperature_2m"));
        feels = round(cur.getDouble("apparent_temperature"));
        code = cur.getInt("weather_code");
        isDay = cur.optInt("is_day", 1) == 1;

        JSONObject h = j.getJSONObject("hourly");
        JSONArray ht = h.getJSONArray("time");
        JSONArray htemp = h.getJSONArray("temperature_2m");
        JSONArray hrain = h.getJSONArray("precipitation_probability");
        JSONArray hcode = h.getJSONArray("weather_code");
        for (int i = 0; i < ht.length(); i++) {
            if (htemp.isNull(i) || hcode.isNull(i)) continue;
            hours.add(new Hour(dateTime.parse(ht.getString(i)).getTime() - offsetMs,
                    round(htemp.getDouble(i)), hrain.isNull(i) ? -1 : hrain.getInt(i), hcode.getInt(i)));
        }

        JSONObject d = j.getJSONObject("daily");
        JSONArray dt = d.getJSONArray("time");
        JSONArray dcode = d.getJSONArray("weather_code");
        JSONArray dmax = d.getJSONArray("temperature_2m_max");
        JSONArray dmin = d.getJSONArray("temperature_2m_min");
        for (int i = 0; i < dt.length(); i++) {
            if (dcode.isNull(i) || dmax.isNull(i) || dmin.isNull(i)) continue;
            days.add(new Day(date.parse(dt.getString(i)).getTime() - offsetMs + 12 * 3_600_000L,
                    dcode.getInt(i), round(dmax.getDouble(i)), round(dmin.getDouble(i))));
        }
    }

    /** Parses a cached response; null if it is missing or malformed. */
    static Weather parse(String json) {
        if (json == null) return null;
        try {
            return new Weather(new JSONObject(json));
        } catch (JSONException | ParseException e) {
            return null;
        }
    }

    /** Up to {@code max} hours that start after {@code now}. */
    List<Hour> nextHours(long now, int max) {
        List<Hour> out = new ArrayList<>();
        for (Hour hour : hours) {
            if (hour.time > now) out.add(hour);
            if (out.size() == max) break;
        }
        return out;
    }

    /** "Rain now", "Rain likely from 16:00" (formatted by the caller), or null if dry for the next 6 hours. */
    Hour nextWetHour(long now) {
        for (Hour hour : nextHours(now, 6)) {
            if (hour.rain >= 50 || isWet(kind(hour.code))) return hour;
        }
        return null;
    }

    static boolean isWet(Kind k) {
        return k == Kind.DRIZZLE || k == Kind.RAIN || k == Kind.SNOW || k == Kind.STORM;
    }

    /** WMO weather interpretation codes, as used by Open-Meteo. */
    static Kind kind(int code) {
        if (code <= 1) return Kind.CLEAR;
        if (code == 2) return Kind.PARTLY;
        if (code == 3) return Kind.CLOUDY;
        if (code == 45 || code == 48) return Kind.FOG;
        if (code >= 51 && code <= 57) return Kind.DRIZZLE;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return Kind.SNOW;
        if (code >= 95) return Kind.STORM;
        return Kind.RAIN; // 61-67, 80-82
    }

    static String label(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mainly clear";
            case 2: return "Partly cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: case 63: return "Rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: case 82: return "Showers";
            case 85: case 86: return "Snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm, hail";
            default: return "";
        }
    }

    /** What is falling, for "Rain from 16:00" style summaries. */
    static String precipWord(int code) {
        Kind k = kind(code);
        if (k == Kind.SNOW) return "Snow";
        if (k == Kind.STORM) return "Storms";
        return "Rain";
    }

    private static SimpleDateFormat utc(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    private static int round(double v) {
        return (int) Math.round(v);
    }
}
