package tv.orchard.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** PURE JAVA (no android.* imports; host-tested). Order, dock and hidden list rules for AppCatalog. */
public final class CatalogOrder {
    private CatalogOrder() {}

    /** Home on Fire: hidden until the config says otherwise. */
    public static final String[] DEFAULT_HIDDEN = {"io.github.toolicious.homeonfire"};
    public static final int DOCK_MAX = 6;

    /** Split on ',', trim, drop empties and duplicates (first wins). null gives an empty list. */
    public static List<String> parseList(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String t = part.trim();
                if (!t.isEmpty()) out.add(t);
            }
        }
        return new ArrayList<String>(out);
    }

    public static String joinList(List<String> list) {
        StringBuilder sb = new StringBuilder();
        if (list != null) {
            for (String s : list) {
                if (sb.length() > 0) sb.append(',');
                sb.append(s);
            }
        }
        return sb.toString();
    }

    /** raw == null (key absent) gives the defaults; an explicit empty string means nothing is hidden. */
    public static Set<String> hidden(String raw) {
        if (raw == null) return new LinkedHashSet<String>(Arrays.asList(DEFAULT_HIDDEN));
        return new LinkedHashSet<String>(parseList(raw));
    }

    /** Saved entries that are installed (saved order), then installed entries not saved (given order). */
    public static List<String> order(List<String> saved, List<String> installedInAppendOrder) {
        LinkedHashSet<String> installed = new LinkedHashSet<String>();
        if (installedInAppendOrder != null) installed.addAll(installedInAppendOrder);
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        if (saved != null) {
            for (String s : saved) if (installed.contains(s)) out.add(s);
        }
        for (String s : installed) out.add(s);
        return new ArrayList<String>(out);
    }

    /**
     * The catalogue merge AppCatalog.refresh() uses: saved entries still installed (saved order), then the new
     * installs in appendOrder. installed = every package the scan found (saved or not).
     */
    public static List<String> merge(List<String> saved, java.util.Collection<String> installed, List<String> appendOrder) {
        List<String> all = new ArrayList<String>();
        if (saved != null && installed != null) {
            for (String s : saved) if (installed.contains(s)) all.add(s);
        }
        if (appendOrder != null) all.addAll(appendOrder);
        return order(saved, all);
    }

    /**
     * Default dock when no dock key is saved (P4 review): Netflix, YouTube, Prime Video, JioHotstar, Apple TV, Spotify.
     * Each row lists alternative package names for the same service; the first installed, visible one wins.
     */
    public static final String[][] PREFERRED_DOCK = {
        {"com.netflix.ninja"},
        {"com.amazon.firetv.youtube", "com.google.android.youtube.tv"},
        {"com.amazon.avod", "com.amazon.avod.thirdpartyclient"},
        {"in.startv.hotstar", "in.startv.hotstar.dplus"},
        {"com.apple.atve.amazon.appletv", "com.apple.atve.androidtv.appletv"},
        {"com.spotify.tv.android"},
    };

    /** dock(rawDock, ordered, hidden, null): no preferred seed. */
    public static List<String> dock(String rawDock, List<String> ordered, Set<String> hidden) {
        return dock(rawDock, ordered, hidden, null);
    }

    /**
     * rawDock == null: the preferred apps that are installed and visible, in preferred order; when none of them
     * is installed (or preferred is null), the first DOCK_MAX visible apps. Otherwise the listed ones that exist
     * and are not hidden.
     */
    public static List<String> dock(String rawDock, List<String> ordered, Set<String> hidden, String[][] preferred) {
        List<String> out = new ArrayList<String>();
        if (rawDock == null) {
            if (preferred != null) {
                for (String[] group : preferred) {
                    if (out.size() >= DOCK_MAX) break;
                    for (String pkg : group) {
                        if (ordered.contains(pkg) && !hidden.contains(pkg) && !out.contains(pkg)) {
                            out.add(pkg);
                            break;
                        }
                    }
                }
                if (!out.isEmpty()) return out;
            }
            for (String s : ordered) {
                if (out.size() >= DOCK_MAX) break;
                if (!hidden.contains(s)) out.add(s);
            }
            return out;
        }
        Set<String> present = new LinkedHashSet<String>(ordered);
        for (String s : parseList(rawDock)) {
            if (out.size() >= DOCK_MAX) break;
            if (present.contains(s) && !hidden.contains(s)) out.add(s);
        }
        return out;
    }

    /** ordered minus dock minus hidden, order kept. */
    public static List<String> grid(List<String> ordered, List<String> dock, Set<String> hidden) {
        List<String> out = new ArrayList<String>();
        for (String s : ordered) {
            if (!dock.contains(s) && !hidden.contains(s)) out.add(s);
        }
        return out;
    }

    // ---- edit helpers (Phase 5: dock/hidden pickers, context menu, grid move). Each returns a NEW list. ----

    /** Append pkg when absent and the dock has room (DOCK_MAX); otherwise an unchanged copy. */
    public static List<String> addToDock(List<String> dock, String pkg) {
        List<String> out = copyOf(dock);
        if (pkg == null || pkg.isEmpty() || out.contains(pkg) || out.size() >= DOCK_MAX) return out;
        out.add(pkg);
        return out;
    }

    /** Remove pkg; refused (unchanged copy) when that would leave the dock empty. */
    public static List<String> removeFromDock(List<String> dock, String pkg) {
        List<String> out = copyOf(dock);
        if (pkg == null || !out.contains(pkg) || out.size() <= 1) return out;
        out.remove(pkg);
        return out;
    }

    /** Move pkg by delta places (negative = towards the front), clamped to both ends. */
    public static List<String> moveInDock(List<String> dock, String pkg, int delta) {
        List<String> out = copyOf(dock);
        int from = pkg == null ? -1 : out.indexOf(pkg);
        if (from < 0) return out;
        long want = (long) from + delta;
        int to = (int) Math.max(0L, Math.min((long) out.size() - 1, want));
        out.remove(from);
        out.add(to, pkg);
        return out;
    }

    /** The hidden set as a list (insertion order) with pkg added (hide) or removed (!hide). */
    public static List<String> withHidden(java.util.Collection<String> hidden, String pkg, boolean hide) {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        if (hidden != null) for (String s : hidden) if (s != null) set.add(s);
        if (pkg != null && !pkg.isEmpty()) {
            if (hide) set.add(pkg); else set.remove(pkg);
        }
        return new ArrayList<String>(set);
    }

    /**
     * New FULL order where pkg sits at grid position newGridIndex (clamped to [0, grid.size()-1]) and every
     * dock/hidden entry keeps its relative place. g = grid without pkg: idx < g.size() inserts pkg immediately
     * before g.get(idx) in the order, else immediately after g.get(last) (end of order when g is empty).
     * pkg not in grid (or not in order) gives an unchanged copy.
     * New installs still append at the end of the grid: merge() puts them after the saved order, and
     * HomeActivity.layoutRows appends the Fire TV Home tile after the grid.
     */
    public static List<String> moveInGrid(List<String> order, List<String> grid, String pkg, int newGridIndex) {
        List<String> out = copyOf(order);
        if (pkg == null || grid == null || !grid.contains(pkg) || !out.contains(pkg)) return out;
        List<String> g = new ArrayList<String>(grid);
        g.remove(pkg);
        out.remove(pkg);
        int idx = Math.max(0, Math.min(grid.size() - 1, newGridIndex));
        if (g.isEmpty()) {
            out.add(pkg);
        } else if (idx < g.size()) {
            int at = out.indexOf(g.get(idx));
            if (at < 0) out.add(pkg); else out.add(at, pkg);
        } else {
            int at = out.indexOf(g.get(g.size() - 1));
            if (at < 0) out.add(pkg); else out.add(at + 1, pkg);
        }
        return out;
    }

    private static List<String> copyOf(List<String> in) {
        return in == null ? new ArrayList<String>() : new ArrayList<String>(in);
    }
}
