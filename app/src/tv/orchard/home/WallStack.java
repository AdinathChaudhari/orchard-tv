package tv.orchard.home;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * Rev 4 static walls (Theme Auto with a non-sky wallpaper, or a fixed Light / Dark theme), as bitmap layers
 * cross-faded by Paint alpha only: the night wall (opaque), the day wall at alpha p, a day vignette baked
 * to 240x135 at alpha p, the twilight palette (240x135, baked once) at alpha TW_PEAK*4p(1-p) only while
 * 0 &lt; p &lt; 1, and the golden-hour warmth (240x135, baked once) at alpha g. The small bitmaps are drawn
 * scaled by the GPU. Idle: nothing redraws; only setState and the 900 ms fade invalidate.
 *
 * <p>Approximation: the mockup fades the day wall and its vignette as one group; here the vignette is drawn over
 * the (day over night) mix at the same alpha, a difference of at most a few percent of a 10 % vignette.
 */
public final class WallStack extends View {
    private final Wallpaper night, day;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private Bitmap twilight, golden, dayVig;
    private String wallName = "";
    private float p, g, fromP, fromG, toP, toG;
    private boolean reduced, hasState;
    private AmbientState last;
    private SkyAssets.SliceListener sliceListener;
    private ValueAnimator anim;
    private int lastSliceKey = Integer.MIN_VALUE;

    public WallStack(Context c) {
        super(c);
        setWillNotDraw(false);
        night = new Wallpaper(c);
        day = new Wallpaper(c);
    }

    public void setReducedMotion(boolean r) { reduced = r; }

    public void setSliceListener(SkyAssets.SliceListener l) {
        sliceListener = l;
        lastSliceKey = Integer.MIN_VALUE;
        if (l != null && last != null) requestSlice();
    }

    public void setState(AmbientState s, boolean animate) {
        if (s == null) return;
        last = s.copy();
        float np = s.themeMode == 1 ? 1f : s.themeMode == 2 ? 0f : s.p;
        float ng = s.themeMode == 0 ? s.g : 0f;
        np = SkyPhase.clamp01(np);
        ng = SkyPhase.clamp01(ng);
        String wn = Wallpaper.normalName(s.wallName);
        if (!wn.equals(wallName)) {
            wallName = wn;
            night.load(wn, w -> { invalidate(); lastSliceKey = Integer.MIN_VALUE; requestSlice(); });
        }
        if (day.sharp() == null && !dayLoading) loadDay();
        ensureBaked(np, ng);
        finishFade();
        if (animate && !reduced && hasState && (np != p || ng != g)) {
            fromP = p; fromG = g; toP = np; toG = ng;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(Ui.THEME_FADE_MS);
            anim.setInterpolator(new AccelerateDecelerateInterpolator());
            anim.addUpdateListener(a -> {
                float t = (Float) a.getAnimatedValue();
                p = fromP + (toP - fromP) * t;
                g = fromG + (toG - fromG) * t;
                invalidate();
            });
            anim.addListener(new OnAnimEnd(a -> {
                if (anim == a) { anim = null; p = toP; g = toG; invalidate(); }
            }));
            anim.start();
        } else {
            p = np;
            g = ng;
            toP = np;
            toG = ng;
            invalidate();
        }
        hasState = true;
        requestSlice();
    }

    private boolean dayLoading;

    private void loadDay() {
        dayLoading = true;
        day.load("day", w -> { dayLoading = false; invalidate(); lastSliceKey = Integer.MIN_VALUE; requestSlice(); });
    }

    private void finishFade() {
        if (anim != null) {
            ValueAnimator a = anim;
            anim = null;
            a.cancel();
            p = toP;
            g = toG;
        }
    }

    private void ensureBaked(float np, float ng) {
        if ((np != p || (np > 0f && np < 1f)) && twilight == null) twilight = bakeTwilight();
        if ((np > 0f || p > 0f) && dayVig == null) dayVig = bakeDayVignette();
        if ((ng > 0f || g > 0f) && golden == null) golden = bakeGolden();
    }

    /** onStop: drop the sharp walls (the blurs stay on disk) and the baked layers. */
    public void release() {
        finishFade();
        night.release();
        day.release();
        dayLoading = false;
        wallName = "";
        twilight = null;
        golden = null;
        dayVig = null;
    }

    /** onStart: reload the walls and re-bake for the last state. */
    public void restore() {
        if (last != null) setState(last, false);
    }

    private void requestSlice() {
        if (sliceListener == null || last == null) return;
        int key = Math.round(toP * 64f) * 131 + Math.round(toG * 64f) * 17 + wallName.hashCode();
        if (key == lastSliceKey) return;
        lastSliceKey = key;
        AmbientState s = last.copy();
        s.p = toP;
        s.g = toG;
        SkyAssets.slice(getContext(), s, sliceListener);
    }

    @Override
    protected void onDraw(Canvas c) {
        dst.set(0, 0, getWidth(), getHeight());
        Bitmap nb = night.sharp();
        if (nb != null) {
            paint.setAlpha(255);
            c.drawBitmap(nb, null, dst, paint);
        } else {
            c.drawColor(Theme.DARK.bg);
        }
        if (p > 0f) {
            Bitmap db = day.sharp();
            if (db != null) {
                paint.setAlpha(Math.round(p * 255f));
                c.drawBitmap(db, null, dst, paint);
                if (dayVig != null) c.drawBitmap(dayVig, null, dst, paint);
            }
        }
        if (p > 0f && p < 1f && twilight != null) {
            paint.setAlpha(Math.round(Ui.TW_PEAK * 4f * p * (1f - p) * 255f));
            c.drawBitmap(twilight, null, dst, paint);
        }
        if (g > 0f && golden != null) {
            paint.setAlpha(Math.round(g * 255f));
            c.drawBitmap(golden, null, dst, paint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        finishFade();
        super.onDetachedFromWindow();
    }

    // ---- baked layers (240x135, mockup CSS) -----------------------------------------------------------------

    private static int rgba(int rgb, float a) {
        return (Math.round(a * 255f) << 24) | (rgb & 0xFFFFFF);
    }

    /** CSS radial-gradient(rx% ry% at cx% cy%, c0 0%, c1 stop): an ellipse as a unit circle under a matrix. */
    private static void radial(Canvas cv, int w, int h, float rx, float ry, float cx, float cy, int c0, int c1, float stop) {
        RadialGradient rg = new RadialGradient(0f, 0f, 1f, new int[] {c0, c1}, new float[] {0f, stop}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(rx * w, ry * h);
        m.postTranslate(cx * w, cy * h);
        rg.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(rg);
        cv.drawRect(0, 0, w, h, p);
    }

    private static void vertical(Canvas cv, int w, int h, int[] colors, float[] stops) {
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, h, colors, stops, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h, p);
    }

    /** Mockup #twilight: linear tw-top 0 / tw-mid 58 % / tw-low 100 % under a tw-glow radial (110% x 80% at 8% 108 %, 0 to 70 %). */
    static Bitmap bakeTwilight() {
        int w = Ui.BLUR_W, h = Ui.BLUR_H;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        vertical(cv, w, h, new int[] {0xFF000000 | Ui.TW_TOP, 0xFF000000 | Ui.TW_MID, 0xFF000000 | Ui.TW_LOW}, new float[] {0f, 0.58f, 1f});
        radial(cv, w, h, 1.10f, 0.80f, 0.08f, 1.08f, rgba(Ui.TW_GLOW, Ui.TW_GLOW_A), rgba(Ui.TW_GLOW, 0f), 0.70f);
        return b;
    }

    /** Mockup #golden: golden-rgb2 top wash (a2 to 0 at 62 %) under a golden-rgb1 radial (120% x 95% at 12% 104 %, a1 to 0 at 72 %). */
    static Bitmap bakeGolden() {
        int w = Ui.BLUR_W, h = Ui.BLUR_H;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        vertical(cv, w, h, new int[] {rgba(Ui.GOLDEN_RGB2, Ui.GOLDEN_A2), rgba(Ui.GOLDEN_RGB2, 0f)}, new float[] {0f, 0.62f});
        radial(cv, w, h, 1.20f, 0.95f, 0.12f, 1.04f, rgba(Ui.GOLDEN_RGB1, Ui.GOLDEN_A1), rgba(Ui.GOLDEN_RGB1, 0f), 0.72f);
        return b;
    }

    /** Mockup #wallday::after: radial (130% x 100% at 50% 40 %, 0 at 45 % to alpha at 100 %) plus a bottom linear (0 at 60 % to 0.7 alpha). */
    static Bitmap bakeDayVignette() {
        int w = Ui.BLUR_W, h = Ui.BLUR_H;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        vertical(cv, w, h, new int[] {rgba(Ui.DAY_VIGNETTE_RGB, 0f), rgba(Ui.DAY_VIGNETTE_RGB, 0f), rgba(Ui.DAY_VIGNETTE_RGB, Ui.DAY_VIGNETTE_ALPHA * 0.7f)},
                new float[] {0f, 0.60f, 1f});
        RadialGradient rg = new RadialGradient(0f, 0f, 1f,
                new int[] {rgba(Ui.DAY_VIGNETTE_RGB, 0f), rgba(Ui.DAY_VIGNETTE_RGB, 0f), rgba(Ui.DAY_VIGNETTE_RGB, Ui.DAY_VIGNETTE_ALPHA)},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        // CSS "farthest-corner"-style sizing is replaced by the explicit 130% x 100% radii the mockup states
        m.setScale(1.30f * w, 1.00f * h);
        m.postTranslate(0.5f * w, 0.40f * h);
        rg.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(rg);
        cv.drawRect(0, 0, w, h, p);
        return b;
    }
}
