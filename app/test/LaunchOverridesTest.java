package tv.orchard.home;

import java.util.Map;

public final class LaunchOverridesTest {
    static int fails;

    static void eq(String name, String want, String got) {
        boolean ok = want == null ? got == null : want.equals(got);
        if (!ok) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }

    public static void main(String[] args) {
        eq("Appstore routed to ADEHomeActivity", "com.amazon.venezia.ade.ADEHomeActivity", LaunchOverrides.activityFor("com.amazon.venezia"));
        eq("other app uses the default intent", null, LaunchOverrides.activityFor("com.netflix.ninja"));
        eq("null package tolerated", null, LaunchOverrides.activityFor(null));
        for (Map.Entry<String, String> en : LaunchOverrides.all().entrySet()) {
            if (!en.getValue().startsWith(en.getKey() + ".")) { fails++; System.err.println("FAIL activity outside its package: " + en); }
        }

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
