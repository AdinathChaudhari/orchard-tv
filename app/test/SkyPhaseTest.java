package tv.orchard.home;

public final class SkyPhaseTest {
    static int fails;

    static void ok(String name, boolean cond) {
        if (!cond) { fails++; System.err.println("FAIL " + name); }
    }

    static void near(String name, double want, double got, double tol) {
        if (Math.abs(want - got) > tol) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }

    static void pr(String name, SkyPhase.Pair p, int a, int b, double f) {
        ok(name + " keys " + p.a + ">" + p.b + " want " + a + ">" + b, p.a == a && p.b == b);
        near(name + " f", f, p.f, 1e-4);
    }

    static SkyPhase.Frame fr(double e, boolean rising, float p, int cond) {
        return SkyPhase.frame(e, 180, rising, 66, 600, 370, 1077, p, cond);
    }

    public static void main(String[] args) {
        final int N = SkyPhase.NIGHT, D = SkyPhase.DAWN, S = SkyPhase.SUNRISE, M = SkyPhase.MORNING, NO = SkyPhase.NOON,
                A = SkyPhase.AFTERNOON, G = SkyPhase.GOLDEN, HZ = SkyPhase.HORIZON, SS = SkyPhase.SUNSET, DK = SkyPhase.DUSK;
        pr("rise -20", SkyPhase.pair(-20, true, 66), N, N, 0);
        pr("rise -11.5", SkyPhase.pair(-11.5, true, 66), N, D, 0.5);
        pr("rise -7", SkyPhase.pair(-7, true, 66), D, D, 0);
        pr("rise -4.405", SkyPhase.pair(-4.405, true, 66), D, S, 0.5);
        pr("rise -0.67", SkyPhase.pair(-0.67, true, 66), S, M, 0.5);
        pr("rise 26.5", SkyPhase.pair(26.5, true, 66), M, NO, 0.5);
        pr("rise 60", SkyPhase.pair(60, true, 66), NO, NO, 0);
        pr("set 60", SkyPhase.pair(60, false, 66), NO, NO, 0);
        pr("set 33.5", SkyPhase.pair(33.5, false, 66), NO, A, 0.5);
        pr("set 10", SkyPhase.pair(10, false, 66), A, G, 0.5);
        pr("set -0.77", SkyPhase.pair(-0.77, false, 66), G, HZ, 0.5);
        pr("set -3.665", SkyPhase.pair(-3.665, false, 66), HZ, SS, 0.5);
        pr("set -7.5", SkyPhase.pair(-7.5, false, 66), SS, DK, 0.5);
        pr("set -14.5", SkyPhase.pair(-14.5, false, 66), DK, N, 0.5);
        pr("set -30", SkyPhase.pair(-30, false, 66), N, N, 0);

        // solar-noon continuity, low-sun scaling
        pr("noon rising emax 66", SkyPhase.pair(66, true, 66), NO, NO, 0);
        pr("noon setting emax 66", SkyPhase.pair(66, false, 66), NO, NO, 0);
        pr("noon rising emax 14", SkyPhase.pair(14, true, 14), NO, NO, 0);
        pr("noon setting emax 14", SkyPhase.pair(14, false, 14), NO, NO, 0);
        pr("k floor: 1.2 is the morning bound", SkyPhase.pair(1.2, true, 2), M, M, 0);
        pr("k floor: midpoint 3.975", SkyPhase.pair(3.975, true, 2), M, NO, 0.5);
        pr("k floor: 6.75 reaches noon", SkyPhase.pair(6.75, true, 2), NO, NO, 0);
        pr("k 0.28 scaled midpoint 7.42", SkyPhase.pair(7.42, true, 14), M, NO, 0.5);
        pr("k unscaled bound -1.37 holds", SkyPhase.pair(-1.37, true, 2), S, S, 0);

        // monotone f across every window, both directions
        for (boolean rising : new boolean[] {true, false}) {
            int prevA = -1, prevB = -1; float prevF = 0;
            int start = rising ? -2000 : 6600, step = rising ? 1 : -1;
            for (int i = 0, x = start; i <= 8600; i++, x += step) {
                SkyPhase.Pair q = SkyPhase.pair(x / 100.0, rising, 66);
                if (q.a == prevA && q.b == prevB && q.f < prevF - 1e-6) { fails++; System.err.println("FAIL monotone " + rising + " at " + x / 100.0); break; }
                prevA = q.a; prevB = q.b; prevF = q.f;
            }
        }

        // swap count
        int swaps = 0; SkyPhase.Pair prev = null; boolean chain = true;
        for (int i = 0; i <= 8600 * 2 + 1; i++) {
            boolean rising = i <= 8600;
            int x = rising ? -2000 + i : 6600 - (i - 8601);
            SkyPhase.Pair q = SkyPhase.pair(x / 100.0, rising, 66);
            if (SkyPhase.shouldSwap(prev, q)) { swaps++; if (q.a != prev.b) chain = false; }
            prev = q;
        }
        ok("swap fires 10 times, got " + swaps, swaps == 10);
        ok("swap next.a == prev.b", chain);
        ok("time jump never swaps", !SkyPhase.shouldSwap(new SkyPhase.Pair(N, D, 0.5f), new SkyPhase.Pair(D, S, 0.1f)));
        ok("same pair never swaps", !SkyPhase.shouldSwap(new SkyPhase.Pair(N, D, 0.95f), new SkyPhase.Pair(N, D, 0.95f)));
        ok("null prev never swaps", !SkyPhase.shouldSwap(null, new SkyPhase.Pair(N, D, 0.1f)));
        ok("swap at f 0.9", SkyPhase.shouldSwap(new SkyPhase.Pair(N, D, 0.9f), new SkyPhase.Pair(D, D, 0f)));

        // tint
        SkyPhase.Frame c = fr(30, true, 1f, SkyPhase.WX_CLOUD);
        near("cloud oc", 1, c.oc, 1e-6); near("cloud glow", 0.6 * 0.35, c.sunA, 1e-4);
        SkyPhase.Frame h = fr(30, true, 1f, SkyPhase.WX_HAZE);
        near("haze oc", 0.30, h.oc, 1e-6); near("haze glow", 0.6 * 0.45, h.sunA, 1e-4);
        near("haze fogDay p1", 0.42, h.fogDay, 1e-4); near("haze fogNight p1", 0, h.fogNight, 1e-6);
        SkyPhase.Frame r = fr(30, true, 1f, SkyPhase.WX_RAIN);
        near("rain rdim", 0.01, r.rdim, 1e-6); near("rain glow", 0, r.sunA, 1e-6); ok("rainOn", r.rainOn);
        near("rain rainDay p1", 0.20, r.rainDay, 1e-4); near("lightFactor p1", 1, r.rainLightFactor, 1e-6);
        SkyPhase.Frame cl = fr(30, true, 1f, SkyPhase.WX_CLEAR), nn = fr(30, true, 1f, SkyPhase.WX_NONE);
        ok("none == clear", cl.oc == nn.oc && cl.rdim == nn.rdim && cl.sunA == nn.sunA && cl.scrimRgb == nn.scrimRgb && !nn.rainOn);
        near("clear glow", 0.6, cl.sunA, 1e-4);
        for (int cond : new int[] {SkyPhase.WX_HAZE, SkyPhase.WX_RAIN}) {
            SkyPhase.Frame z = fr(30, true, 0.43f, cond);
            near("flip fogDay", 0, z.fogDay, 1e-6); near("flip fogNight", 0, z.fogNight, 1e-6);
            near("flip rainDay", 0, z.rainDay, 1e-6); near("flip lightFactor", 0, z.rainLightFactor, 1e-6);
        }
        near("haze p0 fogNight", 0.14, fr(30, true, 0f, SkyPhase.WX_HAZE).fogNight, 1e-4);
        near("haze p0 fogDay", 0, fr(30, true, 0f, SkyPhase.WX_HAZE).fogDay, 1e-6);
        near("clear no fog", 0, fr(30, true, 1f, SkyPhase.WX_CLEAR).fogDay, 1e-6);

        // light-sky streak alpha (peak contrast 0.20 over the slate tile, clamp .25 .. .55, times the day factor)
        near("rainAlphaL noon 0.85", 0.20 / (0.85 - 0.305), SkyPhase.rainAlphaLight(0.85, 1f), 1e-4);
        near("rainAlphaL golden 0.70", 0.20 / (0.70 - 0.305), SkyPhase.rainAlphaLight(0.70, 1f), 1e-4);
        near("rainAlphaL mid 0.60 cap", 0.55, SkyPhase.rainAlphaLight(0.60, 1f), 1e-6);
        near("rainAlphaL near tile lum cap", 0.55, SkyPhase.rainAlphaLight(0.31, 1f), 1e-6);
        near("rainAlphaL white sky", 0.20 / 0.695, SkyPhase.rainAlphaLight(1.0, 1f), 1e-6);   // d max .695: the .25 floor is never reached
        near("rainAlphaL factor 0", 0, SkyPhase.rainAlphaLight(0.70, 0f), 1e-6);
        near("rainAlphaL factor .5", 0.5 * 0.20 / (0.70 - 0.305), SkyPhase.rainAlphaLight(0.70, 0.5f), 1e-4);
        near("frame dark rainAlpha", 0.25, fr(-30, false, 0f, SkyPhase.WX_RAIN).rainAlpha, 1e-6);
        near("frame flip rainAlpha 0", 0, fr(30, true, 0.43f, SkyPhase.WX_RAIN).rainAlpha, 1e-6);
        near("frame light rainAlpha", SkyPhase.rainAlphaLight(SkyPhase.lum(fr(60, true, 1f, SkyPhase.WX_RAIN).scrimRgb), 1f),
                fr(60, true, 1f, SkyPhase.WX_RAIN).rainAlpha, 1e-6);

        // glows
        near("az 90", 153.6, SkyPhase.frame(10, 90, true, 66, 600, 370, 1077, 1f, 0).sunX, 0.1);
        near("az 270", 1766.4, SkyPhase.frame(10, 270, true, 66, 600, 370, 1077, 1f, 0).sunX, 0.1);
        near("az 180", 960, SkyPhase.frame(10, 180, true, 66, 600, 370, 1077, 1f, 0).sunX, 0.1);
        near("e 0 sunY", 1080, fr(0, true, 1f, 0).sunY, 0.1);
        near("e 70 sunY", 129.6, fr(70, true, 1f, 0).sunY, 0.1);
        near("sunA at -1.5", 0, fr(-1.5, true, 1f, 0).sunA, 1e-6);
        near("sunA at 5", 0.60, fr(5, true, 1f, 0).sunA, 1e-4);
        near("sunA at 5 cloud", 0.21, fr(5, true, 1f, SkyPhase.WX_CLOUD).sunA, 1e-4);
        SkyPhase.Frame m0 = SkyPhase.frame(-20, 0, false, 66, 1077, 370, 1077, 0f, 0);
        near("moon at set x", 537.6, m0.moonX, 0.1); near("moon at set y", 561.6, m0.moonY, 0.1);
        near("moon mid x", 0.47 * 1920, SkyPhase.frame(-20, 0, false, 66, 0, 360, 1080, 0f, 0).moonX, 0.1);
        near("moon mid y", 496.8, SkyPhase.frame(-20, 0, false, 66, 0, 360, 1080, 0f, 0).moonY, 0.1);
        near("moon NaN u .5 x", 902.4, SkyPhase.frame(-20, 0, false, 66, 0, Double.NaN, Double.NaN, 0f, 0).moonX, 0.1);
        near("moonA at -4", 0, fr(-4, false, 0f, 0).moonA, 1e-6);
        near("moonA at -12", 0.90, fr(-12, false, 0f, 0).moonA, 1e-4);
        near("moonA at -12 haze", 0.90 * 0.45, fr(-12, false, 0f, SkyPhase.WX_HAZE).moonA, 1e-4);

        // scrim
        near("night scrim", 0xFF060C20, fr(-30, false, 0f, 0).scrimRgb, 0);
        near("noon cloud scrim", 0xFFD4DAE1, fr(60, true, 1f, SkyPhase.WX_CLOUD).scrimRgb, 0);
        ok("noon clear scrim", fr(60, true, 1f, 0).scrimRgb == 0xFFD2E0EC);
        ok("names", SkyPhase.NAMES.length == 10 && SkyPhase.NAMES[SkyPhase.DUSK].equals("dusk"));

        if (fails > 0) { System.err.println(fails + " FAILED"); System.exit(1); }
        System.out.println("SkyPhaseTest PASS");
    }
}
