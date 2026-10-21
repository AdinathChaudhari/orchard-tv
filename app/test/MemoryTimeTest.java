package tv.orchard.home;

import java.util.List;
import java.util.TimeZone;

/** Host tests: SpriteLru bounds, TimeSource, OverlayRules, Contrast.veilAlpha. */
public final class MemoryTimeTest {
    static int fails;
    static void eq(String n, Object want, Object got) {
        if (!String.valueOf(want).equals(String.valueOf(got))) { fails++; System.err.println("FAIL " + n + ": want [" + want + "] got [" + got + "]"); }
    }

    public static void main(String[] args) {
        // SpriteLru: count cap, byte cap, LRU order, refusal, never exceeds the ceiling over a long run
        SpriteLru<String, String> l = new SpriteLru<String, String>(3, 100);
        l.put("a", "A", 10); l.put("b", "B", 10); l.put("c", "C", 10);
        l.get("a");
        eq("count cap evicts LRU (b)", "[B]", l.put("d", "D", 10));
        eq("count", 3, l.count());
        eq("bytes by byte cap", "[C, A]", l.put("e", "E", 85));
        eq("bytes", 95L, l.bytes());
        eq("oversize refused", "[Z]", l.put("z", "Z", 101));
        eq("oversize not stored", null, l.get("z"));
        eq("replace same key frees old", "[E]", l.put("e", "E2", 5));
        eq("trimTo", "[D]", l.trimTo(1, 100));
        eq("clear", "[E2]", l.clear());
        eq("cleared bytes", 0L, l.bytes());
        SpriteLru<Integer, Integer> big = new SpriteLru<Integer, Integer>(96, 6L << 20);
        long worst = 0; int worstN = 0;
        for (int i = 0; i < 5000; i++) {
            big.put(i, i, 1000 + (i * 7919) % 400000);
            worst = Math.max(worst, big.bytes()); worstN = Math.max(worstN, big.count());
        }
        eq("5000 puts stay under byte cap", true, worst <= (6L << 20));
        eq("5000 puts stay under count cap", true, worstN <= 96);

        // TimeSource: every consumer pins alike; date never rolls
        TimeZone tz = TimeZone.getTimeZone("Asia/Kolkata");
        long real = 1_792_500_000_000L;
        eq("unset keeps real", real, TimeSource.now(real, null, tz));
        eq("bad keeps real", real, TimeSource.now(real, "25:99", tz));
        long pin = TimeSource.now(real, "18:10", tz);
        java.util.Calendar c = java.util.Calendar.getInstance(tz); c.setTimeInMillis(pin);
        java.util.Calendar r = java.util.Calendar.getInstance(tz); r.setTimeInMillis(real);
        eq("pinned hour", 18, c.get(java.util.Calendar.HOUR_OF_DAY));
        eq("pinned minute", 10, c.get(java.util.Calendar.MINUTE));
        eq("date does not roll", r.get(java.util.Calendar.DAY_OF_YEAR), c.get(java.util.Calendar.DAY_OF_YEAR));
        eq("pinned flag", true, TimeSource.pinned("06:00"));
        eq("unpinned flag", false, TimeSource.pinned(""));
        eq("minute pinned", 1090, TimeSource.minute(5, "18:10"));
        eq("minute real", 5, TimeSource.minute(5, null));

        // OverlayRules
        eq("chip hidden when reaching panel", true, OverlayRules.hideChip(true, 600f, 460f, 20f));
        eq("chip stays when clear", false, OverlayRules.hideChip(true, 400f, 460f, 20f));
        eq("chip stays with no overlay", false, OverlayRules.hideChip(false, 600f, 460f, 20f));
        eq("sheet no keeps sizes to content", 0, OverlayRules.sheetMinHeight(false, 420));
        eq("sheet keeps keeps min", 420, OverlayRules.sheetMinHeight(true, 420));
        eq("jump 12:00 -> 18:10", true, OverlayRules.largePhaseJump(720, 1090, 90));
        eq("jump across midnight small", false, OverlayRules.largePhaseJump(1430, 10, 90));
        eq("first eval no jump", false, OverlayRules.largePhaseJump(-1, 720, 90));
        eq("minute tick no jump", false, OverlayRules.largePhaseJump(720, 721, 90));

        SpriteLru<String, String> rm = new SpriteLru<String, String>(2, 100);
        rm.put("k", "X", 40);
        eq("remove returns value", "X", rm.remove("k"));
        eq("remove frees bytes", 0L, rm.bytes());
        SpriteLru<String, String> sh = new SpriteLru<String, String>(2, 100);   // ArtCache shelf sets: count 2 AND bytes
        sh.put("a", "A", 60); sh.put("b", "B", 30);
        eq("shelf byte cap evicts oldest", "[A]", sh.put("c", "C", 50));
        eq("shelf bytes after", 80L, sh.bytes());
        eq("shelf count cap", "[B]", sh.put("d", "D", 5));

        // overlay rules added for the dusk pre-composite and the greeting
        eq("mix when settled and both visible", true, OverlayRules.useMixBake(false, 0.9f, 0.03f, 0.9f, 0.03f, 1e-3f));
        eq("no mix while animating", false, OverlayRules.useMixBake(true, 0.9f, 0.03f, 0.9f, 0.03f, 1e-3f));
        eq("no mix before alphas reach targets", false, OverlayRules.useMixBake(false, 0.9f, 0.03f, 0.5f, 0.4f, 1e-3f));
        eq("no mix when light is 0", false, OverlayRules.useMixBake(false, 1f, 0f, 1f, 0f, 1e-3f));
        eq("no mix when dark is 0", false, OverlayRules.useMixBake(false, 0f, 1f, 0f, 1f, 1e-3f));
        eq("greeting under panel hidden", true, OverlayRules.overlaps(true, 1350f, 1460f, 460f, 1460f, 20f));
        eq("greeting right of panel kept", false, OverlayRules.overlaps(true, 1500f, 1600f, 460f, 1460f, 20f));
        eq("greeting left of panel kept", false, OverlayRules.overlaps(true, 100f, 300f, 460f, 1460f, 20f));
        eq("greeting kept with no overlay", false, OverlayRules.overlaps(false, 1350f, 1460f, 460f, 1460f, 20f));

        // Contrast.veilAlpha: label reaches 4.5:1 on every grey sky, both fill ends
        int dark = 0xFF08080B, light = 0xFFFFFFFF;
        int worstFail = 0;
        for (int g = 0; g < 256; g++) {
            int bg = 0xFF000000 | g << 16 | g << 8 | g;
            int bgB = 0xFF000000 | Math.min(255, g + 14) << 16 | Math.min(255, g + 14) << 8 | Math.min(255, g + 14);
            int ink = Contrast.pickInk(0xCCFFFFFF, bg, bgB, dark, light);
            int a = Contrast.veilAlpha(ink, bg, bgB);
            int veil = Contrast.luminance(ink) < 0.5 ? 0xFFFFFF : 0;
            int fg = a << 24 | veil;
            double got = Contrast.worst(ink | 0xFF000000, Contrast.over(fg, bg), Contrast.over(fg, bgB));
            if (got < Contrast.AA) { worstFail++; System.err.println("grey " + g + " ratio " + got); }
        }
        eq("every grey clears 4.5", 0, worstFail);
        int veiled = 0;
        for (int g = 0; g < 256; g++) {
            int bg = 0xFF000000 | g << 16 | g << 8 | g;
            if (Contrast.veilAlpha(Contrast.pickInk(0xCCFFFFFF, bg, bg, dark, light), bg, bg) > 0) veiled++;
        }
        eq("some mid-luminance skies need a veil", true, veiled > 0);
        eq("dark bg needs none", 0, Contrast.veilAlpha(0xFFFFFFFF, 0xFF101018, 0xFF101018));

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
        System.out.println("ok");
    }
}
