package tv.orchard.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fixed top overlay, full width x Ui.px(190): opaque-to-clear scrim (faded in while a grid row is focused),
 * "Options" hint, optional status chip, weather chip, greeting + date + clock. Redraws only at minute changes, on a
 * setter that changed something, and during the 250 ms scrim fade. Not focusable. Place at the top of the screen with size (screen width) x viewHeight().
 */
public class StatusStrip extends View {
    private static final int SCRIM_H = Ui.STRIP_VIEW_H, SCRIM_SOLID_H = Ui.STRIP_SCRIM_SOLID_H;
    private static final int ICON = 22, ICON_GAP = 12, CHIP_GAP = 20, CHIP_PAD_V = 5, CHIP_PAD_H = 16, DATE_GAP = 22;
    private static final float ICON_STROKE = 2.6f;       // viewBox units of 24

    private final Paint scrimPaint = new Paint();
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint greetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tempPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hlPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scratch = new Paint(Paint.ANTI_ALIAS_FLAG);       // Halo scratch
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path iconPath = new Path();
    private int iconPathId = 1;               // Halo sprite key; bumped whenever iconPath is rebuilt
    private final RectF chipRect = new RectF();
    private final RectF chipRimRect = new RectF();
    private final ValueAnimator fade = ValueAnimator.ofFloat(0f, 1f);
    private final Date now = new Date();
    private final Runnable tick = this::onTick;

    private SimpleDateFormat dateFmt, clockFmt;
    private boolean hintVisible = true;
    private boolean clock24 = true, clockVisible = true, attached, paused;
    private float scrimA;
    private boolean scrimOn;
    private String chip, dateStr = "", clockStr = "", internalDate = "", dateOverride;
    private String greeting;
    private static final String DOT = "\u00b7";

    // weather chip state
    private boolean wxShown, wxHidden;
    private int wxGlyph;
    private String wxTemp = "", wxRain = "", wxHl = "", wxCap = "", wxCapDraw = "";
    private int scrimRgb = Theme.cur().bg;
    private Theme appliedTheme;
    private float appliedTs = -1f, appliedTn = -1f;

    // layout (device px), computed outside onDraw
    private float hintX, hintBase, chipBase, chipRadius, dateX, clockX, clockBase;
    private float greetX, dotX;
    private float wxGlyphX, wxGlyphY, wxTempX, wxRainX, wxHlX, wxCapX, wxRow1Base, wxCapBase;
    private int laidOutWidth = -1;

    public StatusStrip(Context context) {
        super(context);
        setFocusable(false);
        float stroke = Ui.pxf(ICON) / 24f * ICON_STROKE;
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeWidth(stroke);
        text(hintPaint, Ui.T_CAPTION2, true, 0);
        text(chipText, Ui.T_CAPTION2, true, 0);
        text(datePaint, Ui.T_CAPTION1, false, 0);
        text(greetPaint, Ui.T_CAPTION1, false, 0);
        text(clockPaint, Ui.T_HEADLINE, true, 0);
        clockPaint.setFontFeatureSettings("tnum");
        text(tempPaint, Ui.T_HEADLINE, true, 0);
        tempPaint.setFontFeatureSettings("tnum");
        text(rainPaint, Ui.T_CAPTION1, true, 0);
        rainPaint.setFontFeatureSettings("tnum");
        text(hlPaint, Ui.T_CAPTION2, true, 0);
        hlPaint.setFontFeatureSettings("tnum");
        text(capPaint, Ui.T_CAPTION1, false, 0);
        chipRim.setStyle(Paint.Style.STROKE);
        chipRim.setStrokeWidth(Math.max(1f, Ui.pxf(1)));
        applyTheme();
        fade.addUpdateListener(a -> {
            scrimA = (Float) a.getAnimatedValue();
            invalidate();
        });
        buildFormats();
        refreshStrings();
    }

    private static void text(Paint p, int designPx, boolean medium, int color) {
        p.setTextSize(Ui.pxf(designPx));
        p.setTypeface(medium ? Ui.medium() : Ui.regular());
        p.setLetterSpacing(Ui.tracking(designPx));
        p.setColor(color);
    }

    public static int viewHeight() { return Ui.px(SCRIM_H); }

    // ---- public API ----------------------------------------------------------------------------------------

    /** The "Options" hint (menu icon + label) belongs to the home screen only; Settings hides it. */
    public void setHintVisible(boolean on) {
        if (on == hintVisible) return;
        hintVisible = on;
        invalidate();
    }

    /** Status chip text; null or empty hides it. */
    public void setChip(String text) {
        String t = (text == null || text.isEmpty()) ? null : text;
        if (t == null ? chip == null : t.equals(chip)) return;
        chip = t;
        laidOutWidth = -1;
        invalidate();
    }

    /** Opaque scrim while a grid row is focused (fades over Ui.SHELF_HIDE_MS). */
    public void setScrim(boolean on) {
        if (on == scrimOn) return;
        scrimOn = on;
        fade.cancel();
        fade.setFloatValues(scrimA, on ? 1f : 0f);
        fade.setDuration(Ui.ms(Ui.SHELF_HIDE_MS));
        fade.start();
    }

    public void setClock24h(boolean h24) {
        if (h24 == clock24) return;
        clock24 = h24;
        buildFormats();
        refreshStrings();
        laidOutWidth = -1;
        invalidate();
    }

    public void setClockVisible(boolean visible) {
        if (visible == clockVisible) return;
        clockVisible = visible;
        relayout();
    }

    /** Re-reads Theme (cur, ts, tn) into every colour; invalidates only when something actually changed. */
    public void applyTheme() {
        Theme t = Theme.cur();
        float ts = Theme.ts(), tn = Theme.tn();
        boolean changed = t != appliedTheme || Math.abs(ts - appliedTs) > 1e-3f || Math.abs(tn - appliedTn) > 1e-3f;
        appliedTheme = t;
        appliedTs = ts;
        appliedTn = tn;
        int hint = Theme.hint();
        iconPaint.setColor(hint);
        hintPaint.setColor(hint);
        chipFill.setColor(t.glass);
        chipRim.setColor(t.glassHairline);
        chipText.setColor(t.text);
        int t2 = Theme.rise(t.text2), tx = Theme.rise(t.text);
        greetPaint.setColor(t2);
        datePaint.setColor(t2);
        clockPaint.setColor(Theme.clock());
        tempPaint.setColor(tx);
        rainPaint.setColor(tx);
        hlPaint.setColor(t2);
        capPaint.setColor(t2);
        if (changed) invalidate();
    }

    private long pinnedMs = -1;

    /** debug_time: show this instant instead of the real clock (pinned false restores real time). Re-lays out only on a change. */
    public void setClockPin(long ms, boolean pinned) {
        long v = pinned ? ms : -1;
        if (v == pinnedMs) return;
        pinnedMs = v;
        if (refreshStrings()) relayout();
    }

    /** Greeting left of the date ("Good evening"); null or empty hides it (and its middle dot). */
    public void setGreeting(String g) {
        String v = (g == null || g.isEmpty()) ? null : g;
        if (v == null ? greeting == null : v.equals(greeting)) return;
        greeting = v;
        relayout();
    }

    /** Replaces the internal date label; null restores the TV-local "EEE d MMM". */
    public void setDate(String label) {
        String v = (label == null || label.isEmpty()) ? null : label;
        if (v == null ? dateOverride == null : v.equals(dateOverride)) return;
        dateOverride = v;
        dateStr = v != null ? v : internalDate;
        relayout();
    }

    /** Weather chip content. glyph is a WeatherParse.GLYPH_*; rain, highLow and caption may be null or empty. */
    public void setWeather(boolean shown, int glyph, String temp, String rain, String highLow, String caption) {
        String t = temp == null ? "" : temp, r = rain == null ? "" : rain;
        String h = highLow == null ? "" : highLow, c = caption == null ? "" : caption;
        if (shown == wxShown && glyph == wxGlyph && t.equals(wxTemp) && r.equals(wxRain) && h.equals(wxHl)
                && c.equals(wxCap)) return;
        wxShown = shown;
        wxGlyph = glyph;
        wxTemp = t;
        wxRain = r;
        wxHl = h;
        wxCap = c;
        relayout();
    }

    /** Panel or sheet open: the weather chip (and the status pill, when it would reach the panel) is not drawn but keeps its layout, so nothing moves (CSS visibility:hidden). */
    public void setWeatherHidden(boolean hidden) {
        if (hidden == wxHidden) return;
        wxHidden = hidden;
        invalidate();
    }

    /** Scrim colour: flat rgb, opaque to STRIP_SCRIM_SOLID_H and clear at STRIP_VIEW_H. Rebuilt only on change. */
    public void setScrimColour(int rgb) {
        rgb &= 0xFFFFFF;
        if (rgb == scrimRgb) return;
        scrimRgb = rgb;
        buildScrim(getHeight());
        if (scrimA > 0f) invalidate();
    }

    private void buildScrim(int h) {
        if (h <= 0) return;
        int solid = 0xFF000000 | scrimRgb, clear = scrimRgb;
        scrimPaint.setShader(new LinearGradient(0, 0, 0, h, new int[] { solid, solid, clear },
                new float[] { 0f, SCRIM_SOLID_H / (float) SCRIM_H, 1f }, Shader.TileMode.CLAMP));
    }

    /** String or size changed: recompute positions now (not in onDraw) and redraw. */
    private void relayout() {
        laidOutWidth = -1;
        if (getWidth() > 0) layout();
        invalidate();
    }

    /** onStop: stop the minute tick. */
    public void pause() {
        paused = true;
        removeCallbacks(tick);
    }

    /** onStart: re-format immediately and restart the tick. */
    public void resume() {
        paused = false;
        buildFormats();
        refreshStrings();
        laidOutWidth = -1;
        invalidate();
        schedule();
    }

    // ---- clock ------------------------------------------------------------------------------------------------

    private void buildFormats() {
        Locale l = Locale.getDefault();
        dateFmt = new SimpleDateFormat("EEE d MMM", l);
        clockFmt = new SimpleDateFormat(clock24 ? "HH:mm" : "h:mm", l);
    }

    /** @return true when a string changed */
    private boolean refreshStrings() {
        now.setTime(pinnedMs >= 0 ? pinnedMs : System.currentTimeMillis());
        String d = dateFmt.format(now), c = clockFmt.format(now);
        internalDate = d;
        if (dateOverride != null) d = dateOverride;
        boolean changed = !d.equals(dateStr) || !c.equals(clockStr);
        dateStr = d;
        clockStr = c;
        return changed;
    }

    private void schedule() {
        removeCallbacks(tick);
        if (!attached || paused) return;
        long t = System.currentTimeMillis();
        postDelayed(tick, 60000L - (t % 60000L) + 50L);
    }

    private void onTick() {
        if (refreshStrings()) {
            relayout();
        }
        schedule();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (refreshStrings()) laidOutWidth = -1;
        schedule();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(tick);
        fade.cancel();
        super.onDetachedFromWindow();
    }

    // ---- layout and draw -----------------------------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Ui.px(Ui.CANVAS_W) : MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, viewHeight());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildScrim(h);
        laidOutWidth = -1;
    }

    private static float centredBase(Paint p, float cy) {
        Paint.FontMetrics m = p.getFontMetrics();
        return cy - (m.ascent + m.descent) / 2f;
    }

    private void layout() {
        float cy = Ui.pxf((Ui.STRIP_Y0 + Ui.STRIP_Y1) / 2f);
        float iconSize = Ui.pxf(ICON), k = iconSize / 24f;
        float ix = Ui.pxf(Ui.SAFE_X), iy = cy - iconSize / 2f;
        iconPath.rewind();
        iconPathId++;
        for (int i = 0; i < 3; i++) {                      // 'M4 7h16 M4 12h16 M4 17h16'
            float y = iy + (7 + 5 * i) * k;
            iconPath.moveTo(ix + 4 * k, y);
            iconPath.lineTo(ix + 20 * k, y);
        }
        hintX = ix + iconSize + Ui.pxf(ICON_GAP);
        hintBase = centredBase(hintPaint, cy);
        float hintEnd = hintX + hintPaint.measureText("Options");

        if (chip != null) {
            float x = hintEnd + Ui.pxf(CHIP_GAP);
            Paint.FontMetrics m = chipText.getFontMetrics();
            float ch = (m.descent - m.ascent) + 2 * Ui.pxf(CHIP_PAD_V);
            float cw = chipText.measureText(chip) + 2 * Ui.pxf(CHIP_PAD_H);
            chipRect.set(x, cy - ch / 2f, x + cw, cy + ch / 2f);
            float hs = chipRim.getStrokeWidth() / 2f;
            chipRimRect.set(chipRect.left + hs, chipRect.top + hs, chipRect.right - hs, chipRect.bottom - hs);
            chipRadius = ch / 2f;
            chipBase = centredBase(chipText, cy);
        }

        float right = Ui.pxf(Ui.CANVAS_W - Ui.SAFE_X), gap = Ui.pxf(DATE_GAP);
        clockX = right - clockPaint.measureText(clockStr);
        dateX = clockX - gap - datePaint.measureText(dateStr);
        clockBase = centredBase(clockPaint, cy);
        float groupLeft = dateX;
        if (greeting != null) {
            dotX = dateX - gap - greetPaint.measureText(DOT);
            greetX = dotX - gap - greetPaint.measureText(greeting);
            groupLeft = greetX;
        }

        if (wxShown) layoutWeather(clockVisible ? groupLeft - Ui.pxf(Ui.WX_CLOCK_GAP) : right, cy);
        laidOutWidth = getWidth();
    }

    /** Chip = glyph | 14 | two-row text column, vertically centred on cy; its right edge is chipRight. */
    private void layoutWeather(float chipRight, float cy) {
        float tw = tempPaint.measureText(wxTemp), g16 = Ui.pxf(Ui.WX_TEMP_GAP);
        float row1 = tw;
        boolean rain = !wxRain.isEmpty(), hl = !wxHl.isEmpty();
        float rainW = rain ? rainPaint.measureText(wxRain) : 0f, hlW = hl ? hlPaint.measureText(wxHl) : 0f;
        if (rain) row1 += g16 + rainW;
        if (hl) row1 += g16 + hlW;
        wxCapDraw = ellipsize(wxCap, capPaint, Ui.pxf(Ui.WX_NAME_MAX_W));
        float colW = Math.max(row1, capPaint.measureText(wxCapDraw));
        float glyph = Ui.pxf(Ui.WX_GLYPH), gw = Ui.pxf(Ui.WX_GAP);
        float left = chipRight - (glyph + gw + colW);
        wxGlyphX = left;
        wxGlyphY = cy - glyph / 2f;
        float x = left + glyph + gw;
        wxTempX = x;
        wxRainX = x + tw + g16;
        wxHlX = rain ? wxRainX + rainW + g16 : x + tw + g16;
        wxCapX = x;
        float lh1 = 1.1f * Ui.pxf(Ui.T_HEADLINE), lh2 = 1.2f * Ui.pxf(Ui.T_CAPTION1);
        boolean cap = !wxCapDraw.isEmpty();
        float colH = lh1 + (cap ? lh2 : 0f), top = cy - colH / 2f;
        wxRow1Base = lineBase(tempPaint, top, lh1);
        wxCapBase = lineBase(capPaint, top + lh1, lh2);
    }

    /** Baseline of text centred (CSS half-leading) in a line box of height lh whose top is at top. */
    private static float lineBase(Paint p, float top, float lh) {
        Paint.FontMetrics m = p.getFontMetrics();
        return top + (lh - (m.descent - m.ascent)) / 2f - m.ascent;
    }

    private static String ellipsize(String s, Paint p, float maxW) {
        if (s.isEmpty() || p.measureText(s) <= maxW) return s;
        float ell = p.measureText("\u2026");
        int n = p.breakText(s, true, Math.max(0f, maxW - ell), null);
        while (n > 0 && s.charAt(n - 1) == ' ') n--;
        return s.substring(0, n) + "\u2026";
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (laidOutWidth != getWidth()) layout();
        if (scrimA > 0f) {
            scrimPaint.setAlpha(Math.round(scrimA * 255f));
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        }
        if (hintVisible) {
            Halo.drawPath(canvas, iconPathId, iconPath, iconPaint, scratch);
            Halo.drawText(canvas, "Options", hintX, hintBase, hintPaint, scratch);
        }
        if (chip != null && !OverlayRules.hideChip(wxHidden, chipRect.right, Ui.pxf(Ui.PANEL_X), Ui.pxf(CHIP_GAP))) {
            canvas.drawRoundRect(chipRect, chipRadius, chipRadius, chipFill);
            canvas.drawRoundRect(chipRimRect, chipRadius, chipRadius, chipRim);
            Halo.drawText(canvas, chip, chipRect.left + Ui.pxf(CHIP_PAD_H), chipBase, chipText, scratch);
        }
        if (wxShown && !wxHidden) {
            canvas.drawBitmap(WxGlyph.get(wxGlyph), wxGlyphX, wxGlyphY, bmpPaint);
            Halo.drawText(canvas, wxTemp, wxTempX, wxRow1Base, tempPaint, scratch);
            if (!wxRain.isEmpty()) Halo.drawText(canvas, wxRain, wxRainX, wxRow1Base, rainPaint, scratch);
            if (!wxHl.isEmpty()) Halo.drawText(canvas, wxHl, wxHlX, wxRow1Base, hlPaint, scratch);
            if (!wxCapDraw.isEmpty()) Halo.drawText(canvas, wxCapDraw, wxCapX, wxCapBase, capPaint, scratch);
        }
        if (clockVisible) {
            // with the Quick Panel open the greeting (and its dot) would sit under the panel's glass: hide it like the pill
            boolean greetUnderPanel = greeting != null && OverlayRules.overlaps(wxHidden, greetX, dotX + greetPaint.measureText(DOT),
                    Ui.pxf(Ui.PANEL_X), Ui.pxf(Ui.PANEL_X + Ui.PANEL_W), Ui.pxf(CHIP_GAP));
            if (greeting != null && !greetUnderPanel) {
                Halo.drawText(canvas, greeting, greetX, clockBase, greetPaint, scratch);
                Halo.drawText(canvas, DOT, dotX, clockBase, greetPaint, scratch);
            }
            Halo.drawText(canvas, dateStr, dateX, clockBase, datePaint, scratch);
            Halo.drawText(canvas, clockStr, clockX, clockBase, clockPaint, scratch);
        }
    }
}
