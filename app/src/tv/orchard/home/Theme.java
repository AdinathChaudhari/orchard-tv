package tv.orchard.home;

/**
 * PURE JAVA (no android.* imports; host-tested). Rev 4-6 theme: two immutable palettes (DARK, LIGHT,
 * copied from mockup/tokens.css :root and :root[data-theme=light]) plus the live theme state the
 * views read (main thread only). Colours are ARGB ints, alphas floats.
 */
public final class Theme {
    static int a(float al, int rgb) { return (Math.round(al * 255f) << 24) | (rgb & 0xFFFFFF); }

    // ---- palette fields ----
    public final boolean light;
    public final int bg;
    public final int text;
    public final int textHi;
    public final int text2;
    public final int text3;
    public final int tileEdge;
    public final int glassThin;
    public final int glass;
    public final int glassHairline;
    public final int glassThick;
    public final int glassOutline;
    public final int shadow;
    public final int btnFocusFill;
    public final int btnFocusText;
    public final int dockFill;
    public final int dropRgb;
    public final int veil;
    public final int ctlOutline;
    public final int togOff;
    public final int titleShadow;
    public final int fthFillA;
    public final int fthFillB;
    public final int fthInk;
    public final int wxSun;
    public final int wxSunCore;
    public final int wxMoon;
    public final int wxCloud;
    public final int wxCloudEdge;
    public final int wxRain;
    public final int wxHaze;
    public final float rimHi;
    public final float rimLo;
    public final float topEdgeAlpha;
    public final float innerAlpha;
    public final float refractAlpha;
    public final float stripHintAlpha;
    public final float clockAlpha;
    public final float shadowAlpha;
    public final float contactAlpha;
    public final float glassDropAlpha;
    public final float heroArtShadowAlpha;
    public final float heroArtContactAlpha;
    public final float glowAlpha;
    public final float glowLum;
    public final float glowDarkAlpha;
    public final float glowDarkLum;
    public final float heroLum;
    public final float heroSatMax;
    public final float heroWashMidLum;
    public final float heroWashMid;
    public final float heroLeakAlpha;
    public final float heroDimLeft;
    public final float heroDimRight;
    public final float heroDimBottom;
    public final float heroGlowAlpha;
    public final float heroGlowFlatAlpha;
    public final float heroEmblemGlowAlpha;
    public final float heroBlAlpha;
    public final float hbSky;
    public final float settingsDim;

    private Theme(boolean light, int bg, int text, int textHi, int text2, int text3, int tileEdge, int glassThin, int glass, int glassHairline, int glassThick, int glassOutline, int shadow, int btnFocusFill, int btnFocusText, int dockFill, int dropRgb, int veil, int ctlOutline, int togOff, int titleShadow, int fthFillA, int fthFillB, int fthInk, int wxSun, int wxSunCore, int wxMoon, int wxCloud, int wxCloudEdge, int wxRain, int wxHaze, float rimHi, float rimLo, float topEdgeAlpha, float innerAlpha, float refractAlpha, float stripHintAlpha, float clockAlpha, float shadowAlpha, float contactAlpha, float glassDropAlpha, float heroArtShadowAlpha, float heroArtContactAlpha, float glowAlpha, float glowLum, float glowDarkAlpha, float glowDarkLum, float heroLum, float heroSatMax, float heroWashMidLum, float heroWashMid, float heroLeakAlpha, float heroDimLeft, float heroDimRight, float heroDimBottom, float heroGlowAlpha, float heroGlowFlatAlpha, float heroEmblemGlowAlpha, float heroBlAlpha, float hbSky, float settingsDim) {
        this.light = light;
        this.bg = bg;
        this.text = text;
        this.textHi = textHi;
        this.text2 = text2;
        this.text3 = text3;
        this.tileEdge = tileEdge;
        this.glassThin = glassThin;
        this.glass = glass;
        this.glassHairline = glassHairline;
        this.glassThick = glassThick;
        this.glassOutline = glassOutline;
        this.shadow = shadow;
        this.btnFocusFill = btnFocusFill;
        this.btnFocusText = btnFocusText;
        this.dockFill = dockFill;
        this.dropRgb = dropRgb;
        this.veil = veil;
        this.ctlOutline = ctlOutline;
        this.togOff = togOff;
        this.titleShadow = titleShadow;
        this.fthFillA = fthFillA;
        this.fthFillB = fthFillB;
        this.fthInk = fthInk;
        this.wxSun = wxSun;
        this.wxSunCore = wxSunCore;
        this.wxMoon = wxMoon;
        this.wxCloud = wxCloud;
        this.wxCloudEdge = wxCloudEdge;
        this.wxRain = wxRain;
        this.wxHaze = wxHaze;
        this.rimHi = rimHi;
        this.rimLo = rimLo;
        this.topEdgeAlpha = topEdgeAlpha;
        this.innerAlpha = innerAlpha;
        this.refractAlpha = refractAlpha;
        this.stripHintAlpha = stripHintAlpha;
        this.clockAlpha = clockAlpha;
        this.shadowAlpha = shadowAlpha;
        this.contactAlpha = contactAlpha;
        this.glassDropAlpha = glassDropAlpha;
        this.heroArtShadowAlpha = heroArtShadowAlpha;
        this.heroArtContactAlpha = heroArtContactAlpha;
        this.glowAlpha = glowAlpha;
        this.glowLum = glowLum;
        this.glowDarkAlpha = glowDarkAlpha;
        this.glowDarkLum = glowDarkLum;
        this.heroLum = heroLum;
        this.heroSatMax = heroSatMax;
        this.heroWashMidLum = heroWashMidLum;
        this.heroWashMid = heroWashMid;
        this.heroLeakAlpha = heroLeakAlpha;
        this.heroDimLeft = heroDimLeft;
        this.heroDimRight = heroDimRight;
        this.heroDimBottom = heroDimBottom;
        this.heroGlowAlpha = heroGlowAlpha;
        this.heroGlowFlatAlpha = heroGlowFlatAlpha;
        this.heroEmblemGlowAlpha = heroEmblemGlowAlpha;
        this.heroBlAlpha = heroBlAlpha;
        this.hbSky = hbSky;
        this.settingsDim = settingsDim;
    }

    public static final Theme DARK = new Theme(
            false,
            0xFF0B0B10,
            0xFFFFFFFF,
            0xFFFFFFFF,
            a(.66f, 0xFFFFFF),
            a(.52f, 0xFFFFFF),
            a(.07f, 0xFFFFFF),
            a(.07f, 0xFFFFFF),
            a(.12f, 0xFFFFFF),
            a(.16f, 0xFFFFFF),
            a(.74f, 0x0E0E14),
            0,
            a(.48f, 0x000000),
            0xFFF2F2F7,
            0xFF111111,
            a(.065f, 0xFFFFFF),
            0x000000,
            0xFF000000,
            0,
            a(.22f, 0xFFFFFF),
            a(.35f, 0x000000),
            a(.11f, 0xFFFFFF),
            a(.04f, 0xFFFFFF),
            a(.92f, 0xFFFFFF),
            0xFFFFB21E,
            0xFFFFD36A,
            0xFFE9E6FF,
            0xFFDCE2EE,
            0,
            0xFF5FA8FF,
            a(.72f, 0xFFFFFF),
            .34f,
            .07f,
            .28f,
            .10f,
            .12f,
            .85f,
            .92f,
            .48f,
            .40f,
            .35f,
            .55f,
            .45f,
            .65f,
            .50f,
            .85f,
            .58f,
            .32f,
            .70f,
            .14f,
            .38f,
            .06f,
            .55f,
            .10f,
            .35f,
            .50f,
            .25f,
            .40f,
            .35f,
            1f,
            .55f);
    public static final Theme LIGHT = new Theme(
            true,
            0xFFF4F1EC,
            0xFF1B1B1F,
            0xFF08080B,
            a(.82f, 0x1B1B1F),
            a(.68f, 0x1B1B1F),
            a(.08f, 0x000000),
            a(.46f, 0xFFFFFF),
            a(.66f, 0xFFFFFF),
            a(.80f, 0xFFFFFF),
            a(.80f, 0xFAF8F4),
            a(.13f, 0x463A2E),
            a(.26f, 0x34281C),
            0xFFFFFFFF,
            0xFF111111,
            a(.52f, 0xFFFFFF),
            0x34281C,
            a(.36f, 0x28221C),
            a(.78f, 0x463A2E),
            a(.24f, 0x3C3228),
            0,
            a(.70f, 0xFFFFFF),
            a(.36f, 0xFFFFFF),
            a(.78f, 0x1B1B1F),
            0xFFF59E0B,
            0xFFFFC83D,
            0xFF6D71AE,
            0xFF9AA6BC,
            a(.16f, 0x1B1B1F),
            0xFF2F7FE0,
            a(.55f, 0x1B1B1F),
            .95f,
            .40f,
            .95f,
            .38f,
            .50f,
            .80f,
            .94f,
            .22f,
            .20f,
            .16f,
            .26f,
            .22f,
            .38f,
            .55f,
            .45f,
            .60f,
            .80f,
            .50f,
            .90f,
            .42f,
            .30f,
            .30f,
            0f,
            .20f,
            .32f,
            .20f,
            .30f,
            .22f,
            .45f,
            .55f);

    // ---- halo constants (tokens.css Rev 6) ----
    public static final float HALO_CAP_DARK = 0.30f;
    public static final float HALO_CAP_LIGHT = 0.40f;
    public static final float TN_RAMP = 0.5f;
    public static final int HALO_PASSES = 4;
    public static final int HALO_BLUR = 16;                  // design px (CSS blur radius)

    // ---- live state (main thread only) ----
    private static Theme cur = DARK;
    private static float ts, tn;

    public static Theme cur() { return cur; }
    public static float ts() { return ts; }
    public static float tn() { return tn; }

    /** Opaque rgb of what sits behind the tiles (sky scrim, or the theme bg for a static wall); main thread only. */
    private static int scrim = DARK.bg;
    public static int scrim() { return scrim; }
    public static void setScrim(int rgb) { scrim = 0xFF000000 | rgb; }

    /** Sets the live state. True when anything changed (ts / tn compared with 1e-3). */
    public static boolean set(boolean light, float newTs, float newTn) {
        Theme t = light ? LIGHT : DARK;
        boolean changed = t != cur || Math.abs(newTs - ts) > 1e-3f || Math.abs(newTn - tn) > 1e-3f;
        cur = t;
        ts = newTs;
        tn = newTn;
        return changed;
    }

    /** Per-channel ARGB lerp from base to the current textHi by tn. */
    public static int rise(int base) {
        int to = cur.textHi;
        float k = tn < 0f ? 0f : tn > 1f ? 1f : tn;
        int out = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int x = (base >>> sh) & 0xFF, y = (to >>> sh) & 0xFF;
            out |= (Math.round(x + (y - x) * k) & 0xFF) << sh;
        }
        return out;
    }

    /** Strip hint colour: textHi rgb at alpha max(stripHintAlpha, tn). */
    public static int hint() { return a(Math.max(cur.stripHintAlpha, tn), cur.textHi); }

    /** Strip clock colour: textHi rgb at alpha max(clockAlpha, tn). */
    public static int clock() { return a(Math.max(cur.clockAlpha, tn), cur.textHi); }

    /** Halo colour: bg rgb at alpha round(ts * 255); 0 when ts == 0. */
    public static int haloColour() {
        if (ts <= 0f) return 0;
        return a(Math.min(1f, ts), cur.bg);
    }

    public static int argb(float al, int rgb) { return a(al, rgb); }
}
