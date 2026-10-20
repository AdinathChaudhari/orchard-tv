package tv.orchard.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Fixed top overlay, full width x Ui.px(190): opaque-to-clear scrim (faded in while a grid row is focused),
 * "Options" hint, optional status chip, date + clock. Redraws only at minute changes and during the 250 ms
 * scrim fade. Not focusable. Place at the top of the screen with size (screen width) x viewHeight().
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
    private final RectF chipRect = new RectF();
    private final RectF chipRimRect = new RectF();
    private final float[] icon = new float[12];
    private final ValueAnimator fade = ValueAnimator.ofFloat(0f, 1f);
    private final Date now = new Date();
    private final Runnable tick = this::onTick;

    private SimpleDateFormat dateFmt, clockFmt;
    private boolean hintVisible = true;
    private boolean clock24 = true, clockVisible = true, attached, paused;
    private float scrimA;
    private boolean scrimOn;
    private String chip, dateStr = "", clockStr = "";

    // layout (device px), computed outside onDraw
    private float hintX, hintBase, chipBase, chipRadius, dateX, clockX, clockBase;
    private int laidOutWidth = -1;

    public StatusStrip(Context context) {
        super(context);
        setFocusable(false);
        float stroke = Ui.pxf(ICON) / 24f * ICON_STROKE;
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeWidth(stroke);
        int hint = Ui.argb(Ui.STRIP_HINT_ALPHA, 0xFFFFFF);
        iconPaint.setColor(hint);
        text(hintPaint, Ui.T_CAPTION2, true, hint);
        text(chipText, Ui.T_CAPTION2, true, Ui.TEXT);
        text(datePaint, Ui.T_CAPTION1, false, Ui.TEXT2);
        text(clockPaint, Ui.T_HEADLINE, true, Ui.argb(Ui.CLOCK_ALPHA, 0xFFFFFF));
        clockPaint.setFontFeatureSettings("tnum");
        chipFill.setColor(Ui.GLASS);
        chipRim.setStyle(Paint.Style.STROKE);
        chipRim.setStrokeWidth(Math.max(1f, Ui.pxf(1)));
        chipRim.setColor(Ui.GLASS_HAIRLINE);
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
        now.setTime(System.currentTimeMillis());
        String d = dateFmt.format(now), c = clockFmt.format(now);
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
            laidOutWidth = -1;
            if (clockVisible) invalidate();
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
        int bg = Ui.BG, clear = Ui.argb(0f, Ui.BG);
        scrimPaint.setShader(new LinearGradient(0, 0, 0, h, new int[] { bg, bg, clear },
                new float[] { 0f, SCRIM_SOLID_H / (float) SCRIM_H, 1f }, Shader.TileMode.CLAMP));
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
        for (int i = 0; i < 3; i++) {                      // 'M4 7h16 M4 12h16 M4 17h16'
            float y = iy + (7 + 5 * i) * k;
            icon[i * 4] = ix + 4 * k;
            icon[i * 4 + 1] = y;
            icon[i * 4 + 2] = ix + 20 * k;
            icon[i * 4 + 3] = y;
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

        float right = Ui.pxf(Ui.CANVAS_W - Ui.SAFE_X);
        clockX = right - clockPaint.measureText(clockStr);
        dateX = clockX - Ui.pxf(DATE_GAP) - datePaint.measureText(dateStr);
        clockBase = centredBase(clockPaint, cy);
        laidOutWidth = getWidth();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (laidOutWidth != getWidth()) layout();
        if (scrimA > 0f) {
            scrimPaint.setAlpha(Math.round(scrimA * 255f));
            canvas.drawRect(0, 0, getWidth(), getHeight(), scrimPaint);
        }
        if (hintVisible) {
            canvas.drawLines(icon, iconPaint);
            canvas.drawText("Options", hintX, hintBase, hintPaint);
        }
        if (chip != null) {
            canvas.drawRoundRect(chipRect, chipRadius, chipRadius, chipFill);
            canvas.drawRoundRect(chipRimRect, chipRadius, chipRadius, chipRim);
            canvas.drawText(chip, chipRect.left + Ui.pxf(CHIP_PAD_H), chipBase, chipText);
        }
        if (clockVisible) {
            canvas.drawText(dateStr, dateX, clockBase, datePaint);
            canvas.drawText(clockStr, clockX, clockBase, clockPaint);
        }
    }
}
