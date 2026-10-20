package tv.orchard.home;

public final class RainTextTest {
    static int fails;

    static void eq(String name, String want, String got) {
        if (want == null ? got != null : !want.equals(got)) { fails++; System.err.println("FAIL " + name + ": want [" + want + "] got [" + got + "]"); }
    }

    static void eqi(String name, int want, int got) { eq(name, String.valueOf(want), String.valueOf(got)); }

    static int[] pop(int fill) { int[] p = new int[24]; java.util.Arrays.fill(p, fill); return p; }

    public static void main(String[] args) {
        eq("step 0", "0.0", String.valueOf(RainText.step(0f, 44)));
        eq("step holds within a step", String.valueOf(RainText.step(0.0001f, 44)), String.valueOf(RainText.step(0.02f, 44)));
        eq("step moves next step", "true", String.valueOf(RainText.step(0.03f, 44) > RainText.step(0.02f, 44)));
        eq("step stays under 1", "true", String.valueOf(RainText.step(0.9999f, 44) < 1f));
        eq("step 1 step is linear", "0.5", String.valueOf(RainText.step(0.5f, 1)));
        int[] p = pop(10);
        eqi("none", -1, RainText.likelyHour(p, 10));
        p[16] = 70;
        eqi("later hour", 16, RainText.likelyHour(p, 10));
        p = pop(0); p[15] = 40;
        eqi("40 qualifies", 15, RainText.likelyHour(p, 10));
        p[15] = 39;
        eqi("39 does not", -1, RainText.likelyHour(p, 10));
        p = pop(0); p[14] = 70;
        eqi("current hour skipped", -1, RainText.likelyHour(p, 14));
        p = pop(0); for (int h = 16; h <= 19; h++) p[h] = 70;
        eqi("nothing after 19:00 when current is 20", -1, RainText.likelyHour(p, 20));
        p = pop(-1); p[12] = -1; p[13] = 80;
        eqi("missing skipped", 13, RainText.likelyHour(p, 10));
        eqi("null pop", -1, RainText.likelyHour(null, 10));
        eqi("hour 23 current", -1, RainText.likelyHour(pop(90), 23));

        eq("hhmm rise", "06:11", RainText.hhmm(370.55));
        eq("hhmm set", "17:58", RainText.hhmm(1078.12));
        eq("hhmm wrap", "00:00", RainText.hhmm(1440));
        eq("hhmm negative", "23:59", RainText.hhmm(-1));

        eq("temp c", "24\u00b0", RainText.temp(24.3, false));
        eq("temp f", "76\u00b0", RainText.temp(24.3, true));
        eq("temp negative", "-3\u00b0", RainText.temp(-3.2, false));
        eq("highLow", "H 28\u00b0  L 20\u00b0", RainText.highLow(28, 20, false));

        int[] rp = pop(0); rp[14] = 70;
        eq("rainNow dry", "", RainText.rainNow(false, rp, 14));
        eq("rainNow pct", "\u00b7 Rain 70%", RainText.rainNow(true, rp, 14));
        rp[14] = -1;
        eq("rainNow no value", "\u00b7 Rain", RainText.rainNow(true, rp, 14));
        eq("rainNow null pop", "\u00b7 Rain", RainText.rainNow(true, null, 14));

        double rise = 370.55, set = 1078.12;
        eq("caption rain", "Rain likely from 16:00", RainText.caption("Bengaluru", 16, rise, set, 600));
        eq("caption sunset", "Bengaluru \u00b7 Sunset 17:58", RainText.caption("Bengaluru", -1, rise, set, 600));
        eq("caption sunrise at night", "Bengaluru \u00b7 Sunrise 06:11", RainText.caption("Bengaluru", -1, rise, set, 1302));
        eq("caption sunrise pre-dawn", "Bengaluru \u00b7 Sunrise 06:11", RainText.caption("Bengaluru", -1, rise, set, 100));
        eq("caption polar", "Tromso", RainText.caption("Tromso", -1, Double.NaN, Double.NaN, 600));
        String[] caps = {
            RainText.caption("Bengaluru", 16, rise, set, 600), RainText.caption("Bengaluru", -1, rise, set, 600),
            RainText.caption("Bengaluru", -1, rise, set, 1302), RainText.caption("Tromso", -1, Double.NaN, Double.NaN, 0)};
        for (String c : caps) if (c.contains("now")) { fails++; System.err.println("FAIL caption contains 'now': " + c); }

        eq("useF auto US", "true", String.valueOf(RainText.useF("auto", "US")));
        eq("useF auto IN", "false", String.valueOf(RainText.useF("auto", "IN")));
        eq("useF f IN", "true", String.valueOf(RainText.useF("f", "IN")));
        eq("useF c US", "false", String.valueOf(RainText.useF("c", "US")));
        eq("useF auto null", "false", String.valueOf(RainText.useF("auto", null)));
        eq("useF null units us", "true", String.valueOf(RainText.useF(null, "us")));

        eq("date 2026-10-20", "Tue 20 Oct", RainText.dateLabel(2026, 10, 20));
        eq("date 2026-01-01", "Thu 1 Jan", RainText.dateLabel(2026, 1, 1));
        eq("date leap", "Tue 29 Feb", RainText.dateLabel(2028, 2, 29));

        String d = "Tue 20 Oct";
        eq("dream off", d, RainText.dreamLine(d, false, "24\u00b0", true, 70, 16, "Bengaluru"));
        eq("dream raining", "Tue 20 Oct \u00b7 24\u00b0 \u00b7 Rain 70%", RainText.dreamLine(d, true, "24\u00b0", true, 70, -1, "Bengaluru"));
        eq("dream raining no pct", "Tue 20 Oct \u00b7 24\u00b0 \u00b7 Rain", RainText.dreamLine(d, true, "24\u00b0", true, -1, -1, "Bengaluru"));
        eq("dream coming", "Tue 20 Oct \u00b7 24\u00b0 \u00b7 Rain from 16:00", RainText.dreamLine(d, true, "24\u00b0", false, -1, 16, "Bengaluru"));
        eq("dream dry", "Tue 20 Oct \u00b7 24\u00b0 Bengaluru", RainText.dreamLine(d, true, "24\u00b0", false, -1, -1, "Bengaluru"));
        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
