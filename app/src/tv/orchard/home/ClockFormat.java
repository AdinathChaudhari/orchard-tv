package tv.orchard.home;

/**
 * Screensaver clock geometry: text, burn-in offsets, fixed-cell layout and worst-case ink margins.
 * PURE JAVA (no android.* imports; host-tested).
 *
 * Numbers are the mockup tokens (Rev 5/6): digits are Inter Thin, drawn one glyph per fixed cell
 * (the proportional glyph centred in the cell), the colon is two circles, everything in a 1920 x 1080 design canvas.
 */
public final class ClockFormat {
    private ClockFormat() {}

    public static final int CANVAS_W = 1920, CANVAS_H = 1080;
    public static final int DIGIT_H = 560;
    /** Token ratio (Inter cap height .727), kept even if the shipped font's ratio differs slightly. */
    public static final float FONT_PX = 560 / 0.727f;
    public static final float DIGIT_CELL_EM = 0.6484f, COLON_CELL_EM = 0.2686f;
    public static final float TRACK_EM = -0.125f;
    /**
     * Device nudge 0, not the mockup's dream-nudge -0.031: the mockup renders Google Fonts' variable Inter, whose
     * Thin side-bearings differ from the shipped Inter 4.1 Thin subset. With the shipped metrics the cell group is
     * already ink-centred (00:00 116 / 116 px at -12 / +12); -0.031 would leave 92 px, under the 96 px overscan.
     */
    public static final float NUDGE_EM = 0f;
    public static final int COLON_D = 26, COLON_GAP = 190;
    public static final float FG_ALPHA = 0.85f;
    public static final int LINE_SIZE = 44, LINE_GAP = 64;
    public static final float LINE_ALPHA = 0.56f, LINE_TRACK = 0.03f;
    public static final int LIFT = 24, SHIFT_BOX = 24;
    public static final float DIM_ALPHA = 0.40f;
    public static final int DIM_AFTER_MIN = 10, DIM_MS = 1500;

    /** Burn-in offsets (x, y) in px, one per minute, cycling; all within +-12 (mockup DREAM_SHIFTS). */
    public static final int[][] SHIFTS = {{0,0},{12,-6},{-12,6},{6,12},{-6,-12},{12,10},{-10,-4},{4,-10},{-4,10},{10,2},{-12,-10},{8,-12}};

    // Source: Inter 4.1 Thin (extras/ttf), app/res/font/inter_thin.ttf, default (proportional) cmap glyphs for 0-9.
    public static final int UPM = 2048;
    public static final int CAP_HEIGHT = 1490;   // OS/2 sCapHeight (1490 / 2048 = 0.7275)
    static final int[] ADV  = {1210, 752, 1272, 1192, 1218, 1130, 1156, 1120, 1222, 1156};
    static final int[] XMIN = { 148, 146,  190,  160,  152,  148,  148,  128,  148,  148};
    static final int[] XMAX = {1062, 520, 1088, 1044, 1068,  982, 1008,  992, 1074, 1008};

    /** 24 h, zero-padded "HH:MM". */
    public static String hhmm(int h, int m) {
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    public static int[] offset(long epochMinute) {
        return SHIFTS[(int) Math.floorMod(epochMinute, (long) SHIFTS.length)];
    }

    /** Ms until just after the next minute boundary (+20 ms so the clock never reads the old minute). */
    public static long delayToNextMinute(long nowMs) {
        return 60000 - Math.floorMod(nowMs, 60000L) + 20;
    }

    /** debug_time support: nowMs when minuteOfDay < 0, else today (in tz) at HH:MM:00. The date never rolls. */
    public static long pinnedMillis(long nowMs, int minuteOfDay, java.util.TimeZone tz) {
        if (minuteOfDay < 0) return nowMs;
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.setTimeInMillis(nowMs);
        c.set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        c.set(java.util.Calendar.MINUTE, minuteOfDay % 60);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    public static float digitPitch() { return (DIGIT_CELL_EM + TRACK_EM) * FONT_PX; }
    public static float colonPitch() { return (COLON_CELL_EM + TRACK_EM) * FONT_PX; }

    /** Left edge of the first digit cell for a group shifted by dx (nudge applies to the digits only). */
    public static float groupLeft(float dx) {
        return (CANVAS_W - (4 * digitPitch() + colonPitch())) / 2f + NUDGE_EM * FONT_PX + dx;
    }

    /** Left edge of cell index 0..4 (index 2 is the colon cell). */
    public static float cellLeft(int index, float dx) {
        float x = groupLeft(dx);
        for (int i = 0; i < index; i++) x += (i == 2) ? colonPitch() : digitPitch();
        return x;
    }

    /** Where to draw a digit glyph (its origin) so the proportional glyph is centred in its fixed cell. */
    public static float glyphX(char digit, float cellLeft) {
        return cellLeft + (digitPitch() - ADV[digit - '0'] * FONT_PX / UPM) / 2f;
    }

    /** {left margin of the first digit's ink, right margin of the last digit's ink} in px for "HH:MM" at offset dx. */
    public static float[] inkMargins(String hhmm, float dx) {
        float s = FONT_PX / UPM;
        char first = hhmm.charAt(0), last = hhmm.charAt(4);
        float left = glyphX(first, cellLeft(0, dx)) + XMIN[first - '0'] * s;
        float right = glyphX(last, cellLeft(4, dx)) + XMAX[last - '0'] * s;
        return new float[] {left, CANVAS_W - right};
    }

    /** x of both colon circle centres (the colon cell centre) for offset dx. */
    public static float colonCx(float dx) { return cellLeft(2, dx) + colonPitch() / 2f; }

    /** y of the upper (-1) or lower (+1) colon circle centre, given the digit-box top. */
    public static float colonCy(float digitTop, int side) { return digitTop + DIGIT_H / 2f + side * COLON_GAP / 2f; }

    public static float lineBoxH() { return 1.2f * LINE_SIZE; }
    public static float groupHeight() { return DIGIT_H + LINE_GAP + lineBoxH(); }
    /** Top of the group (digit box top) before the burn-in dy. */
    public static float groupTop() { return CANVAS_H / 2f - LIFT - groupHeight() / 2f; }
    public static float digitBaseline() { return groupTop() + DIGIT_H; }
    public static float lineTop() { return digitBaseline() + LINE_GAP; }
}
