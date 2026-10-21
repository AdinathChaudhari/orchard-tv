package tv.orchard.home;

import java.util.TimeZone;

/**
 * PURE JAVA (no android.* imports; host-tested). The one place every clock, date and greeting reads "now" from, so
 * debug_time pins them all alike (home strip, Settings strip, screensaver clock, greeting). The date never rolls.
 */
public final class TimeSource {
    private TimeSource() {}

    /** The instant to display: real nowMs, or today (in tz) at the debug_time minute when debugTime parses. */
    public static long now(long realMs, String debugTime, TimeZone tz) {
        return ClockFormat.pinnedMillis(realMs, DebugFlags.timeMinute(debugTime), tz);
    }

    /** True when debugTime pins the clock. */
    public static boolean pinned(String debugTime) { return DebugFlags.timeMinute(debugTime) >= 0; }

    /** Minute of day to display for a real minute-of-day: the pinned one when set. */
    public static int minute(int realMinute, String debugTime) {
        int d = DebugFlags.timeMinute(debugTime);
        return d >= 0 ? d : realMinute;
    }
}
