package tv.orchard.home;

import android.content.Context;
import java.io.File;
import java.io.IOException;

/** Runtime state in getFilesDir()/state.conf (same parser as Config). One instance per process (OrchardApp.state()). */
public final class State implements DebugNonce.Store {
    private final Config conf;
    private final File crashes;

    public State(Context ctx) {
        File dir = ctx.getFilesDir();
        conf = new Config(new File(dir, "state.conf"));
        crashes = new File(dir, "crashes");
    }

    public File crashesFile() { return crashes; }
    @Override
    public String get(String key, String def) { return conf.get(key, def); }
    public int getInt(String key, int def) { return conf.getInt(key, def); }

    public long getLong(String key, long def) {
        String v = conf.get(key, null);
        if (v == null) return def;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** In memory until commit(). */
    public void set(String key, String value) { conf.set(key, value); }

    /** set() + commit() in one call. */
    @Override
    public void put(String key, String value) {
        conf.set(key, value);
        commit();
    }

    /** Reload from disk if another writer changed the file. Unsaved set() calls are discarded. */
    public boolean refresh() { return conf.refresh(); }

    /** Synchronous write; safe to call from a dying process (never throws). */
    public void commit() {
        try {
            conf.save();
        } catch (IOException e) {
            // nothing sensible to do while dying
        } catch (RuntimeException e) {
            // same
        }
    }

    /** boot_attempts++ with a synchronous write; returns the new value. */
    public int incrementBootAttempts() {
        conf.refresh();
        int n = Math.max(0, conf.getInt(Config.Keys.BOOT_ATTEMPTS, 0)) + 1;
        conf.set(Config.Keys.BOOT_ATTEMPTS, Integer.toString(n));
        conf.set(Config.Keys.LAUNCH_VIA_HOME, "0");   // a crash before HomeActivity.onCreate must not see a stale "1"
        commit();
        return n;
    }

    /** boot_attempts = 0 with a synchronous write. */
    public void clearBootAttempts() {
        conf.set(Config.Keys.BOOT_ATTEMPTS, "0");
        commit();
    }
}
