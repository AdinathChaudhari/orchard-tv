package tv.orchard.home;

public final class KeyRulesTest {
    static int fails;

    static void is(String name, boolean want, boolean got) {
        if (want != got) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }

    static boolean tap(long ms) {
        KeyRules.MenuTap t = new KeyRules.MenuTap(450);
        t.onDown(0);
        return t.onUp(false, 1000, 1000 + ms);
    }

    public static void main(String[] args) {
        is("100 ms tap", true, tap(100));
        is("449 ms tap", true, tap(449));
        is("450 ms not a tap", false, tap(450));
        is("0 ms tap", true, tap(0));
        is("negative duration", false, tap(-5));

        KeyRules.MenuTap t = new KeyRules.MenuTap(450);
        t.onDown(0);
        is("canceled", false, t.onUp(true, 1000, 1100));

        t = new KeyRules.MenuTap(450);
        t.onDown(0);
        t.onDown(1);
        is("repeat seen then UP (HoF long-press)", false, t.onUp(false, 1000, 1100));

        t = new KeyRules.MenuTap(450);
        is("UP without DOWN", false, t.onUp(false, 1000, 1100));

        t = new KeyRules.MenuTap(450);
        t.onDown(0);                    // this DOWN never gets its UP
        t.onDown(0);                    // next press
        is("forgotten DOWN then new press", true, t.onUp(false, 5000, 5100));

        t = new KeyRules.MenuTap(450);
        t.onDown(0);
        is("first UP", true, t.onUp(false, 1000, 1100));
        is("second UP after a tap", false, t.onUp(false, 1000, 1100));

        t = new KeyRules.MenuTap(450);
        t.onDown(0);
        t.reset();
        is("reset disarms", false, t.onUp(false, 1000, 1100));

        is("select repeat 0", false, KeyRules.isLongSelect(0, true, 0, 700, 600));
        is("select repeat 1 + flag at 400 ms", true, KeyRules.isLongSelect(1, true, 0, 400, 600));
        is("select repeat 3 no flag 599", false, KeyRules.isLongSelect(3, false, 0, 599, 600));
        is("select repeat 3 no flag 600", true, KeyRules.isLongSelect(3, false, 0, 600, 600));

        is("rest alpha: move dim beats idle", true, KeyRules.restAlpha(0.35f, true, 0.6f) == 0.35f);
        is("rest alpha: move dim beats full", true, KeyRules.restAlpha(0.35f, false, 0.6f) == 0.35f);
        is("rest alpha: idle only", true, KeyRules.restAlpha(1f, true, 0.6f) == 0.6f);
        is("rest alpha: nothing", true, KeyRules.restAlpha(1f, false, 0.6f) == 1f);

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
