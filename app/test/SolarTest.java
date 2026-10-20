package tv.orchard.home;

public final class SolarTest {
    static int fails;

    static void near(String name, double want, double got, double tol) {
        if (!(Math.abs(want - got) <= tol)) { fails++; System.err.println("FAIL " + name + ": want " + want + " +-" + tol + " got " + got); }
    }

    static void eq(String name, int want, int got) {
        if (want != got) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }

    static double hm(int h, int m) { return h * 60 + m; }

    static void times(String name, double lat, double lon, int y, int m, int d, double tz, double rise, double set) {
        Solar.Times t = Solar.times(lat, lon, y, m, d, tz);
        near(name + " rise", rise, t.rise, 1.0);
        near(name + " set", set, t.set, 1.0);
        eq(name + " polar", Solar.POLAR_NONE, t.polar);
    }

    public static void main(String[] args) {
        double lat = 12.9716, lon = 77.5946, tz = 5.5;
        Solar.Times b = Solar.times(lat, lon, 2026, 10, 20, tz);
        near("blr rise", 6 * 60 + 10 + 44 / 60.0, b.rise, 0.5);
        near("blr set", 17 * 60 + 57 + 56 / 60.0, b.set, 0.5);
        near("blr noon", 12 * 60 + 4 + 25 / 60.0, b.noon, 0.5);
        near("blr elev 12:04", 66.67, Solar.position(lat, lon, 2026, 10, 20, tz, hm(12, 4)).elevation, 0.05);
        Solar.Pos p = Solar.position(lat, lon, 2026, 10, 20, tz, hm(12, 3));
        near("blr elev 12:03", 66.67, p.elevation, 0.05);
        near("blr az 12:03", 179.1, p.azimuth, 0.3);
        p = Solar.position(lat, lon, 2026, 10, 20, tz, hm(6, 11));
        near("blr elev 06:11", -0.27, p.elevation, 0.05);
        near("blr az 06:11", 100.4, p.azimuth, 0.3);
        p = Solar.position(lat, lon, 2026, 10, 20, tz, hm(17, 58));
        near("blr elev 17:58", -0.40, p.elevation, 0.05);
        near("blr az 17:58", 259.5, p.azimuth, 0.3);
        near("blr elev 23:59", -87.2, Solar.position(lat, lon, 2026, 10, 20, tz, hm(23, 59)).elevation, 0.3);

        double dl = 28.6139, dn = 77.2090;
        near("delhi elev 12:06", 51.0, Solar.position(dl, dn, 2026, 10, 20, tz, hm(12, 6)).elevation, 0.1);
        near("delhi noon", hm(12, 6), Solar.times(dl, dn, 2026, 10, 20, tz).noon, 1.0);

        times("delhi jun", dl, dn, 2026, 6, 21, 5.5, hm(5, 24), hm(19, 22));
        times("london jun", 51.5072, -0.1276, 2026, 6, 21, 1, hm(4, 43), hm(21, 21));
        times("london dec", 51.5072, -0.1276, 2026, 12, 21, 0, hm(8, 4), hm(15, 53));
        times("nyc jun", 40.7128, -74.0060, 2026, 6, 21, -4, hm(5, 25), hm(20, 31));
        times("delhi oct19", dl, dn, 2026, 10, 19, 5.5, hm(6, 24), hm(17, 48));
        times("equator mar", 0, 0, 2026, 3, 20, 0, hm(6, 4), hm(18, 11));

        Solar.Times tj = Solar.times(69.6492, 18.9553, 2026, 6, 21, 2);
        eq("tromso jun polar", Solar.POLAR_DAY, tj.polar);
        if (!Double.isNaN(tj.rise)) { fails++; System.err.println("FAIL tromso jun rise not NaN: " + tj.rise); }
        Solar.Times td = Solar.times(69.6492, 18.9553, 2026, 12, 21, 1);
        eq("tromso dec polar", Solar.POLAR_NIGHT, td.polar);
        if (!Double.isNaN(td.set)) { fails++; System.err.println("FAIL tromso dec set not NaN: " + td.set); }

        near("london dec elevMax", 15.11, Solar.elevationMax(51.5072, -0.1276, 2026, 12, 21, 0), 0.1);

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
        System.out.println("SolarTest OK");
    }
}
