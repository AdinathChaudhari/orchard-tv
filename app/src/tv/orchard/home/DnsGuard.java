package tv.orchard.home;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.net.InetAddress;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import tv.orchard.home.DnsGuardPolicy.Gate;
import tv.orchard.home.DnsGuardPolicy.Step;
import tv.orchard.home.DnsGuardPolicy.Timings;

/**
 * Private DNS repair (docs/learnings.md 2.10, docs/DETOX.md). After a power-off the Stick restores a stale clock, strict
 * Private DNS cannot validate its resolver and the network never validates. This class notices that with two
 * zero-permission signals (the wall clock is behind the last wall time Orchard saved, and a strict lookup of a name nobody
 * can have cached fails), then runs hostname -> opportunistic -> off (3 s) -> hostname. All decisions live in
 * DnsGuardPolicy (host-tested); this is the driver: it reads and writes the settings, probes, persists and schedules.
 *
 * Safety: state is persisted (synchronously, atomically) before every settings write; both settings are re-read at the
 * start of every step and the run is abandoned on any change the guard did not make; a run never ends in mode off; every
 * entry point is exception-contained so HomeActivity never sees a throw. Nothing runs while idle except at most three
 * detection posts per boot. The only network use is DNS-only lookups of a random name under Android's own probe suffix.
 */
public final class DnsGuard {
    private static final String TAG = "OrchardDns";
    private static final String S_MODE = "private_dns_mode", S_SPEC = "private_dns_specifier", S_ATS = "auto_time_sync_enabled";
    private static final long PROBE_TIMEOUT_MS = 8000L;
    private static final long ATS_RESTORE_MS = 2000L;

    /** Receives the result of a debug check (DebugReceiver, on the guard thread). */
    public interface Done { void done(String result); }

    private static DnsGuard instance;

    public static synchronized DnsGuard get(Context ctx) {
        if (instance == null) instance = new DnsGuard(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final State state;
    private final Config cfg;                       // own view of orchard.conf (refreshed before each read)
    private final ContentResolver cr;
    private HandlerThread thread;
    private Handler handler;
    // guard thread only
    private int armed;
    private volatile int detectProbes;
    private String startSpec;            // specifier read at run start; non-null only inside startRun's first write
    private boolean oppOk;
    // any thread
    private volatile boolean detectPending;
    private volatile int detectPosts;
    private volatile long floorCache;
    private volatile long wallSavedCache;

    private DnsGuard(Context ctx) {
        app = ctx;
        State shared = null;
        try {
            shared = ((OrchardApp) ctx).state();
        } catch (ClassCastException | NullPointerException e) {
            // not our Application (host harness): own State
        }
        state = shared != null ? shared : new State(ctx);
        cfg = new Config(FireTvBridge.settingsFile(ctx));
        cr = ctx.getContentResolver();
        detectProbes = state.getInt(DnsGuardPolicy.K_DETECT_PROBES, 0);
        detectPosts = state.getInt(DnsGuardPolicy.K_DETECT_POSTS, 0);
    }

    private void persistDetect() {
        state.set(DnsGuardPolicy.K_DETECT_PROBES, Integer.toString(detectProbes));
        state.set(DnsGuardPolicy.K_DETECT_POSTS, Integer.toString(detectPosts));
        state.commit();
    }

    // ---- entry points (any thread; exception-contained) -----------------------------------------------------------

    /** OrchardApp.onCreate: cheap; starts the worker only when a run is persisted or a nudge was left unrestored. */
    public void resume() {
        try {
            String phase = state.get(DnsGuardPolicy.K_PHASE, DnsGuardPolicy.P_IDLE);
            boolean ats = state.get(DnsGuardPolicy.K_ATS_PREV, "").length() > 0;
            if (DnsGuardPolicy.P_IDLE.equals(phase) && !ats) return;
            post(this::resumeOnThread, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "resume failed: " + e);
        }
    }

    /** HomeActivity first frame (the proven boot hook): the first detection pass comes FIRST_CHECK_MS later. */
    public void onHomeVisible() {
        try {
            post(this::homeVisibleOnThread, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "onHomeVisible failed: " + e);
        }
    }

    /** Ambient minute tick, main thread, real wall clock. One compare when the clock is fine. */
    public void onMinute(long nowWall) {
        try {
            long fl = floorCache;
            if (fl == 0L) {
                fl = computeFloor();
                floorCache = fl;
            }
            if (nowWall - wallSavedCache >= DnsGuardPolicy.WALL_SAVE_EVERY_MS) post(this::noteWallOnThread, 0);
            if (!detectPending && DnsGuardPolicy.detectAllowed(detectPosts) && DnsGuardPolicy.stale(nowWall, fl)) {
                detectPending = true;
                detectPosts++;
                post(this::detectStep, 0);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "onMinute failed: " + e);
        }
    }

    /** HomeActivity.onStop: save the wall time as the floor for the next boot (on the guard thread). */
    public void noteWall() {
        try {
            post(this::noteWallOnThread, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "noteWall failed: " + e);
        }
    }

    /** Home chip text while ad-blocking DNS is paused; null otherwise. Reads state only. */
    public String notice() {
        try {
            return DnsGuardPolicy.notice(state.get(DnsGuardPolicy.K_PHASE, DnsGuardPolicy.P_IDLE),
                state.get(DnsGuardPolicy.K_LAST_RESULT, ""), mode());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Settings > Private DNS status > select: one manual pass (skips stale and cooldown, keeps mode/spec/probe gates; needs ARM_COUNT failed probes; from paused it repairs once). */
    public void requestCheck(boolean manual) {
        try {
            post(() -> evaluate(manual, false, false), 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "requestCheck failed: " + e);
        }
    }

    /** DebugReceiver: one pass now; the result string is handed to done on the guard thread. */
    public void debugCheck(boolean force, boolean runNow, Done done) {
        try {
            post(() -> done.done(evaluate(false, force, runNow)), 0);
        } catch (RuntimeException e) {
            done.done("error");
        }
    }

    /** DebugReceiver: the manual (Settings) check with its result string; same code path as requestCheck(true). */
    public void debugManual(Done done) {
        try {
            post(() -> done.done(evaluate(true, false, false)), 0);
        } catch (RuntimeException e) {
            done.done("error");
        }
    }

    /** DebugReceiver reset: dnsg.* back to idle and zero (keeps last_wall); no settings write. */
    public void debugReset(Done done) {
        try {
            post(() -> {
                handler.removeCallbacksAndMessages(null);
                detectPending = false;
                armed = 0;
                detectProbes = 0;
                detectPosts = 0;
                for (String k : new String[] {DnsGuardPolicy.K_PHASE, DnsGuardPolicy.K_WROTE_MODE, DnsGuardPolicy.K_SAVED_SPEC,
                    DnsGuardPolicy.K_RETRY, DnsGuardPolicy.K_NONET, DnsGuardPolicy.K_NUDGED, DnsGuardPolicy.K_RUN_UPTIME,
                    DnsGuardPolicy.K_PHASE_UPTIME, DnsGuardPolicy.K_RUNS_BOOT, DnsGuardPolicy.K_LAST_RESULT,
                    DnsGuardPolicy.K_LAST_RESULT_WALL, DnsGuardPolicy.K_WRITES_BOOT, DnsGuardPolicy.K_OFFSET_START,
                    DnsGuardPolicy.K_KNOWN_SPEC, DnsGuardPolicy.K_DETECT_PROBES, DnsGuardPolicy.K_DETECT_POSTS}) {
                    state.remove(k);
                }
                state.set(DnsGuardPolicy.K_PHASE, DnsGuardPolicy.P_IDLE);
                state.commit();
                done.done("reset");
            }, 0);
        } catch (RuntimeException e) {
            done.done("error");
        }
    }

    /** "phase=..;retry=..;runs=..;result=..;ago=..;mode=..;specMatch=..;floorAgeMin=.." */
    public String status() {
        try {
            long wall = System.currentTimeMillis();
            long rw = state.getLong(DnsGuardPolicy.K_LAST_RESULT_WALL, 0L);
            String spec = Settings.Global.getString(cr, S_SPEC);
            return "phase=" + state.get(DnsGuardPolicy.K_PHASE, DnsGuardPolicy.P_IDLE)
                + ";retry=" + state.getInt(DnsGuardPolicy.K_RETRY, 0)
                + ";runs=" + state.getInt(DnsGuardPolicy.K_RUNS_BOOT, 0)
                + ";result=" + state.get(DnsGuardPolicy.K_LAST_RESULT, "")
                + ";ago=" + (rw == 0 ? -1 : Math.max(0L, (wall - rw) / 1000L))
                + ";mode=" + Settings.Global.getString(cr, S_MODE)
                + ";specMatch=" + DnsGuardPolicy.specExpected(spec, state.get(DnsGuardPolicy.K_KNOWN_SPEC, null))
                + ";floorAgeMin=" + ((wall - computeFloor()) / 60000L);
        } catch (RuntimeException e) {
            return "error";
        }
    }

    /** One line for the Settings row. Reads only. */
    public String statusLine() {
        try {
            String phase = state.get(DnsGuardPolicy.K_PHASE, DnsGuardPolicy.P_IDLE);
            String mode = Settings.Global.getString(cr, S_MODE);
            if (notice() != null) return "Paused (opportunistic) until the next restart; select to retry once";
            // settings back on hostname while a stale paused phase lingers: report the settings, not "paused"
            boolean stalePaused = DnsGuardPolicy.isPaused(phase) && DnsGuardPolicy.MODE_HOSTNAME.equals(mode);
            if (!DnsGuardPolicy.P_IDLE.equals(phase) && !stalePaused) return "Repairing now";
            String spec = Settings.Global.getString(cr, S_SPEC);
            if (mode == null || DnsGuardPolicy.MODE_OFF.equals(mode)) return "Off in Fire TV settings";
            if (DnsGuardPolicy.MODE_OPP.equals(mode)) return "Automatic in Fire TV settings";
            if (!DnsGuardPolicy.MODE_HOSTNAME.equals(mode)) return "Off in Fire TV settings";
            String label = DnsGuardPolicy.DEFAULT_SPEC.equals(spec) ? "AdGuard" : "custom";
            long rw = state.getLong(DnsGuardPolicy.K_LAST_RESULT_WALL, 0L);
            long wall = System.currentTimeMillis();
            if (DnsGuardPolicy.R_OK.equals(state.get(DnsGuardPolicy.K_LAST_RESULT, "")) && rw > 0 && wall >= rw && wall - rw < 86400000L) {
                return "Strict, repaired " + Math.max(1L, (wall - rw) / 60000L) + " min ago";
            }
            return "Strict, " + label + ". No repair needed";
        } catch (RuntimeException e) {
            return "Unknown";
        }
    }

    // ---- worker ----------------------------------------------------------------------------------------------------

    private synchronized void post(Runnable r, long delayMs) {
        if (handler == null) {
            thread = new HandlerThread("OrchardDnsGuard", Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        handler.postDelayed(new Guarded(this, r), delayMs);
    }

    /** Every posted body runs inside this: a throw is logged and the run rolled back, never propagated. */
    private static final class Guarded implements Runnable {
        private final DnsGuard owner;
        private final Runnable body;
        Guarded(DnsGuard owner, Runnable body) { this.owner = owner; this.body = body; }

        @Override
        public void run() {
            try {
                body.run();
            } catch (RuntimeException e) {
                Log.w(TAG, "step failed: " + e);
                owner.safeEnd();
            }
        }
    }

    private Timings timings() {
        cfg.refresh();
        return "1".equals(cfg.get(Config.Keys.DEBUG_DNS_FAST, "0")) ? Timings.fast() : Timings.normal();
    }

    private String phase() { return state.get(DnsGuardPolicy.K_PHASE, DnsGuardPolicy.P_IDLE); }
    private static long up() { return SystemClock.elapsedRealtime(); }
    private String mode() { return Settings.Global.getString(cr, S_MODE); }
    private String spec() { return Settings.Global.getString(cr, S_SPEC); }

    private long computeFloor() {
        return DnsGuardPolicy.floor(state.getLong(DnsGuardPolicy.K_LAST_WALL, 0L), BuildStamp.BUILD_UTC_MS);
    }

    // ---- wall floor ------------------------------------------------------------------------------------------------

    private void noteWallOnThread() {
        long now = System.currentTimeMillis();
        long stored = state.getLong(DnsGuardPolicy.K_LAST_WALL, 0L);
        if (now > stored && now >= BuildStamp.BUILD_UTC_MS && now <= BuildStamp.BUILD_UTC_MS + DnsGuardPolicy.FLOOR_MAX_AHEAD_MS) {
            state.set(DnsGuardPolicy.K_LAST_WALL, Long.toString(now));
            wallSavedCache = now;
        }
        state.set(DnsGuardPolicy.K_SEEN_UPTIME, Long.toString(up()));
        if (!isNewBoot()) markBoot();
        state.commit();
        floorCache = computeFloor();
    }

    private boolean isNewBoot() {
        long stored = Math.max(state.getLong(DnsGuardPolicy.K_PHASE_UPTIME, 0L), state.getLong(DnsGuardPolicy.K_SEEN_UPTIME, 0L));
        if (DnsGuardPolicy.newBoot(up(), stored)) return true;
        String bc = bootCount(), seen = state.get(K_BOOT_COUNT, "");
        return bc != null && !seen.isEmpty() && !bc.equals(seen);   // a reboot the uptime compare cannot see
    }

    private static final String K_BOOT_COUNT = "dnsg.boot_count";

    private String bootCount() {
        try { return Settings.Global.getString(cr, "boot_count"); } catch (RuntimeException e) { return null; }
    }

    private void markBoot() {
        String bc = bootCount();
        if (bc != null) state.set(K_BOOT_COUNT, bc);
    }

    /** On a new boot the per-boot counters restart. Persists. */
    private void rollBootCounters() {
        if (!isNewBoot()) return;
        for (String k : DnsGuardPolicy.BOOT_COUNTER_KEYS) state.set(k, "0");
        detectProbes = 0;
        detectPosts = 0;
        state.set(DnsGuardPolicy.K_SEEN_UPTIME, Long.toString(up()));
        state.set(DnsGuardPolicy.K_PHASE_UPTIME, Long.toString(up()));
        markBoot();
        state.commit();
    }

    // ---- detection -------------------------------------------------------------------------------------------------

    private void homeVisibleOnThread() {
        floorCache = computeFloor();
        wallSavedCache = state.getLong(DnsGuardPolicy.K_LAST_WALL, 0L);
        rollBootCounters();
        if (detectPending) return;
        detectPending = true;
        detectPosts++;
        persistDetect();
        post(this::detectStep, timings().firstCheckMs);
    }

    private void detectStep() {
        persistDetect();        // the post counted on the main thread (onMinute) is saved here
        if (!DnsGuardPolicy.detectAllowed(detectProbes)) {
            detectPending = false;
            return;
        }
        String r = evaluate(false, false, false);
        if (!r.startsWith("armed:")) detectPending = false;
    }

    /** One detection pass. Returns the result string of the DNS_GUARD debug command. Guard thread. */
    private String evaluate(boolean manual, boolean force, boolean runNow) {
        cfg.refresh();
        boolean auto = !manual && !force && !runNow;
        if ("1".equals(cfg.get(Config.Keys.DEBUG_DNS_FORCE, "0"))) {       // consumed by this check
            force = true;
            cfg.remove(Config.Keys.DEBUG_DNS_FORCE);
            try { cfg.save(); } catch (java.io.IOException | RuntimeException e) { /* flag stays set; harmless */ }
        }
        Timings t = timings();
        rollBootCounters();
        String curPhase = phase();
        boolean pausedRepair = false;
        String mp = DnsGuardPolicy.manualPaused(curPhase, manual,
            DnsGuardPolicy.settingsAsWritten(state.get(DnsGuardPolicy.K_WROTE_MODE, ""), state.get(DnsGuardPolicy.K_SAVED_SPEC, ""), mode(), spec()));
        if (DnsGuardPolicy.MP_ABORT.equals(mp)) {          // the user already restored the settings by hand
            handler.removeCallbacksAndMessages(null);
            detectPending = false;
            armed = 0;
            Log.i(TAG, "manual from " + curPhase + ": settings changed by hand -> idle");
            finishRun(DnsGuardPolicy.P_IDLE, DnsGuardPolicy.R_ABORTED, up());
            return "idle:aborted_user";
        }
        pausedRepair = DnsGuardPolicy.MP_REPAIR.equals(mp);
        long wall = System.currentTimeMillis();
        long floor = computeFloor();
        floorCache = floor;
        String mode = mode(), spec = spec();
        String known = state.get(DnsGuardPolicy.K_KNOWN_SPEC, null);
        Gate g = new Gate();
        g.enabled = cfg.getBool(Config.Keys.DNS_GUARD, true);
        g.phaseIdle = DnsGuardPolicy.P_IDLE.equals(phase());
        g.mode = mode;
        g.spec = spec;
        g.knownSpec = known;
        g.stale = DnsGuardPolicy.stale(wall, floor);
        g.forced = force;
        g.manual = manual;
        g.pausedRepair = pausedRepair;
        g.runsBoot = state.getInt(DnsGuardPolicy.K_RUNS_BOOT, 0);
        g.writesBoot = state.getInt(DnsGuardPolicy.K_WRITES_BOOT, 0);
        long runUp = state.getLong(DnsGuardPolicy.K_RUN_UPTIME, 0L);
        g.sinceLastRunMs = runUp > 0 && up() >= runUp ? up() - runUp : Long.MAX_VALUE;
        String skip = DnsGuardPolicy.gate(g, t);
        boolean specMatch = DnsGuardPolicy.specExpected(spec, known);
        if (skip != null) {
            Log.i(TAG, "check stale=" + g.stale + " probe=skip mode=" + mode + " spec=" + (specMatch ? "match" : "other") + " -> " + skip);
            armed = 0;
            return "skip:" + skip;
        }
        if (pausedRepair) {      // opportunistic works by design; one explicit repair run, no probe
            handler.removeCallbacksAndMessages(null);       // the pending fallback timer must not double-drive the run
            detectPending = false;
            armed = 0;
            String sv = state.get(DnsGuardPolicy.K_SAVED_SPEC, "");
            Log.i(TAG, "manual repair from " + curPhase);
            return startRun(sv.isEmpty() ? spec : sv, t);
        }
        if (auto) { detectProbes++; persistDetect(); }
        boolean ok = probe();
        Log.i(TAG, "check stale=" + g.stale + " probe=" + (ok ? "ok" : "fail") + " mode=" + mode + " spec="
            + (specMatch ? "match" : "other") + " -> " + (ok ? "skip" : "armed"));
        if (ok) {
            armed = 0;
            if (DnsGuardPolicy.recordsKnownSpec(force, spec, known)) {
                state.set(DnsGuardPolicy.K_KNOWN_SPEC, spec);
                state.commit();
            }
            return "skip:probe=ok";
        }
        armed++;
        if (DnsGuardPolicy.startsRun(armed, runNow)) {
            armed = 0;
            return startRun(spec, t);
        }
        if (auto) post(this::detectStep, t.recheckMs);
        else {
            detectPending = false;
            if (manual) post(() -> evaluate(true, false, false), t.recheckMs);   // a manual check also needs ARM_COUNT failed probes
        }
        return "armed:" + armed;
    }

    // ---- probe -----------------------------------------------------------------------------------------------------

    /** A lookup of a never-cached random name: succeeds only when the resolver path (strict DoT, or plain DNS) works. */
    private boolean probe() {
        FutureTask<Boolean> f = new FutureTask<Boolean>(new ProbeCall(DnsGuardPolicy.probeName(System.nanoTime())));
        Thread th = new Thread(f, "OrchardDnsProbe");
        th.setDaemon(true);
        th.start();
        try {
            return f.get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException e) {
            f.cancel(true);
            return false;
        }
    }

    private static final class ProbeCall implements Callable<Boolean> {
        private final String name;
        ProbeCall(String name) { this.name = name; }

        @Override
        public Boolean call() {
            try {
                InetAddress[] a = InetAddress.getAllByName(name);
                return a != null && a.length > 0;
            } catch (java.net.UnknownHostException | RuntimeException e) {
                return false;
            }
        }
    }

    // ---- run -------------------------------------------------------------------------------------------------------

    private String startRun(String curSpec, Timings t) {
        if (!new FireTvBridge(app).hasGrant()) {
            Log.i(TAG, "skip:no_grant");
            return "skip:no_grant";
        }
        String saved = curSpec;
        String test = cfg.get(Config.Keys.DEBUG_DNS_RESTORE_HOST, "");
        if (test != null && !test.isEmpty()) {
            saved = test;
            Log.w(TAG, "TEST restore target in effect");
        }
        long now = up();
        long wall = System.currentTimeMillis();
        int runs = state.getInt(DnsGuardPolicy.K_RUNS_BOOT, 0) + 1;
        state.set(DnsGuardPolicy.K_RUNS_BOOT, Integer.toString(runs));
        state.set(DnsGuardPolicy.K_SAVED_SPEC, saved);
        state.set(DnsGuardPolicy.K_OFFSET_START, Long.toString(wall - now));
        state.set(DnsGuardPolicy.K_RETRY, "0");
        state.set(DnsGuardPolicy.K_NONET, "0");
        state.set(DnsGuardPolicy.K_NUDGED, "0");
        state.remove(DnsGuardPolicy.K_ATS_PREV);
        state.set(DnsGuardPolicy.K_RUN_UPTIME, Long.toString(now));
        state.set(DnsGuardPolicy.K_SEEN_UPTIME, Long.toString(now));
        oppOk = false;
        startSpec = curSpec;
        Log.i(TAG, "phase idle -> opp +0 write=opportunistic reason=run_start run=" + runs);
        if (!persistAndWrite(DnsGuardPolicy.P_OPP, DnsGuardPolicy.MODE_OPP, null, now, "run_start")) { startSpec = null; return "skip:write"; }
        startSpec = null;
        post(this::step, t.netPollMs);
        return "run";
    }

    /**
     * Persist phase, wrote_mode and phase_uptime (+ the write counters), commit, then write the setting. False when the
     * write was refused (per-boot write cap on a non-restoring mode, or a SecurityException).
     */
    private boolean persistAndWrite(String nextPhase, String writeMode, String writeSpec, long now, String reason) {
        // Hard constraint 2: re-read both settings immediately before the write (a probe may have blocked for seconds).
        String prevWrote0 = state.get(DnsGuardPolicy.K_WROTE_MODE, "");
        boolean unchanged = prevWrote0.isEmpty()
            ? startSpec != null && DnsGuardPolicy.MODE_HOSTNAME.equals(mode()) && startSpec.equals(spec())
            : DnsGuardPolicy.settingsAsWritten(prevWrote0, state.get(DnsGuardPolicy.K_SAVED_SPEC, ""), mode(), spec());
        if (!unchanged) {
            Log.i(TAG, "abort:user_changed");
            finishRun(DnsGuardPolicy.P_IDLE, DnsGuardPolicy.R_ABORTED, now);
            return false;
        }
        int writes = state.getInt(DnsGuardPolicy.K_WRITES_BOOT, 0);
        if (writes >= DnsGuardPolicy.MAX_WRITES_PER_BOOT && !DnsGuardPolicy.MODE_HOSTNAME.equals(writeMode)) {
            Log.w(TAG, "abort:write_cap");
            safeEnd();
            return false;
        }
        String prevWrote = state.get(DnsGuardPolicy.K_WROTE_MODE, "");
        state.set(DnsGuardPolicy.K_PHASE, nextPhase);
        state.set(DnsGuardPolicy.K_WROTE_MODE, writeMode);
        state.set(DnsGuardPolicy.K_PHASE_UPTIME, Long.toString(now));
        state.set(DnsGuardPolicy.K_WRITES_BOOT, Integer.toString(writes + 1));
        state.commit();
        try {
            if (writeSpec != null) Settings.Global.putString(cr, S_SPEC, writeSpec);
            Settings.Global.putString(cr, S_MODE, writeMode);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "write refused: " + e);
            state.set(DnsGuardPolicy.K_WROTE_MODE, prevWrote);   // the settings are still what the previous step wrote
            safeEnd();
            return false;
        }
    }

    private void step() {
        String phase = phase();
        if (DnsGuardPolicy.P_IDLE.equals(phase) || DnsGuardPolicy.P_FINAL.equals(phase)) return;
        Timings t = timings();
        long now = up();
        long inPhase = Math.max(0L, now - state.getLong(DnsGuardPolicy.K_PHASE_UPTIME, now));
        String wrote = state.get(DnsGuardPolicy.K_WROTE_MODE, "");
        String saved = state.get(DnsGuardPolicy.K_SAVED_SPEC, "");
        if (!DnsGuardPolicy.settingsAsWritten(wrote, saved, mode(), spec())) {
            Log.i(TAG, "abort:user_changed");
            finishRun(DnsGuardPolicy.P_IDLE, DnsGuardPolicy.R_ABORTED, now);
            return;
        }
        int retry = state.getInt(DnsGuardPolicy.K_RETRY, 0);
        boolean nonet = state.getInt(DnsGuardPolicy.K_NONET, 0) == 1;
        boolean nudged = state.getInt(DnsGuardPolicy.K_NUDGED, 0) == 1;
        boolean ok = false;
        if (DnsGuardPolicy.needsProbe(phase)) {
            if (DnsGuardPolicy.P_OPP.equals(phase) && oppOk) {
                ok = true;                       // the network answered once in this phase; only the clock is awaited
            } else {
                ok = probe();
                if (ok && DnsGuardPolicy.P_OPP.equals(phase)) oppOk = true;
            }
        }
        long wall = System.currentTimeMillis();
        boolean synced = DnsGuardPolicy.clockSynced(wall, computeFloor(), state.getLong(DnsGuardPolicy.K_OFFSET_START, wall - now), wall - now);
        Step s = DnsGuardPolicy.next(phase, retry, nonet, nudged, inPhase, ok, synced, t);
        if (!s.nextPhase.equals(phase) || s.writeMode != null || s.nudge || s.result != null) {
            Log.i(TAG, "phase " + phase + " -> " + s.nextPhase + " +" + inPhase + " write=" + (s.writeMode == null ? "none" : s.writeMode)
                + " reason=probe_" + (ok ? "ok" : "fail") + (synced ? "_synced" : ""));
        }
        if (s.nudge) nudge();
        if (s.markNonet) state.set(DnsGuardPolicy.K_NONET, "1");
        if (s.setRetry) state.set(DnsGuardPolicy.K_RETRY, "1");
        if (s.writeMode != null) {
            String spec = DnsGuardPolicy.SPEC_SAVED.equals(s.writeSpec) ? saved : s.writeSpec;
            if (!persistAndWrite(s.nextPhase, s.writeMode, spec, now, "step")) return;
            oppOk = false;
        } else if (!s.nextPhase.equals(phase)) {
            state.set(DnsGuardPolicy.K_PHASE, s.nextPhase);
            if (DnsGuardPolicy.P_IDLE.equals(s.nextPhase)) state.set(DnsGuardPolicy.K_WROTE_MODE, "");
            state.set(DnsGuardPolicy.K_PHASE_UPTIME, Long.toString(now));
            state.commit();
        } else {
            state.commit();
        }
        if (s.result != null) recordResult(s.result, now);
        String np = s.nextPhase;
        if (DnsGuardPolicy.P_IDLE.equals(np)) {
            restoreAts();
            if (DnsGuardPolicy.R_OK.equals(s.result)) noteWallOnThread();
        } else if (!DnsGuardPolicy.P_FINAL.equals(np)) {
            post(this::step, s.delayMs);
        }
    }

    private void recordResult(String result, long now) {
        int writes = state.getInt(DnsGuardPolicy.K_WRITES_BOOT, 0);
        long took = Math.max(0L, now - state.getLong(DnsGuardPolicy.K_RUN_UPTIME, now));
        state.set(DnsGuardPolicy.K_LAST_RESULT, result);
        state.set(DnsGuardPolicy.K_LAST_RESULT_WALL, Long.toString(System.currentTimeMillis()));
        state.commit();
        Log.i(TAG, "result " + result + " writes=" + writes + " took=" + took);
        notifyHome();
    }

    /** Ends the run in the given phase (idle) with a result; no settings write. */
    private void finishRun(String phase, String result, long now) {
        state.set(DnsGuardPolicy.K_PHASE, phase);
        state.set(DnsGuardPolicy.K_WROTE_MODE, "");
        state.set(DnsGuardPolicy.K_PHASE_UPTIME, Long.toString(now));
        state.commit();
        recordResult(result, now);
        restoreAts();
    }

    /** A visible Home re-evaluates its chip through the existing settings-changed path (rare: results only). */
    private void notifyHome() {
        try {
            final OrchardApp a = (OrchardApp) app;
            new Handler(Looper.getMainLooper()).post(a::notifySettingsChanged);
        } catch (RuntimeException e) {
            // the chip refreshes at the next tick or resume
        }
    }

    // ---- auto-time nudge -------------------------------------------------------------------------------------------

    /** Toggles auto_time_sync_enabled 0 -> 1 once so TimeService re-evaluates; the previous value is persisted first. */
    private void nudge() {
        String prev = Settings.Secure.getString(cr, S_ATS);
        state.set(DnsGuardPolicy.K_ATS_PREV, prev == null ? "null" : prev);
        state.set(DnsGuardPolicy.K_NUDGED, "1");
        state.commit();
        try {
            Settings.Secure.putString(cr, S_ATS, "0");
            Settings.Secure.putString(cr, S_ATS, "1");
        } catch (SecurityException e) {
            Log.w(TAG, "nudge refused: " + e);
            restoreAts();
            return;
        }
        Log.i(TAG, "nudge auto_time_sync 0/1");
        post(this::restoreAts, ATS_RESTORE_MS);
    }

    private void restoreAts() {
        String prev = state.get(DnsGuardPolicy.K_ATS_PREV, "");
        if (prev.isEmpty()) return;
        try {
            if ("null".equals(prev)) {
                int n = cr.delete(Settings.Secure.CONTENT_URI, "name=?", new String[] {S_ATS});
                if (n == 0 && Settings.Secure.getString(cr, S_ATS) != null) Settings.Secure.putString(cr, S_ATS, "1");
            } else {
                Settings.Secure.putString(cr, S_ATS, prev);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "ats restore failed: " + e);
        }
        state.remove(DnsGuardPolicy.K_ATS_PREV);
        state.commit();
    }

    // ---- resume and rollback ---------------------------------------------------------------------------------------

    private void resumeOnThread() {
        restoreAts();
        String phase = phase();
        if (DnsGuardPolicy.P_IDLE.equals(phase)) return;
        Timings t = timings();
        long now = up();
        String wrote = state.get(DnsGuardPolicy.K_WROTE_MODE, "");
        String saved = state.get(DnsGuardPolicy.K_SAVED_SPEC, "");
        boolean as = DnsGuardPolicy.settingsAsWritten(wrote, saved, mode(), spec());
        boolean nb = isNewBoot();
        long inPhase = Math.max(0L, now - state.getLong(DnsGuardPolicy.K_PHASE_UPTIME, now));
        Step s = DnsGuardPolicy.resume(phase, as, nb, inPhase, state.getInt(DnsGuardPolicy.K_RETRY, 0),
            state.getInt(DnsGuardPolicy.K_NONET, 0) == 1, t);
        String action = s.writeMode != null ? "write_" + s.writeMode : DnsGuardPolicy.P_IDLE.equals(s.nextPhase) ? "idle" : "continue";
        Log.i(TAG, "resume phase=" + phase + " asWritten=" + as + " newBoot=" + nb + " -> " + action);
        if (nb) {
            for (String k : DnsGuardPolicy.BOOT_COUNTER_KEYS) state.set(k, "0");
            detectProbes = 0;
            detectPosts = 0;
        }
        if (s.writeMode != null) {
            String spec = DnsGuardPolicy.SPEC_SAVED.equals(s.writeSpec) ? saved : s.writeSpec;
            if (!persistAndWrite(s.nextPhase, s.writeMode, spec, now, "resume")) return;
            if (DnsGuardPolicy.P_IDLE.equals(s.nextPhase)) state.set(DnsGuardPolicy.K_WROTE_MODE, "");
            state.commit();
        } else if (DnsGuardPolicy.P_IDLE.equals(s.nextPhase)) {
            state.set(DnsGuardPolicy.K_PHASE, DnsGuardPolicy.P_IDLE);
            state.set(DnsGuardPolicy.K_WROTE_MODE, "");
            state.set(DnsGuardPolicy.K_PHASE_UPTIME, Long.toString(now));
            state.commit();
        }
        if (nb) { state.set(DnsGuardPolicy.K_SEEN_UPTIME, Long.toString(now)); markBoot(); }
        if (s.result != null) recordResult(s.result, now); else state.commit();
        if (!DnsGuardPolicy.P_IDLE.equals(s.nextPhase) && !DnsGuardPolicy.P_FINAL.equals(s.nextPhase)) post(this::step, s.delayMs);
    }

    /**
     * Any unexpected failure: if the settings are still what the guard wrote and not hostname, put the user's hostname and
     * specifier back; end the run as error. Never throws.
     */
    private void safeEnd() {
        try {
            String wrote = state.get(DnsGuardPolicy.K_WROTE_MODE, "");
            String saved = state.get(DnsGuardPolicy.K_SAVED_SPEC, "");
            if (!wrote.isEmpty() && !DnsGuardPolicy.MODE_HOSTNAME.equals(wrote)
                && DnsGuardPolicy.settingsAsWritten(wrote, saved, mode(), spec()) && !saved.isEmpty()) {
                Settings.Global.putString(cr, S_SPEC, saved);
                Settings.Global.putString(cr, S_MODE, DnsGuardPolicy.MODE_HOSTNAME);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "safeEnd write failed: " + e);
        }
        try {
            state.set(DnsGuardPolicy.K_PHASE, DnsGuardPolicy.P_IDLE);
            state.set(DnsGuardPolicy.K_WROTE_MODE, "");
            state.set(DnsGuardPolicy.K_LAST_RESULT, DnsGuardPolicy.R_ERROR);
            state.set(DnsGuardPolicy.K_LAST_RESULT_WALL, Long.toString(System.currentTimeMillis()));
            state.commit();
            restoreAts();
            Log.i(TAG, "result error");
        } catch (RuntimeException e) {
            Log.w(TAG, "safeEnd state failed: " + e);
        }
    }
}
