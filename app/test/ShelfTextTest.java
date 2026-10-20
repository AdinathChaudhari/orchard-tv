package tv.orchard.home;

public final class ShelfTextTest {
    static int fails;
    static final long NOW = 1_800_000_000_000L;
    static final long S = 1000L, M = 60 * S, H = 60 * M, D = 24 * H;

    static void eq(String name, long ago, String want) {
        String got = ShelfText.opened(NOW - ago, NOW);
        if (!want.equals(got)) { fails++; System.err.println("FAIL " + name + ": want '" + want + "' got '" + got + "'"); }
    }

    static void ini(String name, String label, String pkg, String want) {
        String got = ShelfText.initial(label, pkg);
        if (!want.equals(got)) { fails++; System.err.println("FAIL initial " + name + ": want '" + want + "' got '" + got + "'"); }
    }

    public static void main(String[] args) {
        String said = "Never opened";
        if (!said.equals(ShelfText.opened(0, NOW))) { fails++; System.err.println("FAIL zero"); }
        if (!said.equals(ShelfText.opened(-5, NOW))) { fails++; System.err.println("FAIL negative lastOpened"); }
        eq("0 s", 0, "Opened just now");
        eq("future (skew)", -5 * M, "Opened just now");
        eq("59 s", 59 * S, "Opened just now");
        eq("60 s", 60 * S, "Opened 1 min ago");
        eq("59 min", 59 * M, "Opened 59 min ago");
        eq("60 min", 60 * M, "Opened 1 h ago");
        eq("2 h 30", 2 * H + 30 * M, "Opened 2 h ago");
        eq("23 h 59", 23 * H + 59 * M, "Opened 23 h ago");
        eq("24 h", 24 * H, "Opened yesterday");
        eq("47 h", 47 * H, "Opened yesterday");
        eq("48 h", 48 * H, "Opened 2 days ago");
        eq("6 d", 6 * D, "Opened 6 days ago");
        eq("7 d", 7 * D, "Opened last week");
        eq("13 d", 13 * D, "Opened last week");
        eq("14 d", 14 * D, "Opened 2 weeks ago");
        eq("20 d", 20 * D, "Opened 2 weeks ago");
        eq("59 d", 59 * D, "Opened 8 weeks ago");
        eq("60 d", 60 * D, "Opened over a month ago");
        eq("400 d", 400 * D, "Opened over a month ago");
        ini("label", "Netflix", "com.x", "N");
        ini("lower case", "prime video", "com.x", "P");
        ini("leading punctuation", "  (beta) Kodi", "org.x", "B");
        ini("digit", "9Now", "com.x", "9");
        ini("emoji then letter", "\uD83C\uDF4E Orchard", "tv.x", "O");
        ini("blank label uses package", "  ", "tv.orchard.home", "T");
        ini("null label uses package", null, "org.xbmc.kodi", "O");
        ini("nothing usable", "--", null, "?");
        ini("all null", null, null, "?");
        ini("non-latin letter", "\u00e9cran", "com.x", "\u00c9");
        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
