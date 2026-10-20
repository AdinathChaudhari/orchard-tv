package tv.orchard.home;

import java.util.GregorianCalendar;
import java.util.TimeZone;

/** PURE JAVA (no android.* imports; host-tested). Weather chip, caption and screensaver-line strings. */
public final class RainText {
    private RainText() {}

    /** Hourly precipitation probability (percent) at or above which rain counts as likely. */
    public static final int RAIN_LIKELY_PCT = 40;

    private static final String DOT = " \u00b7 ";
    private static final String DEG = "\u00b0";
    private static final String[] WD = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
    private static final String[] MON = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};

    /** First hour after currentHour (up to 23) with pop >= 40; -1 if none. pop[h] -1 = missing. */
    public static int likelyHour(int[] pop, int currentHour) {
        if (pop == null) return -1;
        for (int h = Math.max(currentHour + 1, 0); h < 24 && h < pop.length; h++) {
            if (pop[h] >= RAIN_LIKELY_PCT) return h;
        }
        return -1;
    }

    /** Minute of day as zero-padded HH:MM, rounded and wrapped into 0..1439. */
    public static String hhmm(double minute) {
        long m = Math.round(minute) % 1440L;
        if (m < 0) m += 1440L;
        return two((int) (m / 60)) + ":" + two((int) (m % 60));
    }

    /** Rounded temperature with degree sign, Celsius or Fahrenheit. */
    public static String temp(double c, boolean f) {
        return Math.round(f ? c * 9 / 5 + 32 : c) + DEG;
    }

    /** "H 28\u00b0  L 20\u00b0" (two spaces between). */
    public static String highLow(double hiC, double loC, boolean f) {
        return "H " + temp(hiC, f) + "  L " + temp(loC, f);
    }

    /** Chip text: empty when dry, "\u00b7 Rain 70%" when raining ("\u00b7 Rain" without a value). */
    /** Quantise a loop fraction t in [0,1) to {@code steps} equal steps (30 fps rain: the value moves every other frame). */
    public static float step(float t, int steps) {
        if (steps <= 1) return t;
        float v = (float) Math.floor(t * steps) / steps;
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    /** Caption under the clock while it is raining. */
    public static final String RAINING_NOW = "Raining now";

    /** Chip text from a resolved percentage: "\u00b7 Rain 70%", or "\u00b7 Rain" when pct < 0. */
    public static String rainNowPct(boolean raining, int pct) {
        if (!raining) return "";
        return pct >= 0 ? "\u00b7 Rain " + pct + "%" : "\u00b7 Rain";
    }

    public static String rainNow(boolean raining, int[] pop, int currentHour) {
        if (!raining) return "";
        if (pop != null && currentHour >= 0 && currentHour < pop.length && pop[currentHour] >= 0) {
            return "\u00b7 Rain " + pop[currentHour] + "%";
        }
        return "\u00b7 Rain";
    }

    /** Caption under the clock: rain forecast, else next sun event, else just the city. */
    public static String caption(String city, int likelyHour, double rise, double set, int minute) {
        if (likelyHour >= 0) return "Rain likely from " + hhmm(likelyHour * 60.0);
        if (Double.isNaN(rise) || Double.isNaN(set)) return city;
        if (minute >= set || minute < rise) return city + DOT + "Sunrise " + hhmm(rise);
        return city + DOT + "Sunset " + hhmm(set);
    }

    /** Fahrenheit? 'f' yes, 'c' no, anything else (auto) only for the US. */
    public static boolean useF(String units, String countryCode) {
        if ("f".equals(units)) return true;
        if ("c".equals(units)) return false;
        return "US".equalsIgnoreCase(countryCode);
    }

    /** "Tue 20 Oct" (English, locale-independent, month 1..12). */
    public static String dateLabel(int y, int m, int d) {
        GregorianCalendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(y, m - 1, d);
        int wd = c.get(GregorianCalendar.DAY_OF_WEEK) - 1;
        return WD[wd] + " " + d + " " + MON[m - 1];
    }

    /** Screensaver line under the giant clock. */
    public static String dreamLine(String date, boolean showWeather, String temp, boolean raining, int pct, int likelyHour, String city) {
        if (!showWeather) return date;
        if (raining) return date + DOT + temp + DOT + (pct < 0 ? "Rain" : "Rain " + pct + "%");
        if (likelyHour >= 0) return date + DOT + temp + DOT + "Rain from " + two(likelyHour) + ":00";
        return date + DOT + temp + " " + city;
    }

    private static String two(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }
}
