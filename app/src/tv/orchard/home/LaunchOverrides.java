package tv.orchard.home;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * PURE JAVA (host-tested). Packages whose default launch intent lands in the wrong place, mapped to the activity
 * that opens the right screen. AppCatalog tries this component first and falls back to the default intent when
 * it cannot be resolved, so a future Fire OS update cannot leave a dead tile.
 * Amazon Appstore: its only MAIN/LAUNCHER activity (grid.AppsGridLauncherActivity) bounces to Amazon's own home
 * (HomeActivity_vNext), which Home on Fire redirects back to Orchard; ade.ADEHomeActivity opens the Appstore UI.
 */
public final class LaunchOverrides {
    private LaunchOverrides() {}

    public static final String APPSTORE_PACKAGE = "com.amazon.venezia";
    public static final String APPSTORE_ACTIVITY = "com.amazon.venezia.ade.ADEHomeActivity";

    private static final Map<String, String> ACTIVITY = new HashMap<String, String>();
    static { ACTIVITY.put(APPSTORE_PACKAGE, APPSTORE_ACTIVITY); }

    /** Fully qualified activity class to start for pkg, or null when the default intent is right. */
    public static String activityFor(String pkg) {
        return pkg == null ? null : ACTIVITY.get(pkg);
    }

    /** Read-only view (tests). */
    public static Map<String, String> all() { return Collections.unmodifiableMap(ACTIVITY); }
}
