package top.zainzh.rearscreen;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only the fields needed on the rear screen; never retains city or coordinates. */
public final class RearWeatherSnapshot {
    public static final long MAX_AGE_MS = 6 * 60 * 60 * 1000L;
    private static final Pattern TEMPERATURE = Pattern.compile("^([+-]?\\d{1,3})(?:\\.\\d+)?\\s*(℃|℉|°[CF]?|[CF])?$", Pattern.CASE_INSENSITIVE);
    public enum Icon { CLEAR, CLOUD, OVERCAST, RAIN, THUNDER, SNOW, SLEET, HAZE, UNKNOWN }
    public final String temperature;
    public final Icon icon;
    public final long publishedAt;

    private RearWeatherSnapshot(String temperature, Icon icon, long publishedAt) {
        this.temperature = temperature; this.icon = icon; this.publishedAt = publishedAt;
    }

    public static RearWeatherSnapshot parse(String temperature, String type, String published, String unit) {
        if (temperature == null || published == null) return null;
        Matcher match = TEMPERATURE.matcher(temperature.trim().replace('−', '-'));
        if (!match.matches()) return null;
        try {
            int degrees = Integer.parseInt(match.group(1));
            long timestamp = Long.parseLong(published);
            if (timestamp <= 0 || degrees < -100 || degrees > 160) return null;
            String suffix = match.group(2);
            boolean fahrenheit = suffix == null || suffix.equals("°") ? "0".equals(unit)
                    : suffix.toUpperCase(java.util.Locale.ROOT).contains("F") || suffix.equals("℉");
            // Keep Fahrenheit explicit; Celsius follows the compact degree-only convention.
            String label = degrees + (fahrenheit ? "°F" : "°");
            int code;
            try { code = Integer.parseInt(type); } catch (RuntimeException ignored) { code = -1; }
            return new RearWeatherSnapshot(label, iconFor(code), timestamp);
        } catch (NumberFormatException ignored) { return null; }
    }

    public boolean fresh(long now) { return publishedAt <= now + 5 * 60 * 1000L && publishedAt >= now - MAX_AGE_MS; }

    /** Xiaomi theme weather codes, not the unrelated WMO/other weather-service codes. */
    public static Icon iconFor(int code) {
        if (code == 0) return Icon.CLEAR;
        if (code == 1) return Icon.CLOUD;
        if (code == 2) return Icon.OVERCAST;
        if (code == 7) return Icon.THUNDER;
        if (code == 12 || code == 22 || code == 25) return Icon.SLEET;
        if (code >= 13 && code <= 17) return Icon.SNOW;
        if (code >= 4 && code <= 11) return Icon.RAIN;
        if (code == 3 || code >= 18 && code <= 24) return Icon.HAZE;
        return Icon.UNKNOWN;
    }
}
