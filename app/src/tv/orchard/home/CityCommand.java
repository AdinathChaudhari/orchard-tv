package tv.orchard.home;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PURE JAVA (no android.* imports; host-tested).
 * City list parsing, name matching, the 'city' command's config edit, and resolving the active city.
 */
public final class CityCommand {
    private CityCommand() {}

    public static final String DEFAULT_ID = "bengaluru";
    private static final int LABEL_MAX = 24;

    public static final class City {
        public final String id, name, cc, tz;
        public final double lat, lon;

        City(String id, String name, String cc, double lat, double lon, String tz) {
            this.id = id; this.name = name; this.cc = cc; this.lat = lat; this.lon = lon; this.tz = tz;
        }
    }

    /** Parse cities.csv text (header 'id,name,cc,lat,lon,tz,...'). Header, blank and malformed rows are skipped. */
    public static List<City> parseCsv(String text) {
        List<City> out = new ArrayList<City>();
        if (text == null) return out;
        boolean first = true;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (first) { first = false; continue; }
            String[] f = t.split(",", -1);
            if (f.length < 6) continue;
            String id = f[0].trim(), name = f[1].trim(), cc = f[2].trim(), tz = f[5].trim();
            if (id.isEmpty() || name.isEmpty() || tz.isEmpty()) continue;
            double lat, lon;
            try {
                lat = Double.parseDouble(f[3].trim());
                lon = Double.parseDouble(f[4].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (!validLatLon(lat, lon)) continue;
            out.add(new City(id, name, cc, lat, lon, tz));
        }
        return out;
    }

    /** Lowercase, keep only a-z0-9 (drops spaces, hyphens and the rest). */
    public static String norm(String s) {
        if (s == null) return "";
        String l = s.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) b.append(c);
        }
        return b.toString();
    }

    /** First city whose normalised id or name equals the normalised query; null if none. */
    public static City find(List<City> all, String nameOrId) {
        String q = norm(nameOrId);
        if (q.isEmpty() || all == null) return null;
        for (City c : all) {
            if (q.equals(norm(c.id)) || q.equals(norm(c.name))) return c;
        }
        return null;
    }

    /** Letters, digits, space . - ' only (so ';' and '=' can never enter the conf); max 24 chars; empty -> null. */
    public static String cleanLabel(String raw) {
        if (raw == null) return null;
        StringBuilder b = new StringBuilder();
        boolean space = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ' ') {
                space = true;
                continue;
            }
            if (Character.isLetter(c) || Character.isDigit(c) || c == '.' || c == '-' || c == '\'') {
                if (space && b.length() > 0) b.append(' ');
                space = false;
                b.append(c);
            }
        }
        String s = b.toString().trim();
        if (s.length() > LABEL_MAX) s = s.substring(0, LABEL_MAX).trim();
        return s.isEmpty() ? null : s;
    }

    public static String defaultLabel(double lat, double lon) {
        return String.format(Locale.ROOT, "%.2f,%.2f", lat, lon);
    }

    private static boolean validLatLon(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isInfinite(lat) && !Double.isNaN(lon) && !Double.isInfinite(lon)
                && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static final String[] KEYS = {
        Config.Keys.CITY_ID, Config.Keys.CITY_LAT, Config.Keys.CITY_LON, Config.Keys.CITY_NAME, Config.Keys.CITY_TZ };

    private static String snapshot(Map<String, String> conf) {
        StringBuilder b = new StringBuilder();
        for (String k : KEYS) b.append(conf.get(k)).append('\u0001');
        return b.toString();
    }

    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    /**
     * Apply a 'city' command to conf. Returns 1 applied (changed), 0 same as before, -1 rejected (conf untouched).
     * Exactly one of cityId / (lat and lon) must be given.
     */
    public static int apply(Map<String, String> conf, List<City> all, String cityId, String lat, String lon, String name) {
        boolean byId = !blank(cityId);
        boolean byPos = !blank(lat) || !blank(lon);
        if (byId == byPos) return -1;
        String before = snapshot(conf);
        if (byId) {
            City c = find(all, cityId);
            if (c == null) return -1;
            conf.put(Config.Keys.CITY_ID, c.id);
            conf.remove(Config.Keys.CITY_LAT);
            conf.remove(Config.Keys.CITY_LON);
            conf.remove(Config.Keys.CITY_NAME);
            conf.remove(Config.Keys.CITY_TZ);
        } else {
            if (blank(lat) || blank(lon)) return -1;
            double la, lo;
            try {
                la = Double.parseDouble(lat.trim());
                lo = Double.parseDouble(lon.trim());
            } catch (NumberFormatException e) {
                return -1;
            }
            if (!validLatLon(la, lo)) return -1;
            String label = cleanLabel(name);
            if (label == null) label = defaultLabel(la, lo);
            conf.remove(Config.Keys.CITY_ID);
            conf.remove(Config.Keys.CITY_TZ);
            conf.put(Config.Keys.CITY_LAT, String.format(Locale.ROOT, "%.4f", la));
            conf.put(Config.Keys.CITY_LON, String.format(Locale.ROOT, "%.4f", lo));
            conf.put(Config.Keys.CITY_NAME, label);
        }
        return snapshot(conf).equals(before) ? 0 : 1;
    }

    public static final class Resolved {
        /** null for a custom city */
        public final String id;
        public final String name;
        /** null for a custom city */
        public final String cc;
        /** null = the TV's own zone */
        public final String tz;
        public final double lat, lon;

        Resolved(String id, String name, String cc, String tz, double lat, double lon) {
            this.id = id; this.name = name; this.cc = cc; this.tz = tz; this.lat = lat; this.lon = lon;
        }

        /** The id, or "lat,lon" for a custom city. */
        public String key() { return id != null ? id : defaultLabel(lat, lon); }
    }

    private static Resolved of(City c) { return new Resolved(c.id, c.name, c.cc, c.tz, c.lat, c.lon); }

    /** Active city from conf: valid custom lat/lon wins, else city_id, else Bengaluru. */
    public static Resolved resolve(Map<String, String> conf, List<City> all) {
        String la = conf.get(Config.Keys.CITY_LAT), lo = conf.get(Config.Keys.CITY_LON);
        if (!blank(la) && !blank(lo)) {
            try {
                double lat = Double.parseDouble(la.trim()), lon = Double.parseDouble(lo.trim());
                if (validLatLon(lat, lon)) {
                    String label = cleanLabel(conf.get(Config.Keys.CITY_NAME));
                    if (label == null) label = defaultLabel(lat, lon);
                    String tz = conf.get(Config.Keys.CITY_TZ);
                    return new Resolved(null, label, null, blank(tz) ? null : tz.trim(), lat, lon);
                }
            } catch (NumberFormatException e) {
                // fall through to city_id
            }
        }
        String id = conf.get(Config.Keys.CITY_ID);
        City c = blank(id) ? null : find(all, id);
        if (c == null) c = find(all, DEFAULT_ID);
        if (c == null) return new Resolved(DEFAULT_ID, "Bengaluru", "IN", "Asia/Kolkata", 12.9716, 77.5946);
        return of(c);
    }
}
