package tv.orchard.home;

/**
 * Rev 4 light/dark phase by sun times: night / dawn / day / golden / dusk, the day-layer
 * alpha p, the golden overlay alpha g, the theme flip point and the text-protection halo.
 * Port of mockup phaseAt + the halo block of applyTheme.
 *
 * PURE JAVA (no android.* imports; host-tested)
 */
public final class ThemePhase {
    private ThemePhase() {}

    public static final int DAWN_START = -15, DAWN_LEN = 30, DUSK_START = -15, DUSK_LEN = 30,
            GOLDEN_LEN = 45, GOLDEN_RAMP = 15, GOLDEN_FADE = 25;
    public static final float FLIP_AT = 0.43f;

    /** name night|dawn|day|golden|dusk; p = day-layer alpha 0..1; g = golden overlay alpha 0..1. */
    public static final class Phase {
        public final String name;
        public final float p, g;

        Phase(String name, float p, float g) {
            this.name = name;
            this.p = p;
            this.g = g;
        }
    }

    public static float smooth(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3 - 2 * x);
    }

    public static boolean light(float p) {
        return p >= FLIP_AT;
    }

    public static Phase at(int minuteOfDay, Solar.Times sun) {
        if (sun.polar == Solar.POLAR_DAY) return new Phase("day", 1f, 0f);
        if (sun.polar == Solar.POLAR_NIGHT) return new Phase("night", 0f, 0f);
        float t = minuteOfDay;
        float ds = (float) sun.rise + DAWN_START, us = (float) sun.set + DUSK_START, gs = (float) sun.set - GOLDEN_LEN;
        String name;
        float p;
        if (t < ds) { name = "night"; p = 0f; }
        else if (t < ds + DAWN_LEN) { name = "dawn"; p = smooth((t - ds) / DAWN_LEN); }
        else if (t < us) { name = t >= gs ? "golden" : "day"; p = 1f; }
        else if (t < us + DUSK_LEN) { name = "dusk"; p = 1f - smooth((t - us) / DUSK_LEN); }
        else { name = "night"; p = 0f; }
        float g = 0f;
        if (t >= gs && t < us) g = smooth((t - gs) / GOLDEN_RAMP);
        else if (t >= us && t < us + GOLDEN_FADE) g = 1f - smooth((t - us) / GOLDEN_FADE);
        return new Phase(name, p, g);
    }

    /** Returns {ts, tn}: halo alpha and how far the text colour has risen to full. */
    public static float[] halo(Phase ph, boolean light, float capDark, float capLight, float tnRamp) {
        float raw = 0f;
        if ("dawn".equals(ph.name) || "dusk".equals(ph.name)) {
            raw = 0.9f * (1f - Math.abs(ph.p - FLIP_AT) / (ph.p < FLIP_AT ? FLIP_AT : 1f - FLIP_AT));
        }
        float cap = light ? capLight : capDark;
        float ts = Math.max(0f, Math.min(cap, raw));
        float tn = cap > 0f ? Math.min(1f, ts / (cap * tnRamp)) : 0f;
        return new float[] {ts, tn};
    }
}
