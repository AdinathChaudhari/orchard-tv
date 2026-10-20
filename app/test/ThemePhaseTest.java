package tv.orchard.home;

public final class ThemePhaseTest {
    static int fails;

    static void check(String name, boolean ok, String got) {
        if (!ok) { fails++; System.err.println("FAIL " + name + ": got " + got); }
    }

    static void name(String n, String want, ThemePhase.Phase ph) {
        if (!want.equals(ph.name)) { fails++; System.err.println("FAIL " + n + ": want " + want + " got " + ph.name); }
    }

    static void val(String n, float want, float got) {
        if (Math.abs(want - got) > 1e-6f) { fails++; System.err.println("FAIL " + n + ": want " + want + " got " + got); }
    }

    static ThemePhase.Phase at(int h, int m, Solar.Times s) { return ThemePhase.at(h * 60 + m, s); }

    public static void main(String[] args) {
        Solar.Times s = Solar.times(12.9716, 77.5946, 2026, 10, 20, 5.5);

        ThemePhase.Phase ph = at(5, 0, s);
        name("05:00", "night", ph); val("05:00 p", 0f, ph.p);
        ph = at(6, 0, s);
        name("06:00", "dawn", ph); check("06:00 p in (0,1)", ph.p > 0f && ph.p < 1f, "" + ph.p);
        check("06:09 dark", !ThemePhase.light(at(6, 9, s).p), "" + at(6, 9, s).p);
        check("06:10 light", ThemePhase.light(at(6, 10, s).p), "" + at(6, 10, s).p);
        ph = at(12, 0, s);
        name("12:00", "day", ph); val("12:00 p", 1f, ph.p); val("12:00 g", 0f, ph.g);
        ph = at(17, 20, s);
        name("17:20", "golden", ph); check("17:20 g>0", ph.g > 0f, "" + ph.g);
        val("17:30 g", 1f, at(17, 30, s).g);
        ph = at(17, 50, s);
        name("17:50", "dusk", ph); check("17:50 g>0 p<1", ph.g > 0f && ph.p < 1f, ph.g + "/" + ph.p);
        check("17:59 light", ThemePhase.light(at(17, 59, s).p), "" + at(17, 59, s).p);
        check("18:00 dark", !ThemePhase.light(at(18, 0, s).p), "" + at(18, 0, s).p);
        val("18:10 g", 0f, at(18, 10, s).g);
        ph = at(18, 15, s);
        name("18:15", "night", ph); val("18:15 p", 0f, ph.p);

        float[] h = ThemePhase.halo(at(12, 0, s), true, 0.30f, 0.30f, 0.5f);
        val("noon ts", 0f, h[0]); val("noon tn", 0f, h[1]);
        ph = at(18, 0, s);
        h = ThemePhase.halo(ph, ThemePhase.light(ph.p), 0.30f, 0.30f, 0.5f);
        check("18:00 ts in (0,.3]", h[0] > 0f && h[0] <= 0.30f, "" + h[0]);
        for (int t = 360; t < 400; t++) {
            ThemePhase.Phase q = ThemePhase.at(t, s);
            float[] hh = ThemePhase.halo(q, ThemePhase.light(q.p), 0.30f, 0.30f, 0.5f);
            if (hh[0] >= 0.30f * 0.5f) val("tn full at t=" + t, 1f, hh[1]);
            check("ts <= cap t=" + t, hh[0] <= 0.30f + 1e-6f, "" + hh[0]);
        }
        h = ThemePhase.halo(ph, false, 0.30f, 0f, 0.5f);
        check("light cap 0 -> tn 0", ThemePhase.halo(at(6, 10, s), true, 0.3f, 0f, 0.5f)[1] == 0f, "");

        Solar.Times pd = Solar.times(69.6492, 18.9553, 2026, 6, 21, 2);
        ph = ThemePhase.at(0, pd);
        name("polar day", "day", ph); val("polar day p", 1f, ph.p);
        Solar.Times pn = Solar.times(69.6492, 18.9553, 2026, 12, 21, 1);
        ph = ThemePhase.at(720, pn);
        name("polar night", "night", ph); val("polar night p", 0f, ph.p);

        check("flip 0.42 dark", !ThemePhase.light(0.42f), "");
        check("flip 0.44 light", ThemePhase.light(0.44f), "");
        val("smooth clamp lo", 0f, ThemePhase.smooth(-1f));
        val("smooth clamp hi", 1f, ThemePhase.smooth(2f));
        val("smooth mid", 0.5f, ThemePhase.smooth(0.5f));

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
        System.out.println("ThemePhaseTest OK");
    }
}
