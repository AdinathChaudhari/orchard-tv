package tv.orchard.home;

/** PURE JAVA (no android.* imports; host-tested). Top Shelf subtitle text and the letter-plate initial. */
public final class ShelfText {
    private ShelfText() {}

    private static final long MIN = 60L * 1000L;
    private static final long HOUR = 60L * MIN;
    private static final long DAY = 24L * HOUR;

    /** "Opened 2 h ago", "Never opened", ... for an entry's last-opened epoch ms. */
    public static String opened(long lastOpenedMs, long nowMs) {
        if (lastOpenedMs <= 0) return "Never opened";
        long d = nowMs - lastOpenedMs;
        if (d < MIN) return "Opened just now";          // also negative (clock skew)
        if (d < HOUR) return "Opened " + (d / MIN) + " min ago";
        if (d < DAY) return "Opened " + (d / HOUR) + " h ago";
        if (d < 2 * DAY) return "Opened yesterday";
        if (d < 7 * DAY) return "Opened " + (d / DAY) + " days ago";
        if (d < 14 * DAY) return "Opened last week";
        if (d < 60 * DAY) return "Opened " + (d / (7 * DAY)) + " weeks ago";
        return "Opened over a month ago";
    }

    /** Letter-plate glyph: the label's first letter or digit, upper-cased; else the package's; else "?". */
    public static String initial(String label, String pkg) {
        String src = label != null && !label.trim().isEmpty() ? label.trim() : (pkg != null ? pkg : "?");
        for (int i = 0; i < src.length(); ) {
            int cp = src.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return new String(Character.toChars(cp)).toUpperCase(java.util.Locale.ROOT);
            i += Character.charCount(cp);
        }
        return "?";
    }
}
