package tv.orchard.home;

import java.util.ArrayList;
import java.util.List;

/**
 * PURE JAVA (no android.* imports; host-tested). Strip/append Home on Fire's accessibility
 * service ids in a ':'-separated enabled_accessibility_services list. See PLAN section 2.1.
 */
public final class AccessibilityList {
    public static final String[] HOF_IDS = {
        "io.github.toolicious.homeonfire/.HijackService",
        "io.github.toolicious.homeonfire/io.github.toolicious.homeonfire.HijackService",
    };

    private AccessibilityList() {}

    private static boolean isHof(String id) {
        for (String h : HOF_IDS) if (h.equals(id)) return true;
        return false;
    }

    /** Split on ':', trim, drop "" and "null" and duplicates (first occurrence wins). null -> empty list. */
    private static List<String> tokens(String list) {
        List<String> out = new ArrayList<String>();
        if (list == null) return out;
        for (String raw : list.split(":")) {
            String t = raw.trim();
            if (t.isEmpty() || t.equals("null")) continue;
            if (!out.contains(t)) out.add(t);
        }
        return out;
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String s : items) {
            if (sb.length() > 0) sb.append(':');
            sb.append(s);
        }
        return sb.toString();
    }

    /** Drop "", "null", both HoF id forms and duplicates; keep the other services in order. */
    public static String strip(String list) {
        List<String> kept = new ArrayList<String>();
        for (String t : tokens(list)) if (!isHof(t)) kept.add(t);
        return join(kept);
    }

    /** strip(list) then append HOF_IDS[0] (never twice). */
    public static String append(String list) {
        List<String> kept = new ArrayList<String>();
        for (String t : tokens(list)) if (!isHof(t)) kept.add(t);
        kept.add(HOF_IDS[0]);
        return join(kept);
    }

    /** True if any HoF id is present (same normalisation as strip). */
    public static boolean contains(String list) {
        for (String t : tokens(list)) if (isHof(t)) return true;
        return false;
    }
}
