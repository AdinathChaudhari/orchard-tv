package tv.orchard.home;

/** Solar + SkyPhase + ThemePhase together over one real day (Bengaluru 2026-10-20), PLAN 9.2 table. */
public final class SkyDayTest {
    static int fails;
    static final double LAT = 12.9716, LON = 77.5946, TZ = 5.5;
    static final int Y = 2026, MO = 10, D = 20;

    static void ok(String name, boolean cond, String detail) {
        if (!cond) { fails++; System.err.println("FAIL " + name + ": " + detail); }
    }

    static int hm(int h, int m) { return h * 60 + m; }

    static String hhmm(int m) { return String.format("%02d:%02d", m / 60, m % 60); }

    static SkyPhase.Pair[] pairs = new SkyPhase.Pair[1440];
    static Solar.Pos[] pos = new Solar.Pos[1440];
    static Solar.Times times;
    static double emax;

    static boolean hold(int m) { return pairs[m].a == pairs[m].b; }

    /** hold of keyframe kf at minute m, tolerant of +-1 minute at the edge. */
    static void holdAt(String name, int h, int mi, int kf) {
        int m = hm(h, mi);
        ok(name + " hold " + hhmm(m), hold(m) && pairs[m].a == kf,
                "a=" + SkyPhase.NAMES[pairs[m].a] + " b=" + SkyPhase.NAMES[pairs[m].b] + " f=" + pairs[m].f + " e=" + pos[m].elevation);
    }

    /** table edge: some minute within +-1 of it holds kf. */
    static void holdEdge(String name, int h, int mi, int kf) {
        int m = hm(h, mi);
        boolean any = false;
        for (int d = -1; d <= 1; d++) any |= hold(m + d) && pairs[m + d].a == kf;
        ok(name + " " + hhmm(m) + " (+-1)", any, "a=" + SkyPhase.NAMES[pairs[m].a] + " b=" + SkyPhase.NAMES[pairs[m].b] + " f=" + pairs[m].f);
    }

    static void holdRange(String name, int from, int to, int kf) {
        for (int m = from; m <= to; m++) {
            ok(name + " " + hhmm(m), hold(m) && pairs[m].a == kf,
                    "a=" + SkyPhase.NAMES[pairs[m].a] + " b=" + SkyPhase.NAMES[pairs[m].b] + " f=" + pairs[m].f + " e=" + pos[m].elevation);
        }
    }

    public static void main(String[] args) {
        times = Solar.times(LAT, LON, Y, MO, D, TZ);
        emax = Solar.elevationMax(LAT, LON, Y, MO, D, TZ);
        for (int m = 0; m < 1440; m++) {
            pos[m] = Solar.position(LAT, LON, Y, MO, D, TZ, m);
            pairs[m] = SkyPhase.pair(pos[m].elevation, m < times.noon, emax);
        }
        System.out.println("rise " + hhmm((int) Math.round(times.rise)) + " set " + hhmm((int) Math.round(times.set))
                + " noon " + hhmm((int) Math.round(times.noon)) + " emax " + String.format("%.2f", emax));

        // PLAN 9.2 table, +-1 minute at each edge
        holdRange("night early", 0, hm(5, 6), SkyPhase.NIGHT);
        holdRange("night late", hm(19, 6), 1439, SkyPhase.NIGHT);
        holdAt("dawn", 5, 46, SkyPhase.DAWN);
        holdAt("sunrise", 6, 5, SkyPhase.SUNRISE);
        holdAt("morning", 6, 30, SkyPhase.MORNING);
        holdAt("noon", 12, 0, SkyPhase.NOON);
        holdAt("afternoon", 16, 40, SkyPhase.AFTERNOON);
        holdAt("golden", 17, 45, SkyPhase.GOLDEN);
        holdAt("horizon", 18, 4, SkyPhase.HORIZON);
        holdAt("dusk", 18, 40, SkyPhase.DUSK);
        // table edges, +-1 minute: the hold windows listed in the PLAN
        holdEdge("dawn edge", 5, 45, SkyPhase.DAWN);
        holdEdge("dawn edge", 5, 48, SkyPhase.DAWN);
        holdEdge("sunrise edge", 6, 4, SkyPhase.SUNRISE);
        holdEdge("sunrise edge", 6, 7, SkyPhase.SUNRISE);
        holdEdge("morning edge", 6, 12, SkyPhase.MORNING);
        holdEdge("morning edge", 6, 47, SkyPhase.MORNING);
        holdEdge("noon edge", 9, 29, SkyPhase.NOON);
        holdEdge("noon edge", 14, 39, SkyPhase.NOON);
        holdEdge("afternoon edge", 16, 22, SkyPhase.AFTERNOON);
        holdEdge("afternoon edge", 16, 56, SkyPhase.AFTERNOON);
        holdEdge("golden edge", 17, 31, SkyPhase.GOLDEN);
        holdEdge("golden edge", 17, 56, SkyPhase.GOLDEN);
        holdEdge("horizon edge", 18, 2, SkyPhase.HORIZON);
        holdEdge("horizon edge", 18, 6, SkyPhase.HORIZON);
        holdEdge("dusk edge", 18, 36, SkyPhase.DUSK);
        holdEdge("dusk edge", 18, 45, SkyPhase.DUSK);

        // hand-overs
        int swaps = 0;
        StringBuilder list = new StringBuilder();
        for (int m = 1; m < 1440; m++) {
            SkyPhase.Pair prev = pairs[m - 1], now = pairs[m];
            if (SkyPhase.shouldSwap(prev, now)) {
                swaps++;
                list.append(hhmm(m)).append(' ').append(SkyPhase.NAMES[prev.a]).append('>').append(SkyPhase.NAMES[prev.b])
                        .append(" -> ").append(SkyPhase.NAMES[now.a]).append('>').append(SkyPhase.NAMES[now.b]).append("; ");
                ok("swap at " + hhmm(m) + " follows a finished window", now.a == prev.b && now.a != prev.a && prev.f >= 0.9f,
                        "prev " + prev.a + ">" + prev.b + " f=" + prev.f + " now " + now.a + ">" + now.b);
            }
        }
        System.out.println("hand-over swaps: " + swaps + " : " + list);
        ok("swap count 6..10", swaps >= 6 && swaps <= 10, "got " + swaps);

        // theme flip consistency
        float p0609 = ThemePhase.at(hm(6, 9), times).p, p0610 = ThemePhase.at(hm(6, 10), times).p;
        float p1759 = ThemePhase.at(hm(17, 59), times).p, p1800 = ThemePhase.at(hm(18, 0), times).p;
        ok("light 06:09 false", !ThemePhase.light(p0609), "p=" + p0609);
        ok("light 06:10 true", ThemePhase.light(p0610), "p=" + p0610);
        ok("light 17:59 true", ThemePhase.light(p1759), "p=" + p1759);
        ok("light 18:00 false", !ThemePhase.light(p1800), "p=" + p1800);
        SkyPhase.Pair s0610 = pairs[hm(6, 10)];
        ok("06:10 sky inside or past sunrise>morning", s0610.a == SkyPhase.SUNRISE || s0610.a == SkyPhase.MORNING || s0610.a == SkyPhase.NOON,
                SkyPhase.NAMES[s0610.a] + ">" + SkyPhase.NAMES[s0610.b] + " f=" + s0610.f);
        SkyPhase.Pair s1800 = pairs[hm(18, 0)];
        ok("18:00 sky inside golden>horizon or past", s1800.a == SkyPhase.GOLDEN || s1800.a == SkyPhase.HORIZON
                || s1800.a == SkyPhase.SUNSET || s1800.a == SkyPhase.DUSK || s1800.a == SkyPhase.NIGHT,
                SkyPhase.NAMES[s1800.a] + ">" + SkyPhase.NAMES[s1800.b] + " f=" + s1800.f);

        // frame sanity
        int noonMin = hm(12, 0), lateMin = hm(23, 0);
        SkyPhase.Frame fn = SkyPhase.frame(pos[noonMin].elevation, pos[noonMin].azimuth, noonMin < times.noon, emax, noonMin,
                times.rise, times.set, ThemePhase.at(noonMin, times).p, SkyPhase.WX_CLEAR);
        ok("12:00 sunA ~0.60", Math.abs(fn.sunA - 0.60f) < 0.02f, "sunA=" + fn.sunA);
        ok("12:00 moonA 0", fn.moonA == 0f, "moonA=" + fn.moonA);
        SkyPhase.Frame fl = SkyPhase.frame(pos[lateMin].elevation, pos[lateMin].azimuth, lateMin < times.noon, emax, lateMin,
                times.rise, times.set, ThemePhase.at(lateMin, times).p, SkyPhase.WX_CLEAR);
        ok("23:00 moonA ~0.90", Math.abs(fl.moonA - 0.90f) < 0.02f, "moonA=" + fl.moonA);
        ok("23:00 sunA 0", fl.sunA == 0f, "sunA=" + fl.sunA);

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
        System.out.println("SkyDayTest ok");
    }
}
