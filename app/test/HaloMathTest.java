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
        if (fails > 0) System.exit(1);
    }
}
