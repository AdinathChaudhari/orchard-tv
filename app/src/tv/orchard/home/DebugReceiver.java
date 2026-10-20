package tv.orchard.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.IOException;

/**
 * Shell-only debug channel. Declared in the manifest with android:permission="android.permission.DUMP", which adb shell and
 * system hold and ordinary apps cannot get, so unlike the exported HomeActivity it may set the crash-failsafe keys
 * (debug_crash, crash_on_home, crashguard_strip). Examples:
 *   am broadcast -n tv.orchard.home/.DebugReceiver --es set "debug_crash=1;crash_on_home=1"
 *   am broadcast -n tv.orchard.home/.DebugReceiver --es set "crashguard_strip=on"
 *   am broadcast -n tv.orchard.home/.DebugReceiver --es unset "debug_crash;crash_on_home"
 * The result data of the broadcast is the number of changes ("result=N" in am's output). A change (N > 0) also bumps
 * the settings epoch and notifies a live HomeActivity, which re-reads orchard.conf, recomputes ArtCache.setPlaceholder
 * and rebuilds tiles + shelf immediately (no force-stop). Unlike am start this form needs no nonce: a broadcast is
 * never replayed after process death.
 */
public final class DebugReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
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
}
