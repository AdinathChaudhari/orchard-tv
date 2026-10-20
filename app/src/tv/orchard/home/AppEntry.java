package tv.orchard.home;

import android.content.ComponentName;

/** Model for one launchable app (filled by AppCatalog.refresh()). */
public final class AppEntry {
    public ComponentName component;
    public String packageName;
    public String label;
    public boolean leanback;
    public long lastOpened;
    public long firstInstallTime;
    public long lastUpdateTime;
    public long versionCode;
    public int appFlags;          // ApplicationInfo.flags (FLAG_SYSTEM etc.)

    public String key() { return packageName; }
}
