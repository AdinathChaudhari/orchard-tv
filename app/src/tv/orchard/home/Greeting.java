package tv.orchard.home;

/** PURE JAVA (no android.* imports; host-tested). Time-of-day greeting; boundaries mirror the greet-* tokens. */
public final class Greeting {
    private Greeting() {}

    /** Local minute-of-day at which each greeting starts. */
    public static final int MORNING = 300;
    public static final int AFTERNOON = 720;
    public static final int EVENING = 1020;
    public static final int NIGHT = 1260;

    /** Greeting for a local minute of day (0..1439). */
    public static String at(int minuteOfDay) {
        if (minuteOfDay >= NIGHT || minuteOfDay < MORNING) return "Good night";
        if (minuteOfDay >= EVENING) return "Good evening";
        if (minuteOfDay >= AFTERNOON) return "Good afternoon";
        return "Good morning";
    }
}
