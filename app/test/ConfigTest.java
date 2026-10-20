package tv.orchard.home;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

public final class ConfigTest {
    static int fails;

    static void eq(String name, String want, String got) {
        if (want == null ? got != null : !want.equals(got)) { fails++; System.err.println("FAIL " + name + ": want [" + want + "] got [" + got + "]"); }
    }
    static void is(String name, boolean want, boolean got) {
        if (want != got) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> m = Config.parse("# comment\n# hidden=1\n\n a = 1 \nunknown_key=zzz\nnoequals\n=novalue\nb=2\na=3\nc=\n");
        eq("parse trims, later dup wins", "3", m.get("a"));
        eq("parse unknown kept", "zzz", m.get("unknown_key"));
        eq("parse empty value", "", m.get("c"));
        is("parse skips comment", false, m.containsKey("# comment"));
        is("parse skips commented key=value", false, m.containsKey("# hidden") || m.containsKey("hidden"));
        is("parse skips no '='", false, m.containsKey("noequals"));
        is("parse skips empty key", false, m.containsKey(""));
        eq("parse key order", "[a, unknown_key, b, c]", m.keySet().toString());
        eq("parse null", "0", String.valueOf(Config.parse(null).size()));
        eq("parse CRLF", "1", Config.parse("a=1\r\n").get("a"));
        eq("value containing =", "x=y", Config.parse("k=x=y").get("k"));
        eq("roundtrip", m.toString(), Config.parse(Config.serialize(m)).toString());
        eq("serialize flattens newline", "k=a b\n", Config.serialize(Config.parse("k=a").size() == 1 ? single("k", "a\nb") : null));

        File dir = Files.createTempDirectory("orchard-config").toFile();
        File f = new File(dir, "orchard.conf");
        Config c = new Config(f);
        eq("missing file default", "dflt", c.get("nope", "dflt"));
        c.set("labels", "always");
        c.set("future_key", "keepme");
        c.set("n", "42");
        c.set("flag", "ON");
        c.save();
        is("atomic: no temp left", false, new File(dir, "orchard.conf.tmp").exists());
        is("file written", true, f.isFile());
        is("own write not reloaded", false, c.refresh());

        Config d = new Config(f);
        eq("reload value", "always", d.get("labels", "x"));
        eq("reload unknown key", "keepme", d.get("future_key", "x"));
        is("getBool ON", true, d.getBool("flag", false));
        is("getBool bad -> def", true, d.getBool("n", true));
        is("getBool missing -> def", false, d.getBool("zzz", false));
        eq("getInt", "42", String.valueOf(d.getInt("n", 0)));
        eq("getInt bad -> def", "7", String.valueOf(d.getInt("labels", 7)));
        d.set("future_key", "changed");
        d.save();
        eq("unknown key survives re-save", "changed", new Config(f).get("future_key", "x"));
        d.remove("future_key");
        d.save();
        eq("remove persists", "gone", new Config(f).get("future_key", "gone"));

        // signature refresh: external edit is picked up, unchanged file is not
        Config e = new Config(f);
        is("refresh unchanged", false, e.refresh());
        long mt = f.lastModified();
        Files.write(f.toPath(), "labels=never\nextra=1\n".getBytes("UTF-8"));
        f.setLastModified(mt + 5000);
        is("refresh after external edit", true, e.refresh());
        eq("refresh value", "never", e.get("labels", "x"));
        eq("refresh new key", "1", e.get("extra", "x"));
        is("refresh again no change", false, e.refresh());
        // same mtime, different length is still detected
        Files.write(f.toPath(), "labels=longer-value\n".getBytes("UTF-8"));
        f.setLastModified(mt + 5000);
        is("refresh on length change", true, e.refresh());
        // same length, new mtime
        Files.write(f.toPath(), "labels=LONGER-VALUE\n".getBytes("UTF-8"));
        f.setLastModified(mt + 9000);
        is("refresh on mtime change", true, e.refresh());
        eq("refresh mtime value", "LONGER-VALUE", e.get("labels", "x"));
        // delete -> empty
        f.delete();
        is("refresh after delete", true, e.refresh());
        eq("deleted -> default", "dflt", e.get("labels", "dflt"));

        File[] left = dir.listFiles();
        if (left != null) for (File x : left) x.delete();
        dir.delete();
        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }

    static Map<String, String> single(String k, String v) {
        Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        m.put(k, v);
        return m;
    }
}
