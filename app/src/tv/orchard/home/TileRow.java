package tv.orchard.home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Horizontal row of TileViews (PLAN 0.5): draws the shared shadow, glow and focus label from dispatchDraw
 * under/over transformed tile bounds, draws the focused tile last, bumps at the row ends, and hands
 * vertical moves to a {@link Navigator}. The parent of this row must setClipChildren(false) or the shadow
 * is cut at the row bounds. Task 3.2.
 */
public class TileRow extends LinearLayout {
    /** Resolves UP/DOWN focus moves (HomeActivity implements it). Null result keeps focus where it is. */
    public interface Navigator {
        View next(TileRow from, View focused, int direction);
    }


    // Shared per process, device scale. ALPHA_8: the Paint colour tints them when drawn.
    private static Bitmap shadowBmp, glowBmp;
    private static float shadowBuiltA = -1f, contactBuiltA = -1f;   // the theme alphas shadowBmp was drawn with

    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloScratch = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean themeApplied;
    private boolean appliedLight;
    private float appliedTs, appliedTn;
    private int labelRgb = 0xFFFFFF;                                    // Theme.rise(text), no alpha
    private final RectF rect = new RectF();
    private final RectF dst = new RectF();
    private float labelAscent;
    private boolean labelsAlways;
    private Navigator navigator;
    private ObjectAnimator bumpAnim;
    private TileView bumpTarget;
    private final ValueAnimator.AnimatorUpdateListener bumpTick = a -> invalidate();

    public TileRow(Context context) {
        super(context);
        setOrientation(HORIZONTAL);
        setClipChildren(false);
        setClipToPadding(false);
        setChildrenDrawingOrderEnabled(true);
        setWillNotDraw(false);
        ensureBitmaps();
        shadowPaint.setColor(0xFF000000 | (Theme.cur().dropRgb & 0xFFFFFF));
        labelPaint.setTypeface(Ui.medium());
        labelPaint.setTextSize(Ui.pxf(Ui.T_BODY));
        labelPaint.setLetterSpacing(Ui.tracking(Ui.T_BODY));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        paintLabelTheme();
        labelAscent = labelPaint.getFontMetrics().ascent;                 // negative
    }

    // ---- shared bitmaps -----------------------------------------------------------------------------

    private static synchronized void ensureBitmaps() {
        Theme thm = Theme.cur();
        if (shadowBmp == null || shadowBuiltA != thm.shadowAlpha || contactBuiltA != thm.contactAlpha) {
            int pad = Ui.px(Ui.SHADOW_BITMAP_PAD), tw = Ui.px(Ui.TILE_W), th = Ui.px(Ui.TILE_H);
            Bitmap b = Bitmap.createBitmap(tw + pad, th + pad, Bitmap.Config.ALPHA_8);
            Canvas c = new Canvas(b);
            RectF r = new RectF();
            float x0 = pad / 2f, y0 = pad / 2f;
            r.set(x0, y0 + Ui.pxf(Ui.SHADOW_Y), x0 + tw, y0 + Ui.pxf(Ui.SHADOW_Y) + th);
            blurredRoundRect(c, r, Ui.SHADOW_BLUR, thm.shadowAlpha);
            r.set(x0, y0 + Ui.pxf(Ui.CONTACT_Y), x0 + tw, y0 + Ui.pxf(Ui.CONTACT_Y) + th);
            blurredRoundRect(c, r, Ui.CONTACT_BLUR, thm.contactAlpha);
            shadowBmp = b;
            shadowBuiltA = thm.shadowAlpha;
            contactBuiltA = thm.contactAlpha;
        }
        if (glowBmp == null) {
            int pad = Ui.px(Ui.GLOW_BITMAP_PAD), tw = Ui.px(Ui.TILE_W), th = Ui.px(Ui.TILE_H);
            Bitmap b = Bitmap.createBitmap(tw + pad, th + pad, Bitmap.Config.ALPHA_8);
            Canvas c = new Canvas(b);
            RectF r = new RectF(pad / 2f, pad / 2f, pad / 2f + tw, pad / 2f + th);
            blurredRoundRect(c, r, Ui.GLOW_BLUR, 1f);
            glowBmp = b;
        }
    }

    private static void blurredRoundRect(Canvas c, RectF r, float cssBlur, float alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setAlpha(Math.round(alpha * 255f));
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, (Ui.pxf(cssBlur) / 2f - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL));   // CSS sigma = blur/2 (same as Glass)
        float rad = Ui.pxf(Ui.TILE_R);
        c.drawRoundRect(r, rad, rad, p);
    }

    // ---- theme --------------------------------------------------------------------------------------

    private void paintLabelTheme() {
        Theme t = Theme.cur();
        labelRgb = Theme.rise(t.text) & 0xFFFFFF;
        if ((t.titleShadow >>> 24) == 0) {
            labelPaint.clearShadowLayer();
        } else {                                                        // mockup #flabel: text-shadow 0 1px 12px (CSS sigma = blur / 2)
            float sigma = Ui.pxf(12) / 2f;
            labelPaint.setShadowLayer(Math.max(1f, (sigma - 0.5f) / 0.57735f), 0f, Ui.pxf(Ui.FLABEL_SHADOW_DY), t.titleShadow);
        }
    }

    /**
     * Theme tokens changed (Theme.set already ran): label ink, shadow tint, shadow bitmap alphas, glow tint (the
     * tiles recompute it), then every tile. Cheap and idempotent (key = light + ts + tn).
     */
    public void applyTheme() {
        Theme t = Theme.cur();
        if (themeApplied && appliedLight == t.light && appliedTs == Theme.ts() && appliedTn == Theme.tn()) return;
        themeApplied = true;
        appliedLight = t.light;
        appliedTs = Theme.ts();
        appliedTn = Theme.tn();
        ensureBitmaps();                                                // redraws the shared shadow only when its alphas changed
        shadowPaint.setColor(0xFF000000 | (t.dropRgb & 0xFFFFFF));
        paintLabelTheme();
        for (int i = 0, n = getChildCount(); i < n; i++) {
            View c = getChildAt(i);
            if (c instanceof TileView) ((TileView) c).applyTheme();
        }
        invalidate();
    }

    // ---- population ---------------------------------------------------------------------------------

    public void addTile(TileView t) {
        LayoutParams lp = new LayoutParams(Ui.px(Ui.TILE_W), Ui.px(Ui.TILE_H));
        if (getChildCount() > 0) lp.leftMargin = Ui.px(Ui.GUTTER);
        addView(t, lp);
        t.applyTheme();                                                 // no-op when it already follows the live theme
    }

    public TileView tileAt(int i) {
        View v = i >= 0 && i < getChildCount() ? getChildAt(i) : null;
        return v instanceof TileView ? (TileView) v : null;
    }

    public int tileCount() { return getChildCount(); }

    /** Index of the focused child, -1 when none. */
    public int focusedIndex() {
        for (int i = 0, n = getChildCount(); i < n; i++) {
            if (getChildAt(i).isFocused()) return i;
        }
        return -1;
    }

    public void setLabelsAlways(boolean on) {
        if (labelsAlways == on) return;
        labelsAlways = on;
        invalidate();
    }

    public void setNavigator(Navigator n) { navigator = n; }

    /** Child whose horizontal centre (row coordinates) is closest to x; the first wins a tie; null when empty. */
    public View nearest(int x) {
        View best = null;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0, n = getChildCount(); i < n; i++) {
            View c = getChildAt(i);
            int d = Math.abs(c.getLeft() + c.getWidth() / 2 - x);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    // ---- focus navigation -----------------------------------------------------------------------------

    @Override
    public View focusSearch(View focused, int direction) {
        int idx = -1;
        for (View v = focused; v != null && v != this; ) {
            if (v.getParent() == this) {
                idx = indexOfChild(v);
                break;
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        if (idx < 0) return super.focusSearch(focused, direction);
        View self = getChildAt(idx);
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
            int step = direction == View.FOCUS_LEFT ? -1 : 1;
            for (int i = idx + step; i >= 0 && i < getChildCount(); i += step) {
                View c = getChildAt(i);
                if (c.getVisibility() == View.VISIBLE && c.isFocusable()) return c;
            }
            bump(self, direction);
            return self;
        }
        if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
            View r = navigator != null ? navigator.next(this, self, direction) : null;
            if (r == null) bump(self, direction);                         // top of the dock / last row: no wrap
            return r != null ? r : self;
        }
        return super.focusSearch(focused, direction);
    }

    /** Idle dim for every tile of the row (see {@link TileView#setIdle}). */
    public void setIdle(boolean idle) {
        for (int i = 0, n = getChildCount(); i < n; i++) {
            View c = getChildAt(i);
            if (c instanceof TileView) ((TileView) c).setIdle(idle);
        }
    }

    /** Stop the idle sway on every tile of the row. */
    public void stopSway() {
        for (int i = 0, n = getChildCount(); i < n; i++) {
            View c = getChildAt(i);
            if (c instanceof TileView) ((TileView) c).stopSway();
        }
    }

    /** Edge bump for a tile of this row (Move mode: a clamped D-pad press). direction = View.FOCUS_*. */
    public void bumpTile(TileView t, int direction) { bump(t, direction); }

    /** Move-mode dim: every tile of this row except {@code except} (may be null) to alpha a. */
    public void setMoveDim(TileView except, float a, boolean animate) {
        for (int i = 0, n = getChildCount(); i < n; i++) {
            View c = getChildAt(i);
            if (c instanceof TileView && c != except) ((TileView) c).setMoveAlpha(a, animate);
        }
    }

    /** Detaches every tile (Move-mode relayout re-adds the same instances); settles a running bump first. */
    public void clearTiles() {
        if (bumpAnim != null) {
            bumpAnim.cancel();
            if (bumpTarget != null) {
                bumpTarget.setTranslationX(0f);
                bumpTarget.setTranslationY(0f);
            }
        }
        removeAllViews();
    }

    /** Nudge the tile BUMP_PX toward the blocked direction and back; restarts instead of stacking. None in reduced motion. */
    private void bump(View v, int direction) {
        if (!(v instanceof TileView) || TileView.isReducedMotion()) return;
        boolean vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN;
        if (bumpAnim != null) {
            bumpAnim.cancel();
            if (bumpTarget != null) {
                bumpTarget.setTranslationX(0f);
                bumpTarget.setTranslationY(0f);
            }
        }
        float d = Ui.pxf(Ui.BUMP_PX) * (direction == View.FOCUS_LEFT || direction == View.FOCUS_UP ? -1f : 1f);
        bumpTarget = (TileView) v;
        bumpAnim = ObjectAnimator.ofFloat(bumpTarget, vertical ? View.TRANSLATION_Y : View.TRANSLATION_X, 0f, d, 0f);
        bumpAnim.setDuration(Ui.ms(Ui.BUMP_MS));
        bumpAnim.addUpdateListener(bumpTick);
        bumpAnim.start();
    }

    // ---- drawing ----------------------------------------------------------------------------------------

    @Override
    protected int getChildDrawingOrder(int childCount, int drawingPosition) {
        int f = focusedIndex();
        if (f < 0 || f >= childCount) return drawingPosition;
        if (drawingPosition == childCount - 1) return f;                  // focused child last
        return drawingPosition >= f ? drawingPosition + 1 : drawingPosition;
    }

    /** Transformed bounds of a child in this row's coordinates (scale, pivot and translation applied). */
    private void childRect(View c, RectF out) {
        out.set(0, 0, c.getWidth(), c.getHeight());
        c.getMatrix().mapRect(out);
        out.offset(c.getLeft(), c.getTop());   // getMatrix() already includes translation
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int n = getChildCount();
        // 1) glow then shadow, under the tiles
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (!(c instanceof TileView)) continue;
            TileView t = (TileView) c;
            float p = t.focusProgress();
            if (p <= Ui.DRAW_MIN_PROGRESS) continue;
            childRect(c, rect);
            float k = c.getWidth() > 0 ? rect.width() / c.getWidth() : 1f;   // current tile scale
            float cx = rect.centerX(), cy = rect.centerY();

            float gw = glowBmp.getWidth() * k, gh = glowBmp.getHeight() * k;
            float gy = cy + Ui.pxf(Ui.GLOW_Y) * k;
            dst.set(cx - gw / 2f, gy - gh / 2f, cx + gw / 2f, gy + gh / 2f);
            glowPaint.setColor(Ui.argb(Math.min(1f, t.themedGlowAlpha() * p), t.themedGlowColor()));
            canvas.drawBitmap(glowBmp, null, dst, glowPaint);

            float sw = shadowBmp.getWidth() * k, sh = shadowBmp.getHeight() * k;
            dst.set(cx - sw / 2f, cy - sh / 2f, cx + sw / 2f, cy + sh / 2f);
            shadowPaint.setAlpha(Math.round(p * 255f));
            canvas.drawBitmap(shadowBmp, null, dst, shadowPaint);
        }

        super.dispatchDraw(canvas);

        // 2) labels over everything
        float gap = Ui.pxf(Ui.LABEL_GAP);
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            if (!(c instanceof TileView)) continue;
            TileView t = (TileView) c;
            if (c.isFocused()) {
                float p = Math.min(1f, t.focusProgress());
                if (p <= 0f) continue;
                childRect(c, rect);
                labelPaint.setColor(Ui.argb(p, labelRgb));
                float rise = t.usesReducedFade() ? 0f : (1f - p) * Ui.pxf(Ui.LABEL_RISE);   // reduced: alpha only
                Halo.drawText(canvas, t.label(), rect.centerX(), rect.bottom + gap + rise - labelAscent, labelPaint, haloScratch);
            } else if (labelsAlways && !t.isFireTvHome()) {
                labelPaint.setColor(Ui.argb(Ui.LABEL_ALWAYS_ALPHA, labelRgb));
                float cx = c.getLeft() + c.getWidth() / 2f;
                Halo.drawText(canvas, t.label(), cx, c.getBottom() + gap - labelAscent, labelPaint, haloScratch);
            }
        }
    }
}
