package tv.orchard.home;

/**
 * Host tests: the Fire TV Home label must clear 4.5:1 against the REAL sky behind the glass, not a theme token.
 * Sweeps coarse RGB skies (including the measured 06:00 and 18:10 horizon colours) through both themes' glass fills.
 */
public final class ContrastTest {
    static int fails;
    static void ok(String n, boolean v) { if (!v) { fails++; System.err.println("FAIL " + n); } }

    /** Same pipeline as TileView.onDraw: glass over sky, pick the ink, lay the smallest veil, measure. */
    static double labelRatio(Theme th, int sky) {
        int bgA = Contrast.over(th.fthFillA, sky), bgB = Contrast.over(th.fthFillB, sky);
        int ci = Contrast.pickInk(th.text2, bgA, bgB, Theme.LIGHT.textHi, 0xFFFFFFFF);
        int a = Contrast.veilAlpha(ci, bgA, bgB, Contrast.SAMPLED);
        int veil = Contrast.luminance(ci) < 0.5 ? 0xFFFFFF : 0x000000;
        int fg = a << 24 | veil;
        return Contrast.worst(ci | 0xFF000000, Contrast.over(fg, bgA), Contrast.over(fg, bgB));
    }

    public static void main(String[] args) {
        // measured on the TV: 06:00 glass pixels (213,130,133), 18:10 (201,115,118); the sky under them is darker/less opaque.
        int[] skies = {0xFFC87A80, 0xFFB86E75, 0xFFD58285, 0xFFC97377, 0xFF777788, 0xFF9E5560, 0xFFE8A08A, 0xFF101018};
        Theme[] themes = {Theme.DARK, Theme.LIGHT};
        int bad = 0, n = 0;
        for (Theme th : themes) {
            for (int sky : skies) { n++; if (labelRatio(th, sky) < Contrast.AA) { bad++; System.err.println("sky " + Integer.toHexString(sky) + " light=" + th.light + " " + labelRatio(th, sky)); } }
            for (int r = 0; r < 256; r += 51) for (int g = 0; g < 256; g += 51) for (int b = 0; b < 256; b += 51) {
                int sky = 0xFF000000 | r << 16 | g << 8 | b;
                n++;
                if (labelRatio(th, sky) < Contrast.AA) { bad++; System.err.println("rgb " + Integer.toHexString(sky) + " light=" + th.light + " " + labelRatio(th, sky)); }
            }
        }
        ok("every sampled sky clears 4.5:1 (" + bad + " of " + n + " fail)", bad == 0);

        // the old token-based backdrop (Theme.scrim = a dark bg) would have left the horizon unveiled: prove the sample matters
        int horizon = 0xFFC87A80;
        int tokenSky = Theme.DARK.bg;
        int bgTok = Contrast.over(Theme.DARK.fthFillA, tokenSky), bgTokB = Contrast.over(Theme.DARK.fthFillB, tokenSky);
        int inkTok = Contrast.pickInk(Theme.DARK.text2, bgTok, bgTokB, Theme.LIGHT.textHi, 0xFFFFFFFF);
        int veilTok = Contrast.veilAlpha(inkTok, bgTok, bgTokB, Contrast.SAMPLED);
        int bgReal = Contrast.over(Theme.DARK.fthFillA, horizon), bgRealB = Contrast.over(Theme.DARK.fthFillB, horizon);
        double unveiledOnReal = Contrast.worst(inkTok | 0xFF000000, bgReal, bgRealB);
        ok("token backdrop picks an ink that fails on the real horizon", veilTok == 0 && unveiledOnReal < Contrast.AA);
        ok("real horizon gets a veil or a better ink", labelRatio(Theme.DARK, horizon) >= Contrast.AA);

        // meanRgb
        int[] px = new int[4 * 4];
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | (i < 8 ? 0x640000 : 0x006400);
        ok("mean top half", Contrast.meanRgb(px, 4, 0, 0, 4, 2) == 0xFF640000);
        ok("mean bottom half", Contrast.meanRgb(px, 4, 0, 2, 4, 4) == 0xFF006400);
        ok("mean mix", Contrast.meanRgb(px, 4, 0, 0, 4, 4) == 0xFF323200);
        ok("mean clamps", Contrast.meanRgb(px, 4, -5, -5, 99, 99) == 0xFF323200);
        ok("mean empty", Contrast.meanRgb(px, 4, 2, 2, 2, 3) == -1);
        ok("mean null", Contrast.meanRgb(null, 4, 0, 0, 1, 1) == -1);

        // veilAlpha target overload: a stricter target never lowers the veil
        int ink = 0xFFFFFFFF, bg = 0xFF9A9A9A;
        ok("stricter target veils at least as much", Contrast.veilAlpha(ink, bg, bg, 6.0) >= Contrast.veilAlpha(ink, bg, bg, 4.5));
        ok("overload default equals AA", Contrast.veilAlpha(ink, bg, bg) == Contrast.veilAlpha(ink, bg, bg, Contrast.AA));

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
        System.out.println("ok");
    }
}
