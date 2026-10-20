package tv.orchard.home;

/**
 * The Rev 6 sky engine's maths: which two sky keyframes are on screen and how far the second is mixed in,
 * the hand-over swap rule, the weather tint, the fog and rain veils, the sun and moon glow placement and the
 * flat scrim colour for the status strip. Numbers are copies of mockup/tokens.css section "Rev 6"
 * (design canvas 1920 x 1080 px). Mirrors mockup/mock.js skyAt / wxSky / applySky.
 *
 * <p>PURE JAVA (no android.* imports; host-tested). Inputs are plain numbers; the view applies the output.
 */
public final class SkyPhase {
    private SkyPhase() {}

    /** Keyframe indexes; the drawable suffixes are sky_&lt;name&gt;.webp and sky_&lt;name&gt;_oc.webp. */
    public static final int NIGHT = 0, DAWN = 1, SUNRISE = 2, MORNING = 3, NOON = 4,
            AFTERNOON = 5, GOLDEN = 6, HORIZON = 7, SUNSET = 8, DUSK = 9;
    public static final String[] NAMES = {"night", "dawn", "sunrise", "morning", "noon", "afternoon", "golden", "horizon", "sunset", "dusk"};

    /** Weather conditions (same values as the weather parser). NONE = off / offline / stale, drawn as clear. */
    public static final int WX_NONE = -1, WX_CLEAR = 0, WX_CLOUD = 1, WX_RAIN = 2, WX_HAZE = 3;

    static final double W = 1920, H = 1080;
    static final double FLIP = 0.43;
    static final double K_REF = 50;

    // windows: {from, to, a, b} in degrees of sun elevation
    private static final double[][] RISING = {
        {NIGHT, DAWN, -16, -7}, {DAWN, SUNRISE, -6.4, -2.41}, {SUNRISE, MORNING, -1.37, 0.03}, {MORNING, NOON, 8, 45}};
    private static final double[][] SETTING = {
        {NOON, AFTERNOON, 45, 22}, {AFTERNOON, GOLDEN, 14, 6}, {GOLDEN, HORIZON, -0.07, -1.47},
        {HORIZON, SUNSET, -2.48, -4.85}, {SUNSET, DUSK, -5, -10}, {DUSK, NIGHT, -12, -17}};

    private static final int[][] TOP_CLEAR = {
        {6, 12, 32}, {12, 19, 63}, {81, 81, 157}, {156, 192, 235}, {210, 224, 236},
        {168, 199, 237}, {180, 186, 216}, {81, 81, 157}, {19, 22, 75}, {9, 14, 50}};
    private static final int[][] TOP_OC = {
        {9, 13, 25}, {23, 24, 46}, {82, 80, 118}, {168, 186, 208}, {212, 218, 225},
        {182, 196, 212}, {180, 182, 195}, {84, 82, 122}, {28, 26, 52}, {17, 17, 36}};

    /** Two keyframes and the mix of the second over the first (0 = a only). */
    public static final class Pair {
        public final int a, b;
        public final float f;
        public Pair(int a, int b, float f) { this.a = a; this.b = b; this.f = f; }
    }

    /** Everything the view needs for one sky state. Glow positions are centres in design px. */
    public static final class Frame {
        public final int a, b;
        public final float f, oc, fogDay, fogNight, rdim, rainDay, rainLightFactor, rainAlpha;
        public final float sunX, sunY, sunA, moonX, moonY, moonA;
        public final boolean rainOn;
        public final int scrimRgb;
        public final double e, az;

        Frame(Pair k, float oc, float fogDay, float fogNight, float rdim, float rainDay, float rainLightFactor, float rainAlpha,
              float sunX, float sunY, float sunA, float moonX, float moonY, float moonA,
              boolean rainOn, int scrimRgb, double e, double az) {
            this.a = k.a; this.b = k.b; this.f = k.f;
            this.oc = oc; this.fogDay = fogDay; this.fogNight = fogNight; this.rdim = rdim; this.rainDay = rainDay;
            this.rainLightFactor = rainLightFactor; this.rainAlpha = rainAlpha;
            this.sunX = sunX; this.sunY = sunY; this.sunA = sunA;
            this.moonX = moonX; this.moonY = moonY; this.moonA = moonA;
            this.rainOn = rainOn; this.scrimRgb = scrimRgb; this.e = e; this.az = az;
        }
    }

    /** Slate tile luminance (60,80,110) the light-theme streaks are drawn in. */
    public static final double RAIN_TILE_LUM_L = 0.305;
    public static final double RAIN_CONTRAST_L = 0.20, RAIN_ALPHA_L_MIN = 0.25, RAIN_ALPHA_L_MAX = 0.55;
    public static final float RAIN_ALPHA_DARK = 0.25f;

    /** Rec.709 luminance (0..1) of a packed 0xRRGGBB colour, on the gamma-encoded values (the contrast gate's measure). */
    public static double lum(int rgb) {
        return (0.2126 * ((rgb >> 16) & 255) + 0.7152 * ((rgb >> 8) & 255) + 0.0722 * (rgb & 255)) / 255.0;
    }

    /** Light-sky streak alpha: tracks the sky so the peak streak contrast stays near RAIN_CONTRAST_L, times the day factor. */
    public static float rainAlphaLight(double skyLum, float factor) {
        double d = skyLum - RAIN_TILE_LUM_L;
        double a = d <= 1e-6 ? RAIN_ALPHA_L_MAX : RAIN_CONTRAST_L / d;
        a = Math.max(RAIN_ALPHA_L_MIN, Math.min(RAIN_ALPHA_L_MAX, a));
        return (float) (a * factor);
    }

    public static float clamp01(float x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

    public static double clamp01(double x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

    public static float smooth(float x) { x = clamp01(x); return x * x * (3 - 2 * x); }

    private static double smoothD(double x) { x = clamp01(x); return x * x * (3 - 2 * x); }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Elevation (deg) and side of solar noon to the two keyframes and the mix; windows above 3 deg shrink on low-sun days. */
    public static Pair pair(double e, boolean rising, double emax) {
        double k = Math.min(1, Math.max(0.15, emax / K_REF));
        int cur = rising ? NIGHT : NOON;
        for (double[] w : rising ? RISING : SETTING) {
            double a = w[2] > 3 ? w[2] * k : w[2], b = w[3] > 3 ? w[3] * k : w[3];
            double f = (e - a) / (b - a);
            int from = (int) w[0], to = (int) w[1];
            if (f <= 0) return new Pair(from, from, 0f);
            if (f < 1) return new Pair(from, to, smooth((float) f));
            cur = to;
        }
        return new Pair(cur, cur, 0f);
    }

    /** Hand-over: a window just finished and the next began, so swap layer roles with no fade. */
    public static boolean shouldSwap(Pair prev, Pair next) {
        return prev != null && next.a != prev.a && next.a == prev.b && prev.f >= 0.9f;
    }

    public static Frame frame(double e, double az, boolean rising, double emax, int minute, double rise, double set, float p, int cond) {
        Pair k = pair(e, rising, emax);
        boolean rain = cond == WX_RAIN, haze = cond == WX_HAZE;
        float oc = cond == WX_CLOUD || rain ? 1f : haze ? 0.30f : 0f;
        float rdim = rain ? 0.01f : 0f;
        double glowMult = cond == WX_CLOUD ? 0.35 : rain ? 0 : haze ? 0.45 : 1;
        double day = smoothD((p - FLIP) / (1 - FLIP)), night = smoothD((FLIP - p) / FLIP);
        float fogDay = haze ? (float) (0.42 * day) : 0f;
        float fogNight = haze ? (float) (0.14 * night) : 0f;
        float rainDay = rain ? (float) (0.20 * day) : 0f;
        float lightFactor = (float) day;

        double sunX = W * lerp(0.08, 0.92, 0.5 + 0.5 * Math.sin(Math.toRadians(az - 180)));
        double sunY = H * lerp(1.00, 0.12, Math.pow(clamp01(e / 70), 0.8));
        double sunA = 0.60 * smoothD((e + 1.5) / 6.5) * glowMult;
        double u = 0.5;
        if (!Double.isNaN(rise) && !Double.isNaN(set)) {
            double len = rise + 1440 - set;
            u = clamp01((minute >= set ? minute - set : minute + 1440 - set) / len);
        }
        double moonX = W * lerp(0.28, 0.66, u);
        double moonY = H * lerp(0.52, 0.46, Math.pow(Math.sin(Math.PI * u), 0.8));
        double moonA = 0.90 * smoothD((e - (-4)) / (-12 - (-4))) * glowMult;

        // scrim: the sky stack in flat colours
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            c[i] = lerp(TOP_CLEAR[k.a][i], TOP_CLEAR[k.b][i], k.f);
            double o = lerp(TOP_OC[k.a][i], TOP_OC[k.b][i], k.f);
            c[i] = lerp(c[i], o, oc);
        }
        over(c, 255, 255, 255, fogDay);
        over(c, 128, 138, 166, fogNight);
        over(c, 0, 0, 0, rdim);
        over(c, 96, 110, 136, rainDay);
        int rgb = 0xFF000000 | (ch(c[0]) << 16) | (ch(c[1]) << 8) | ch(c[2]);
        float rainAlpha = p >= FLIP ? rainAlphaLight(lum(rgb), lightFactor) : RAIN_ALPHA_DARK;
        return new Frame(k, oc, fogDay, fogNight, rdim, rainDay, lightFactor, rainAlpha,
                (float) sunX, (float) sunY, (float) sunA, (float) moonX, (float) moonY, (float) moonA, rain, rgb, e, az);
    }

    private static void over(double[] c, double r, double g, double b, double alpha) {
        c[0] = lerp(c[0], r, alpha); c[1] = lerp(c[1], g, alpha); c[2] = lerp(c[2], b, alpha);
    }

    private static int ch(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }
}
