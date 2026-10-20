package tv.orchard.home;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PURE JAVA (host-tested). The am-start debug channel: on SDK 30 adb cannot touch
 * /sdcard/Android/data/tv.orchard.home (ext_data_rw), so HomeActivity applies flags itself from intent extras:
 *   am start -n tv.orchard.home/.HomeActivity --es orchard.debug "debug_card=banner;debug_motion=reduced"
 *   am start -n tv.orchard.home/.HomeActivity --ez orchard.debug_clear true
 * Only keys starting with "debug_" are ever written or removed. crashguard_strip and every other setting key
 * are refused here, so the exported activity cannot be used to arm the crash-failsafe strip. debug_crash and crash_on_home are refused too.
 */
public final class DebugFlags {
    private DebugFlags() {}

    public static final String ACTION_SET_KEY = "set";       // am broadcast -n tv.orchard.home/.DebugReceiver --es set "k=v;k=v"
    public static final String ACTION_UNSET_KEY = "unset";   //                                               --es unset "k;k"

    public static final String EXTRA_SET = "orchard.debug";
    public static final String EXTRA_CLEAR = "orchard.debug_clear";
    private static final String PREFIX = "debug_";

    /**
     * Keys that arm crash behaviour (debug_crash enables the crash/hang extras, crash_on_home is the R6c flag).
     * They may only come from orchard.conf, never from this exported channel (set or clear).
     */
    private static boolean refused(String k) {
        return k.equals(Config.Keys.DEBUG_CRASH) || k.equals(Config.Keys.CRASH_ON_HOME);
    }

    /** Applies clear (first) then spec ("k=v;k=v") to cfg and saves when anything changed. Returns the number of changes. */
    public static int apply(Config cfg, String spec, boolean clear) throws IOException {
        int n = 0;
        if (clear) {
            for (String k : debugKeys(cfg)) {
                if (refused(k)) continue;
                cfg.remove(k);
                n++;
            }
        }
        if (spec != null) {
            for (String part : spec.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                if (!k.startsWith(PREFIX) || k.length() == PREFIX.length() || v.isEmpty() || refused(k)) continue;
                if (!v.equals(cfg.get(k, null))) {
                    cfg.set(k, v);
                    n++;
                }
            }
        }
        if (n > 0) cfg.save();
        return n;
    }

    /**
     * The shell-only channel (DebugReceiver, guarded by android.permission.DUMP, so only adb shell/system can call it).
     * Unlike apply() it may also set the crash-failsafe keys: debug_*, crash_on_home (1 or 0) and crashguard_strip
     * (on or off). Anything else is skipped. {@code set} is "k=v;k=v", {@code unset} is "k;k". Unset runs first.
     * Returns the number of changes; saves when it is above 0.
     */
    public static int applyShell(Config cfg, String set, String unset) throws IOException {
        int n = 0;
        if (unset != null) {
            for (String part : unset.split(";")) {
                String k = part.trim();
                if (!shellKey(k) || cfg.get(k, null) == null) continue;
                cfg.remove(k);
                n++;
            }
        }
        if (set != null) {
            for (String part : set.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String k = part.substring(0, eq).trim();
                String v = part.substring(eq + 1).trim();
                if (!shellKey(k) || v.isEmpty() || !shellValue(k, v)) continue;
                if (!v.equals(cfg.get(k, null))) {
                    cfg.set(k, v);
                    n++;
                }
            }
        }
        if (n > 0) cfg.save();
        return n;
    }

    /** Value of debug_art that swaps every banner/icon for a letter plate (logo-free screenshots). */
    public static final String ART_PLACEHOLDER = "placeholder";

    /** True when debug_art=placeholder: ArtCache draws letter plates (first letter, app colour) instead of real art. */
    public static boolean placeholderArt(Config cfg) {
        return cfg != null && ART_PLACEHOLDER.equals(cfg.get(Config.Keys.DEBUG_ART, "").trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean shellKey(String k) {
        return (k.startsWith(PREFIX) && k.length() > PREFIX.length())
            || k.equals(Config.Keys.CRASH_ON_HOME) || k.equals(Config.Keys.CRASHGUARD_STRIP);
    }

    private static boolean shellValue(String k, String v) {
        if (k.equals(Config.Keys.CRASHGUARD_STRIP)) return v.equals("on") || v.equals("off");
        if (k.equals(Config.Keys.CRASH_ON_HOME)) return v.equals("1") || v.equals("0");
        return true;
    }

    private static List<String> debugKeys(Config cfg) {
        List<String> out = new ArrayList<String>();
        for (String k : Config.parse(Config.serialize(cfg.snapshot())).keySet()) {
            if (k.startsWith(PREFIX)) out.add(k);
        }
        return out;
    }
}
