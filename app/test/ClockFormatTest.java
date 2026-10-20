package tv.orchard.home;

public final class ClockFormatTest {
    static int fails;

    static void eq(String name, String want, String got) {
        if (!want.equals(got)) { fails++; System.err.println("FAIL " + name + ": want [" + want + "] got [" + got + "]"); }
    }
    static void ok(String name, boolean c) {
        if (!c) { fails++; System.err.println("FAIL " + name); }
    }
    static void near(String name, float want, float got, float tol) {
        System.out.println("  " + name + ": model " + got + " (mockup ~" + want + ")");
        ok(name + " within " + tol, Math.abs(want - got) <= tol);
    }

    public static void main(String[] args) {
        eq("hhmm 00:05", "00:05", ClockFormat.hhmm(0, 5));
        eq("hhmm 09:07", "09:07", ClockFormat.hhmm(9, 7));
        eq("hhmm 23:59", "23:59", ClockFormat.hhmm(23, 59));

        boolean[] seen = new boolean[ClockFormat.SHIFTS.length];
        for (int[] s : ClockFormat.SHIFTS) {
            ok("shift x in box", Math.abs(s[0]) <= 12);
            ok("shift y in box", Math.abs(s[1]) <= 12);
        }
        eq("12 shifts", "12", String.valueOf(ClockFormat.SHIFTS.length));
        long base = 29_000_123L;
        for (long k = 0; k < 12; k++) {
            int[] o = ClockFormat.offset(base + k);
            for (int i = 0; i < 12; i++) if (o == ClockFormat.SHIFTS[i]) seen[i] = true;
        }
        for (int i = 0; i < 12; i++) ok("offset visits entry " + i, seen[i]);
        ok("offset negative minute safe", ClockFormat.offset(-1) == ClockFormat.SHIFTS[11]);

        for (long k : new long[] {0, 1, 12345678}) {
            for (long d : new long[] {0, 1, 59999}) {
                long dl = ClockFormat.delayToNextMinute(k * 60000 + d);
                ok("delay >= 1 (k=" + k + ",d=" + d + ")", dl >= 1);
                ok("delay <= 60020 (k=" + k + ",d=" + d + ")", dl <= 60020);
            }
        }
        eq("delay at boundary", "60020", String.valueOf(ClockFormat.delayToNextMinute(60000)));
        eq("delay 1 ms before boundary", "21", String.valueOf(ClockFormat.delayToNextMinute(59999)));

        java.util.TimeZone ist = java.util.TimeZone.getTimeZone("Asia/Kolkata");
        long noonIst = 1_760_000_000_000L;   // an arbitrary instant
        long p1720 = ClockFormat.pinnedMillis(noonIst, 17 * 60 + 20, ist);
        java.util.Calendar a = java.util.Calendar.getInstance(ist), b = java.util.Calendar.getInstance(ist);
        a.setTimeInMillis(noonIst); b.setTimeInMillis(p1720);
        eq("pinned 17:20 hour", "17", String.valueOf(b.get(java.util.Calendar.HOUR_OF_DAY)));
        eq("pinned 17:20 minute", "20", String.valueOf(b.get(java.util.Calendar.MINUTE)));
        eq("pinned 17:20 second", "0", String.valueOf(b.get(java.util.Calendar.SECOND)));
        eq("pinned 17:20 same date", a.get(java.util.Calendar.YEAR) + "-" + a.get(java.util.Calendar.DAY_OF_YEAR),
                b.get(java.util.Calendar.YEAR) + "-" + b.get(java.util.Calendar.DAY_OF_YEAR));
        eq("pinned -1 identity", String.valueOf(noonIst), String.valueOf(ClockFormat.pinnedMillis(noonIst, -1, ist)));
        b.setTimeInMillis(ClockFormat.pinnedMillis(noonIst, 23 * 60 + 59, ist));
        eq("pinned 23:59 hour", "23", String.valueOf(b.get(java.util.Calendar.HOUR_OF_DAY)));
        eq("pinned 23:59 minute", "59", String.valueOf(b.get(java.util.Calendar.MINUTE)));
        eq("pinned 23:59 same date", String.valueOf(a.get(java.util.Calendar.DAY_OF_YEAR)), String.valueOf(b.get(java.util.Calendar.DAY_OF_YEAR)));
        b.setTimeInMillis(ClockFormat.pinnedMillis(noonIst, 0, ist));
        eq("pinned 00:00 same date", String.valueOf(a.get(java.util.Calendar.DAY_OF_YEAR)), String.valueOf(b.get(java.util.Calendar.DAY_OF_YEAR)));

        float minL = 1e9f, minR = 1e9f;
        String minLt = "", minRt = "";
        for (int h = 0; h < 24; h++) {
            for (int m = 0; m < 60; m++) {
                String t = ClockFormat.hhmm(h, m);
                float l = ClockFormat.inkMargins(t, -12)[0];
                float r = ClockFormat.inkMargins(t, 12)[1];
                if (l < minL) { minL = l; minLt = t; }
                if (r < minR) { minR = r; minRt = t; }
            }
        }
        System.out.println("  worst left margin @dx=-12: " + minL + " at " + minLt + "; worst right @dx=+12: " + minR + " at " + minRt);
        ok("worst left margin >= 96", minL >= 96);
        ok("worst right margin >= 96", minR >= 96);

        // Pinned to the shipped Inter 4.1 Thin metrics (the mockup's 118 / 114 / 109 / 104 were measured with Google
        // Fonts' variable Inter, a different Thin build). A retune of track, nudge or digit height moves these.
        System.out.println("model pins (shipped font):");
        near("00:00 left @-12", 116.1f, ClockFormat.inkMargins("00:00", -12)[0], 1);
        near("00:00 right @+12", 116.1f, ClockFormat.inkMargins("00:00", 12)[1], 1);
        near("08:08 left @-12", 116.1f, ClockFormat.inkMargins("08:08", -12)[0], 1);
        near("08:08 right @+12", 113.8f, ClockFormat.inkMargins("08:08", 12)[1], 1);
        near("00:02 right @+12", 117.9f, ClockFormat.inkMargins("00:02", 12)[1], 1);
        float[] even = ClockFormat.inkMargins("00:00", 0);
        ok("00:00 ink centred at dx 0", Math.abs(even[0] - even[1]) <= 1);

        near("digit pitch", 403.2f, ClockFormat.digitPitch(), 0.5f);
        near("colon pitch", 110.6f, ClockFormat.colonPitch(), 0.5f);
        float top = ClockFormat.groupTop();
        near("group height", 676.8f, ClockFormat.groupHeight(), 0.01f);
        near("baseline", top + 560, ClockFormat.digitBaseline(), 0.01f);
        near("line top", top + 560 + 64, ClockFormat.lineTop(), 0.01f);
        near("colon upper cy", top + 280 - 95, ClockFormat.colonCy(top, -1), 0.01f);
        near("colon lower cy", top + 280 + 95, ClockFormat.colonCy(top, 1), 0.01f);

        if (fails > 0) { System.err.println(fails + " failure(s)"); System.exit(1); }
        System.out.println("ClockFormatTest PASS");
    }
}
