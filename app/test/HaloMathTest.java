package tv.orchard.home;

public final class HaloMathTest {
    static int fails;

    static void eq(String n, float want, float got) {
        if (Math.abs(want - got) > 1e-6f) { fails++; System.err.println("FAIL " + n + ": want " + want + " got " + got); }
    }

    static void ok(String n, boolean v) {
        if (!v) { fails++; System.err.println("FAIL " + n); }
    }

    public static void main(String[] args) {
        eq("left", 100f, HaloMath.leftEdge(HaloMath.LEFT, 100f, 50f));
        eq("center", 75f, HaloMath.leftEdge(HaloMath.CENTER, 100f, 50f));
        eq("right", 50f, HaloMath.leftEdge(HaloMath.RIGHT, 100f, 50f));
        eq("pad r0", 4f, HaloMath.pad(0f));          // ceil(1.5) + 2
        ok("pad grows", HaloMath.pad(10f) > HaloMath.pad(5f));
        ok("pad covers 3 sigma", HaloMath.pad(14f) >= 3 * (0.57735f * 14f + 0.5f));
        ok("tooBig zero", HaloMath.tooBig(0, 10, 1000));
        ok("tooBig big", HaloMath.tooBig(100, 100, 1000));
        ok("not tooBig", !HaloMath.tooBig(10, 10, 1000));
        // compound alpha LUT: baked once so Halo draws each sprite once instead of HALO_PASSES times
        ok("quant zero", HaloMath.quantTs(0f) == 0 && HaloMath.quantTs(-1f) == 0 && HaloMath.quantTs(Float.NaN) == 0);
        ok("quant tiny rounds to zero", HaloMath.quantTs(0.005f) == 0);
        ok("quant 0.06", HaloMath.quantTs(0.06f) == 4);
        ok("quant cap", HaloMath.quantTs(2f) == HaloMath.QSTEPS);
        byte[] lut = HaloMath.compoundLut(32, 4);                // ts = 0.5
        ok("lut 0 -> 0", lut[0] == 0);
        double want = 255.0 * (1 - Math.pow(1 - 0.5, 4));        // m = 255: 1-(1-.5)^4 = .9375
        ok("lut full mask", Math.abs((lut[255] & 0xFF) - want) <= 1);
        double w128 = 255.0 * (1 - Math.pow(1 - 0.5 * 128 / 255.0, 4));
        ok("lut mid mask", Math.abs((lut[128] & 0xFF) - w128) <= 1);
        boolean mono = true;
        for (int i = 1; i < 256; i++) if ((lut[i] & 0xFF) < (lut[i - 1] & 0xFF)) mono = false;
        ok("lut monotonic", mono);
        ok("lut ts 0 is empty", HaloMath.compoundLut(0, 4)[255] == 0);
        ok("lut one pass = ts*m", Math.abs((HaloMath.compoundLut(64, 1)[200] & 0xFF) - 200) <= 1);
        if (fails > 0) System.exit(1);
    }
}
