package tv.orchard.home;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CityCommandTest {
    static int fails;

    static void eq(String name, String want, String got) {
        if (want == null ? got != null : !want.equals(got)) { fails++; System.err.println("FAIL " + name + ": want [" + want + "] got [" + got + "]"); }
    }

    static void eq(String name, int want, int got) { eq(name, String.valueOf(want), String.valueOf(got)); }

    static String id(CityCommand.City c) { return c == null ? null : c.id; }

    public static void main(String[] args) throws Exception {
        String csv = new String(Files.readAllBytes(Paths.get("res/raw/cities.csv")), "UTF-8");
        List<CityCommand.City> all = CityCommand.parseCsv(csv);
        eq("12 cities", 12, all.size());
        CityCommand.City b = all.get(0);
        eq("first id", "bengaluru", b.id);
        eq("first lat", "12.9716", String.valueOf(b.lat));
        eq("first lon", "77.5946", String.valueOf(b.lon));
        eq("first tz", "Asia/Kolkata", b.tz);
        eq("first cc", "IN", b.cc);
        eq("malformed rows skipped", 1, CityCommand.parseCsv("id,name\nx,X,IN,1,2,UTC\nbad,Bad,IN,abc,2,UTC\nshort,S\n\nbig,Big,IN,95,0,UTC\n").size());

        eq("find New Delhi", "newdelhi", id(CityCommand.find(all, "New Delhi")));
        eq("find newdelhi", "newdelhi", id(CityCommand.find(all, "newdelhi")));
        eq("find new-delhi", "newdelhi", id(CityCommand.find(all, "new-delhi")));
        eq("find NEW DELHI", "newdelhi", id(CityCommand.find(all, "NEW DELHI")));
        eq("find Bengaluru", "bengaluru", id(CityCommand.find(all, "Bengaluru")));
        eq("find bengaluru", "bengaluru", id(CityCommand.find(all, "bengaluru")));
        eq("find Atlantis", null, id(CityCommand.find(all, "Atlantis")));
        eq("find empty", null, id(CityCommand.find(all, "")));

        Map<String, String> m = new LinkedHashMap<String, String>();
        eq("by id applied", 1, CityCommand.apply(m, all, "New Delhi", null, null, null));
        eq("city_id set", "newdelhi", m.get("city_id"));
        eq("by id again", 0, CityCommand.apply(m, all, "newdelhi", null, null, null));
        eq("unknown rejected", -1, CityCommand.apply(m, all, "Atlantis", null, null, null));
        eq("unknown leaves map", "{city_id=newdelhi}", m.toString());

        eq("custom applied", 1, CityCommand.apply(m, all, null, "51.5072", "-0.1276", "London"));
        eq("custom removes id", null, m.get("city_id"));
        eq("custom lat", "51.5072", m.get("city_lat"));
        eq("custom lon", "-0.1276", m.get("city_lon"));
        eq("custom name", "London", m.get("city_name"));
        eq("custom again", 0, CityCommand.apply(m, all, null, "51.5072", "-0.1276", "London"));
        String snap = m.toString();
        eq("lat 91", -1, CityCommand.apply(m, all, null, "91", "0", "X"));
        eq("lon -181", -1, CityCommand.apply(m, all, null, "0", "-181", "X"));
        eq("NaN", -1, CityCommand.apply(m, all, null, "NaN", "0", "X"));
        eq("Infinity", -1, CityCommand.apply(m, all, null, "Infinity", "0", "X"));
        eq("abc", -1, CityCommand.apply(m, all, null, "abc", "0", "X"));
        eq("lat only", -1, CityCommand.apply(m, all, null, "10", null, "X"));
        eq("id and lat", -1, CityCommand.apply(m, all, "mumbai", "10", "10", "X"));
        eq("neither", -1, CityCommand.apply(m, all, null, null, null, null));
        eq("rejections leave map", snap, m.toString());
        eq("by id after custom clears custom", 1, CityCommand.apply(m, all, "tokyo", null, null, null));
        eq("only city_id remains", "{city_id=tokyo}", m.toString());

        eq("cleanLabel injection", "London1", CityCommand.cleanLabel("Lon;don=1"));
        eq("cleanLabel spaces", "A b", CityCommand.cleanLabel("  A   b  "));
        eq("cleanLabel keeps . - '", "St. John-o'x", CityCommand.cleanLabel("St. John-o'x"));
        eq("cleanLabel empty", null, CityCommand.cleanLabel(";=;"));
        eq("cleanLabel null", null, CityCommand.cleanLabel(null));
        eq("cleanLabel cut 24", 24, CityCommand.cleanLabel("abcdefghijklmnopqrstuvwxyz0123").length());
        eq("cleanLabel cut trims", "abcdefghijklmnopqrstuvwx", CityCommand.cleanLabel("abcdefghijklmnopqrstuvwx yz"));
        eq("cleanLabel cut trims space", "abcdefghijklmnopqrstuvw", CityCommand.cleanLabel("abcdefghijklmnopqrstuvw xyz"));

        Map<String, String> e = new LinkedHashMap<String, String>();
        eq("empty label default", 1, CityCommand.apply(e, all, null, "51.5072", "-0.1276", ";"));
        eq("default label name", "51.51,-0.13", e.get("city_name"));

        CityCommand.Resolved r0 = CityCommand.resolve(new LinkedHashMap<String, String>(), all);
        eq("resolve empty id", "bengaluru", r0.id);
        eq("resolve empty tz", "Asia/Kolkata", r0.tz);
        eq("resolve empty key", "bengaluru", r0.key());
        CityCommand.Resolved rc = CityCommand.resolve(e, all);
        eq("resolve custom lat", "51.5072", String.valueOf(rc.lat));
        eq("resolve custom tz", null, rc.tz);
        eq("resolve custom id", null, rc.id);
        eq("resolve custom cc", null, rc.cc);
        eq("resolve custom key", "51.51,-0.13", rc.key());
        Map<String, String> bad = new LinkedHashMap<String, String>();
        bad.put("city_lat", "x");
        bad.put("city_lon", "1");
        bad.put("city_id", "mumbai");
        eq("resolve bad custom falls to id", "mumbai", CityCommand.resolve(bad, all).id);
        bad.put("city_id", "nowhere");
        eq("resolve unknown id", "bengaluru", CityCommand.resolve(bad, all).id);
        Map<String, String> tz = new LinkedHashMap<String, String>(e);
        tz.put("city_tz", "Europe/London");
        eq("resolve custom tz kept", "Europe/London", CityCommand.resolve(tz, all).tz);

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
        System.out.println("CityCommandTest: all passed");
    }
}
