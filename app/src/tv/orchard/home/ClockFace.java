package tv.orchard.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.util.Calendar;

/**
 * Giant Inter Thin clock shared by ClockDream and the Quick Panel preview (PLAN Rev 5/6, 9.7).
 * One custom View, no children, no hardware layer, always black (never reads Theme: night-safe).
 * Four digit glyphs in fixed cells, a colon of two circles, one small date/weather line.
 * All geometry (ClockFormat design px, scaled by Ui.pxf) is computed in onSizeChanged and tick(), never in onDraw;
 * onDraw is 4 drawText + 2 drawCircle + 1 drawText and allocates nothing.
 * Once a minute: new strings, a burn-in offset (a canvas translate after the black fill, so no strip of the window behind shows) and one invalidate().
 * After ClockFormat.DIM_AFTER_MIN minutes it dims to ClockFormat.DIM_ALPHA. Nothing runs per second or per frame.
 * Never touches the network: the weather part reads the cache only.
 */
public class ClockFace extends View {
    private static final int BG = 0xFF000000;

    private final Paint digitPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint colonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRun = this::onTick;   // method refs, not anonymous classes (d8 8.2.2 crash, see OnAnimEnd)
    private final Runnable dimRun = this::dim;

    private boolean reduced = false;
    private boolean running = false;

    private final char[] digits = {'0', '0', '0', '0'};
    private final float[] digitX = new float[4];
    private float digitY, colonX, colonY1, colonY2, colonR, lineX, lineY;
    private String line = "";
    private boolean laidOut = false;
    private float offX, offY;     // burn-in shift: applied to the canvas content, so the black fill never moves off the screen edge

    public ClockFace(Context c) {
        super(c);
        digitPaint.setTypeface(Ui.thin());
        digitPaint.setTextSize(Ui.pxf(ClockFormat.FONT_PX));
        digitPaint.setLetterSpacing(0f);
        digitPaint.setColor(0xFFFFFFFF);
        digitPaint.setAlpha(Math.round(ClockFormat.FG_ALPHA * 255f));
        colonPaint.setColor(0xFFFFFFFF);
        colonPaint.setAlpha(Math.round(ClockFormat.FG_ALPHA * 255f));
        linePaint.setTypeface(Ui.regular());
        linePaint.setTextSize(Ui.pxf(ClockFormat.LINE_SIZE));
        linePaint.setLetterSpacing(ClockFormat.LINE_TRACK);
        linePaint.setTextAlign(Paint.Align.CENTER);
        linePaint.setColor(0xFFFFFFFF);
        linePaint.setAlpha(Math.round(ClockFormat.LINE_ALPHA * 255f));
        colonR = Ui.pxf(ClockFormat.COLON_D) / 2f;
        compute();
    }

    /** Kept for the callers; the screensaver clock is always 24 h (ClockFormat.hhmm), so this is ignored. */
    public void setClock24h(boolean h24) { /* ignored by design */ }

    public void setReducedMotion(boolean reduced) {
        this.reduced = reduced;
    }

    /** Compute, then tick at every minute boundary; dim after ClockFormat.DIM_AFTER_MIN minutes. */
    public void start() {
        stopInternal();
        running = true;
        compute();
        invalidate();
        handler.postDelayed(tickRun, ClockFormat.delayToNextMinute(System.currentTimeMillis()));
        handler.postDelayed(dimRun, ClockFormat.DIM_AFTER_MIN * 60000L);
    }

    public void stop() {
        stopInternal();
    }

    private void stopInternal() {
        running = false;
        handler.removeCallbacks(tickRun);
        handler.removeCallbacks(dimRun);
        animate().cancel();
        setAlpha(1f);
        offX = 0f;
        offY = 0f;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopInternal();
        super.onDetachedFromWindow();
    }

    private void onTick() {
        if (!running) return;
        compute();
        invalidate();
        handler.postDelayed(tickRun, ClockFormat.delayToNextMinute(System.currentTimeMillis()));
    }

    private void dim() {
        if (!running) return;
        if (reduced) {
            setAlpha(ClockFormat.DIM_ALPHA);
        } else {
            animate().alpha(ClockFormat.DIM_ALPHA).setDuration(ClockFormat.DIM_MS).withLayer();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        compute();
    }

    // ---- strings and geometry (never in onDraw) -------------------------------------------------

    private void compute() {
        Config cfg = new Config(FireTvBridge.settingsFile(getContext()));
        cfg.refresh();
        long now = TimeSource.now(System.currentTimeMillis(), cfg.get(DebugFlags.KEY_TIME, null), java.util.TimeZone.getDefault());   // debug_time pins this clock too
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        String t = ClockFormat.hhmm(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        for (int i = 0; i < 4; i++) digits[i] = t.charAt(i < 2 ? i : i + 1);
        line = buildLine(cal, cfg);

        // Layout origin: the 1920 x 1080 design canvas centred in the view (the view is full-screen on the TV).
        float ox = (getWidth() - Ui.pxf(ClockFormat.CANVAS_W)) / 2f;
        float oy = (getHeight() - Ui.pxf(ClockFormat.CANVAS_H)) / 2f;
        if (getWidth() == 0) { ox = 0f; oy = 0f; }
        float pitch = ClockFormat.digitPitch();
        float x = ClockFormat.groupLeft(0f);
        for (int i = 0; i < 5; i++) {
            if (i == 2) {
                colonX = ox + Ui.pxf(x + ClockFormat.colonPitch() / 2f);
                x += ClockFormat.colonPitch();
                continue;
            }
            int k = i < 2 ? i : i - 1;
            float adv = digitPaint.measureText(String.valueOf(digits[k]));
            digitX[k] = ox + Ui.pxf(x) + (Ui.pxf(pitch) - adv) / 2f;
            x += pitch;
        }
        float top = ClockFormat.groupTop();
        digitY = oy + Ui.pxf(ClockFormat.digitBaseline());
        colonY1 = oy + Ui.pxf(ClockFormat.colonCy(top, -1));
        colonY2 = oy + Ui.pxf(ClockFormat.colonCy(top, 1));
        lineX = ox + Ui.pxf(ClockFormat.CANVAS_W / 2f);
        Paint.FontMetrics fm = linePaint.getFontMetrics();
        float boxMid = oy + Ui.pxf(ClockFormat.lineTop() + ClockFormat.lineBoxH() / 2f);
        lineY = boxMid - (fm.ascent + fm.descent) / 2f;
        laidOut = true;

        int[] off = ClockFormat.offset(Math.floorDiv(now, 60000L));
        offX = Ui.pxf(off[0]);
        offY = Ui.pxf(off[1]);
    }

    private String buildLine(Calendar cal, Config cfg) {
        String date = RainText.dateLabel(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
        try {
            Context ctx = getContext();
            CityCommand.Resolved city = Cities.current(ctx, cfg);
            boolean on = !"off".equals(cfg.get(Config.Keys.WEATHER, "on"));
            WeatherClient.Cached k = WeatherClient.cached(ctx);
            boolean show = on && WeatherClient.usable(k, city.lat, city.lon, System.currentTimeMillis())   /* real time: cache age, not the pinned minute */;
            if (!show) return RainText.dreamLine(date, false, "", false, -1, -1, city.name);
            String cc = city.cc != null ? city.cc : java.util.Locale.getDefault().getCountry();
            boolean f = RainText.useF(cfg.get(Config.Keys.UNITS, "auto"), cc);
            String today = String.format(java.util.Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
            int[] pop = WeatherParse.popFor(k.w, today);
            int hr = cal.get(Calendar.HOUR_OF_DAY);
            boolean raining = WeatherParse.raining(k.w.code);
            int pct = pop != null && hr < pop.length ? pop[hr] : -1;
            return RainText.dreamLine(date, true, RainText.temp(k.w.tempC, f), raining, pct,
                RainText.likelyHour(pop, hr), city.name);
        } catch (RuntimeException e) {
            return date;
        }
    }

    // ---- drawing -------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(BG);
        if (!laidOut) return;
        canvas.translate(offX, offY);
        canvas.drawText(digits, 0, 1, digitX[0], digitY, digitPaint);
        canvas.drawText(digits, 1, 1, digitX[1], digitY, digitPaint);
        canvas.drawCircle(colonX, colonY1, colonR, colonPaint);
        canvas.drawCircle(colonX, colonY2, colonR, colonPaint);
        canvas.drawText(digits, 2, 1, digitX[2], digitY, digitPaint);
        canvas.drawText(digits, 3, 1, digitX[3], digitY, digitPaint);
        canvas.drawText(line, lineX, lineY, linePaint);
    }
}
