package tv.orchard.home;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * PURE JAVA (host-tested). Which catalogue apps are hidden. Default: show apps the user installed and a short
 * allow-list of useful system apps; every other system app is hidden (but still listed in Settings, Hidden apps).
 * The user's explicit choice always wins: explicit hide (config "hidden") beats everything, explicit show
 * (config "shown") beats the automatic rule.
 */
public final class HiddenRules {
    private HiddenRules() {}

    /** android.content.pm.ApplicationInfo.FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP (mirrored so this class stays pure). */
    public static final int FLAG_SYSTEM = 1;
    public static final int FLAG_UPDATED_SYSTEM_APP = 1 << 7;

    /** System apps worth showing by default (YouTube; Prime Video is com.amazon.firebat on the Stick 4K Plus, avod on older models). */
    public static final Set<String> SYSTEM_ALLOW = new HashSet<String>(Arrays.asList(
            "com.amazon.firetv.youtube", "com.amazon.avod", "com.amazon.firebat"));

    public static boolean isUserApp(int appFlags) { return (appFlags & FLAG_SYSTEM) == 0; }

    /**
     * @param explicitHidden packages the user hid (config "hidden"; CatalogOrder.hidden(raw) gives the defaults
     *                       Home on Fire while the key is absent)
     * @param explicitShown  packages the user unhid (config "shown")
     */
    public static boolean isHidden(String pkg, int appFlags, Set<String> explicitHidden, Set<String> explicitShown) {
        if (pkg == null) return true;
        if (explicitHidden != null && explicitHidden.contains(pkg)) return true;
        if (explicitShown != null && explicitShown.contains(pkg)) return false;
        if (isUserApp(appFlags)) return false;
        return !SYSTEM_ALLOW.contains(pkg);   // system app (updated or not): shown only when allow-listed
    }
}
