package tv.orchard.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.LinearInterpolator;

import java.util.Calendar;
import java.util.Random;

/**
 * Drifting clock face shared by ClockDream and the Quick Panel preview (PLAN 0.11).
 * Black field, 76 px Medium time, one pre-rendered ALPHA_8 radial glow whose paint colour cycles through
 * Ui.DREAM_COLOURS. Every Ui.DREAM_MOVE_MS the pair jumps to a new spot (fade out, move, fade in).
 * onDraw allocates nothing: one drawBitmap plus one drawText.
 */
public class ClockFace extends View {
    private static final int BG = 0xFF000000;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final ValueAnimator glowAnim = ValueAnimator.ofFloat(0f, 1f);
    private final ValueAnimator fadeAnim = ValueAnimator.ofFloat(0f, 1f);
    private final Runnable minuteTick = () -> onMinute();
    private final Runnable driftTick = () -> onDrift();

    private boolean h24 = true;
    private boolean reduced = false;
    private boolean running = false;
    private boolean moved = false;

    private String text = "";
    private float textW = 0f;
    private float capHalf = 0f;
    private float cx = -1f, cy = -1f;       // text centre in view px; < 0 until first layout
    private float contentAlpha = 1f;
    private static final long GLOW_FRAME_MS = 80;
    private long lastGlowDraw = -1000;
    private float glowPhase = 0f;
    private Bitmap glow;
    private int glowR = 0;

    public ClockFace(Context c) {
        super(c);
        setBackgroundColor(BG);
        textPaint.setTypeface(Ui.medium());
        textPaint.setColor(0xFFFFFFFF);
        glowPaint.setFilterBitmap(true);
        glowAnim.setInterpolator(new LinearInterpolator());
        glowAnim.setRepeatCount(ValueAnimator.INFINITE);
        glowAnim.addUpdateListener(a -> {
            float ph = (Float) a.getAnimatedValue();
            long t = a.getCurrentPlayTime();
            if (t - lastGlowDraw < GLOW_FRAME_MS && ph > glowPhase) return;   // cap redraws (glow cycles over 8 s: 12 fps is smooth)
            lastGlowDraw = t;
            glowPhase = ph;
            invalidate();
        });
        fadeAnim.setInterpolator(new LinearInterpolator());
        fadeAnim.addUpdateListener(a -> {
            float t = (Float) a.getAnimatedValue();
            if (t >= 0.5f && !moved) {
                moved = true;
                pickSpot();
            }
            contentAlpha = Math.abs(1f - 2f * t);
            invalidate();
        });
        applyTextMetrics();
        refreshText();
    }

    public void setClock24h(boolean h24) {
        if (this.h24 == h24) return;
        this.h24 = h24;
        refreshText();
        invalidate();
    }

    public void setReducedMotion(boolean reduced) {
        if (this.reduced == reduced) return;
        this.reduced = reduced;
        if (running) applyGlowState();
        invalidate();
    }

    /** Start ticking/drifting (call from onAttachedToWindow or the dream's start). */
    public void start() {
        stopInternal();
        running = true;
        refreshText();
        scheduleMinute();
        handler.postDelayed(driftTick, Math.max(1L, Ui.ms(Ui.DREAM_MOVE_MS)));
        applyGlowState();
        invalidate();
    }

    public void stop() {
        stopInternal();
    }

    private void stopInternal() {
        running = false;
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(driftTick);
        glowAnim.cancel();
        lastGlowDraw = -1000;
        fadeAnim.cancel();
        contentAlpha = 1f;
        glowPhase = 0f;
    }

    @Override
    protected void onDetachedFromWindow() {
        stopInternal();
        super.onDetachedFromWindow();
    }

    // ---- state ---------------------------------------------------------------------------------

    private void applyGlowState() {
        if (reduced) {
            glowAnim.cancel();
            glowPhase = 0f;
        } else if (!glowAnim.isStarted()) {
            glowAnim.setDuration(Math.max(1L, Ui.ms(Ui.DREAM_GLOW_CYCLE_MS)));
            glowAnim.start();
        }
    }

    private void applyTextMetrics() {
        textPaint.setTextSize(Ui.pxf(Ui.T_CLOCK_TITLE1));
        textPaint.setLetterSpacing(Ui.tracking(Ui.T_CLOCK_TITLE1));
        textPaint.setAlpha(Math.round(255 * Ui.DREAM_TEXT_ALPHA));
        Rect r = new Rect();
        textPaint.getTextBounds("0", 0, 1, r);
        capHalf = r.height() / 2f;
    }

    /** Formats the time string (called only at start, minute change and 12/24 h toggle). */
    private void refreshText() {
        Calendar cal = Calendar.getInstance();
        int hr = cal.get(Calendar.HOUR_OF_DAY);
        int mn = cal.get(Calendar.MINUTE);
        String m = mn < 10 ? "0" + mn : Integer.toString(mn);
        if (h24) {
            text = (hr < 10 ? "0" + hr : Integer.toString(hr)) + ":" + m;
        } else {
            int h12 = hr % 12;
            text = (h12 == 0 ? 12 : h12) + ":" + m;
        }
        textW = textPaint.measureText(text);
        clampSpot();
    }

    private void scheduleMinute() {
        long now = System.currentTimeMillis();
        long delay = 60000L - (now % 60000L) + 50L;
        handler.removeCallbacks(minuteTick);
        handler.postDelayed(minuteTick, delay);
    }

    private void onMinute() {
        if (!running) return;
        refreshText();
        invalidate();
        scheduleMinute();
    }

    private void onDrift() {
        if (!running) return;
        handler.postDelayed(driftTick, Math.max(1L, Ui.ms(Ui.DREAM_MOVE_MS)));
        if (reduced) {
            pickSpot();
            invalidate();
        } else {
            moved = false;
            fadeAnim.cancel();
            fadeAnim.setDuration(Math.max(1L, Ui.ms(Ui.DREAM_FADE_MS)));
            fadeAnim.start();
        }
    }

    // ---- geometry ------------------------------------------------------------------------------

    private float minX() { return Ui.px(Ui.SAFE_X) + textW / 2f; }
    private float maxX() { return getWidth() - Ui.px(Ui.SAFE_X) - textW / 2f; }
    private float minY() { return Ui.px(Ui.SAFE_Y) + capHalf; }
    private float maxY() { return getHeight() - Ui.px(Ui.SAFE_Y) - capHalf; }

    private void clampSpot() {
        if (getWidth() == 0 || cx < 0f) return;
        cx = Math.max(minX(), Math.min(maxX(), cx));
        cy = Math.max(minY(), Math.min(maxY(), cy));
    }

    private void pickSpot() {
        if (getWidth() == 0) return;
        float x0 = minX(), x1 = maxX(), y0 = minY(), y1 = maxY();
        if (x1 <= x0 || y1 <= y0) {
            cx = getWidth() / 2f;
            cy = getHeight() / 2f;
            return;
        }
        float minDist = Ui.pxf(200f);
        float nx = cx, ny = cy;
        for (int i = 0; i < 24; i++) {
            nx = x0 + rnd.nextFloat() * (x1 - x0);
            ny = y0 + rnd.nextFloat() * (y1 - y0);
            float dx = nx - cx, dy = ny - cy;
            if (dx * dx + dy * dy >= minDist * minDist) break;
        }
        cx = nx;
        cy = ny;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        // The glow radius is 520 design px on a 1920 canvas; when embedded in a smaller preview scale it down.
        float k = Math.min(w / (float) Ui.px(Ui.CANVAS_W), 1f);
        int r = Math.max(8, Math.round(Ui.px(Ui.DREAM_GLOW_R) * k));
        if (glow == null || r != glowR) {
            glowR = r;
            if (glow != null) glow.recycle();
            glow = buildGlow(r);
        }
        if (cx < 0f) {
            cx = w / 2f;
            cy = h / 2f;
        }
        clampSpot();
    }

    private static Bitmap buildGlow(int r) {
        Bitmap b = Bitmap.createBitmap(2 * r, 2 * r, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(r, r, r, 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(r, r, r, p);
        return b;
    }

    // ---- drawing -------------------------------------------------------------------------------

    private static int lerpColour(int a, int b, float f) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = ar + Math.round((br - ar) * f);
        int g = ag + Math.round((bg - ag) * f);
        int bl = ab + Math.round((bb - ab) * f);
        return (r << 16) | (g << 8) | bl;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cx < 0f) return;
        if (glow != null) {
            int[] cols = Ui.DREAM_COLOURS;
            float pos = glowPhase * cols.length;
            int i = (int) pos;
            if (i >= cols.length) i = cols.length - 1;
            int rgb = lerpColour(cols[i], cols[(i + 1) % cols.length], pos - i);
            int a = Math.round(255f * Ui.DREAM_GLOW_ALPHA * contentAlpha);
            glowPaint.setColor((a << 24) | rgb);
            canvas.drawBitmap(glow, cx - glowR, cy - glowR, glowPaint);
        }
        textPaint.setAlpha(Math.round(255f * Ui.DREAM_TEXT_ALPHA * contentAlpha));
        canvas.drawText(text, cx - textW / 2f, cy + capHalf, textPaint);
    }
}
