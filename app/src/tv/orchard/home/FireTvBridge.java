package tv.orchard.home;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import java.io.File;
import java.util.List;

/**
 * Android side of the reset (PLAN sections 2.1 and 2.2). Layer A: returnToFireTv / switchBackOn.
 * Layer B policy: crashListener() for the dying process and evaluateLaunch() for the next launch.
 */
public final class FireTvBridge {
    public interface Callback {
        /** ok=false means HoF was still bound after 2 s (show the "Hold Home" sheet). */
        void onDone(boolean ok);
    }

    public static final String HOF_PACKAGE = "io.github.toolicious.homeonfire";
    public static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    public static final String SETTING_SERVICES = "enabled_accessibility_services";
    public static final String SETTING_ENABLED = "accessibility_enabled";

    /** Shown wherever a reset action needs the one-time grant (sheet, SafeModeView). */
    public static final String GRANT_HELP =
        "Orchard needs a one-time permission first. On the Mac run: scripts/launcher.sh grant";
    public static final String GRANT_ALT = "Or hold Home on the remote for Amazon's home screen.";
    public static final String HOLD_HOME_HELP =
        "Could not switch the redirect off. Hold Home on the remote for Amazon's home screen.";

    /** Alias of Config.Keys.HOME_PRESS_SEEN_AT: epoch ms of the last Home press seen by HomeActivity.onUserLeaveHint. */
    public static final String KEY_HOME_PRESS_SEEN_AT = Config.Keys.HOME_PRESS_SEEN_AT;

    private final Context ctx;
    private final State state;

    public FireTvBridge(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        State shared = null;
        if (this.ctx instanceof OrchardApp) shared = ((OrchardApp) this.ctx).state();
        this.state = shared != null ? shared : new State(this.ctx);
    }

    public Context context() { return ctx; }

    // ---- reads ---------------------------------------------------------------------------------

    public boolean hasGrant() {
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            == PackageManager.PERMISSION_GRANTED;
    }

    private String currentList() {
        return Settings.Secure.getString(ctx.getContentResolver(), SETTING_SERVICES);
    }

    /** True while HoF's accessibility service is in the enabled list (the redirect is armed). */
    public boolean redirectOn() {
        return AccessibilityList.contains(currentList());
    }

    public boolean hofInstalled() {
        try {
            ctx.getPackageManager().getPackageInfo(HOF_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Best available signal that HoF's private "Replace Home" toggle is off while its service is on:
     * Orchard was HoF's target before (a CATEGORY_HOME launch on record), a Home press was seen since boot, but no
     * CATEGORY_HOME launch followed it. Never fires while another launcher is the target; see RedirectCheck.
     */
    public boolean redirectDoubtful() {
        long bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return RedirectCheck.doubtful(redirectOn(), state.getLong(Config.Keys.LAST_HOME_LAUNCH_AT, 0L),
            state.getLong(KEY_HOME_PRESS_SEEN_AT, 0L), bootAt);
    }

    /** HomeActivity.onUserLeaveHint calls this (feeds redirectDoubtful). */
    public void noteHomePressSeen() {
        state.put(KEY_HOME_PRESS_SEEN_AT, Long.toString(System.currentTimeMillis()));
    }

    /** Is any HoF id still in the system's *bound* enabled-service list? */
    boolean hofStillListed() {
        AccessibilityManager am = (AccessibilityManager) ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> list =
            am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        if (list == null) return false;
        for (AccessibilityServiceInfo info : list) {
            if (AccessibilityList.contains(info.getId())) return true;
        }
        return false;
    }

    // ---- Layer A -------------------------------------------------------------------------------

    /** Writes the stripped list. Needs the grant; never deletes the key. Returns false on failure. */
    private boolean writeStripped() {
        try {
            return Settings.Secure.putString(ctx.getContentResolver(), SETTING_SERVICES,
                AccessibilityList.strip(currentList()));
        } catch (SecurityException e) {
            return false;
        }
    }

    boolean launchAmazonHome() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setPackage(AMAZON_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Strip HoF, wait for the system to unbind it (poll every 100 ms, up to 2 s), then open Amazon's home.
     * Without the grant (callers should check hasGrant() first to show GRANT_HELP) nothing changes and
     * cb gets false. cb may be null; it runs on the main thread.
     */
    public void returnToFireTv(final Callback cb) {
        if (!hasGrant() || !writeStripped()) {
            if (cb != null) cb.onDone(false);
            return;
        }
        state.put(Config.Keys.REDIRECT_OFF_AT, Long.toString(System.currentTimeMillis()));
        final Handler h = new Handler(Looper.getMainLooper());
        final long deadline = SystemClock.uptimeMillis() + Ui.SHEET_RESET_CHECK_MS;
        // A static nested class, not an anonymous/inner one: d8 8.2.2 crashes on javac 21 anonymous inner classes
        // (MethodParameters with a null name for the implicit outer-instance parameter; the same applies to non-static inner classes).
        new Poll(this, cb, deadline, h).run();
    }

    /** Polls the AccessibilityManager until HoF is unbound (or the deadline), then opens Amazon's home. */
    private static final class Poll implements Runnable {
        private final FireTvBridge bridge;
        private final Callback cb;
        private final long deadline;
        private final Handler h;

        Poll(FireTvBridge bridge, Callback cb, long deadline, Handler h) {
            this.bridge = bridge;
            this.cb = cb;
            this.deadline = deadline;
            this.h = h;
        }

        @Override
        public void run() {
            if (!bridge.hofStillListed()) {
                boolean launched = bridge.launchAmazonHome();
                if (cb != null) cb.onDone(launched);
            } else if (SystemClock.uptimeMillis() >= deadline) {
                if (cb != null) cb.onDone(false);
            } else {
                h.postDelayed(this, Ui.SHEET_POLL_MS);
            }
        }
    }

    /** Blocking variant for the dying process (crash handler thread). Returns true if Amazon home was launched. */
    private boolean stripAndLaunchBlocking() {
        if (!hasGrant() || !writeStripped()) return false;
        state.put(Config.Keys.REDIRECT_OFF_AT, Long.toString(System.currentTimeMillis()));
        long deadline = SystemClock.uptimeMillis() + Ui.SHEET_RESET_CHECK_MS;
        try {
            while (hofStillListed() && SystemClock.uptimeMillis() < deadline) Thread.sleep(Ui.SHEET_POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !hofStillListed() && launchAmazonHome();
    }

    /** Put HoF back in the list and enable accessibility. Returns false without the grant. */
    public boolean switchBackOn() {
        try {
            Settings.Secure.putString(ctx.getContentResolver(), SETTING_SERVICES,
                AccessibilityList.append(currentList()));
            Settings.Secure.putInt(ctx.getContentResolver(), SETTING_ENABLED, 1);
        } catch (SecurityException e) {
            return false;
        }
        state.put(Config.Keys.REDIRECT_OFF_AT, "");
        return true;
    }

    // ---- Layer B policy --------------------------------------------------------------------------

    /** getExternalFilesDir()/orchard.conf (adb-writable); falls back to getFilesDir() when external is unavailable. */
    public static File settingsFile(Context c) {
        Context app = c.getApplicationContext();
        File dir = app.getExternalFilesDir(null);
        if (dir == null) dir = app.getFilesDir();
        return new File(dir, "orchard.conf");
    }

    /** Strip HoF on a loop only if the grant exists, crashguard_strip=on and the launch came through Home. */
    private boolean canStripOnLoop(boolean looping, boolean arrivedViaHome) {
        if (!looping || !arrivedViaHome) return false;
        boolean armed = new Config(settingsFile(ctx)).getBool(Config.Keys.CRASHGUARD_STRIP, false);
        return CrashGuard.stripAllowed(looping, hasGrant(), armed, arrivedViaHome);
    }

    /** "Orchard" once it has been HoF's HOME target (a CATEGORY_HOME launch on record), else "your launcher". */
    public String hofTargetName() {
        return state.getLong(Config.Keys.LAST_HOME_LAUNCH_AT, 0L) > 0 ? "Orchard" : "your launcher";
    }

    /** Fire TV settings. Never android.settings.SETTINGS (it reopens Amazon's home). */
    public static Intent fireTvSettingsIntent() {
        return new Intent(Intent.ACTION_MAIN)
            .addCategory("android.intent.category.LEANBACK_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The system App info screen for pkg. */
    public static Intent appInfoIntent(String pkg) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** True when some activity can handle i (false on any RuntimeException). */
    public boolean resolves(Intent i) {
        try {
            return ctx.getPackageManager().resolveActivity(i, 0) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Runs inside the dying process (PLAN 2.2): record last_crash, flag safe mode on a loop, maybe strip. */
    private void handleCrash(Throwable t, boolean windowLooping) {
        state.set(Config.Keys.LAST_CRASH, t.getClass().getName());
        boolean looping = windowLooping
            || state.getInt(Config.Keys.BOOT_ATTEMPTS, 0) >= CrashGuard.BOOT_ATTEMPT_LIMIT;
        if (looping) state.set(Config.Keys.SAFE_MODE, "1");
        state.commit();
        boolean viaHome = "1".equals(state.get(Config.Keys.LAUNCH_VIA_HOME, "0"));
        if (looping && canStripOnLoop(looping, viaHome)) stripAndLaunchBlocking();
    }

    /** Wire with CrashGuard.install(state.crashesFile(), FireTvBridge.crashListener(this)). */
    public static CrashGuard.Listener crashListener(final Context c) {
        return new CrashGuard.Listener() {
            @Override
            public void onCrash(Throwable t, boolean windowLooping) {
                new FireTvBridge(c).handleCrash(t, windowLooping);
            }
        };
    }

    /**
     * Call from HomeActivity.onCreate (and onNewIntent) with the launching intent. Records
     * last_home_launch_at for CATEGORY_HOME launches, evaluates boot_attempts / crash window, sets
     * safe_mode, and (armed + granted + CATEGORY_HOME) strips HoF. Returns true when SafeModeView
     * should be shown instead of the home.
     */
    public boolean evaluateLaunch(Intent intent) {
        boolean viaHome = intent != null && intent.hasCategory(Intent.CATEGORY_HOME);
        long now = System.currentTimeMillis();
        state.set(Config.Keys.LAUNCH_VIA_HOME, viaHome ? "1" : "0");
        if (viaHome) state.set(Config.Keys.LAST_HOME_LAUNCH_AT, Long.toString(now));
        state.commit();
        boolean alreadySafe = "1".equals(state.get(Config.Keys.SAFE_MODE, ""));
        boolean looping = state.getInt(Config.Keys.BOOT_ATTEMPTS, 0) >= CrashGuard.BOOT_ATTEMPT_LIMIT
            || CrashGuard.window(CrashGuard.read(state.crashesFile()), now);
        if (looping && !alreadySafe) {
            state.put(Config.Keys.SAFE_MODE, "1");
            if (canStripOnLoop(true, viaHome)) returnToFireTv(null);
        }
        return alreadySafe || looping;
    }

    /** "Try Orchard again": clears safe_mode, boot_attempts and the crash history. */
    public void clearSafeMode() {
        state.set(Config.Keys.SAFE_MODE, "0");
        state.set(Config.Keys.BOOT_ATTEMPTS, "0");
        state.commit();
        state.crashesFile().delete();
    }

    /** "Reset Orchard settings": deletes orchard.conf and clears the safe-mode flags. */
    public boolean resetOrchardSettings() {
        File f = settingsFile(ctx);
        boolean ok = !f.exists() || f.delete();
        clearSafeMode();
        return ok;
    }

    public String lastCrash() { return state.get(Config.Keys.LAST_CRASH, ""); }
}
