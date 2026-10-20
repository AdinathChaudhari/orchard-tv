package tv.orchard.home;

/**
 * PURE JAVA (host-tested). Decides whether the "Redirect may be off in Home on Fire" chip is warranted.
 * Home on Fire's own target is private, so the evidence is: Orchard has been launched as HOME before (so it was
 * HoF's target at some point), yet since boot a Home press was seen and no HOME launch followed it. While
 * another launcher is the target, Orchard is never launched as HOME and the chip stays off (no false positive).
 */
public final class RedirectCheck {
    private RedirectCheck() {}

    /**
     * @param redirectOn     HoF's accessibility service is in the enabled list
     * @param lastHomeLaunch epoch ms of the last CATEGORY_HOME launch of Orchard, 0 = never
     * @param pressSeen      epoch ms of the last Home press seen by onUserLeaveHint, 0 = never
     * @param bootAt         epoch ms of the last boot
     */
    public static boolean doubtful(boolean redirectOn, long lastHomeLaunch, long pressSeen, long bootAt) {
        if (!redirectOn) return false;
        if (lastHomeLaunch <= 0L) return false;          // Orchard was never HoF's target: nothing to doubt
        return lastHomeLaunch < bootAt && pressSeen >= bootAt;
    }
}
