package tv.orchard.home;

public final class RedirectCheckTest {
    static int fails;

    static void is(String name, boolean want, boolean got) {
        if (want != got) { fails++; System.err.println("FAIL " + name + ": want " + want + " got " + got); }
    }

    public static void main(String[] args) {
        long boot = 1000L;
        is("other-launcher target: never launched as HOME, press seen -> no chip", false, RedirectCheck.doubtful(true, 0L, 2000L, boot));
        is("was target, press seen, no HOME launch since boot -> chip", true, RedirectCheck.doubtful(true, 500L, 2000L, boot));
        is("HOME launch since boot -> no chip", false, RedirectCheck.doubtful(true, 1500L, 2000L, boot));
        is("no press seen since boot -> no chip", false, RedirectCheck.doubtful(true, 500L, 900L, boot));
        is("redirect off -> no chip", false, RedirectCheck.doubtful(false, 500L, 2000L, boot));

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
    }
}
