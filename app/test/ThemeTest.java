package tv.orchard.home;

public final class ThemeTest {
    static int fails;

    static void ok(String name, boolean cond, String detail) {
        if (!cond) { fails++; System.err.println("FAIL " + name + ": " + detail); }
    }

    static String hex(int v) { return Integer.toHexString(v); }
    static int al(int c) { return c >>> 24; }

    public static void main(String[] args) {
        Theme.set(false, 0f, 0f);
        ok("dark text2 alpha", al(Theme.DARK.text2) == Math.round(.66f * 255f), hex(Theme.DARK.text2));
        ok("dark text2 rgb", (Theme.DARK.text2 & 0xFFFFFF) == 0xFFFFFF, hex(Theme.DARK.text2));
        ok("light bg", Theme.LIGHT.bg == 0xFFF4F1EC, hex(Theme.LIGHT.bg));
        ok("rise tn0", Theme.rise(Theme.DARK.text2) == Theme.DARK.text2, hex(Theme.rise(Theme.DARK.text2)));

        Theme.set(false, .3f, 1f);
        ok("rise tn1", Theme.rise(Theme.DARK.text2) == Theme.cur().textHi, hex(Theme.rise(Theme.DARK.text2)));
        Theme.set(false, .3f, .5f);
        int base = 0x10203040, to = Theme.cur().textHi, r = Theme.rise(base);
        for (int sh = 0; sh <= 24; sh += 8) {
            int mid = (((base >>> sh) & 0xFF) + ((to >>> sh) & 0xFF)) / 2;
            ok("rise mid ch" + sh, Math.abs(((r >>> sh) & 0xFF) - mid) <= 1, hex(r));
        }

        Theme.set(false, 0f, 0f);
        ok("hint tn0", al(Theme.hint()) == Math.round(.85f * 255f), hex(Theme.hint()));
        ok("clock tn0", al(Theme.clock()) == Math.round(.92f * 255f), hex(Theme.clock()));
        Theme.set(false, 0f, 1f);
        ok("hint tn1", al(Theme.hint()) == 255, hex(Theme.hint()));
        ok("clock tn1", al(Theme.clock()) == 255, hex(Theme.clock()));

        Theme.set(false, 0f, 0f);
        ok("halo ts0", Theme.haloColour() == 0, hex(Theme.haloColour()));
        Theme.set(false, .3f, 0f);
        ok("halo dark", Theme.haloColour() == ((Math.round(.3f * 255f) << 24) | 0x0B0B10), hex(Theme.haloColour()));
        Theme.set(true, .3f, 0f);
        ok("halo light rgb", (Theme.haloColour() & 0xFFFFFF) == 0xF4F1EC, hex(Theme.haloColour()));
        ok("light hint rgb", (Theme.hint() & 0xFFFFFF) == 0x08080B, hex(Theme.hint()));

        Theme.set(true, .2f, .4f);
        ok("set same -> false", !Theme.set(true, .2f, .4f), "changed");
        ok("set tiny -> false", !Theme.set(true, .2005f, .4f), "changed");
        ok("set ts -> true", Theme.set(true, .3f, .4f), "unchanged");
        ok("set theme -> true", Theme.set(false, .3f, .4f), "unchanged");
        ok("cur follows", Theme.cur() == Theme.DARK, "cur");

        ok("cap light", Theme.HALO_CAP_LIGHT == 0.40f, "" + Theme.HALO_CAP_LIGHT);
        ok("cap dark", Theme.HALO_CAP_DARK == 0.30f, "" + Theme.HALO_CAP_DARK);
        ok("passes", Theme.HALO_PASSES == 4, "" + Theme.HALO_PASSES);
        ok("tn ramp", Theme.TN_RAMP == 0.5f, "" + Theme.TN_RAMP);
        ok("light glass thick", Theme.LIGHT.glassThick == ((Math.round(.80f * 255f) << 24) | 0xFAF8F4), hex(Theme.LIGHT.glassThick));
        ok("light flags", Theme.LIGHT.light && !Theme.DARK.light, "light");

        AmbientState s = new AmbientState();
        s.minute = 5; s.cityName = "X"; s.raining = true;
        AmbientState c = s.copy();
        ok("copy", c != s && c.minute == 5 && "X".equals(c.cityName) && c.raining, "copy");

        Theme.set(false, 0f, 0f);
        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
        System.out.println("ThemeTest PASS");
    }
}
