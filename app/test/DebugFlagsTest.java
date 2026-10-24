package tv.orchard.home;

import java.io.File;
import java.nio.file.Files;

public final class DebugFlagsTest {
    static int fails;

    static void eq(String name, String want, String got) {
        if (want == null ? got != null : !want.equals(got)) { fails++; System.err.println("FAIL " + name + ": want [" + want + "] got [" + got + "]"); }
    }

    public static void main(String[] args) {
        try {
            File dir = Files.createTempDirectory("orchard-dbg").toFile();
            File f = new File(dir, "orchard.conf");
            Config c = new Config(f);
            c.set("labels", "always");
            c.save();
            int n = DebugFlags.apply(c, "debug_card=banner; debug_motion=reduced;crashguard_strip=on;labels=focus;debug_=x;debug_empty=;junk", false);
            eq("two debug keys written", "2", String.valueOf(n));
            Config re = new Config(f);
            eq("debug_card persisted", "banner", re.get("debug_card", null));
            eq("debug_motion persisted", "reduced", re.get("debug_motion", null));
            eq("crashguard_strip refused", null, re.get("crashguard_strip", null));
            eq("non-debug key untouched", "always", re.get("labels", null));
            eq("empty value refused", null, re.get("debug_empty", null));
            eq("same value again is no change", "0", String.valueOf(DebugFlags.apply(re, "debug_card=banner", false)));
            re.set("crashguard_strip", "on");
            re.save();
            eq("clear removes debug keys only", "2", String.valueOf(DebugFlags.apply(re, null, true)));
            Config after = new Config(f);
            eq("debug_card gone", null, after.get("debug_card", null));
            eq("crashguard_strip kept by clear", "on", after.get("crashguard_strip", null));
            eq("labels kept by clear", "always", after.get("labels", null));
            eq("debug_crash refused", "0", String.valueOf(DebugFlags.apply(after, "debug_crash=1", false)));
            eq("crash_on_home refused", "0", String.valueOf(DebugFlags.apply(after, "crash_on_home=1", false)));
            eq("both refused in one spec, other key kept", "1", String.valueOf(DebugFlags.apply(after, "debug_crash=1;crash_on_home=1;debug_card=banner", false)));
            Config chk = new Config(f);
            eq("debug_crash not persisted", null, chk.get("debug_crash", null));
            eq("crash_on_home not persisted", null, chk.get("crash_on_home", null));
            eq("debug_card persisted alongside", "banner", chk.get("debug_card", null));
            // values that came from orchard.conf survive the exported clear
            chk.set("debug_crash", "1");
            chk.set("crash_on_home", "1");
            chk.save();
            eq("clear removes only the allowed debug key", "1", String.valueOf(DebugFlags.apply(chk, null, true)));
            Config keep = new Config(f);
            eq("conf debug_crash kept by clear", "1", keep.get("debug_crash", null));
            eq("conf crash_on_home kept by clear", "1", keep.get("crash_on_home", null));
            eq("debug_slow_anim still allowed", "1", String.valueOf(DebugFlags.apply(keep, "debug_slow_anim=10", true)));
            // shell-only channel (DebugReceiver)
            File f2 = new File(Files.createTempDirectory("orchard-dbg2").toFile(), "orchard.conf");
            Config s = new Config(f2);
            eq("shell sets four", "4", String.valueOf(DebugFlags.applyShell(s, "debug_crash=1; crash_on_home=1;crashguard_strip=on;debug_card=banner;labels=always;crashguard_strip2=on", null)));
            Config sr = new Config(f2);
            eq("shell debug_crash", "1", sr.get("debug_crash", null));
            eq("shell crash_on_home", "1", sr.get("crash_on_home", null));
            eq("shell crashguard_strip", "on", sr.get("crashguard_strip", null));
            eq("shell other key refused", null, sr.get("labels", null));
            eq("shell lookalike key refused", null, sr.get("crashguard_strip2", null));
            eq("shell bad strip value refused", "0", String.valueOf(DebugFlags.applyShell(sr, "crashguard_strip=maybe", null)));
            eq("shell bad crash_on_home value refused", "0", String.valueOf(DebugFlags.applyShell(sr, "crash_on_home=yes", null)));
            eq("shell same value is no change", "0", String.valueOf(DebugFlags.applyShell(sr, "crash_on_home=1", null)));
            eq("shell unset two, refuses labels", "2", String.valueOf(DebugFlags.applyShell(sr, null, "debug_crash;crash_on_home;labels;debug_missing")));
            Config su = new Config(f2);
            eq("shell unset removed debug_crash", null, su.get("debug_crash", null));
            eq("shell unset removed crash_on_home", null, su.get("crash_on_home", null));
            eq("shell unset kept crashguard_strip", "on", su.get("crashguard_strip", null));
            eq("shell unset runs before set", "2", String.valueOf(DebugFlags.applyShell(su, "crashguard_strip=off", "crashguard_strip")));
            eq("shell set after unset", "off", new Config(f2).get("crashguard_strip", null));
            eq("shell no-op", "0", String.valueOf(DebugFlags.applyShell(su, null, null)));
            // the exported channel still refuses all three
            eq("exported still refuses crashguard_strip", "0", String.valueOf(DebugFlags.apply(su, "crashguard_strip=on", false)));
            // DnsGuard test keys: the exported channel drops all three (set and clear), the shell channel keeps them
            File f4 = new File(Files.createTempDirectory("orchard-dbg4").toFile(), "orchard.conf");
            Config d = new Config(f4);
            eq("exported refuses the three debug_dns keys", "0", String.valueOf(DebugFlags.apply(d,
                "debug_dns_restore_host=evil.example;debug_dns_fast=1;debug_dns_force=1", false)));
            eq("none of them persisted", null, new Config(f4).get("debug_dns_restore_host", null));
            eq("shell accepts the three debug_dns keys", "3", String.valueOf(DebugFlags.applyShell(d,
                "debug_dns_restore_host=dns.adguard-dns.com;debug_dns_fast=1;debug_dns_force=1", null)));
            Config dr = new Config(f4);
            eq("shell debug_dns_restore_host", "dns.adguard-dns.com", dr.get("debug_dns_restore_host", null));
            eq("shell debug_dns_fast", "1", dr.get("debug_dns_fast", null));
            eq("exported clear keeps them", "0", String.valueOf(DebugFlags.apply(dr, null, true)));
            eq("still there after the exported clear", "1", new Config(f4).get("debug_dns_force", null));
            eq("shell unsets them", "3", String.valueOf(DebugFlags.applyShell(dr, null, "debug_dns_restore_host;debug_dns_fast;debug_dns_force")));
            // debug_art=placeholder: logo-free letter plates, settable over both channels, cleared by debug_clear
            File f3 = new File(Files.createTempDirectory("orchard-dbg3").toFile(), "orchard.conf");
            Config a = new Config(f3);
            eq("placeholder off by default", "false", String.valueOf(DebugFlags.placeholderArt(a)));
            eq("placeholder null config", "false", String.valueOf(DebugFlags.placeholderArt(null)));
            eq("exported sets debug_art", "1", String.valueOf(DebugFlags.apply(a, "debug_art=placeholder", false)));
            eq("placeholder on (exported)", "true", String.valueOf(DebugFlags.placeholderArt(new Config(f3))));
            eq("clear removes debug_art", "1", String.valueOf(DebugFlags.apply(a, null, true)));
            eq("placeholder off after clear", "false", String.valueOf(DebugFlags.placeholderArt(new Config(f3))));
            eq("shell sets debug_art", "1", String.valueOf(DebugFlags.applyShell(a, "debug_art=Placeholder ", null)));
            eq("placeholder on (shell, case/space)", "true", String.valueOf(DebugFlags.placeholderArt(new Config(f3))));
            eq("shell unsets debug_art", "1", String.valueOf(DebugFlags.applyShell(a, null, "debug_art")));
            eq("placeholder off after unset", "false", String.valueOf(DebugFlags.placeholderArt(new Config(f3))));
            DebugFlags.apply(a, "debug_art=real", false);
            eq("other value is not placeholder", "false", String.valueOf(DebugFlags.placeholderArt(new Config(f3))));
        } catch (Exception e) {
            fails++;
            System.err.println("FAIL DebugFlagsTest exception " + e);
        }
        eq("time 06:00", "360", String.valueOf(DebugFlags.timeMinute("06:00")));
        eq("time 23:59", "1439", String.valueOf(DebugFlags.timeMinute(" 23:59 ")));
        eq("time 00:00", "0", String.valueOf(DebugFlags.timeMinute("00:00")));
        eq("time 24:00 bad", "-1", String.valueOf(DebugFlags.timeMinute("24:00")));
        eq("time 12:60 bad", "-1", String.valueOf(DebugFlags.timeMinute("12:60")));
        eq("time 6:00 bad", "-1", String.valueOf(DebugFlags.timeMinute("6:00")));
        eq("time null", "-1", String.valueOf(DebugFlags.timeMinute(null)));
        eq("wx clear", "0", String.valueOf(DebugFlags.wxCode("clear")));
        eq("wx cloudy", "3", String.valueOf(DebugFlags.wxCode("Cloudy")));
        eq("wx rain", "63", String.valueOf(DebugFlags.wxCode("rain")));
        eq("wx fog", "45", String.valueOf(DebugFlags.wxCode("fog")));
        eq("wx bogus", "-1", String.valueOf(DebugFlags.wxCode("snow")));
        eq("wx rain is raining", "true", String.valueOf(WeatherParse.raining(DebugFlags.wxCode("rain"))));
        eq("wx fog is haze", String.valueOf(WeatherParse.WX_HAZE), String.valueOf(WeatherParse.condition(DebugFlags.wxCode("fog"))));
        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
