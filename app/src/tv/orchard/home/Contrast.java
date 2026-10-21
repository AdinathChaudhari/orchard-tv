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

    /**
     * Opacity (0..255) of an opposite-tone veil (white under dark ink, black under light ink) that lifts ink to
     * >= AA on both backdrops; 0 when ink already clears it. Stepped by 5; 255 is always enough for a dark or light ink.
     */
    public static int veilAlpha(int ink, int bgA, int bgB) { return veilAlpha(ink, bgA, bgB, AA); }

    /**
     * Design target for a sampled backdrop: AA plus a margin for the sampling error (blurred slice vs the real pixels).
     * 4.8 measured 4.15-4.68 on the TV at 18:10 (the screen behind the glass reads ~7 % darker than the slice's mean),
     * so the margin is 20 %.
     */
    public static final double SAMPLED = 5.4;

    /** veilAlpha against an explicit ratio target. */
    public static int veilAlpha(int ink, int bgA, int bgB, double target) {
        if (worst(ink | 0xFF000000, bgA, bgB) >= target) return 0;
        int veil = luminance(ink) < 0.5 ? 0xFFFFFF : 0x000000;
        for (int a = 5; a < 255; a += 5) {
            int fg = a << 24 | veil;
            if (worst(ink | 0xFF000000, over(fg, bgA), over(fg, bgB)) >= target) return a;
        }
        return 255;
    }

    /**
     * Mean opaque rgb of the pixel rectangle [x0,x1) x [y0,y1) of an ARGB array (row stride w), clamped to the array;
     * -1 when the rectangle is empty. Used to read the real sky under a label from the baked backdrop slice.
     */
    public static int meanRgb(int[] argb, int w, int x0, int y0, int x1, int y1) {
        if (argb == null || w <= 0) return -1;
        int h = argb.length / w;
        x0 = Math.max(0, x0); y0 = Math.max(0, y0); x1 = Math.min(w, x1); y1 = Math.min(h, y1);
        if (x1 <= x0 || y1 <= y0) return -1;
        long r = 0, g = 0, b = 0;
        int n = (x1 - x0) * (y1 - y0);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = argb[y * w + x];
                r += (p >> 16) & 0xFF; g += (p >> 8) & 0xFF; b += p & 0xFF;
            }
        }
        return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }
}
