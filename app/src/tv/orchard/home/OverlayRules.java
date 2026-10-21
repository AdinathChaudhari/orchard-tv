package tv.orchard.home;

/** PURE JAVA (no android.* imports; host-tested). Small layout and memory decisions with no Android in them. */
public final class OverlayRules {
    private OverlayRules() {}

    /** Status pill is hidden while an overlay is open AND its right edge reaches the overlay's left edge (minus gap). */
    public static boolean hideChip(boolean overlayOpen, float chipRight, float overlayLeft, float gap) {
        return overlayOpen && chipRight + gap > overlayLeft;
    }

    /** An item spanning [left, right] is hidden while an overlay spanning [ovLeft, ovRight] is open and they come within gap. */
    public static boolean overlaps(boolean overlayOpen, float left, float right, float ovLeft, float ovRight, float gap) {
        return overlayOpen && right + gap > ovLeft && left - gap < ovRight;
    }

    /** Sheet min height: the design minimum only when a keeps line is shown; otherwise 0 (size to content). */
    public static int sheetMinHeight(boolean hasKeeps, int designMin) { return hasKeeps ? designMin : 0; }

    /** A phase jump big enough to be worth dropping the baked caches (circular minute-of-day distance). */
    public static boolean largePhaseJump(int prevMinute, int newMinute, int thresholdMin) {
        if (prevMinute < 0 || newMinute < 0) return false;
        int d = Math.abs(prevMinute - newMinute) % 1440;
        return Math.min(d, 1440 - d) >= thresholdMin;
    }

    /**
     * Dusk / dawn pre-composite (TopShelfView.bakeMix) may stand in for the dark + light bakes only when no mix animation
     * runs, the live alphas equal the targets, and BOTH are visible; otherwise a single (or animated) bake path draws.
     */
    public static boolean useMixBake(boolean animating, float darkA, float lightA, float tDarkA, float tLightA, float eps) {
        return !animating && Math.abs(darkA - tDarkA) < eps && Math.abs(lightA - tLightA) < eps
                && darkA > eps && lightA > eps;
    }

    /** True when the label ink's backdrop has been sampled for this sky generation (re-sample when the sky changes). */
    public static boolean needResample(long sampledGen, long skyGen) { return sampledGen != skyGen; }
}
