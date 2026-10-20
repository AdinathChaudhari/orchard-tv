package tv.orchard.home;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PURE JAVA (no android.* imports; host-tested).
 * Open-Meteo request URL, a minimal JSON reader (org.json is Android-only), the WMO code mapping and the
 * freshness rules for the weather cache (PLAN 7.2 and 9.6). Only the 2-decimal coordinates leave the TV.
 */
public final class WeatherParse {
    private WeatherParse() {}

    public static final int REFRESH_MIN = 30;
    public static final int STALE_MIN = 180;
    public static final int MAX_BYTES = 8192;
    public static final int CONNECT_MS = 5000;
    public static final int READ_MS = 8000;

    /** Condition ints; SkyPhase uses the same values. */
    public static final int WX_NONE = -1;
    public static final int WX_CLEAR = 0;
    public static final int WX_CLOUD = 1;
    public static final int WX_RAIN = 2;
    public static final int WX_HAZE = 3;

    public static final int GLYPH_SUN = 0;
    public static final int GLYPH_MOON = 1;
    public static final int GLYPH_CLOUD = 2;
    public static final int GLYPH_RAIN = 3;
    public static final int GLYPH_HAZE = 4;

    private static final long MIN_MS = 60000L;

    /** One parsed forecast. pop has 24 entries (percent per local hour), -1 where missing. */
    public static final class Weather {
        public final double tempC, maxC, minC;
        public final int code;
        public final boolean isDay;
        public final int[] pop;
        public final String date;

        Weather(double tempC, double maxC, double minC, int code, boolean isDay, int[] pop, String date) {
            this.tempC = tempC;
            this.maxC = maxC;
            this.minC = minC;
            this.code = code;
            this.isDay = isDay;
            this.pop = pop;
            this.date = date;
        }
    }

    public static String url(double lat, double lon) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + String.format(Locale.ROOT, "%.2f", lat)
                + "&longitude=" + String.format(Locale.ROOT, "%.2f", lon)
                + "&current=temperature_2m,weather_code,is_day&hourly=precipitation_probability"
                + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1";
    }

    public static String cityKey(double lat, double lon) {
        return String.format(Locale.ROOT, "%.2f,%.2f", lat, lon);
    }

    /**
     * The one place that decides whether a stored cache belongs to a city: the cache is keyed by
     * {@link #cityKey} of the coordinates, never by the city id string ("bengaluru"). Ambient and ClockFace
     * both go through WeatherClient.usable, which calls this.
     */
    public static boolean cacheUsable(String cachedKey, double lat, double lon, long fetchedAtMs, long nowMs) {
        return cachedKey != null && cachedKey.equals(cityKey(lat, lon)) && !stale(fetchedAtMs, nowMs);
    }

    /** True between sunrise and sunset (minutes of the day; NaN times fall back to 06:00 to 18:00). */
    public static boolean dayAt(int minute, double rise, double set) {
        double r = Double.isNaN(rise) ? 360 : rise, s = Double.isNaN(set) ? 1080 : set;
        return minute >= r && minute < s;
    }

    public static int condition(int wmo) {
        if (wmo == 0 || wmo == 1) return WX_CLEAR;
        if (wmo == 45 || wmo == 48) return WX_HAZE;
        if ((wmo >= 51 && wmo <= 67) || (wmo >= 80 && wmo <= 82) || (wmo >= 95 && wmo <= 99)) return WX_RAIN;
        return WX_CLOUD; // 2, 3, snow (71..77, 85, 86) and anything unknown
    }

    public static boolean raining(int wmo) {
        return condition(wmo) == WX_RAIN;
    }

    public static int glyph(int wmo, boolean isDay) {
        switch (condition(wmo)) {
            case WX_CLEAR: return isDay ? GLYPH_SUN : GLYPH_MOON;
            case WX_HAZE: return isDay ? GLYPH_HAZE : GLYPH_MOON;
            case WX_RAIN: return GLYPH_RAIN;
            default: return GLYPH_CLOUD;
        }
    }

    public static boolean needsRefresh(long fetchedAtMs, long nowMs) {
        return fetchedAtMs <= 0 || nowMs < fetchedAtMs || nowMs - fetchedAtMs >= REFRESH_MIN * MIN_MS;
    }

    public static boolean stale(long fetchedAtMs, long nowMs) {
        return fetchedAtMs <= 0 || nowMs < fetchedAtMs - 5 * MIN_MS || nowMs - fetchedAtMs > STALE_MIN * MIN_MS;
    }

    public static int[] popFor(Weather w, String todayIso) {
        if (w == null || todayIso == null || !todayIso.equals(w.date)) return null;
        return w.pop;
    }

    @SuppressWarnings("unchecked")
    public static Weather parse(String json) {
        if (json == null) throw new IllegalArgumentException("null");
        if (json.length() > MAX_BYTES) throw new IllegalArgumentException("too long");
        Object root = new Reader(json).readDocument();
        if (!(root instanceof Map)) throw new IllegalArgumentException("not an object");
        Map<String, Object> top = (Map<String, Object>) root;
        Map<String, Object> cur = obj(top.get("current"));
        if (cur == null) throw new IllegalArgumentException("no current");
        Double temp = num(cur.get("temperature_2m"));
        Double code = num(cur.get("weather_code"));
        if (temp == null || code == null) throw new IllegalArgumentException("missing current fields");
        Double day = num(cur.get("is_day"));
        boolean isDay = day == null || day.doubleValue() != 0;
        if (cur.get("is_day") instanceof Boolean) isDay = ((Boolean) cur.get("is_day")).booleanValue();

        Map<String, Object> daily = obj(top.get("daily"));
        double max = Double.NaN, min = Double.NaN;
        String dailyDate = "";
        if (daily != null) {
            Double mx = first(daily.get("temperature_2m_max"));
            Double mn = first(daily.get("temperature_2m_min"));
            if (mx != null) max = mx.doubleValue();
            if (mn != null) min = mn.doubleValue();
            Object t = first0(daily.get("time"));
            if (t instanceof String) dailyDate = head10((String) t);
        }

        int[] pop = new int[24];
        for (int i = 0; i < 24; i++) pop[i] = -1;
        Map<String, Object> hourly = obj(top.get("hourly"));
        if (hourly != null && hourly.get("precipitation_probability") instanceof List) {
            List<Object> l = (List<Object>) hourly.get("precipitation_probability");
            for (int i = 0; i < 24 && i < l.size(); i++) {
                Double v = num(l.get(i));
                if (v != null && !v.isNaN()) pop[i] = (int) Math.max(0, Math.min(100, Math.round(v.doubleValue())));
            }
        }

        String date = dailyDate;
        if (cur.get("time") instanceof String) {
            String ct = head10((String) cur.get("time"));
            if (ct.length() > 0) date = ct;
        }
        return new Weather(temp.doubleValue(), max, min, (int) Math.round(code.doubleValue()), isDay, pop, date);
    }

    private static String head10(String s) {
        return s.length() >= 10 ? s.substring(0, 10) : "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static Double num(Object o) {
        return o instanceof Double ? (Double) o : null;
    }

    private static Object first0(Object o) {
        if (o instanceof List && !((List<?>) o).isEmpty()) return ((List<?>) o).get(0);
        return null;
    }

    private static Double first(Object o) {
        return num(first0(o));
    }

    /** Small recursive-descent JSON reader. Objects are LinkedHashMap, arrays ArrayList, numbers Double. */
    private static final class Reader {
        private static final int MAX_DEPTH = 32;
        private final String s;
        private int p;

        Reader(String s) {
            this.s = s;
        }

        Object readDocument() {
            ws();
            Object v = value(0);
            ws();
            if (p != s.length()) throw bad("trailing data");
            return v;
        }

        private IllegalArgumentException bad(String m) {
            return new IllegalArgumentException("bad JSON at " + p + ": " + m);
        }

        private void ws() {
            while (p < s.length()) {
                char c = s.charAt(p);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') p++;
                else break;
            }
        }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) throw bad("too deep");
            if (p >= s.length()) throw bad("unexpected end");
            char c = s.charAt(p);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; }
            if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; }
            if (s.startsWith("null", p)) { p += 4; return null; }
            if (c == '-' || (c >= '0' && c <= '9')) return number();
            throw bad("unexpected character");
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            p++;
            ws();
            if (p < s.length() && s.charAt(p) == '}') { p++; return m; }
            while (true) {
                ws();
                if (p >= s.length() || s.charAt(p) != '"') throw bad("key expected");
                String k = string();
                ws();
                if (p >= s.length() || s.charAt(p) != ':') throw bad("colon expected");
                p++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                if (p >= s.length()) throw bad("unexpected end");
                char c = s.charAt(p++);
                if (c == '}') return m;
                if (c != ',') throw bad("comma expected");
            }
        }

        private List<Object> array(int depth) {
            List<Object> l = new ArrayList<Object>();
            p++;
            ws();
            if (p < s.length() && s.charAt(p) == ']') { p++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                if (p >= s.length()) throw bad("unexpected end");
                char c = s.charAt(p++);
                if (c == ']') return l;
                if (c != ',') throw bad("comma expected");
            }
        }

        private String string() {
            p++;
            StringBuilder b = new StringBuilder();
            while (true) {
                if (p >= s.length()) throw bad("unterminated string");
                char c = s.charAt(p++);
                if (c == '"') return b.toString();
                if (c < 0x20) throw bad("control character");
                if (c != '\\') { b.append(c); continue; }
                if (p >= s.length()) throw bad("unterminated escape");
                char e = s.charAt(p++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (p + 4 > s.length()) throw bad("short unicode escape");
                        int v = 0;
                        for (int i = 0; i < 4; i++) {
                            int d = Character.digit(s.charAt(p + i), 16);
                            if (d < 0) throw bad("bad unicode escape");
                            v = v * 16 + d;
                        }
                        p += 4;
                        b.append((char) v);
                        break;
                    default: throw bad("bad escape");
                }
            }
        }

        private Double number() {
            int st = p;
            if (s.charAt(p) == '-') p++;
            while (p < s.length()) {
                char c = s.charAt(p);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') p++;
                else break;
            }
            try {
                double d = Double.parseDouble(s.substring(st, p));
                if (Double.isInfinite(d) || Double.isNaN(d)) throw bad("number out of range");
                return Double.valueOf(d);
            } catch (NumberFormatException ex) {
                throw bad("bad number");
            }
        }
    }
}
