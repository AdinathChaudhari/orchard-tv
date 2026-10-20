package tv.orchard.home;

import java.io.File;
import java.nio.file.Files;

public final class CrashWindowTest {
    static int fails;

    static void is(String name, boolean want, boolean got) {
        if (want != got) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }
    static void eq(String name, long want, long got) {
        if (want != got) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }

    public static void main(String[] args) throws Exception {
        long now = 1000000L;
        is("null", false, CrashGuard.window(null, now));
        is("empty", false, CrashGuard.window(new long[0], now));
        is("2 in 60 s", false, CrashGuard.window(new long[] {now - 30000, now - 1000}, now));
        is("3 in 60 s", true, CrashGuard.window(new long[] {now - 50000, now - 30000, now - 1000}, now));
        is("3 across 61 s", false, CrashGuard.window(new long[] {now - 61000, now - 30000, now - 1000}, now));
        is("boundary exactly 60 s inclusive", true, CrashGuard.window(new long[] {now - 60000, now - 30000, now}, now));
        is("boundary 60 s + 1 ms", false, CrashGuard.window(new long[] {now - 60001, now - 30000, now}, now));
        is("future timestamps ignored", false, CrashGuard.window(new long[] {now - 1000, now + 1000, now + 2000}, now));
        is("old crashes do not count", false, CrashGuard.window(new long[] {1, 2, 3, now - 1000}, now));
        is("4 in window", true, CrashGuard.window(new long[] {now - 4000, now - 3000, now - 2000, now - 1000}, now));

        for (int m = 0; m < 16; m++) {
            boolean lo = (m & 8) != 0, gr = (m & 4) != 0, ar = (m & 2) != 0, vh = (m & 1) != 0;
            is("stripAllowed row " + m, m == 15, CrashGuard.stripAllowed(lo, gr, ar, vh));
        }

        File dir = Files.createTempDirectory("orchard-crash").toFile();
        File f = new File(dir, "crashes.txt");
        eq("read missing", 0, CrashGuard.read(f).length);
        for (int i = 1; i <= 15; i++) CrashGuard.record(f, i * 1000L);
        long[] got = CrashGuard.read(f);
        eq("keeps last 10: count", 10, got.length);
        eq("keeps last 10: oldest", 6000L, got[0]);
        eq("keeps last 10: newest", 15000L, got[9]);
        eq("file itself holds <= 10 lines", 10, new String(Files.readAllBytes(f.toPath()), "UTF-8").split("\n").length);
        eq("no temp file left", 0, new File(dir, "crashes.txt.tmp").exists() ? 1 : 0);
        File junk = new File(dir, "junk.txt");
        Files.write(junk.toPath(), "5\nabc\n\n7\n".getBytes("UTF-8"));
        long[] j = CrashGuard.read(junk);
        eq("junk skipped: count", 2, j.length);
        eq("junk skipped: order", 7L, j[1]);

        f.delete(); junk.delete(); dir.delete();
        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
