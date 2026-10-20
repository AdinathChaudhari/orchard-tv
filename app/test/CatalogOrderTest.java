package tv.orchard.home;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class CatalogOrderTest {
    static int fails;

    static void eq(String name, String want, Object got) {
        String g = String.valueOf(got);
        if (!want.equals(g)) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + g); }
    }
    static List<String> l(String... s) { return Arrays.asList(s); }

    public static void main(String[] args) {
        eq("parse null", "[]", CatalogOrder.parseList(null));
        eq("parse empty", "[]", CatalogOrder.parseList(""));
        eq("parse spaces", "[a, b]", CatalogOrder.parseList(" a , ,b ,"));
        eq("parse dups", "[a, b, c]", CatalogOrder.parseList("a,b,a,c,b"));
        eq("join", "a,b,c", CatalogOrder.joinList(l("a", "b", "c")));
        eq("join empty", "", CatalogOrder.joinList(l()));

        eq("hidden null = defaults", "[io.github.toolicious.homeonfire]", CatalogOrder.hidden(null));
        eq("hidden empty = none", "[]", CatalogOrder.hidden(""));
        eq("hidden explicit", "[a, b]", CatalogOrder.hidden("a,b"));

        eq("order empty saved", "[x, y, z]", CatalogOrder.order(l(), l("x", "y", "z")));
        eq("order saved + new", "[c, a, b, d]", CatalogOrder.order(l("c", "a"), l("a", "b", "c", "d")));
        eq("order drops uninstalled", "[c, a, b]", CatalogOrder.order(l("c", "gone", "a"), l("a", "b", "c")));
        eq("order appends new install", "[b, a, n]", CatalogOrder.order(l("b", "a"), l("a", "b", "n")));
        // merge = AppCatalog.refresh(): a saved order with no new installs must keep every installed app
        eq("merge saved, nothing new", "[b, a]", CatalogOrder.merge(l("b", "a"), l("a", "b"), l()));
        eq("merge saved + new", "[b, a, n]", CatalogOrder.merge(l("b", "a"), l("a", "n", "b"), l("n")));
        eq("merge drops uninstalled", "[a, n]", CatalogOrder.merge(l("gone", "a"), l("a", "n"), l("n")));
        eq("merge empty saved", "[x, y]", CatalogOrder.merge(l(), l("y", "x"), l("x", "y")));
        eq("order no dups", "[a, b]", CatalogOrder.order(l("a", "a", "b"), l("a", "b", "b", "a")));

        Set<String> none = CatalogOrder.hidden("");
        List<String> ord = l("a", "b", "c", "d", "e", "f", "g", "h");
        eq("dock null first 6", "[a, b, c, d, e, f]", CatalogOrder.dock(null, ord, none));
        Set<String> hb = CatalogOrder.hidden("b");
        eq("dock null skips hidden", "[a, c, d, e, f, g]", CatalogOrder.dock(null, ord, hb));
        eq("dock filters uninstalled+hidden", "[h, a]", CatalogOrder.dock("h,zzz,b,a", ord, hb));
        eq("dock truncates", "[a, b, c, d, e, f]", CatalogOrder.dock("a,b,c,d,e,f,g,h", ord, none));
        eq("dock empty raw", "[]", CatalogOrder.dock("", ord, none));
        List<String> dk = CatalogOrder.dock("a,c", ord, hb);
        eq("grid excludes dock+hidden", "[d, e, f, g, h]", CatalogOrder.grid(ord, CatalogOrder.dock("a,c", ord, hb), hb));
        eq("grid keeps order", "[a, c, d]", CatalogOrder.grid(l("a", "b", "c", "d"), l("b"), none));
        String[][] pref = {{"x1", "x2"}, {"y"}, {"zz"}};
        List<String> po = java.util.Arrays.asList("a", "x2", "y", "b");
        eq("preferred seed picks installed, alternates, order", "[x2, y]", CatalogOrder.dock(null, po, none, pref));
        eq("preferred seed skips hidden", "[y]", CatalogOrder.dock(null, po, new java.util.HashSet<String>(java.util.Arrays.asList("x2")), pref));
        eq("preferred none installed falls back", "[a, b]", CatalogOrder.dock(null, java.util.Arrays.asList("a", "b"), none, pref));
        eq("preferred ignored when dock saved", "[a]", CatalogOrder.dock("a", po, none, pref));
        eq("preferred ignored when dock saved empty", "[]", CatalogOrder.dock("", po, none, pref));
        eq("dock sanity", "[a, c]", dk);

        List<String> withApps = l("x", "io.github.toolicious.homeonfire", "y");
        Set<String> def = CatalogOrder.hidden(null);
        List<String> d2 = CatalogOrder.dock(null, withApps, def);
        List<String> g2 = CatalogOrder.grid(withApps, d2, def);
        eq("default hidden not in dock", "[x, y]", d2);
        eq("default hidden not in grid", "[]", g2);
        List<String> d3 = CatalogOrder.dock("x", withApps, def);
        eq("hidden never docked even if listed", "[x]", d3);
        eq("hidden not in grid", "[y]", CatalogOrder.grid(withApps, d3, def));

        // ---- edit helpers ----
        eq("addToDock appends", "[a, b, c]", CatalogOrder.addToDock(l("a", "b"), "c"));
        eq("addToDock no dup", "[a, b]", CatalogOrder.addToDock(l("a", "b"), "a"));
        eq("addToDock full refused", "[a, b, c, d, e, f]", CatalogOrder.addToDock(l("a", "b", "c", "d", "e", "f"), "g"));
        eq("addToDock fills to 6", "[a, b, c, d, e, g]", CatalogOrder.addToDock(l("a", "b", "c", "d", "e"), "g"));
        eq("addToDock null pkg", "[a]", CatalogOrder.addToDock(l("a"), null));
        List<String> frozen = l("a", "b");
        CatalogOrder.addToDock(frozen, "c");
        eq("addToDock no mutate", "[a, b]", frozen);
        eq("removeFromDock normal", "[a, c]", CatalogOrder.removeFromDock(l("a", "b", "c"), "b"));
        eq("removeFromDock last refused", "[a]", CatalogOrder.removeFromDock(l("a"), "a"));
        eq("removeFromDock unknown", "[a, b]", CatalogOrder.removeFromDock(l("a", "b"), "zz"));
        eq("moveInDock -1 at 0 clamps", "[a, b, c]", CatalogOrder.moveInDock(l("a", "b", "c"), "a", -1));
        eq("moveInDock +1 middle", "[a, c, b]", CatalogOrder.moveInDock(l("a", "b", "c"), "b", 1));
        eq("moveInDock -1 middle", "[b, a, c]", CatalogOrder.moveInDock(l("a", "b", "c"), "b", -1));
        eq("moveInDock +9 clamps", "[b, c, a]", CatalogOrder.moveInDock(l("a", "b", "c"), "a", 9));
        eq("moveInDock unknown", "[a, b]", CatalogOrder.moveInDock(l("a", "b"), "zz", 1));
        eq("withHidden add", "[x, y]", CatalogOrder.withHidden(l("x"), "y", true));
        eq("withHidden remove", "[x]", CatalogOrder.withHidden(l("x", "y"), "y", false));
        eq("withHidden add idempotent", "[x, y]", CatalogOrder.withHidden(l("x", "y"), "y", true));
        eq("withHidden remove absent", "[x]", CatalogOrder.withHidden(l("x"), "q", false));
        eq("withHidden from defaults", "[io.github.toolicious.homeonfire, n]",
                CatalogOrder.withHidden(CatalogOrder.hidden(null), "n", true));

        List<String> go = l("a", "b", "c", "d");
        eq("moveInGrid forward", "[b, c, a, d]", CatalogOrder.moveInGrid(go, go, "a", 2));
        eq("moveInGrid backward", "[a, d, b, c]", CatalogOrder.moveInGrid(go, go, "d", 1));
        eq("moveInGrid to 0", "[c, a, b, d]", CatalogOrder.moveInGrid(go, go, "c", 0));
        eq("moveInGrid negative clamps", "[c, a, b, d]", CatalogOrder.moveInGrid(go, go, "c", -5));
        eq("moveInGrid past end clamps", "[b, c, d, a]", CatalogOrder.moveInGrid(go, go, "a", 99));
        eq("moveInGrid same place", "[a, b, c, d]", CatalogOrder.moveInGrid(go, go, "b", 1));
        List<String> mixOrder = l("D1", "a", "H1", "b", "D2", "c", "H2", "d");
        List<String> mixGrid = l("a", "b", "c", "d");
        eq("moveInGrid dock/hidden stay put (forward)", "[D1, H1, b, D2, c, H2, a, d]", CatalogOrder.moveInGrid(mixOrder, mixGrid, "a", 2));
        eq("moveInGrid dock/hidden stay put (backward)", "[D1, d, a, H1, b, D2, c, H2]", CatalogOrder.moveInGrid(mixOrder, mixGrid, "d", 0));
        eq("moveInGrid to end after interleaved", "[D1, H1, b, D2, c, H2, d, a]", CatalogOrder.moveInGrid(mixOrder, mixGrid, "a", 3));
        eq("moveInGrid pkg not in grid", "[D1, a, H1, b, D2, c, H2, d]", CatalogOrder.moveInGrid(mixOrder, mixGrid, "D1", 2));
        eq("moveInGrid only one grid app", "[D1, H1, a]", CatalogOrder.moveInGrid(l("D1", "a", "H1"), l("a"), "a", 3));
        // 5.2c: new installs append at the end of the grid, after the user's moved order. (The Fire TV Home tile is
        // appended after the grid by HomeActivity.layoutRows; it is not part of the catalogue order.)
        List<String> moved = CatalogOrder.moveInGrid(go, go, "d", 0);
        eq("merge after move: new last, moved order kept", "[d, a, b, c, new]",
                CatalogOrder.merge(moved, l("a", "b", "c", "d", "new"), l("new")));

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
