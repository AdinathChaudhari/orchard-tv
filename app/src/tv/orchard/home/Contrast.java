package tv.orchard.home;

/** PURE JAVA (no android.* imports; host-tested). WCAG contrast maths for picking a legible ink. */
public final class Contrast {
    private Contrast() {}

    public static final double AA = 4.5;

    private static double lin(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** Relative luminance of an opaque rgb (alpha ignored). */
    public static double luminance(int rgb) {
        return 0.2126 * lin((rgb >> 16) & 0xFF) + 0.7152 * lin((rgb >> 8) & 0xFF) + 0.0722 * lin(rgb & 0xFF);
    }

    public static double ratio(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        double hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** ARGB fg composited over opaque bg; result opaque. */
    public static int over(int fg, int bg) {
        float a = ((fg >>> 24) & 0xFF) / 255f;
        int out = 0xFF000000;
        for (int sh = 0; sh <= 16; sh += 8) {
            int f = (fg >> sh) & 0xFF, b = (bg >> sh) & 0xFF;
            out |= (Math.round(f * a + b * (1f - a)) & 0xFF) << sh;
        }
        return out;
    }

    /** Worst (lowest) contrast of fg (alpha honoured) against two opaque backdrops. */
    public static double worst(int fg, int bgA, int bgB) {
        return Math.min(ratio(over(fg, bgA), bgA), ratio(over(fg, bgB), bgB));
    }

    /**
     * The preferred ink when it reads at >= 4.5:1 on both backdrops, else dark or light (whichever reads better,
     * dark first on a tie). The returned colour keeps its own alpha (dark and light are passed opaque).
     */
    public static int pickInk(int preferred, int bgA, int bgB, int dark, int light) {
        if (worst(preferred, bgA, bgB) >= AA) return preferred;
        return worst(dark, bgA, bgB) >= worst(light, bgA, bgB) ? dark : light;
    }
}
