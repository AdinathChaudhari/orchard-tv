package tv.orchard.home;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The real catalogue: launchable apps, the saved grid order (config key "order", new installs appended),
 * dock and hidden lists, and a dynamically registered package receiver. Never invents entries.
 */
public final class AppCatalog {
    public interface Listener { void onCatalogChanged(); }

    private static final int DEBOUNCE_MS = 500;
    private static final int MAX_RECENT = 64;

    private final Context ctx;
    private final Config config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable debounced = this::onDebounced;
    private Listener listener;
    private PackageReceiver receiver;
    private boolean closed;

    private List<AppEntry> all = new ArrayList<AppEntry>();
    private List<AppEntry> dock = new ArrayList<AppEntry>();
    private List<AppEntry> grid = new ArrayList<AppEntry>();
    private Set<String> hidden = CatalogOrder.hidden(null);          // effective: explicit hides + automatic rule
    private Set<String> explicitHidden = CatalogOrder.hidden(null);  // what the "hidden" key holds (defaults while absent)
    private Set<String> explicitShown = new java.util.LinkedHashSet<String>();
    private final Map<String, AppEntry> byPkg = new HashMap<String, AppEntry>();

    public AppCatalog(Context ctx, Config config) {
        this.ctx = ctx;
        this.config = config;
    }

    public List<AppEntry> all() { return all; }
    public List<AppEntry> dock() { return dock; }
    public List<AppEntry> grid() { return grid; }
    public Set<String> hiddenPackages() { return hidden; }
    public AppEntry find(String pkg) { return pkg == null ? null : byPkg.get(pkg); }

    public void setListener(Listener l) {
        listener = l;
        if (l != null) start();
    }

    /** Register the package receiver (idempotent). */
    public void start() {
        if (receiver != null || closed) return;
        receiver = new PackageReceiver(this);
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        try {
            ctx.registerReceiver(receiver, f);
        } catch (RuntimeException e) {
            receiver = null;
        }
    }

    public void close() {
        main.removeCallbacks(debounced);
        listener = null;
        closed = true;
        PackageReceiver r = receiver;
        receiver = null;
        if (r != null) {
            try {
                ctx.unregisterReceiver(r);
            } catch (RuntimeException e) {
                // already unregistered
            }
        }
    }

    /** Synchronous rebuild of all(), dock() and grid(). Cheap (about 30 apps, no drawables). */
    public void refresh() {
        config.refresh();
        PackageManager pm = ctx.getPackageManager();
        String self = ctx.getPackageName();
        Map<String, AppEntry> found = new LinkedHashMap<String, AppEntry>();
        scan(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, self, found);
        scan(pm, Intent.CATEGORY_LAUNCHER, false, self, found);

        List<AppEntry> entries = new ArrayList<AppEntry>(found.values());
        for (AppEntry e : entries) fillDetails(pm, e);

        List<String> saved = CatalogOrder.parseList(config.get(Config.Keys.ORDER, null));
        List<AppEntry> append = new ArrayList<AppEntry>();
        if (saved.isEmpty()) {
            append.addAll(entries);
            Collections.sort(append, BY_LABEL);
        } else {
            for (AppEntry e : entries) if (!saved.contains(e.packageName)) append.add(e);
            Collections.sort(append, BY_INSTALL_THEN_LABEL);
        }
        List<String> appendKeys = new ArrayList<String>();
        for (AppEntry e : append) appendKeys.add(e.packageName);
        List<String> ordered = CatalogOrder.merge(saved, found.keySet(), appendKeys);   // P4 fix: order(saved, appendKeys) dropped every saved app

        String joined = CatalogOrder.joinList(ordered);
        // never persist an empty scan over a saved order (a transient PackageManager hiccup must not wipe it)
        if (!ordered.isEmpty() && !joined.equals(config.get(Config.Keys.ORDER, null))) {
            try {
                config.set(Config.Keys.ORDER, joined);
                config.save();
            } catch (IOException | RuntimeException e) {
                // keep the in-memory order; try again on the next refresh
            }
        }

        Set<String> expHidden = CatalogOrder.hidden(config.get(Config.Keys.HIDDEN, null));
        Set<String> expShown = new java.util.LinkedHashSet<String>(CatalogOrder.parseList(config.get(Config.Keys.SHOWN, null)));
        Set<String> hid = new java.util.LinkedHashSet<String>(expHidden);   // keeps explicit entries of apps not installed too
        for (AppEntry e : entries) {
            if (HiddenRules.isHidden(e.packageName, e.appFlags, expHidden, expShown)) hid.add(e.packageName);
            else hid.remove(e.packageName);
        }
        List<String> dockKeys = CatalogOrder.dock(config.get(Config.Keys.DOCK, null), ordered, hid, CatalogOrder.PREFERRED_DOCK);
        if (dockKeys.isEmpty()) {
            // an explicit DOCK whose apps are all gone: seed row 0 for this refresh only (not written)
            dockKeys = CatalogOrder.dock(null, ordered, hid, CatalogOrder.PREFERRED_DOCK);
        }
        List<String> gridKeys = CatalogOrder.grid(ordered, dockKeys, hid);

        List<AppEntry> nAll = pick(ordered, found);
        List<AppEntry> nDock = pick(dockKeys, found);
        List<AppEntry> nGrid = pick(gridKeys, found);
        dropRecentOfRemoved(found);
        byPkg.clear();
        byPkg.putAll(found);
        hidden = hid;
        explicitHidden = expHidden;
        explicitShown = expShown;
        all = nAll;
        dock = nDock;
        grid = nGrid;
    }

    private static List<AppEntry> pick(List<String> keys, Map<String, AppEntry> by) {
        List<AppEntry> out = new ArrayList<AppEntry>(keys.size());
        for (String k : keys) {
            AppEntry e = by.get(k);
            if (e != null) out.add(e);
        }
        return out;
    }

    private static void scan(PackageManager pm, String category, boolean leanback, String self,
                             Map<String, AppEntry> out) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
        if (list == null) return;
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(self) || out.containsKey(pkg)) continue;
            if (!ri.activityInfo.exported) continue;     // not startable by us (Select would do nothing): leave it out
            String perm = ri.activityInfo.permission;
            if (perm != null && pm.checkPermission(perm, self) != PackageManager.PERMISSION_GRANTED) continue;
            AppEntry e = new AppEntry();
            e.packageName = pkg;
            e.component = new ComponentName(pkg, ri.activityInfo.name);
            e.leanback = leanback;
            CharSequence lbl = null;
            try {
                lbl = ri.loadLabel(pm);
            } catch (RuntimeException ex) {
                // fall through to the package name
            }
            e.label = lbl != null ? lbl.toString() : pkg;
            out.put(pkg, e);
        }
    }

    private void fillDetails(PackageManager pm, AppEntry e) {
        try {
            PackageInfo pi = pm.getPackageInfo(e.packageName, 0);
            e.firstInstallTime = pi.firstInstallTime;
            e.lastUpdateTime = pi.lastUpdateTime;
            e.versionCode = pi.getLongVersionCode();
            e.appFlags = pi.applicationInfo != null ? pi.applicationInfo.flags : 0;
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            e.firstInstallTime = 0;
            e.lastUpdateTime = 0;
            e.versionCode = 0;
            e.appFlags = 0;
        }
        long last = 0;
        String raw = config.get(Config.Keys.RECENT_PREFIX + e.packageName, null);
        if (raw != null) {
            try {
                last = Long.parseLong(raw.trim());
            } catch (NumberFormatException ex) {
                last = 0;
            }
        }
        // an in-memory launch newer than the stored value wins (covers a failed save of recent.<pkg>)
        AppEntry old = byPkg.get(e.packageName);
        e.lastOpened = old != null && old.lastOpened > last ? old.lastOpened : last;
    }

    private static final Comparator<AppEntry> BY_LABEL = (a, b) -> {
        int c = String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
        return c != 0 ? c : a.packageName.compareTo(b.packageName);
    };
    private static final Comparator<AppEntry> BY_INSTALL_THEN_LABEL = (a, b) -> {
        int c = Long.compare(a.firstInstallTime, b.firstInstallTime);
        return c != 0 ? c : BY_LABEL.compare(a, b);
    };

    /** Leanback launch intent, else the normal one, else an explicit MAIN component. May return null. */
    public Intent launchIntent(AppEntry e) {
        if (e == null) return null;
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(e.packageName);
        if (i == null) i = pm.getLaunchIntentForPackage(e.packageName);
        if (i == null && e.component != null) {
            i = new Intent(Intent.ACTION_MAIN).setComponent(e.component);
        }
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    /**
     * Records a launch: lastOpened = now, persisted as recent.<pkg> = epoch ms (atomic save). Call on the main
     * thread after a successful startActivity. On a failed save the in-memory value stays and wins in the next
     * refresh() merge. The file is a few hundred bytes, so the save is synchronous (Config is not thread-safe
     * across our own unsynchronised callers, and this keeps it single-threaded).
     */
    public void recordLaunch(AppEntry e) {
        if (e == null || e.packageName == null) return;
        long t = System.currentTimeMillis();
        e.lastOpened = t;
        AppEntry known = byPkg.get(e.packageName);
        if (known != null && known != e) known.lastOpened = t;
        try {
            config.set(Config.Keys.RECENT_PREFIX + e.packageName, String.valueOf(t));
            pruneRecent();
            config.save();
        } catch (IOException | RuntimeException ex) {
            // keep the in-memory value; the next successful save carries it
        }
    }

    /** Keep at most MAX_RECENT recent.* keys (installed packages): drop the oldest stored values. */
    private void pruneRecent() {
        List<AppEntry> stored = new ArrayList<AppEntry>();
        for (AppEntry a : byPkg.values()) {
            if (config.get(Config.Keys.RECENT_PREFIX + a.packageName, null) != null) stored.add(a);
        }
        int extra = stored.size() - MAX_RECENT;
        if (extra <= 0) return;
        Collections.sort(stored, (a, b) -> Long.compare(a.lastOpened, b.lastOpened));
        for (int i = 0; i < extra; i++) config.remove(Config.Keys.RECENT_PREFIX + stored.get(i).packageName);
    }

    /** Packages that vanished since the last refresh lose their recent.* key (skipped for an empty scan). */
    private void dropRecentOfRemoved(Map<String, AppEntry> found) {
        if (found.isEmpty() || byPkg.isEmpty()) return;
        boolean dirty = false;
        for (String pkg : byPkg.keySet()) {
            if (found.containsKey(pkg)) continue;
            String key = Config.Keys.RECENT_PREFIX + pkg;
            if (config.get(key, null) != null) {
                config.remove(key);
                dirty = true;
            }
        }
        if (!dirty) return;
        try {
            config.save();
        } catch (IOException | RuntimeException ex) {
            // retried implicitly by the next save
        }
    }

    // ---- Phase 5 hooks (no UI yet) ----------------------------------------------------------------
    public void saveOrder(List<String> pkgs) { writeList(Config.Keys.ORDER, pkgs); }
    public void setDock(List<String> pkgs) { writeList(Config.Keys.DOCK, pkgs); }
    public void setHidden(List<String> pkgs) { writeList(Config.Keys.HIDDEN, pkgs); }

    /** Single-key write: one save, one refresh, one onCatalogChanged. */
    private void writeList(String key, List<String> pkgs) {
        write(key, pkgs, null, null);
    }

    /** Writes up to two explicit list keys with ONE config.save(), then refresh() and onCatalogChanged() once. */
    private void write(String k1, List<String> v1, String k2, List<String> v2) {
        write3(k1, v1, k2, v2, null, null);
    }

    private void write3(String k1, List<String> v1, String k2, List<String> v2, String k3, List<String> v3) {
        try {
            config.set(k1, CatalogOrder.joinList(CatalogOrder.parseList(CatalogOrder.joinList(v1))));
            if (k2 != null) config.set(k2, CatalogOrder.joinList(CatalogOrder.parseList(CatalogOrder.joinList(v2))));
            if (k3 != null) config.set(k3, CatalogOrder.joinList(CatalogOrder.parseList(CatalogOrder.joinList(v3))));
            config.save();
        } catch (IOException | RuntimeException e) {
            // in-memory value still applies for this refresh
        }
        refresh();
        Listener l = listener;
        if (l != null) l.onCatalogChanged();
    }

    // ---- Phase 5 edit helpers (main thread; computed from the current effective lists) ----------------------
    private static List<String> keys(List<AppEntry> entries) {
        List<String> out = new ArrayList<String>(entries.size());
        for (AppEntry e : entries) out.add(e.packageName);
        return out;
    }

    /** Effective dock (the computed default when the DOCK key is absent). */
    public List<String> dockPackages() { return keys(dock); }
    public List<String> gridPackages() { return keys(grid); }
    /** Full catalogue order: dock, hidden and grid apps together. */
    public List<String> orderPackages() { return keys(all); }
    public boolean isDockFull() { return dock.size() >= CatalogOrder.DOCK_MAX; }

    /** Appends pkg to the dock when there is room. Visible installed apps only. */
    public boolean addToDock(String pkg) {
        if (pkg == null || !byPkg.containsKey(pkg) || hidden.contains(pkg)) return false;
        List<String> cur = dockPackages();
        List<String> next = CatalogOrder.addToDock(cur, pkg);
        if (next.equals(cur)) return false;
        write(Config.Keys.DOCK, next, null, null);
        return true;
    }

    /** Removes pkg from the dock; refused when it is the only dock app. */
    public boolean removeFromDock(String pkg) {
        List<String> cur = dockPackages();
        List<String> next = CatalogOrder.removeFromDock(cur, pkg);
        if (next.equals(cur)) return false;
        write(Config.Keys.DOCK, next, null, null);
        return true;
    }

    public boolean moveInDock(String pkg, int delta) {
        List<String> cur = dockPackages();
        List<String> next = CatalogOrder.moveInDock(cur, pkg, delta);
        if (next.equals(cur)) return false;
        write(Config.Keys.DOCK, next, null, null);
        return true;
    }

    /**
     * Hides pkg (HIDDEN starts from hiddenPackages(), defaults included) and drops it from the dock in the same
     * save. Refused when pkg is the only dock app or the last visible app.
     */
    public boolean canHide(String pkg) {
        if (pkg == null || !byPkg.containsKey(pkg) || hidden.contains(pkg)) return false;
        List<String> d = dockPackages();
        if (d.contains(pkg) && d.size() <= 1) return false;
        int visible = 0;
        for (AppEntry e : all) if (!hidden.contains(e.packageName)) visible++;
        return visible > 1;
    }

    public boolean hide(String pkg) {
        if (!canHide(pkg)) return false;
        List<String> d = dockPackages();
        List<String> nextHidden = CatalogOrder.withHidden(explicitHidden, pkg, true);
        write3(Config.Keys.HIDDEN, nextHidden, Config.Keys.DOCK, CatalogOrder.removeFromDock(d, pkg),
                Config.Keys.SHOWN, CatalogOrder.withHidden(explicitShown, pkg, false));
        return true;
    }

    public boolean unhide(String pkg) {
        if (pkg == null || !hidden.contains(pkg)) return false;
        write3(Config.Keys.HIDDEN, CatalogOrder.withHidden(explicitHidden, pkg, false), null, null,
                Config.Keys.SHOWN, CatalogOrder.withHidden(explicitShown, pkg, true));
        return true;
    }

    /** Moves pkg to grid position newGridIndex (clamped) and writes ORDER. */
    public boolean moveInGrid(String pkg, int newGridIndex) {
        List<String> cur = orderPackages();
        List<String> next = CatalogOrder.moveInGrid(cur, gridPackages(), pkg, newGridIndex);
        if (next.equals(cur)) return false;
        write(Config.Keys.ORDER, next, null, null);
        return true;
    }

    // ---- package changes ----------------------------------------------------------------------------
    private void onPackageEvent(String pkg) {
        if (pkg != null) {
            Context app = ctx.getApplicationContext();
            if (app instanceof OrchardApp) {
                ArtCache art = ((OrchardApp) app).art();
                if (art != null) art.invalidate(pkg);
            }
        }
        main.removeCallbacks(debounced);
        main.postDelayed(debounced, DEBOUNCE_MS);
    }

    private void onDebounced() {
        if (closed) return;
        refresh();
        Listener l = listener;
        if (l != null) l.onCatalogChanged();
    }

    private static final class PackageReceiver extends BroadcastReceiver {
        private final AppCatalog owner;

        PackageReceiver(AppCatalog owner) { this.owner = owner; }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
            owner.onPackageEvent(pkg);
        }
    }
}
