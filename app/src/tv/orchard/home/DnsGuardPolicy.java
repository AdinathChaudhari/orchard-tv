package tv.orchard.home;

/**
 * PURE JAVA (no android.* imports; host-tested). Every decision of DnsGuard, the Private DNS repair: when a probe is
 * warranted, which phase comes next, which settings write that phase needs, what resume does after process death or a
 * reboot. DnsGuard (Android side) only reads settings, probes, persists and schedules; it never decides.
 *
 * The lockout it repairs (docs/learnings.md 2.10): the Stick restores a stale wall clock after a power-off, strict
 * Private DNS (mode hostname) cannot validate the resolver's TLS certificate, the network never validates, and the time
 * sync never runs. A run goes hostname -> opportunistic (the network works, the clock syncs) -> off (3 s) -> hostname.
 * Invariant: no path ever ends with mode off; a failed repair ends in opportunistic and the next boot restores hostname.
 */
public final class DnsGuardPolicy {
    private DnsGuardPolicy() {}

    // ---- modes, names, state keys ------------------------------------------------------------------------------
    public static final String MODE_HOSTNAME = "hostname";
    public static final String MODE_OPP = "opportunistic";
    public static final String MODE_OFF = "off";
    public static final String DEFAULT_SPEC = "dns.adguard-dns.com";
    public static final String PROBE_SUFFIX = "-dnsotls-ds.metric.gstatic.com";
    /** Step.writeSpec value meaning "the specifier saved at run start" (the policy does not know it). */
    public static final String SPEC_SAVED = "@saved";

    public static final String P_IDLE = "idle", P_OPP = "opp", P_OFF = "off", P_STRICT = "strict", P_FALLBACK = "fallback",
        P_RETRY_OFF = "retry_off", P_RETRY_STRICT = "retry_strict", P_FINAL = "final";

    public static final String R_OK = "ok", R_NONET = "nonet", R_FALLBACK = "fallback", R_FINAL = "final",
        R_ABORTED = "aborted_user", R_RESTORED = "restored_boot", R_ERROR = "error";

    public static final String PREFIX = "dnsg.";
    public static final String K_PHASE = "dnsg.phase", K_WROTE_MODE = "dnsg.wrote_mode", K_SAVED_SPEC = "dnsg.saved_spec",
        K_RETRY = "dnsg.retry", K_NONET = "dnsg.nonet", K_NUDGED = "dnsg.nudged", K_ATS_PREV = "dnsg.ats_prev",
        K_RUN_UPTIME = "dnsg.run_uptime", K_PHASE_UPTIME = "dnsg.phase_uptime", K_RUNS_BOOT = "dnsg.runs_boot",
        K_LAST_RESULT = "dnsg.last_result", K_LAST_RESULT_WALL = "dnsg.last_result_wall", K_LAST_WALL = "dnsg.last_wall",
        K_KNOWN_SPEC = "dnsg.known_spec",
        // per-boot detection counters (persisted so the cap survives a process restart inside one boot)
        K_DETECT_PROBES = "dnsg.detect_probes", K_DETECT_POSTS = "dnsg.detect_posts",
        // additions of the driver: writes counter, run offset (a sync shows as an offset jump), last uptime seen
        K_WRITES_BOOT = "dnsg.writes_boot", K_OFFSET_START = "dnsg.offset_start", K_SEEN_UPTIME = "dnsg.seen_uptime";

    /** Keys that restart at zero on a new boot. */
    public static final String[] BOOT_COUNTER_KEYS = {K_RUNS_BOOT, K_WRITES_BOOT, K_DETECT_PROBES, K_DETECT_POSTS};

    // ---- limits --------------------------------------------------------------------------------------------------
    public static final long STALE_MARGIN_MS = 120000L;
    public static final int ARM_COUNT = 2;
    /** Detection passes (and posts) allowed per boot while idle. */
    public static final int DETECT_CAP = ARM_COUNT + 1;
    public static final int MAX_RUNS_PER_BOOT = 3;
    public static final int MAX_WRITES_PER_BOOT = 14;
    /** Most writes one run can make: opp, off, hostname, opp, off, hostname, opp. */
    public static final int WRITES_PER_RUN = 7;
    public static final int MAX_RETRY = 1;
    public static final long FLOOR_MAX_AHEAD_MS = 20L * 366L * 24L * 3600L * 1000L;
    public static final long WALL_SAVE_EVERY_MS = 900000L;

    /** Every wait of a run. normal() is the shipped timing; fast() (debug_dns_fast=1) divides each wait by 10, minimum 2 s. */
    public static final class Timings {
        public final long firstCheckMs, recheckMs, netWaitMs, netPollMs, clockWaitMs, clockPollMs, nudgeAtMs, offDwellMs,
            confirmMs, confirmPollMs, nonetConfirmMs, retryDelayMs, runCooldownMs;

        private Timings(long div) {
            firstCheckMs = d(20000L, div);
            recheckMs = d(30000L, div);
            netWaitMs = d(60000L, div);
            netPollMs = d(5000L, div);
            clockWaitMs = d(120000L, div);
            clockPollMs = d(3000L, div);
            nudgeAtMs = d(45000L, div);
            offDwellMs = div == 1 ? 3000L : 2000L;
            confirmMs = d(150000L, div);
            confirmPollMs = d(3000L, div);
            nonetConfirmMs = d(20000L, div);
            retryDelayMs = d(300000L, div);
            runCooldownMs = d(180000L, div);
        }

        private static long d(long v, long div) { return div == 1 ? v : Math.max(2000L, v / div); }

        public static Timings normal() { return new Timings(1); }
        public static Timings fast() { return new Timings(10); }
    }

    // ---- clock ---------------------------------------------------------------------------------------------------

    /** The lowest plausible wall time: the build time, or the last wall time saved when that is plausible. */
    public static long floor(long lastWall, long buildMs) {
        if (lastWall < buildMs || lastWall > buildMs + FLOOR_MAX_AHEAD_MS) return buildMs;
        return Math.max(buildMs, lastWall);
    }

    public static boolean stale(long nowWall, long floor) {
        return nowWall < floor - STALE_MARGIN_MS;
    }

    /** offset = wall - elapsedRealtime; a time sync shows as an offset jump. */
    public static boolean clockSynced(long nowWall, long floor, long offsetAtStart, long offsetNow) {
        return nowWall >= floor - STALE_MARGIN_MS || offsetNow - offsetAtStart >= STALE_MARGIN_MS;
    }

    public static boolean detectAllowed(int count) {
        return count < DETECT_CAP;
    }

    /** True when enough failed probes were seen to start a run (a manual check needs ARM_COUNT too; debug runNow needs one). */
    public static boolean startsRun(int armed, boolean runNow) {
        return runNow || armed >= ARM_COUNT;
    }

    /** known_spec is recorded only by an unforced check, and only for a specifier the guard already expects. */
    public static boolean recordsKnownSpec(boolean forced, String spec, String knownSpec) {
        return !forced && specExpected(spec, knownSpec) && !spec.equals(knownSpec);
    }

    public static boolean isPaused(String phase) {
        return P_FALLBACK.equals(phase) || P_FINAL.equals(phase);
    }

    public static final String MP_ABORT = "abort", MP_REPAIR = "repair";

    /**
     * A manual check while paused (fallback/final). Null: not this case. MP_ABORT: the user already changed the settings
     * (they no longer match what the guard wrote), go idle with aborted_user. MP_REPAIR: allow one repair run (the gate still
     * applies the per-boot run and write caps).
     */
    public static String manualPaused(String phase, boolean manual, boolean asWritten) {
        if (!manual || !isPaused(phase)) return null;
        return asWritten ? MP_REPAIR : MP_ABORT;
    }

    public static boolean newBoot(long uptimeNow, long storedUptime) {
        return storedUptime > 0 && uptimeNow < storedUptime;
    }

    // ---- gate ----------------------------------------------------------------------------------------------------

    public static boolean specExpected(String spec, String knownSpec) {
        return spec != null && !spec.isEmpty() && (spec.equals(DEFAULT_SPEC) || spec.equals(knownSpec));
    }

    /** Inputs of gate(). Plain fields so a test builds one in a few lines. */
    public static final class Gate {
        public boolean enabled = true, phaseIdle = true, stale, forced, manual, pausedRepair;
        public String mode = MODE_HOSTNAME, spec = DEFAULT_SPEC, knownSpec;
        public int runsBoot, writesBoot;
        public long sinceLastRunMs = Long.MAX_VALUE;
    }

    /**
     * Null when a probe is warranted, else the skip reason. Fixed order: off, busy, mode=m, spec, cap, cooldown, not_stale.
     * forced bypasses spec, cooldown and not_stale; manual (Settings) bypasses cooldown and not_stale only; nothing bypasses
     * off, mode or cap; busy is bypassed only by pausedRepair (manual out of fallback/final, where the mode is the
     * opportunistic one the guard wrote and the specifier is the saved one).
     */
    public static String gate(Gate g, Timings t) {
        if (!g.enabled) return "off";
        boolean pr = g.pausedRepair && g.manual && !g.forced;     // manual repair out of fallback/final
        if (!g.phaseIdle && !pr) return "busy";
        if (!(pr ? MODE_OPP : MODE_HOSTNAME).equals(g.mode)) return "mode=" + g.mode;
        if (!pr && !g.forced && !specExpected(g.spec, g.knownSpec)) return "spec";
        if (g.runsBoot >= MAX_RUNS_PER_BOOT || g.writesBoot > MAX_WRITES_PER_BOOT - WRITES_PER_RUN) return "cap";
        if (!g.forced && !g.manual && g.sinceLastRunMs < t.runCooldownMs) return "cooldown";
        if (!g.forced && !g.manual && !g.stale) return "not_stale";
        return null;
    }

    /** True while the settings are what the guard last wrote (or hostname with the saved specifier): nobody else touched them. */
    public static boolean settingsAsWritten(String wroteMode, String savedSpec, String curMode, String curSpec) {
        if (wroteMode == null || curMode == null || !curMode.equals(wroteMode)) return false;
        return !MODE_HOSTNAME.equals(wroteMode) || (curSpec != null && curSpec.equals(savedSpec));
    }

    // ---- state machine -------------------------------------------------------------------------------------------

    /** One decision. writeMode null = no write; writeSpec null = leave the specifier; result null = the run goes on. */
    public static final class Step {
        public final String nextPhase, writeMode, writeSpec, result;
        public final long delayMs;
        public final boolean nudge, markNonet, setRetry;

        Step(String nextPhase, String writeMode, String writeSpec, long delayMs, String result,
             boolean nudge, boolean markNonet, boolean setRetry) {
            this.nextPhase = nextPhase;
            this.writeMode = writeMode;
            this.writeSpec = writeSpec;
            this.delayMs = delayMs;
            this.result = result;
            this.nudge = nudge;
            this.markNonet = markNonet;
            this.setRetry = setRetry;
        }
    }

    private static Step stay(String phase, long delay) { return new Step(phase, null, null, delay, null, false, false, false); }
    private static Step write(String phase, String mode, String spec, long delay, String result) {
        return new Step(phase, mode, spec, delay, result, false, false, false);
    }

    public static boolean needsProbe(String phase) {
        return P_OPP.equals(phase) || P_STRICT.equals(phase) || P_RETRY_STRICT.equals(phase);
    }

    /** The decision table (see the class comment of DnsGuard for the narrative). inPhaseMs counts from the last phase change. */
    public static Step next(String phase, int retry, boolean nonet, boolean nudged, long inPhaseMs,
                            boolean probeOk, boolean clockSynced, Timings t) {
        switch (phase) {
            case P_OPP:
                if (!probeOk) {
                    if (inPhaseMs < t.netWaitMs) return stay(P_OPP, t.netPollMs);
                    return new Step(P_OFF, MODE_OFF, null, t.offDwellMs, null, false, true, false);
                }
                if (!clockSynced && inPhaseMs < t.clockWaitMs) {
                    boolean nudge = inPhaseMs >= t.nudgeAtMs && !nudged;
                    return new Step(P_OPP, null, null, t.clockPollMs, null, nudge, false, false);
                }
                return write(P_OFF, MODE_OFF, null, t.offDwellMs, null);
            case P_OFF:
            case P_RETRY_OFF: {
                if (inPhaseMs < t.offDwellMs) return stay(phase, t.offDwellMs - inPhaseMs);
                String to = P_RETRY_OFF.equals(phase) ? P_RETRY_STRICT : P_STRICT;
                return write(to, MODE_HOSTNAME, SPEC_SAVED, t.confirmPollMs, null);
            }
            case P_STRICT:
                if (probeOk) return stay2(P_IDLE, R_OK);
                if (nonet) {
                    if (inPhaseMs >= t.nonetConfirmMs) return stay2(P_IDLE, R_NONET);
                    return stay(P_STRICT, t.confirmPollMs);
                }
                if (inPhaseMs < t.confirmMs) return stay(P_STRICT, t.confirmPollMs);
                if (retry < MAX_RETRY) return write(P_FALLBACK, MODE_OPP, null, t.retryDelayMs, R_FALLBACK);
                return write(P_FINAL, MODE_OPP, null, 0, R_FINAL);
            case P_FALLBACK:
                if (inPhaseMs < t.retryDelayMs) return stay(P_FALLBACK, t.retryDelayMs - inPhaseMs);
                if (retry >= MAX_RETRY) return stay(P_FINAL, 0);
                return new Step(P_RETRY_OFF, MODE_OFF, null, t.offDwellMs, null, false, false, true);
            case P_RETRY_STRICT:
                if (probeOk) return stay2(P_IDLE, R_OK);
                if (inPhaseMs < t.confirmMs) return stay(P_RETRY_STRICT, t.confirmPollMs);
                return write(P_FINAL, MODE_OPP, null, 0, R_FINAL);
            case P_FINAL:
                return stay(P_FINAL, 0);
            default:
                return stay(P_IDLE, 0);
        }
    }

    private static Step stay2(String phase, String result) {
        return new Step(phase, null, null, 0, result, false, false, false);
    }

    /**
     * What to do with a persisted run when the app starts again (process death, force-stop, reboot).
     * Phases opp, off, retry_off, fallback and final hold a non-hostname mode; strict and retry_strict hold hostname.
     */
    public static Step resume(String phase, boolean asWritten, boolean isNewBoot, long inPhaseMs, int retry,
                              boolean nonet, Timings t) {
        if (phase == null || P_IDLE.equals(phase)) return stay(P_IDLE, 0);
        if (!asWritten) return stay2(P_IDLE, R_ABORTED);
        boolean strictPhase = P_STRICT.equals(phase) || P_RETRY_STRICT.equals(phase);
        if (isNewBoot) {
            if (strictPhase) return stay(P_IDLE, 0);
            return write(P_IDLE, MODE_HOSTNAME, SPEC_SAVED, 0, R_RESTORED);
        }
        switch (phase) {
            case P_OFF: return write(P_STRICT, MODE_HOSTNAME, SPEC_SAVED, t.confirmPollMs, null);
            case P_RETRY_OFF: return write(P_RETRY_STRICT, MODE_HOSTNAME, SPEC_SAVED, t.confirmPollMs, null);
            default: return stay(phase, 0);     // opp, strict, retry_strict, fallback, final: carry on with the budget already spent
        }
    }

    /** The Home chip text while the guard has paused ad-blocking DNS; null otherwise. */
    public static String notice(String phase, String lastResult, String mode) {
        return MODE_HOSTNAME.equals(mode) ? null : notice(phase, lastResult);
    }

    public static String notice(String phase, String lastResult) {
        if (P_FALLBACK.equals(phase) || P_RETRY_OFF.equals(phase) || P_RETRY_STRICT.equals(phase) || P_FINAL.equals(phase)) {
            return "Private DNS repair paused ad-blocking DNS until the next restart";
        }
        return null;
    }

    /** A name nobody can have cached; the driver passes System.nanoTime(). */
    public static String probeName(long nonce) {
        return Long.toHexString(nonce) + PROBE_SUFFIX;
    }
}
