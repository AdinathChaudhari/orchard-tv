package tv.orchard.home;

import tv.orchard.home.DnsGuardPolicy.Gate;
import tv.orchard.home.DnsGuardPolicy.Step;
import tv.orchard.home.DnsGuardPolicy.Timings;

public final class DnsGuardPolicyTest {
    static int fails;
    static final Timings T = Timings.normal();

    static void is(String name, boolean want, boolean got) {
        if (want != got) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }

    static void eq(String name, Object want, Object got) {
        if (want == null ? got != null : !want.equals(got)) { fails++; System.err.println("FAIL " + name + ": want [" + want + "] got [" + got + "]"); }
    }

    static Step n(String phase, int retry, boolean nonet, boolean nudged, long inPhase, boolean probe, boolean synced) {
        return DnsGuardPolicy.next(phase, retry, nonet, nudged, inPhase, probe, synced, T);
    }

    static void step(String name, Step s, String phase, String write, String result, long delay) {
        eq(name + " phase", phase, s.nextPhase);
        eq(name + " write", write, s.writeMode);
        eq(name + " result", result, s.result);
        if (delay >= 0) eq(name + " delay", Long.valueOf(delay), Long.valueOf(s.delayMs));
    }

    static String gate(Gate g) { return DnsGuardPolicy.gate(g, T); }

    public static void main(String[] args) {
        long build = 1_700_000_000_000L;
        long m = DnsGuardPolicy.STALE_MARGIN_MS;

        // T1 floor / stale
        eq("floor: last below build", Long.valueOf(build), Long.valueOf(DnsGuardPolicy.floor(build - 5, build)));
        eq("floor: last above build", Long.valueOf(build + 99), Long.valueOf(DnsGuardPolicy.floor(build + 99, build)));
        eq("floor: far future ignored", Long.valueOf(build),
            Long.valueOf(DnsGuardPolicy.floor(build + DnsGuardPolicy.FLOOR_MAX_AHEAD_MS + 1, build)));
        eq("floor: at the ahead limit kept", Long.valueOf(build + DnsGuardPolicy.FLOOR_MAX_AHEAD_MS),
            Long.valueOf(DnsGuardPolicy.floor(build + DnsGuardPolicy.FLOOR_MAX_AHEAD_MS, build)));
        is("stale: now = floor - margin is not stale", false, DnsGuardPolicy.stale(build - m, build));
        is("stale: now = floor - margin + 1 is not stale", false, DnsGuardPolicy.stale(build - m + 1, build));
        is("stale: now = floor - margin - 1 is stale", true, DnsGuardPolicy.stale(build - m - 1, build));
        is("stale: far behind", true, DnsGuardPolicy.stale(build - 86400000L, build));
        is("stale: ahead", false, DnsGuardPolicy.stale(build + 1000, build));

        // T2 clockSynced
        is("synced: offset jump margin-1", false, DnsGuardPolicy.clockSynced(build - 10 * m, build, 0, m - 1));
        is("synced: offset jump margin", true, DnsGuardPolicy.clockSynced(build - 10 * m, build, 0, m));
        is("synced: backwards offset", false, DnsGuardPolicy.clockSynced(build - 10 * m, build, 0, -5 * m));
        is("synced: now at the floor, no jump", true, DnsGuardPolicy.clockSynced(build, build, 0, 0));
        is("synced: now just inside margin", true, DnsGuardPolicy.clockSynced(build - m, build, 0, 0));
        is("synced: now outside margin, no jump", false, DnsGuardPolicy.clockSynced(build - m - 1, build, 0, 0));

        // newBoot / specExpected / probeName
        is("newBoot: uptime lower", true, DnsGuardPolicy.newBoot(1000, 5000));
        is("newBoot: uptime higher", false, DnsGuardPolicy.newBoot(6000, 5000));
        is("newBoot: nothing stored", false, DnsGuardPolicy.newBoot(10, 0));
        is("spec: default", true, DnsGuardPolicy.specExpected("dns.adguard-dns.com", null));
        is("spec: known", true, DnsGuardPolicy.specExpected("dns.example", "dns.example"));
        is("spec: other", false, DnsGuardPolicy.specExpected("dns.google", "dns.example"));
        is("spec: empty", false, DnsGuardPolicy.specExpected("", ""));
        is("spec: null", false, DnsGuardPolicy.specExpected(null, null));
        String pn = DnsGuardPolicy.probeName(0xabcdef12345L);
        eq("probeName", "abcdef12345-dnsotls-ds.metric.gstatic.com", pn);

        // T3 gate
        Gate g = new Gate();
        g.stale = true;
        eq("gate: all clear", null, gate(g));
        g = new Gate(); g.stale = true; g.enabled = false; eq("gate off", "off", gate(g));
        g = new Gate(); g.stale = true; g.phaseIdle = false; eq("gate busy", "busy", gate(g));
        g = new Gate(); g.stale = true; g.mode = "opportunistic"; eq("gate mode opp", "mode=opportunistic", gate(g));
        g = new Gate(); g.stale = true; g.mode = "off"; eq("gate mode off", "mode=off", gate(g));
        g = new Gate(); g.stale = true; g.mode = null; eq("gate mode null", "mode=null", gate(g));
        g = new Gate(); g.stale = true; g.spec = "dns.google"; eq("gate spec", "spec", gate(g));
        g = new Gate(); g.stale = true; g.spec = "dns.google"; g.knownSpec = "dns.google"; eq("gate known spec ok", null, gate(g));
        g = new Gate(); g.stale = true; g.runsBoot = 2; eq("gate runs 2 ok", null, gate(g));
        g = new Gate(); g.stale = true; g.runsBoot = 3; eq("gate runs == max skips", "cap", gate(g));
        g = new Gate(); g.stale = true; g.writesBoot = 7; eq("gate writes 7 ok", null, gate(g));
        g = new Gate(); g.stale = true; g.writesBoot = 8; eq("gate writes 8 skips", "cap", gate(g));
        g = new Gate(); g.stale = true; g.sinceLastRunMs = T.runCooldownMs - 1; eq("gate cooldown", "cooldown", gate(g));
        g = new Gate(); g.stale = true; g.sinceLastRunMs = T.runCooldownMs; eq("gate cooldown boundary ok", null, gate(g));
        g = new Gate(); g.stale = false; eq("gate not stale", "not_stale", gate(g));
        // order: off beats everything, busy beats mode, mode beats spec, spec beats cap, cap beats cooldown beats not_stale
        g = new Gate(); g.enabled = false; g.phaseIdle = false; g.mode = "off"; eq("order off first", "off", gate(g));
        g = new Gate(); g.phaseIdle = false; g.mode = "off"; eq("order busy before mode", "busy", gate(g));
        g = new Gate(); g.mode = "off"; g.spec = "x"; eq("order mode before spec", "mode=off", gate(g));
        g = new Gate(); g.spec = "x"; g.runsBoot = 9; eq("order spec before cap", "spec", gate(g));
        g = new Gate(); g.runsBoot = 9; g.sinceLastRunMs = 0; eq("order cap before cooldown", "cap", gate(g));
        g = new Gate(); g.sinceLastRunMs = 0; eq("order cooldown before not_stale", "cooldown", gate(g));
        // forced bypasses spec, cooldown, not_stale; never off, busy, mode, cap
        g = new Gate(); g.forced = true; g.spec = "dns.google"; g.sinceLastRunMs = 0; g.stale = false;
        eq("forced bypasses spec+cooldown+not_stale", null, gate(g));
        g = new Gate(); g.forced = true; g.enabled = false; eq("forced keeps off", "off", gate(g));
        g = new Gate(); g.forced = true; g.phaseIdle = false; eq("forced keeps busy", "busy", gate(g));
        g = new Gate(); g.forced = true; g.mode = "opportunistic"; eq("forced keeps mode", "mode=opportunistic", gate(g));
        g = new Gate(); g.forced = true; g.runsBoot = 3; eq("forced keeps cap", "cap", gate(g));
        g = new Gate(); g.forced = true; g.writesBoot = 14; eq("forced keeps write cap", "cap", gate(g));
        // manual bypasses cooldown + not_stale only
        g = new Gate(); g.manual = true; g.sinceLastRunMs = 0; g.stale = false; eq("manual bypasses cooldown+stale", null, gate(g));
        g = new Gate(); g.manual = true; g.spec = "dns.google"; eq("manual keeps spec", "spec", gate(g));
        g = new Gate(); g.manual = true; g.mode = "off"; eq("manual keeps mode", "mode=off", gate(g));

        // T4 settingsAsWritten
        is("written: hostname + same spec", true, DnsGuardPolicy.settingsAsWritten("hostname", "a", "hostname", "a"));
        is("written: hostname + other spec", false, DnsGuardPolicy.settingsAsWritten("hostname", "a", "hostname", "b"));
        is("written: hostname + null spec", false, DnsGuardPolicy.settingsAsWritten("hostname", "a", "hostname", null));
        is("written: off + any spec", true, DnsGuardPolicy.settingsAsWritten("off", "a", "off", "zzz"));
        is("written: opp + any spec", true, DnsGuardPolicy.settingsAsWritten("opportunistic", "a", "opportunistic", "zzz"));
        is("written: opportunistic vs hostname", false, DnsGuardPolicy.settingsAsWritten("opportunistic", "a", "hostname", "a"));
        is("written: off vs opportunistic", false, DnsGuardPolicy.settingsAsWritten("off", "a", "opportunistic", "a"));
        is("written: null mode", false, DnsGuardPolicy.settingsAsWritten("off", "a", null, "a"));

        // T5 next() table
        step("opp no probe early", n("opp", 0, false, false, 0, false, false), "opp", null, null, T.netPollMs);
        step("opp no probe just before net wait", n("opp", 0, false, false, T.netWaitMs - 1, false, false), "opp", null, null, T.netPollMs);
        Step s = n("opp", 0, false, false, T.netWaitMs, false, false);
        step("opp no probe at net wait", s, "off", "off", null, T.offDwellMs);
        is("opp no probe marks nonet", true, s.markNonet);
        s = n("opp", 0, false, false, 0, true, false);
        step("opp probe, clock not synced", s, "opp", null, null, T.clockPollMs);
        is("no nudge early", false, s.nudge);
        is("no nudge at nudge-1", false, n("opp", 0, false, false, T.nudgeAtMs - 1, true, false).nudge);
        is("nudge at nudgeAt", true, n("opp", 0, false, false, T.nudgeAtMs, true, false).nudge);
        is("nudge once only", false, n("opp", 0, false, true, T.nudgeAtMs + 5000, true, false).nudge);
        s = n("opp", 0, false, false, T.clockWaitMs - 1, true, false);
        step("opp probe, last poll before clock wait", s, "opp", null, null, T.clockPollMs);
        step("opp probe, clock synced", n("opp", 0, false, false, 5000, true, true), "off", "off", null, T.offDwellMs);
        is("synced does not mark nonet", false, n("opp", 0, false, false, 5000, true, true).markNonet);
        step("opp probe, clock wait over", n("opp", 0, false, false, T.clockWaitMs, true, false), "off", "off", null, T.offDwellMs);
        s = n("off", 0, false, false, 0, false, false);
        step("off dwell not over", s, "off", null, null, T.offDwellMs);
        step("off dwell remaining", n("off", 0, false, false, 1000, false, false), "off", null, null, T.offDwellMs - 1000);
        step("off dwell one ms short", n("off", 0, false, false, T.offDwellMs - 1, false, false), "off", null, null, 1);
        s = n("off", 0, false, false, T.offDwellMs, false, false);
        step("off dwell over", s, "strict", "hostname", null, T.confirmPollMs);
        eq("off writes saved spec", DnsGuardPolicy.SPEC_SAVED, s.writeSpec);
        s = n("retry_off", 1, false, true, T.offDwellMs, false, false);
        step("retry_off dwell over", s, "retry_strict", "hostname", null, T.confirmPollMs);
        eq("retry_off writes saved spec", DnsGuardPolicy.SPEC_SAVED, s.writeSpec);
        step("retry_off dwell not over", n("retry_off", 1, false, true, 10, false, false), "retry_off", null, null, T.offDwellMs - 10);
        step("strict probe ok", n("strict", 0, false, true, 1000, true, true), "idle", null, "ok", -1);
        step("strict waits", n("strict", 0, false, true, T.confirmMs - 1, false, true), "strict", null, null, T.confirmPollMs);
        s = n("strict", 0, false, true, T.confirmMs, false, true);
        step("strict fails at confirm -> fallback", s, "fallback", "opportunistic", "fallback", T.retryDelayMs);
        step("strict nonet waits", n("strict", 0, true, true, T.nonetConfirmMs - 1, false, true), "strict", null, null, T.confirmPollMs);
        step("strict nonet ends idle without write", n("strict", 0, true, true, T.nonetConfirmMs, false, true), "idle", null, "nonet", -1);
        step("strict nonet far past confirm still idle", n("strict", 0, true, true, T.confirmMs * 3, false, true), "idle", null, "nonet", -1);
        step("strict nonet probe ok", n("strict", 0, true, true, 100, true, true), "idle", null, "ok", -1);
        step("fallback waits", n("fallback", 0, false, true, 1000, false, true), "fallback", null, null, T.retryDelayMs - 1000);
        step("fallback one ms short", n("fallback", 0, false, true, T.retryDelayMs - 1, false, true), "fallback", null, null, 1);
        s = n("fallback", 0, false, true, T.retryDelayMs, false, true);
        step("fallback -> retry_off", s, "retry_off", "off", null, T.offDwellMs);
        is("retry becomes 1", true, s.setRetry);
        step("fallback after retry stays final", n("fallback", 1, false, true, T.retryDelayMs, false, true), "final", null, null, 0);
        step("retry_strict probe ok", n("retry_strict", 1, false, true, 500, true, true), "idle", null, "ok", -1);
        step("retry_strict waits", n("retry_strict", 1, false, true, T.confirmMs - 1, false, true), "retry_strict", null, null, T.confirmPollMs);
        step("retry_strict fails -> final", n("retry_strict", 1, false, true, T.confirmMs, false, true), "final", "opportunistic", "final", 0);
        step("strict at retry=1 never falls back again", n("strict", 1, false, true, T.confirmMs, false, true), "final", "opportunistic", "final", 0);
        step("final never writes", n("final", 1, false, true, 999999, true, true), "final", null, null, 0);
        step("idle never writes", n("idle", 0, false, false, 0, true, true), "idle", null, null, 0);
        is("probes needed in opp/strict/retry_strict only", true,
            DnsGuardPolicy.needsProbe("opp") && DnsGuardPolicy.needsProbe("strict") && DnsGuardPolicy.needsProbe("retry_strict")
            && !DnsGuardPolicy.needsProbe("off") && !DnsGuardPolicy.needsProbe("fallback") && !DnsGuardPolicy.needsProbe("final"));

        // T6 path walk with a fake clock
        walk("happy", Scenario.HAPPY, 3, "ok");
        walk("nonet", Scenario.NONET, 3, "nonet");
        walk("fallback+retry ok", Scenario.RETRY_OK, 6, "ok");
        walk("fallback+retry fail", Scenario.RETRY_FAIL, 7, "final");
        walk("clock stuck, then ok", Scenario.CLOCK_STUCK, 3, "ok");

        // T7 resume
        s = DnsGuardPolicy.resume("opp", true, true, 5000, 0, false, T);
        step("new boot + opp", s, "idle", "hostname", "restored_boot", 0);
        eq("new boot restore uses saved spec", DnsGuardPolicy.SPEC_SAVED, s.writeSpec);
        step("new boot + fallback", DnsGuardPolicy.resume("fallback", true, true, 5000, 0, false, T), "idle", "hostname", "restored_boot", 0);
        step("new boot + final", DnsGuardPolicy.resume("final", true, true, 5000, 1, false, T), "idle", "hostname", "restored_boot", 0);
        step("new boot + off", DnsGuardPolicy.resume("off", true, true, 5000, 0, false, T), "idle", "hostname", "restored_boot", 0);
        step("new boot + retry_off", DnsGuardPolicy.resume("retry_off", true, true, 5000, 1, false, T), "idle", "hostname", "restored_boot", 0);
        step("new boot + strict", DnsGuardPolicy.resume("strict", true, true, 5000, 0, false, T), "idle", null, null, 0);
        step("new boot + retry_strict", DnsGuardPolicy.resume("retry_strict", true, true, 5000, 1, false, T), "idle", null, null, 0);
        step("idle resumes to nothing", DnsGuardPolicy.resume("idle", true, true, 0, 0, false, T), "idle", null, null, 0);
        for (String ph : new String[] {"opp", "off", "strict", "fallback", "final", "retry_off", "retry_strict"}) {
            step("not as written " + ph, DnsGuardPolicy.resume(ph, false, false, 5000, 0, false, T), "idle", null, "aborted_user", 0);
            step("not as written on a new boot " + ph, DnsGuardPolicy.resume(ph, false, true, 5000, 0, false, T), "idle", null, "aborted_user", 0);
        }
        step("same boot opp continues", DnsGuardPolicy.resume("opp", true, false, 5000, 0, false, T), "opp", null, null, 0);
        step("same boot off writes hostname", DnsGuardPolicy.resume("off", true, false, 1000, 0, false, T), "strict", "hostname", null, T.confirmPollMs);
        step("same boot retry_off writes hostname", DnsGuardPolicy.resume("retry_off", true, false, 1000, 1, false, T), "retry_strict", "hostname", null, T.confirmPollMs);
        step("same boot strict continues", DnsGuardPolicy.resume("strict", true, false, 5000, 0, false, T), "strict", null, null, 0);
        step("same boot fallback continues", DnsGuardPolicy.resume("fallback", true, false, 5000, 0, false, T), "fallback", null, null, 0);
        step("same boot final stays", DnsGuardPolicy.resume("final", true, false, 5000, 1, false, T), "final", null, null, 0);
        // the carried budget decides at once: strict with inPhase >= confirm falls back on the very next step
        step("carried strict budget decides at once", n("strict", 0, false, true, T.confirmMs + 1, false, true),
            "fallback", "opportunistic", "fallback", T.retryDelayMs);
        step("carried opp budget decides at once", n("opp", 0, false, false, T.netWaitMs + 1, false, false), "off", "off", null, T.offDwellMs);

        // notice
        eq("notice fallback", "Private DNS repair paused ad-blocking DNS until the next restart", DnsGuardPolicy.notice("fallback", "fallback"));
        eq("notice final", "Private DNS repair paused ad-blocking DNS until the next restart", DnsGuardPolicy.notice("final", "final"));
        eq("notice retry_off", "Private DNS repair paused ad-blocking DNS until the next restart", DnsGuardPolicy.notice("retry_off", ""));
        eq("notice retry_strict", "Private DNS repair paused ad-blocking DNS until the next restart", DnsGuardPolicy.notice("retry_strict", ""));
        eq("no notice idle", null, DnsGuardPolicy.notice("idle", "ok"));
        eq("no notice opp", null, DnsGuardPolicy.notice("opp", ""));

        // manual repair out of paused (fallback/final)
        eq("manualPaused: fallback as written repairs", "repair", DnsGuardPolicy.manualPaused("fallback", true, true));
        eq("manualPaused: final as written repairs", "repair", DnsGuardPolicy.manualPaused("final", true, true));
        eq("manualPaused: user restored hostname aborts", "abort", DnsGuardPolicy.manualPaused("fallback", true, false));
        eq("manualPaused: final restored aborts", "abort", DnsGuardPolicy.manualPaused("final", true, false));
        eq("manualPaused: automatic never", null, DnsGuardPolicy.manualPaused("final", false, true));
        eq("manualPaused: idle is not paused", null, DnsGuardPolicy.manualPaused("idle", true, true));
        eq("manualPaused: opp is not paused", null, DnsGuardPolicy.manualPaused("opp", true, true));
        g = new Gate(); g.manual = true; g.pausedRepair = true; g.phaseIdle = false; g.mode = "opportunistic"; g.spec = "x";
        eq("paused repair passes busy, mode opp, any spec", null, gate(g));
        g.runsBoot = 3; eq("paused repair keeps run cap", "cap", gate(g));
        g = new Gate(); g.manual = true; g.pausedRepair = true; g.phaseIdle = false; g.mode = "opportunistic"; g.writesBoot = 8;
        eq("paused repair keeps write cap", "cap", gate(g));
        g = new Gate(); g.manual = true; g.pausedRepair = true; g.phaseIdle = false; g.mode = "hostname";
        eq("paused repair needs the opportunistic mode", "mode=hostname", gate(g));
        g = new Gate(); g.manual = true; g.pausedRepair = true; g.phaseIdle = false; g.mode = "opportunistic"; g.enabled = false;
        eq("paused repair keeps off", "off", gate(g));
        g = new Gate(); g.pausedRepair = true; g.phaseIdle = false; g.mode = "opportunistic";
        eq("paused repair needs manual", "busy", gate(g));
        g = new Gate(); g.manual = true; g.phaseIdle = false;
        eq("manual without pausedRepair stays busy", "busy", gate(g));
        eq("no notice with hostname back", null, DnsGuardPolicy.notice("fallback", "fallback", "hostname"));
        eq("no notice final with hostname back", null, DnsGuardPolicy.notice("final", "final", "hostname"));
        eq("notice while opportunistic", "Private DNS repair paused ad-blocking DNS until the next restart",
            DnsGuardPolicy.notice("final", "final", "opportunistic"));

        // manual check needs ARM_COUNT failed probes
        is("startsRun: one failed probe is not enough", false, DnsGuardPolicy.startsRun(1, false));
        is("startsRun: ARM_COUNT failed probes", true, DnsGuardPolicy.startsRun(DnsGuardPolicy.ARM_COUNT, false));
        is("startsRun: debug runNow needs one", true, DnsGuardPolicy.startsRun(1, true));

        // per-boot detection cap persisted and rolled
        is("detect: below cap", true, DnsGuardPolicy.detectAllowed(DnsGuardPolicy.DETECT_CAP - 1));
        is("detect: at cap", false, DnsGuardPolicy.detectAllowed(DnsGuardPolicy.DETECT_CAP));
        java.util.List<String> bc = java.util.Arrays.asList(DnsGuardPolicy.BOOT_COUNTER_KEYS);
        is("boot counters roll detect probes", true, bc.contains(DnsGuardPolicy.K_DETECT_PROBES));
        is("boot counters roll detect posts", true, bc.contains(DnsGuardPolicy.K_DETECT_POSTS));
        is("boot counters roll runs and writes", true, bc.contains(DnsGuardPolicy.K_RUNS_BOOT) && bc.contains(DnsGuardPolicy.K_WRITES_BOOT));

        // known_spec
        is("known: forced never records", false, DnsGuardPolicy.recordsKnownSpec(true, "dns.adguard-dns.com", null));
        is("known: forced other spec never records", false, DnsGuardPolicy.recordsKnownSpec(true, "dns.google", null));
        is("known: unforced expected default records", true, DnsGuardPolicy.recordsKnownSpec(false, "dns.adguard-dns.com", null));
        is("known: unforced unexpected spec does not", false, DnsGuardPolicy.recordsKnownSpec(false, "dns.google", null));
        is("known: already known is not rewritten", false, DnsGuardPolicy.recordsKnownSpec(false, "dns.google", "dns.google"));
        is("known: null spec", false, DnsGuardPolicy.recordsKnownSpec(false, null, null));

        // fast timings
        Timings f = Timings.fast();
        eq("fast confirm", Long.valueOf(15000), Long.valueOf(f.confirmMs));
        eq("fast retry delay", Long.valueOf(30000), Long.valueOf(f.retryDelayMs));
        is("fast waits at least 2 s", true, f.confirmPollMs >= 2000 && f.netPollMs >= 2000 && f.offDwellMs >= 2000);

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }

    // ---- path walk: a fake driver that mirrors DnsGuard's apply rules -------------------------------------------

    enum Scenario { HAPPY, NONET, RETRY_OK, RETRY_FAIL, CLOCK_STUCK }

    /** Whether the probe succeeds at this point of the scenario (mode is what the fake driver last wrote). */
    static boolean probe(Scenario sc, String phase, long runMs) {
        switch (sc) {
            case HAPPY: return true;
            case CLOCK_STUCK: return true;
            case NONET: return false;
            case RETRY_OK: return !"strict".equals(phase);
            case RETRY_FAIL: return "opp".equals(phase);
            default: return false;
        }
    }

    static void walk(String name, Scenario sc, int wantWrites, String wantResult) {
        String phase = "opp";
        int retry = 0;
        boolean nonet = false, nudged = false;
        long now = 0, phaseStart = 0;
        int writes = 1;                                   // the run-start write of opportunistic
        String lastWrite = "opportunistic";
        String result = null;
        long budget = T.netWaitMs + T.clockWaitMs + 2 * (T.offDwellMs + T.confirmMs) + T.retryDelayMs + 60000;
        now += T.netPollMs;
        int guard = 0;
        while (!phase.equals("idle") && !phase.equals("final") && guard++ < 10000) {
            long inPhase = now - phaseStart;
            boolean needs = DnsGuardPolicy.needsProbe(phase);
            boolean ok = needs && probe(sc, phase, now);
            boolean synced = sc != Scenario.CLOCK_STUCK || inPhase >= 60000;
            Step st = DnsGuardPolicy.next(phase, retry, nonet, nudged, inPhase, ok, synced, T);
            if (st.nudge) nudged = true;
            if (st.markNonet) nonet = true;
            if (st.setRetry) retry = 1;
            if (st.writeMode != null) { writes++; lastWrite = st.writeMode; }
            if (!st.nextPhase.equals(phase)) { phase = st.nextPhase; phaseStart = now; }
            if (st.result != null) result = st.result;     // the last result of the run is the one that counts
            now += st.delayMs;
        }
        is(name + ": terminated", true, guard < 10000 && (phase.equals("idle") || phase.equals("final")));
        is(name + ": within budget", true, now <= budget);
        eq(name + ": writes", Integer.valueOf(wantWrites), Integer.valueOf(writes));
        eq(name + ": result", wantResult, result);
        is(name + ": last write is never off", true, !"off".equals(lastWrite));
        is(name + ": writes within per-run cap", true, writes <= DnsGuardPolicy.WRITES_PER_RUN);
        if (name.equals("happy") || name.equals("nonet") || name.startsWith("fallback+retry ok") || name.equals("clock stuck, then ok")) {
            eq(name + ": ends on hostname", "hostname", lastWrite);
        } else {
            eq(name + ": ends on opportunistic", "opportunistic", lastWrite);
        }
        if (sc == Scenario.CLOCK_STUCK) is(name + ": nudged once before the sync", true, nudged);
    }
}
