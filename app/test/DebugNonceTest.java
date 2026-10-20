package tv.orchard.home;

import java.util.HashMap;
import java.util.Map;

public final class DebugNonceTest {
    static int fails;

    static void is(String name, boolean want, boolean got) {
        if (want != got) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }

    static final class Mem implements DebugNonce.Store {
        final Map<String, String> m = new HashMap<String, String>();
        int puts;
        @Override public String get(String k, String d) { String v = m.get(k); return v == null ? d : v; }
        @Override public void put(String k, String v) { m.put(k, v); puts++; }
    }

    public static void main(String[] args) {
        Mem s = new Mem();
        is("null nonce refused", false, DebugNonce.consume(s, null));
        is("empty nonce refused", false, DebugNonce.consume(s, "  "));
        is("null store refused", false, DebugNonce.consume(null, "a"));
        is("refusals record nothing", true, s.puts == 0);
        is("new nonce accepted", true, DebugNonce.consume(s, "n1"));
        is("new nonce recorded", true, "n1".equals(s.get(DebugNonce.KEY, null)));
        is("replay of same nonce refused", false, DebugNonce.consume(s, "n1"));
        is("replay with whitespace refused", false, DebugNonce.consume(s, " n1 "));
        is("replay did not write again", true, s.puts == 1);
        is("next new nonce accepted", true, DebugNonce.consume(s, "n2"));
        is("old nonce after newer one accepted again only if different from last", true, DebugNonce.consume(s, "n1"));
        is("null after consume still refused", false, DebugNonce.consume(s, null));
        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
