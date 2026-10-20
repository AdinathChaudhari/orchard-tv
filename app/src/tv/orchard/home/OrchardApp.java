package tv.orchard.home;

import android.app.Application;

/** Application: boot_attempts++ first line, installs CrashGuard, holds ArtCache. */
public class OrchardApp extends Application {
    private State state;
    private ArtCache art;
    private Config config;
    private int settingsEpoch;
    private Runnable settingsListener;

    @Override
    public void onCreate() {
        super.onCreate();
        state = new State(this);
        state.incrementBootAttempts();           // PLAN 2.2: first thing, before anything can fail
        CrashGuard.install(state.crashesFile(), FireTvBridge.crashListener(this));
        art = new ArtCache(this);
    }

    public State state() { return state; }
    /** Main thread only. SettingsActivity bumps it after each save so HomeActivity rebuilds on resume. */
    public int settingsEpoch() { return settingsEpoch; }
    public void bumpSettingsEpoch() { settingsEpoch++; }
    /** Main thread. Registered by HomeActivity; run by notifySettingsChanged(). */
    public void setSettingsListener(Runnable r) { settingsListener = r; }
    /** Bumps the epoch and tells a live HomeActivity to re-evaluate (ArtCache placeholder, tiles, shelf) without a restart. */
    public void notifySettingsChanged() {
        settingsEpoch++;
        if (settingsListener != null) settingsListener.run();
    }
    public ArtCache art() { return art; }

    /** One Config over orchard.conf, shared by HomeActivity and AppCatalog (lazy; main thread). */
    public Config config() {
        if (config == null) config = new Config(FireTvBridge.settingsFile(this));
        return config;
    }
}
