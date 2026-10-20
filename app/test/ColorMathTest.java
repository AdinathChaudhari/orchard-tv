package tv.orchard.home;

import java.util.Arrays;

public final class ColorMathTest {
    static int fails;

    static void ok(String name, boolean cond, String detail) {
        if (!cond) { fails++; System.err.println("FAIL " + name + ": " + detail); }
    }

    public static void main(String[] args) {
        int[] cols = {0xFFE71C25, 0xFF3D8BFF, 0xFF12A14B, 0xFFFFC107, 0xFF808080, 0xFF0B0B10, 0xFFFFFFFF, 0xFF6E7390};
        float[] hsl = new float[3];
        for (int c : cols) {
            ColorMath.rgbToHsl(c, hsl);
            int back = ColorMath.hslToRgb(hsl[0], hsl[1], hsl[2]);
            for (int sh = 0; sh <= 16; sh += 8) {
                int d = Math.abs(((c >> sh) & 0xFF) - ((back >> sh) & 0xFF));
                ok("roundtrip " + Integer.toHexString(c), d <= 1, "back " + Integer.toHexString(back));
            }
            ok("opaque", (back >>> 24) == 0xFF, "alpha");
        }
        ColorMath.rgbToHsl(0xFFFF0000, hsl);
        ok("red hue", Math.abs(hsl[0]) < 0.5f && Math.abs(hsl[1] - 1f) < 0.01f && Math.abs(hsl[2] - 0.5f) < 0.01f, Arrays.toString(hsl));
        ColorMath.rgbToHsl(0xFF0000FF, hsl);
        ok("blue hue", Math.abs(hsl[0] - 240f) < 0.5f, Arrays.toString(hsl));

        int w = ColorMath.withLightness(0xFFE71C25, 0.58f, 1f);
        ok("withLightness L", Math.abs(ColorMath.lightness(w) - 0.58f) < 0.01f, "L=" + ColorMath.lightness(w));
        int capped = ColorMath.withLightness(0xFF0000FF, 0.5f, 0.4f);
        ColorMath.rgbToHsl(capped, hsl);
        ok("withLightness S cap", Math.abs(hsl[1] - 0.4f) < 0.01f && Math.abs(hsl[2] - 0.5f) < 0.01f, Arrays.toString(hsl));
        ColorMath.rgbToHsl(w, hsl);
        ok("withLightness keeps hue", hsl[0] < 6f || hsl[0] > 354f, Arrays.toString(hsl));

        int[] px = new int[144];
        Arrays.fill(px, 0xFF808080);
        for (int i = 0; i < 12; i++) px[i] = 0xFFD32F2F;
        int v = ColorMath.vivid(px);
        ColorMath.rgbToHsl(v, hsl);
        ok("vivid red hue", (hsl[0] < 12f || hsl[0] > 348f) && hsl[1] > 0.5f, Integer.toHexString(v));
        int[] grey = new int[144];
        Arrays.fill(grey, 0xFF909090);
        ok("vivid grey neutral", ColorMath.vivid(grey) == 0xFF6E7390, Integer.toHexString(ColorMath.vivid(grey)));
        int[] clear = new int[144];
        for (int i = 0; i < 144; i++) clear[i] = 0x00FF0000;
        ok("vivid ignores transparent", ColorMath.vivid(clear) == 0xFF6E7390, Integer.toHexString(ColorMath.vivid(clear)));

        int[] dark = new int[16];
        Arrays.fill(dark, 0xFF101010);
        ok("avg lightness dark", Math.abs(ColorMath.averageLightness(dark) - 16f / 255f) < 0.01f, "" + ColorMath.averageLightness(dark));
        ok("avg saturation grey", ColorMath.averageSaturation(grey) < 0.01f, "sat");

        int[] netflix = new int[100];
        Arrays.fill(netflix, 0xFFFFFFFF);
        for (int i = 0; i < 30; i++) netflix[i] = 0xFFE50914;   // 30% red wordmark on white
        ok("netflix mean sat fails the old rule", ColorMath.averageSaturation(netflix) > 0.15f, "" + ColorMath.averageSaturation(netflix));
        ok("white fraction netflix", Math.abs(ColorMath.whiteFraction(netflix, 0.88f, 0.25f) - 0.70f) < 0.001f,
                "" + ColorMath.whiteFraction(netflix, 0.88f, 0.25f));
        ok("white fraction grey", ColorMath.whiteFraction(grey, 0.88f, 0.25f) == 0f, "grey");
        ok("white fraction transparent", ColorMath.whiteFraction(clear, 0.88f, 0.25f) == 0f, "clear");

        int[] plate = new int[576];
        Arrays.fill(plate, 0xFFF08A24);
        ok("flat plate is flat", ColorMath.isFlat(plate), "plate");
        int[] glyph = plate.clone();
        for (int i = 0; i < 120; i++) glyph[i] = 0xFFFFFFFF;           // a 20% white glyph on the plate
        ok("plate with glyph is not flat", !ColorMath.isFlat(glyph), "glyph");
        int[] speck = plate.clone();
        for (int i = 0; i < 3; i++) speck[i] = 0xFFFFFFFF;             // 3 of 576 px: noise, still flat
        ok("tiny speck still flat", ColorMath.isFlat(speck), "speck");
        int[] seeThrough = new int[576];
        for (int i = 0; i < 576; i++) seeThrough[i] = i < 2 ? 0xFFFFFFFF : 0x00000000;
        ok("almost transparent is flat", ColorMath.isFlat(seeThrough), "transparent");

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
