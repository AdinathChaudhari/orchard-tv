package tv.orchard.home;

/**
 * Geometry for the cached text halo (see Halo): where a left-aligned bake sits for any Paint.Align, and how
 * much blur padding a bitmap needs. Align codes match Paint.Align ordinals: 0 LEFT, 1 CENTER, 2 RIGHT.
 *
 * PURE JAVA (no android.* imports; host-tested)
 */
public final class HaloMath {
    private HaloMath() {}

    public static final int LEFT = 0, CENTER = 1, RIGHT = 2;

    /** Left edge of the text when drawText(x, ...) is called with this alignment. */
    public static float leftEdge(int align, float x, float width) {
        if (align == CENTER) return x - width / 2f;
        if (align == RIGHT) return x - width;
        return x;
    }

    /** Padding (px) around the glyphs so the blur (3 sigma) is not clipped; shadow radius r gives sigma = 0.57735 r + 0.5. */
    public static int pad(float radius) {
        return (int) Math.ceil(3f * (0.57735f * radius + 0.5f)) + 2;
    }

    /** True when a bake of this size would blow the cache budget on its own (caller then skips the cache). */
    public static boolean tooBig(int w, int h, int budgetBytes) {
        return w <= 0 || h <= 0 || (long) w * h > budgetBytes / 2;
    }

    /** Steps per unit of halo strength: the compound alpha is baked per ts rounded to 1/QSTEPS (a minute tick moves ts by far less). */
    public static final int QSTEPS = 64;

    /** ts (0..1) rounded to 1/QSTEPS; 0 means no halo. */
    public static int quantTs(float ts) {
        if (!(ts > 0f)) return 0;
        return Math.min(QSTEPS, Math.round(ts * QSTEPS));
    }

    /**
     * 256-entry table: mask alpha m (0..255) -> alpha of `passes` stacked draws of ts*m, 1-(1-ts*m)^passes, as 0..255.
     * Baking this into the sprite lets Halo draw each sprite once instead of `passes` times.
     */
    public static byte[] compoundLut(int q, int passes) {
        byte[] lut = new byte[256];
        float ts = Math.max(0f, Math.min(1f, q / (float) QSTEPS));
        for (int m = 0; m < 256; m++) {
            float a = ts * (m / 255f);
            float keep = 1f;
            for (int i = 0; i < passes; i++) keep *= 1f - a;
            lut[m] = (byte) Math.round(255f * (1f - keep));
        }
        return lut;
    }
}
