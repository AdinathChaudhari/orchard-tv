package tv.orchard.home;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class HiddenRulesTest {
    static int fails;

    static void is(String name, boolean want, boolean got) {
        if (want != got) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }

    public static void main(String[] args) {
        Set<String> none = Collections.<String>emptySet();
        Set<String> defaults = CatalogOrder.hidden(null);
        int user = 0;
        int sys = HiddenRules.FLAG_SYSTEM;
        int sysUpd = HiddenRules.FLAG_SYSTEM | HiddenRules.FLAG_UPDATED_SYSTEM_APP;

        is("user app shown", false, HiddenRules.isHidden("com.netflix.ninja", user, none, none));
        is("plain system app hidden", true, HiddenRules.isHidden("com.amazon.dcp", sys, none, none));
        is("updated plain system app hidden", true, HiddenRules.isHidden("com.amazon.dcp", sysUpd, none, none));
        is("allow-listed YouTube shown", false, HiddenRules.isHidden("com.amazon.firetv.youtube", sys, none, none));
        is("allow-listed firebat shown", false, HiddenRules.isHidden("com.amazon.firebat", HiddenRules.FLAG_SYSTEM | HiddenRules.FLAG_UPDATED_SYSTEM_APP, none, none));
        is("allow-listed updated Prime Video shown", false, HiddenRules.isHidden("com.amazon.avod", sysUpd, none, none));
        is("allow-listed Appstore shown", false, HiddenRules.isHidden("com.amazon.venezia", sys, none, none));
        Set<String> hidStore = new HashSet<String>(Collections.singleton("com.amazon.venezia"));
        is("user-hidden Appstore hidden", true, HiddenRules.isHidden("com.amazon.venezia", sys, hidStore, none));
        Set<String> shown = new HashSet<String>(Collections.singleton("com.amazon.dcp"));
        is("user-unhidden system app shown", false, HiddenRules.isHidden("com.amazon.dcp", sys, none, shown));
        Set<String> hid = new HashSet<String>(Collections.singleton("com.netflix.ninja"));
        is("user-hidden user app hidden", true, HiddenRules.isHidden("com.netflix.ninja", user, hid, none));
        Set<String> hidYt = new HashSet<String>(Collections.singleton("com.amazon.firetv.youtube"));
        is("user-hidden allow-listed app hidden", true, HiddenRules.isHidden("com.amazon.firetv.youtube", sys, hidYt, none));
        is("Home on Fire hidden by default", true, HiddenRules.isHidden("io.github.toolicious.homeonfire", user, defaults, none));
        Set<String> showHof = new HashSet<String>(Collections.singleton("io.github.toolicious.homeonfire"));
        Set<String> noHof = new HashSet<String>(defaults);
        noHof.remove("io.github.toolicious.homeonfire");
        is("Home on Fire explicitly unhidden shown", false, HiddenRules.isHidden("io.github.toolicious.homeonfire", user, noHof, showHof));
        is("hide beats show", true, HiddenRules.isHidden("com.amazon.dcp", sys, new HashSet<String>(shown), shown));
        is("null package hidden", true, HiddenRules.isHidden(null, user, none, none));
        is("null sets tolerated", false, HiddenRules.isHidden("a.b", user, null, null));

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
