package tv.orchard.home;

/** PURE JAVA (no android.* imports; host-tested). HSL helpers and the "vivid colour" picker (replaces androidx ColorUtils). */
public final class ColorMath {
    private ColorMath() {}

    /** Neutral slate returned when an image has no meaningful colour. */
    public static final int NEUTRAL = 0xFF6E7390;

    /** h in degrees 0..360, s and l in 0..1. */
    public static void rgbToHsl(int rgb, float[] out) {
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float d = max - min, l = (max + min) / 2f, h, s;
        if (d < 1e-6f) {
            h = 0f;
            s = 0f;
        } else {
            s = d / (1f - Math.abs(2f * l - 1f));
            if (max == r) h = ((g - b) / d) % 6f;
            else if (max == g) h = (b - r) / d + 2f;
            else h = (r - g) / d + 4f;
            h *= 60f;
            if (h < 0f) h += 360f;
        }
        out[0] = h;
        out[1] = clamp01(s);
        out[2] = clamp01(l);
    }

    /** Opaque ARGB. */
    public static int hslToRgb(float h, float s, float l) {
        h = ((h % 360f) + 360f) % 360f;
        s = clamp01(s);
        l = clamp01(l);
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = l - c / 2f;
        float r, g, b;
        int sector = (int) (h / 60f);
        switch (sector) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        return 0xFF000000 | (to8(r + m) << 16) | (to8(g + m) << 8) | to8(b + m);
    }

    /** Same hue, lightness set to l, saturation capped at maxSat. */
    public static int withLightness(int rgb, float l, float maxSat) {
        float[] hsl = new float[3];
        rgbToHsl(rgb, hsl);
        return hslToRgb(hsl[0], Math.min(hsl[1], maxSat), l);
    }

    public static float lightness(int rgb) {
        float r = ((rgb >> 16) & 0xFF), g = ((rgb >> 8) & 0xFF), b = (rgb & 0xFF);
        return (Math.max(r, Math.max(g, b)) + Math.min(r, Math.min(g, b))) / 510f;
    }

    /** Mean HSL lightness of the pixels with alpha >= 128 (0 when there are none). */
    public static float averageLightness(int[] px) {
        double sum = 0;
        int n = 0;
        for (int p : px) {
            if ((p >>> 24) < 128) continue;
            sum += lightness(p);
            n++;
        }
        return n == 0 ? 0f : (float) (sum / n);
    }

    /**
     * Share of the pixels with alpha >= 128 that are near-white (HSL L >= minL and S <= maxS); 0 when there are none.
     * A white banner with a coloured wordmark (Netflix, YouTube) fails a mean-saturation test but is mostly white.
     */
    public static float whiteFraction(int[] px, float minL, float maxS) {
        int n = 0, w = 0;
        float[] hsl = new float[3];
        for (int p : px) {
            if ((p >>> 24) < 128) continue;
            n++;
            rgbToHsl(p, hsl);
            if (hsl[2] >= minL && hsl[1] <= maxS) w++;
        }
        return n == 0 ? 0f : w / (float) n;
    }

    /** Mean HSL saturation of the pixels with alpha >= 128 (0 when there are none). */
    public static float averageSaturation(int[] px) {
        double sum = 0;
        int n = 0;
        float[] hsl = new float[3];
        for (int p : px) {
            if ((p >>> 24) < 128) continue;
            rgbToHsl(p, hsl);
            sum += hsl[1];
            n++;
        }
        return n == 0 ? 0f : (float) (sum / n);
    }

    /**
     * Most vivid colour of a small (about 12x12) sample: score = saturation x mid-lightness weight; the winner is
     * lifted to a usable lightness. No meaningfully saturated pixel gives NEUTRAL.
     */
    public static int vivid(int[] px) {
        float[] hsl = new float[3];
        float best = 0f;
        int bestPx = 0;
        for (int p : px) {
            if ((p >>> 24) < 128) continue;
            rgbToHsl(p, hsl);
            float mid = 1f - Math.min(1f, Math.abs(hsl[2] - 0.5f) * 2f);   // 1 at L 0.5, 0 at black or white
            float score = hsl[1] * (0.25f + 0.75f * mid);
            if (hsl[1] >= 0.2f && score > best) {
                best = score;
                bestPx = p;
            }
        }
        if (best < 0.12f) return NEUTRAL;
        rgbToHsl(bestPx, hsl);
        float s = Math.min(1f, hsl[1] * 1.1f);
        float l = Math.max(0.40f, Math.min(0.62f, hsl[2]));
        return hslToRgb(hsl[0], s, l);
    }

    /**
     * True when (almost) every opaque pixel is within a small distance of the mean colour: flat single-colour art
     * (a plain plate with no glyph). Fewer than 4 opaque pixels counts as flat too.
     */
    public static boolean isFlat(int[] px) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int p : px) {
            if ((p >>> 24) < 32) continue;
            r += (p >> 16) & 0xFF;
            g += (p >> 8) & 0xFF;
            b += p & 0xFF;
            n++;
        }
        if (n < 4) return true;
        int mr = (int) (r / n), mg = (int) (g / n), mb = (int) (b / n);
        int far = 0;
        for (int p : px) {
            if ((p >>> 24) < 32) continue;
            int d = Math.abs(((p >> 16) & 0xFF) - mr) + Math.abs(((p >> 8) & 0xFF) - mg) + Math.abs((p & 0xFF) - mb);
            if (d > FLAT_DELTA) far++;
        }
        return far * 50 < n;                                         // under 2% of pixels differ visibly
    }

    /** Summed |dR|+|dG|+|dB| from the mean beyond which a pixel counts as "not flat". */
    private static final int FLAT_DELTA = 36;

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static int to8(float v) { return Math.round(clamp01(v) * 255f); }
}
