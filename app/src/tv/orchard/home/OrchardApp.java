package tv.orchard.home;

import android.app.Application;

/** Application: installs CrashGuard, holds ArtCache. */
public class OrchardApp extends Application {
    private State state;
    private ArtCache art;
    private Config config;
    private int settingsEpoch;
    private Runnable settingsListener;
    private Runnable settingsScreenListener;

    @Override
    public void onCreate() {
        super.onCreate();
        state = new State(this);
        // ClockDream must share this process: Android 11's DreamActivity is started in the package's default process
        // and casts its binder to the in-process DreamServiceWrapper (a ":dream" process crashes it, learnings 2.5).
        // boot_attempts++ lives in HomeActivity.onCreate (PLAN 2.2): dream binds / receiver-only starts are not launches.
        CrashGuard.install(state.crashesFile(), FireTvBridge.crashListener(this));
        art = new ArtCache(this);
        DnsGuard.get(this).resume();                 // reads state.conf; starts a worker only for a persisted run
    }

    public State state() { return state; }
    /** Main thread only. SettingsActivity bumps it after each save so HomeActivity rebuilds on resume. */
    public int settingsEpoch() { return settingsEpoch; }
    public void bumpSettingsEpoch() { settingsEpoch++; }
    /** Main thread. Registered by HomeActivity; run by notifySettingsChanged(). */
    public void setSettingsListener(Runnable r) { settingsListener = r; }
    /** Main thread. Registered by a visible SettingsActivity (null when it stops): debug_time cleared or set under it. */
    public void setSettingsScreenListener(Runnable r) { settingsScreenListener = r; }
    /** Bumps the epoch and tells a live HomeActivity to re-evaluate (ArtCache placeholder, tiles, shelf) without a restart. */
    public void notifySettingsChanged() {
        settingsEpoch++;
        if (settingsListener != null) settingsListener.run();
        if (settingsScreenListener != null) settingsScreenListener.run();
    }
    public ArtCache art() { return art; }

    /** One Config over orchard.conf, shared by HomeActivity and AppCatalog (lazy; main thread). */
    public Config config() {
        if (config == null) config = new Config(FireTvBridge.settingsFile(this));
        return config;
    }
}
