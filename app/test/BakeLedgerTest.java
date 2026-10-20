package tv.orchard.home;

import java.util.List;

public final class BakeLedgerTest {
    static int fails;
    static void eq(String n, Object want, Object got) {
        if (!String.valueOf(want).equals(String.valueOf(got))) { fails++; System.err.println("FAIL " + n + ": want [" + want + "] got [" + got + "]"); }
    }

    public static void main(String[] args) {
        BakeLedger<String> l = new BakeLedger<String>();
        l.add("a", 2); l.add("b", 2); l.add("c", 2);
        eq("count", 3, l.count());
        List<String> ev = l.trim(2, 100, "c", null);
        eq("count cap evicts oldest", "[a]", ev);
        eq("count after", 2, l.count());
        eq("bytes after", 4L, l.bytes());
        l.add("d", 2);
        ev = l.trim(10, 4, "b", "d");
        eq("bytes cap skips protected", "[c]", ev);
        eq("bytes after cap", 4L, l.bytes());
        ev = l.trim(1, 1, "b", "d");
        eq("protected never returned", "[]", ev);
        l.add("e", 2); l.add("f", 2);
        ev = l.retainOnly("f", null);
        eq("phase change keeps one", "[b, d, e]", ev);
        eq("one left", 1, l.count());
        eq("bytes one", 2L, l.bytes());
        eq("clear returns rest", "[f]", l.clear());
        eq("clear empties", 0L, l.bytes());
        // 40 phase switches: live bakes never exceed the cap
        for (int i = 0; i < 40; i++) {
            l.add("p" + i, 2_000_000);
            l.trim(2, 6_000_000L, "p" + i, null);
            if (l.count() > 2) { fails++; System.err.println("FAIL cap exceeded at " + i); }
        }
        // Contrast
        eq("black on white", true, Contrast.ratio(0xFF000000, 0xFFFFFFFF) > 20.9);
        int pale = 0xFFE8EEF8;
        eq("white fails on pale", true, Contrast.ratio(0xFFFFFFFF, pale) < 4.5);
        int ink = Contrast.pickInk(0xCCFFFFFF, pale, pale, 0xFF08080B, 0xFFFFFFFF);
        eq("pale picks dark", 0xFF08080B, ink);
        eq("dark bg keeps preferred", 0xCCFFFFFF, Contrast.pickInk(0xCCFFFFFF, 0xFF101018, 0xFF202030, 0xFF08080B, 0xFFFFFFFF));
        eq("dark ink clears AA on pale", true, Contrast.worst(ink, pale, 0xFFFFFFFF) >= 4.5);
        eq("over opaque", 0xFF808080, Contrast.over(0x80FFFFFF, 0xFF000000) | 0x0);
        // debug rain / day
        eq("debug rain 70", 70, DebugFlags.debugRainPct(true, -1));
        eq("real kept", 35, DebugFlags.debugRainPct(true, 35));
        eq("dry none", -1, DebugFlags.debugRainPct(false, -1));
        eq("chip", "· Rain 70%", RainText.rainNowPct(true, DebugFlags.debugRainPct(true, -1)));
        eq("chip full", "24° · Rain 70%", RainText.temp(24.2, false) + " " + RainText.rainNowPct(true, 70));
        eq("night elev", false, DebugFlags.isDayAtElevation(-12.0));
        eq("noon elev", true, DebugFlags.isDayAtElevation(60.0));
        double e0215 = Solar.position(12.97, 77.59, 2026, 10, 20, 5.5, 2 * 60 + 15).elevation;
        double e1200 = Solar.position(12.97, 77.59, 2026, 10, 20, 5.5, 12 * 60).elevation;
        eq("bengaluru 02:15 night", false, DebugFlags.isDayAtElevation(e0215));
        eq("bengaluru 12:00 day", true, DebugFlags.isDayAtElevation(e1200));
        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
        System.out.println("ok");
    }
}
