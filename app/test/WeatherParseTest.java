package tv.orchard.home;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public final class WeatherParseTest {
    static int fails;

    static void eq(String name, Object want, Object got) {
        String w = String.valueOf(want), g = String.valueOf(got);
        if (!w.equals(g)) { fails++; System.err.println("FAIL " + name + ": want [" + w + "] got [" + g + "]"); }
    }

    static void throwsIae(String name, String json) {
        try {
            WeatherParse.parse(json);
            fails++;
            System.err.println("FAIL " + name + ": no exception");
        } catch (IllegalArgumentException ok) {
            // expected
        }
    }

    public static void main(String[] args) {
        try {
            String json = new String(Files.readAllBytes(Paths.get("test/fixtures/open-meteo-bengaluru.json")), StandardCharsets.UTF_8);
            eq("url", "https://api.open-meteo.com/v1/forecast?latitude=12.97&longitude=77.59&current=temperature_2m,weather_code,is_day&hourly=precipitation_probability&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1", WeatherParse.url(12.9716, 77.5946));
            eq("url negative", true, WeatherParse.url(-33.8688, -0.5).contains("latitude=-33.87&longitude=-0.50&"));
            eq("cityKey", "12.97,77.59", WeatherParse.cityKey(12.9716, 77.5946));
            // regression: the cache is keyed by coordinates; the city id string must never match (it hid the chip and rain)
            long tc = 1_700_000_000_000L;
            eq("cacheUsable coord key", true, WeatherParse.cacheUsable("12.97,77.59", 12.9716, 77.5946, tc, tc + 60_000L));
            eq("cacheUsable city id", false, WeatherParse.cacheUsable("bengaluru", 12.9716, 77.5946, tc, tc + 60_000L));
            eq("cacheUsable other city", false, WeatherParse.cacheUsable("51.51,-0.13", 12.9716, 77.5946, tc, tc + 60_000L));
            eq("cacheUsable null key", false, WeatherParse.cacheUsable(null, 12.9716, 77.5946, tc, tc + 60_000L));
            eq("cacheUsable stale", false, WeatherParse.cacheUsable("12.97,77.59", 12.9716, 77.5946, tc, tc + 24L * 3600_000L));
            eq("dayAt noon", true, WeatherParse.dayAt(720, 360, 1080));
            eq("dayAt 09:00", true, WeatherParse.dayAt(540, 360, 1080));
            eq("dayAt before rise", false, WeatherParse.dayAt(300, 360, 1080));
            eq("dayAt at set", false, WeatherParse.dayAt(1080, 360, 1080));
            eq("dayAt NaN night", false, WeatherParse.dayAt(1200, Double.NaN, Double.NaN));

            WeatherParse.Weather w = WeatherParse.parse(json);
            eq("tempC", 24.3, w.tempC);
            eq("code", 61, w.code);
            eq("isDay", true, w.isDay);
            eq("max", 28.1, w.maxC);
            eq("min", 19.6, w.minC);
            eq("pop length", 24, w.pop.length);
            eq("pop[0]", 0, w.pop[0]);
            eq("pop[14]", 70, w.pop[14]);
            eq("pop[17]", 40, w.pop[17]);
            eq("date", "2026-10-20", w.date);

            eq("condition 61 rain", WeatherParse.WX_RAIN, WeatherParse.condition(61));
            eq("raining 61", true, WeatherParse.raining(61));
            eq("raining 3", false, WeatherParse.raining(3));
            eq("glyph 61 day", WeatherParse.GLYPH_RAIN, WeatherParse.glyph(61, true));
            eq("glyph 61 night", WeatherParse.GLYPH_RAIN, WeatherParse.glyph(61, false));
            eq("glyph 0 day", WeatherParse.GLYPH_SUN, WeatherParse.glyph(0, true));
            eq("glyph 0 night", WeatherParse.GLYPH_MOON, WeatherParse.glyph(0, false));
            eq("glyph 45 day", WeatherParse.GLYPH_HAZE, WeatherParse.glyph(45, true));
            eq("glyph 45 night", WeatherParse.GLYPH_MOON, WeatherParse.glyph(45, false));
            eq("glyph 3", WeatherParse.GLYPH_CLOUD, WeatherParse.glyph(3, true));
            eq("condition 3 cloud", WeatherParse.WX_CLOUD, WeatherParse.condition(3));
            eq("condition 2 cloud", WeatherParse.WX_CLOUD, WeatherParse.condition(2));
            eq("condition 71 cloud", WeatherParse.WX_CLOUD, WeatherParse.condition(71));
            eq("condition 86 cloud", WeatherParse.WX_CLOUD, WeatherParse.condition(86));
            eq("condition 95 rain", WeatherParse.WX_RAIN, WeatherParse.condition(95));
            eq("condition 94 cloud", WeatherParse.WX_CLOUD, WeatherParse.condition(94));
            eq("condition 96 rain", WeatherParse.WX_RAIN, WeatherParse.condition(96));
            eq("condition 51 rain", WeatherParse.WX_RAIN, WeatherParse.condition(51));
            eq("condition 67 rain", WeatherParse.WX_RAIN, WeatherParse.condition(67));
            eq("condition 68 cloud", WeatherParse.WX_CLOUD, WeatherParse.condition(68));
            eq("condition 80 rain", WeatherParse.WX_RAIN, WeatherParse.condition(80));
            eq("condition 82 rain", WeatherParse.WX_RAIN, WeatherParse.condition(82));
            eq("condition 83 cloud", WeatherParse.WX_CLOUD, WeatherParse.condition(83));
            eq("condition 99 rain", WeatherParse.WX_RAIN, WeatherParse.condition(99));
            eq("condition 48 haze", WeatherParse.WX_HAZE, WeatherParse.condition(48));
            eq("condition 1 clear", WeatherParse.WX_CLEAR, WeatherParse.condition(1));
            eq("condition 0 clear", WeatherParse.WX_CLEAR, WeatherParse.condition(0));
            eq("condition unknown cloud", WeatherParse.WX_CLOUD, WeatherParse.condition(1234));

            WeatherParse.Weather night = WeatherParse.parse(json.replace("\"is_day\": 1", "\"is_day\": 0").replace("\"weather_code\": 61", "\"weather_code\": 1"));
            eq("night isDay", false, night.isDay);
            eq("night code", 1, night.code);
            eq("night glyph moon", WeatherParse.GLYPH_MOON, WeatherParse.glyph(night.code, night.isDay));

            WeatherParse.Weather nul = WeatherParse.parse(json.replace("\"precipitation_probability\": [\n   0,", "\"precipitation_probability\": [\n   null,"));
            eq("null hourly -> -1", -1, nul.pop[0]);
            eq("other hourly kept", 70, nul.pop[14]);

            // missing / short / clamped fields
            WeatherParse.Weather min = WeatherParse.parse("{\"current\":{\"temperature_2m\":10,\"weather_code\":3}}");
            eq("is_day missing -> true", true, min.isDay);
            eq("max missing NaN", true, Double.isNaN(min.maxC));
            eq("min missing NaN", true, Double.isNaN(min.minC));
            eq("hourly missing -1", -1, min.pop[5]);
            eq("date missing empty", "", min.date);
            WeatherParse.Weather sh = WeatherParse.parse("{\"current\":{\"temperature_2m\":1,\"weather_code\":0},\"hourly\":{\"precipitation_probability\":[150,-4,20]},\"daily\":{\"time\":[\"2026-01-02\"]}}");
            eq("clamped high", 100, sh.pop[0]);
            eq("clamped low", 0, sh.pop[1]);
            eq("short list value", 20, sh.pop[2]);
            eq("short list absent", -1, sh.pop[3]);
            eq("date from daily", "2026-01-02", sh.date);

            throwsIae("missing temperature", json.replace("\"temperature_2m\": 24.3", "\"x\": 24.3"));
            throwsIae("missing weather_code", json.replace("\"weather_code\": 61", "\"x\": 61"));
            throwsIae("missing current", "{\"daily\":{}}");
            throwsIae("open brace", "{");
            throwsIae("empty", "");
            throwsIae("not json", "hello");
            throwsIae("array root", "[1,2]");
            throwsIae("trailing", "{\"current\":{\"temperature_2m\":1,\"weather_code\":0}} x");
            throwsIae("null input", null);
            StringBuilder big = new StringBuilder("{\"a\":\"");
            while (big.length() < 9000) big.append('x');
            big.append("\"}");
            throwsIae("9000 chars", big.toString());

            WeatherParse.Weather u = WeatherParse.parse("{\"name\":\"Beng\\u0061luru\",\"current\":{\"temperature_2m\":1,\"weather_code\":0,\"time\":\"2026-10-20T14:15\"}}");
            eq("unicode parse ok", "2026-10-20", u.date);

            long min30 = 30L * 60000L, t0 = 1000000000L;
            eq("refresh 29:59", false, WeatherParse.needsRefresh(t0, t0 + min30 - 1000));
            eq("refresh 30:00", true, WeatherParse.needsRefresh(t0, t0 + min30));
            eq("refresh clock back", true, WeatherParse.needsRefresh(t0, t0 - 1));
            eq("refresh never fetched", true, WeatherParse.needsRefresh(0, t0));
            long s180 = 180L * 60000L;
            eq("stale 180:00", false, WeatherParse.stale(t0, t0 + s180));
            eq("stale 180:01", true, WeatherParse.stale(t0, t0 + s180 + 1000));
            eq("stale never fetched", true, WeatherParse.stale(0, t0));
            eq("stale clock back 4 min", false, WeatherParse.stale(t0, t0 - 4L * 60000L));
            eq("stale clock back 6 min", true, WeatherParse.stale(t0, t0 - 6L * 60000L));

            eq("popFor today", true, WeatherParse.popFor(w, "2026-10-20") == w.pop);
            eq("popFor tomorrow", null, WeatherParse.popFor(w, "2026-10-21"));
            eq("popFor null today", null, WeatherParse.popFor(w, null));
        } catch (Exception e) {
            fails++;
            System.err.println("FAIL WeatherParseTest exception " + e);
            e.printStackTrace();
        }
        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
