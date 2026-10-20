package tv.orchard.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.IOException;
import java.util.Map;

/**
 * Shell-only debug channel. Declared in the manifest with android:permission="android.permission.DUMP", which adb shell and
 * system hold and ordinary apps cannot get, so unlike the exported HomeActivity it may set the crash-failsafe keys
 * (debug_crash, crash_on_home, crashguard_strip). Examples:
 *   am broadcast -n tv.orchard.home/.DebugReceiver --es set "debug_crash=1;crash_on_home=1"
 *   am broadcast -n tv.orchard.home/.DebugReceiver --es set "crashguard_strip=on"
 *   am broadcast -n tv.orchard.home/.DebugReceiver --es unset "debug_crash;crash_on_home"
 *   am broadcast -f 0x20 -n tv.orchard.home/.DebugReceiver -a tv.orchard.home.SET_CITY --es city_id bengaluru
 *   am broadcast -f 0x20 -n tv.orchard.home/.DebugReceiver -a tv.orchard.home.SET_CITY --es city_lat 51.5072 --es city_lon -0.1276 --es city_name London
 *   am broadcast -f 0x20 -n tv.orchard.home/.DebugReceiver -a tv.orchard.home.GET_CITY
 * SET_CITY result data: 1 applied, 0 unchanged, -1 rejected. GET_CITY result data: the city id, or
 * "custom:lat,lon,name" for a custom city. No manifest change is needed: the receiver is already exported with
 * android:permission DUMP, and an explicit -n broadcast needs no intent-filter.
 * The result data of the flag broadcast is the number of changes ("result=N" in am's output). A change (N > 0) also bumps
 * the settings epoch and notifies a live HomeActivity, which re-reads orchard.conf, recomputes ArtCache.setPlaceholder
 * and rebuilds tiles + shelf immediately (no force-stop). Unlike am start this form needs no nonce: a broadcast is
 * never replayed after process death.
 */
public final class DebugReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if ("tv.orchard.home.SET_CITY".equals(action)) {
            setCity(context, intent);
            return;
        }
        if ("tv.orchard.home.GET_CITY".equals(action)) {
            String d;
            try {
                CityCommand.Resolved r = Cities.current(context, sharedConfig(context));
                d = r.id != null ? r.id : "custom:" + r.lat + "," + r.lon + "," + r.name;
            } catch (IOException | RuntimeException e) {
                d = "error";
            }
            if (isOrderedBroadcast()) setResultData(d);
            return;
        }
        int n = -1;
        try {
            Config cfg = new Config(FireTvBridge.settingsFile(context));
            cfg.refresh();
            n = DebugFlags.applyShell(cfg, intent.getStringExtra(DebugFlags.ACTION_SET_KEY),
                intent.getStringExtra(DebugFlags.ACTION_UNSET_KEY));
        } catch (IOException | RuntimeException e) {
            // flags stay as they were; result stays -1
        }
        if (n > 0) {
            // Same process as HomeActivity: bump the epoch and rebind live, so no force-stop is needed to see the change.
            try {
                ((OrchardApp) context.getApplicationContext()).notifySettingsChanged();
            } catch (RuntimeException e) {
                // the next onResume still sees the file change through Config.refresh()
            }
        }
        if (isOrderedBroadcast()) setResultData(Integer.toString(n));
    }

    /** The app's shared Config (refreshed) when available, else a fresh one over orchard.conf. */
    private static Config sharedConfig(Context context) throws IOException {
        Config cfg;
        try {
            cfg = ((OrchardApp) context.getApplicationContext()).config();
        } catch (ClassCastException | NullPointerException e) {
            cfg = new Config(FireTvBridge.settingsFile(context));
        }
        cfg.refresh();
        return cfg;
    }

    private static final String[] CITY_KEYS = {
        Config.Keys.CITY_ID, Config.Keys.CITY_LAT, Config.Keys.CITY_LON, Config.Keys.CITY_NAME, Config.Keys.CITY_TZ };

    private void setCity(Context context, Intent intent) {
        int r = -1;
        try {
            Config cfg = sharedConfig(context);
            Map<String, String> m = cfg.snapshot();
            r = CityCommand.apply(m, Cities.all(context), intent.getStringExtra("city_id"),
                intent.getStringExtra("city_lat"), intent.getStringExtra("city_lon"), intent.getStringExtra("city_name"));
            if (r == 1) {
                for (String k : CITY_KEYS) {
                    String v = m.get(k);
                    if (v == null) cfg.remove(k); else cfg.set(k, v);
                }
                cfg.save();
                try {
                    ((OrchardApp) context.getApplicationContext()).notifySettingsChanged();
                } catch (RuntimeException e) {
                    // the next onResume still sees the file change through Config.refresh()
                }
            }
        } catch (IOException | RuntimeException e) {
            r = -1;
        }
        if (isOrderedBroadcast()) setResultData(Integer.toString(r));
    }
}
