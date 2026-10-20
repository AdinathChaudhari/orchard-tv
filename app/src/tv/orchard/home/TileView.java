package tv.orchard.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

/**
 * One-onDraw tile (PLAN 0.3-0.5): art through a BitmapShader round rect, static 1 px edge, focus gloss and
 * rim, press dip, and the Fire TV Home glass control variant. The shadow, glow and focus label are drawn
 * by the parent {@link TileRow} (a view's own layer would clip them). Task 3.2.
 */
public class TileView extends View {

    private static final Interpolator IN = Ui.focusIn();
    private static final Interpolator OUT = Ui.focusOut();
    private static final Interpolator LINEAR = new LinearInterpolator();
    private static final ColorMatrixColorFilter BRIGHT = new ColorMatrixColorFilter(new ColorMatrix(new float[] {
            Ui.FOCUS_BRIGHTNESS, 0, 0, 0, 0,
            0, Ui.FOCUS_BRIGHTNESS, 0, 0, 0,
            0, 0, Ui.FOCUS_BRIGHTNESS, 0, 0,
            0, 0, 0, 1, 0}));
    private static final int WHITE = 0xFFFFFF;
    private static final int SWAY_RAMP_MS = 600;                        // -> Ui (p4-motion): sway envelope ramp 0 -> 1
    private static final float GLOSS_SHIFT = 0.06f;                     // -> Ui (p4-motion): gloss slide per full sway swing, x tile size

    /** Process-wide reduced-motion switch (HomeActivity sets it). See {@link #setReducedMotion}. */
    private static boolean reduced;

    /**
     * Reduced motion on/off for every tile. Takes effect per tile at its next focus change: the tile that is
     * focused right now keeps the mode it was focused under (scale and progress source stay consistent) until
     * focus moves; sway and idle dim stop being started at once, bump and press-dip stop at once. Turning idle
     * dim off (setIdle(false)) is always honoured so a stale dim is never stuck.
     */
    public static void setReducedMotion(boolean on) { reduced = on; }
    public static boolean isReducedMotion() { return reduced; }

    /** Ends a ValueAnimator (natural end or cancel) by running a Runnable. */
    private static final class EndHook extends AnimatorListenerAdapter {
        private final Runnable r;
        EndHook(Runnable r) { this.r = r; }
        @Override public void onAnimationEnd(Animator a) { r.run(); }
    }

    private AppEntry entry;
    private String label = "";
    private boolean fireTvHome;
    private Bitmap art;
    private int glowColor = Ui.FTH_GLOW;
    private float glowAlpha = Ui.GLOW_ALPHA;

    private BitmapShader shader;
    private final Matrix shaderMatrix = new Matrix();
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient glossShader;
    private final Matrix glossMatrix = new Matrix();
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint fthFill, fthRim, glyphPaint, caption;                 // only for the Fire TV Home tile
    private final Path glyph = new Path();
    private final RectF outer = new RectF();
    private final RectF inset = new RectF();
    private float radius, insetRadius, captionBaseline;

    private boolean pressing;
    private Runnable pressAfter;
    private final Runnable pressDone = this::finishPress;
    private final ValueAnimator.AnimatorUpdateListener tick = a -> onTick();

    private boolean idle;                       // idle dim state of this tile (see setIdle)
    private float moveDimAlpha = 1f;            // Move-mode dim for this (non-moving) tile; 1 = none
    private boolean reducedMode;                // this tile's focus progress comes from redP, not the scale
    private float redP, redFrom, redTo;
    private ValueAnimator redAnim;
    private float alphaFrom, alphaTo;
    private ValueAnimator alphaAnim;
    private ValueAnimator swayAnim, relaxAnim;
    private long swayT0;
    private float relaxX, relaxY;
    private boolean cameraSet;
    private final Runnable swayStart = this::startSway;
    private boolean moving;                     // grid Move mode: this tile is the one being dragged (see setMoving)

    public TileView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(false);
        setLongClickable(false);
        edge.setStyle(Paint.Style.STROKE);
        edge.setColor(Ui.argb(Ui.TILE_EDGE_ALPHA, WHITE));
        rim.setStyle(Paint.Style.STROKE);
        plate.setColor(Ui.PLATE);
        animate().setUpdateListener(tick);
    }

    @Override
    public boolean hasOverlappingRendering() { return false; }          // alpha without an offscreen layer

    // ---- binding ------------------------------------------------------------------------------------

    public void bind(AppEntry e) {
        entry = e;
        fireTvHome = false;
        label = e != null && e.label != null ? e.label : "";
        setContentDescription(label);
        clearArt();
    }

    /** The glass "Fire TV Home" control tile; entry() returns null. */
    public void bindFireTvHome(String text) {
        entry = null;
        fireTvHome = true;
        label = text != null ? text : "";
        setContentDescription(label);
        clearArt();
        if (fthFill == null) {
            fthFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fthRim = new Paint(Paint.ANTI_ALIAS_FLAG);
            fthRim.setStyle(Paint.Style.STROKE);
            glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            glyphPaint.setStyle(Paint.Style.STROKE);
            glyphPaint.setStrokeCap(Paint.Cap.ROUND);
            glyphPaint.setStrokeJoin(Paint.Join.ROUND);
            glyphPaint.setColor(Ui.argb(Ui.FTH_GLYPH_ALPHA, WHITE));
            caption = new Paint(Paint.ANTI_ALIAS_FLAG);
            caption.setTypeface(Ui.medium());
            caption.setTextSize(Ui.pxf(Ui.T_CAPTION2));
            caption.setLetterSpacing(Ui.tracking(Ui.T_CAPTION2));
            caption.setTextAlign(Paint.Align.CENTER);
            caption.setColor(Ui.argb(Ui.TEXT2_ALPHA, WHITE));
        }
        buildGeometry();
        invalidate();
    }

    private void clearArt() {
        art = null;
        shader = null;
        artPaint.setShader(null);
        glowColor = Ui.FTH_GLOW;
        glowAlpha = Ui.GLOW_ALPHA;
        invalidate();
    }

    public boolean isFireTvHome() { return fireTvHome; }
    public AppEntry entry() { return entry; }
    public String label() { return label; }

    /** art may be null: placeholder plate. Art is fitted ("cover") to the view size. */
    public void setArt(Bitmap bitmap, int color, float alpha) {
        glowColor = color;
        glowAlpha = alpha;
        // no art yet (cold start, or released in onStop): the app's own vivid colour, so a rebind does not flash grey
        plate.setColor(bitmap == null && color != 0 ? (0xFF000000 | ColorMath.withLightness(color, 0.30f, 0.8f)) : Ui.PLATE);
        if (bitmap != art) {
            art = bitmap;
            shader = bitmap != null ? new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) : null;
            artPaint.setShader(shader);
            updateShaderMatrix();
        }
        invalidate();
        invalidateRow();
    }

    public int glowColor() { return glowColor; }
    public float glowAlpha() { return glowAlpha; }

    // ---- focus / press --------------------------------------------------------------------------------

    /** 0 at rest, 1 at full focus scale (clamped, so the idle 1.14 reads 1); reduced mode: the 150 ms fade. */
    public float focusProgress() {
        if (pressing && isFocused()) return 1f;
        if (reducedMode) return redP;
        float p = (getScaleX() - 1f) / (Ui.FOCUS_SCALE - 1f);
        return p < 0f ? 0f : (p > 1f ? 1f : p);
    }

    /** True while this tile's progress is the reduced-motion fade: the row then draws the label without rise. */
    public boolean usesReducedFade() { return reducedMode; }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (pressing) return;                                           // finishPress() decides the end scale
        applyFocus(gainFocus, true);
        invalidate();
        invalidateRow();
    }

    /** Alpha of this tile when it is not focused: the Move-mode dim if one is set, else idle dim, else 1. */
    private float restAlpha() {
        return KeyRules.restAlpha(moveDimAlpha, idle, Ui.IDLE_ALPHA);
    }

    private void applyFocus(boolean gain, boolean withSway) {
        if (moving) {                                                   // Move mode owns scale, alpha and sway
            if (gain) applyMoving();
            return;
        }
        if (reduced) {
            applyReduced(gain);
            return;
        }
        if (reducedMode) {                                              // leaving reduced: scale is the source again
            reducedMode = false;
            if (redAnim != null) redAnim.cancel();
        }
        if (gain) {
            scaleTo(idle ? Ui.FOCUS_SCALE + Ui.IDLE_SCALE_ADD : Ui.FOCUS_SCALE, IN, Ui.ms(Ui.FOCUS_IN_MS));
            alphaTo(1f, Ui.ms(Ui.FOCUS_IN_MS), IN);
            removeCallbacks(swayStart);
            if (withSway) postDelayed(swayStart, Ui.ms(Ui.FOCUS_IN_MS + Ui.SWAY_START_MS));
        } else {
            scaleTo(1f, OUT, Ui.ms(Ui.FOCUS_OUT_MS));
            alphaTo(restAlpha(), Ui.ms(Ui.IDLE_FADE_MS), LINEAR);   // Move-mode dim wins: the tile that held focus stays dim
            stopSway();
        }
    }

    private void scaleTo(float s, Interpolator ip, long dur) {
        animate().setInterpolator(ip).setDuration(dur).scaleX(s).scaleY(s);
    }

    private void alphaTo(float target, long dur, Interpolator ip) {
        if (alphaAnim != null) alphaAnim.cancel();
        float cur = getAlpha();
        if (cur == target) return;
        if (dur <= 0) {
            setAlpha(target);
            return;
        }
        alphaFrom = cur;
        alphaTo = target;
        if (alphaAnim == null) {
            alphaAnim = ValueAnimator.ofFloat(0f, 1f);
            alphaAnim.addUpdateListener(a -> {
                float v = alphaFrom + (alphaTo - alphaFrom) * a.getAnimatedFraction();
                setAlpha(v < 0f ? 0f : (v > 1f ? 1f : v));
            });
        }
        alphaAnim.setInterpolator(ip);
        alphaAnim.setDuration(dur);
        alphaAnim.start();
    }

    /** Reduced path: scale set at once, progress fades 0<->1 over REDUCED_FADE_MS; no sway, no dim. */
    private void applyReduced(boolean gain) {
        float cur = focusProgress();                                    // under the old source, before flipping
        reducedMode = true;
        removeCallbacks(swayStart);
        stopSway();
        if (relaxAnim != null) relaxAnim.cancel();
        setRotationX(0f);
        setRotationY(0f);
        animate().cancel();
        float s = gain ? Ui.REDUCED_SCALE : 1f;
        setScaleX(s);
        setScaleY(s);
        if (alphaAnim != null) alphaAnim.cancel();
        setAlpha(gain || moveDimAlpha >= 1f ? 1f : moveDimAlpha);
        redP = cur;
        redFrom = cur;
        redTo = gain ? 1f : 0f;
        if (redAnim != null) redAnim.cancel();
        long d = Ui.ms(Ui.REDUCED_FADE_MS);
        if (d <= 0 || cur == redTo) {
            redP = redTo;
            return;
        }
        if (redAnim == null) {
            redAnim = ValueAnimator.ofFloat(0f, 1f);
            redAnim.setInterpolator(LINEAR);
            redAnim.addUpdateListener(a -> {
                redP = redFrom + (redTo - redFrom) * a.getAnimatedFraction();
                onTick();
            });
        }
        redAnim.setDuration(d);
        redAnim.start();
    }

    // ---- Move mode (task p5-2c) -------------------------------------------------------------------------

    private float movingScale() {
        return reduced ? Ui.REDUCED_SCALE + Ui.MOVE_SCALE_ADD / 2f : Ui.FOCUS_SCALE + Ui.MOVE_SCALE_ADD;
    }

    /** Scale, alpha and tilt of the moving tile, set at once (no animator): after a re-attach or a focus change. */
    private void applyMoving() {
        removeCallbacks(swayStart);
        if (swayAnim != null) swayAnim.cancel();
        if (relaxAnim != null) relaxAnim.cancel();
        if (redAnim != null) redAnim.cancel();
        if (alphaAnim != null) alphaAnim.cancel();
        animate().cancel();
        setRotationX(0f);
        setRotationY(0f);
        float s = movingScale();
        setScaleX(s);
        setScaleY(s);
        setAlpha(1f);
        reducedMode = reduced;
        redP = reduced ? 1f : 0f;
        redFrom = redP;
        redTo = redP;
        onTick();
    }

    /**
     * Move mode on/off for this tile. On: scale FOCUS_SCALE + MOVE_SCALE_ADD (reduced: REDUCED_SCALE +
     * MOVE_SCALE_ADD / 2), sway stopped and not restarted, label stays (the row draws it for the focused tile).
     * Off: back to the normal focused or resting state.
     */
    public void setMoving(boolean on) {
        if (on == moving) return;
        moving = on;
        if (on) {
            removeCallbacks(swayStart);
            if (reduced || !isAttachedToWindow()) {
                applyMoving();
            } else {
                if (swayAnim != null && swayAnim.isRunning()) swayAnim.cancel();   // its end hook eases the tilt out
                if (redAnim != null) redAnim.cancel();
                reducedMode = false;
                scaleTo(movingScale(), IN, Ui.ms(Ui.FOCUS_IN_MS));
                alphaTo(1f, Ui.ms(Ui.FOCUS_IN_MS), IN);
            }
        } else {
            boolean f = isFocused();
            applyFocus(f, f);
        }
        onTick();
    }

    public boolean isMoving() { return moving; }

    /**
     * Dim for the other tiles while one is being moved. animate=false sets it at once (relayout path, where a
     * detach just reset the alpha). Never touches the idle dim: Move mode runs with the idle timer off.
     */
    public void setMoveAlpha(float a, boolean animate) {
        moveDimAlpha = a;
        if (alphaAnim != null) alphaAnim.cancel();
        if (animate) alphaTo(a, Ui.ms(Ui.FOCUS_IN_MS), LINEAR);
        else setAlpha(a);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (moving) applyMoving();                                      // detach reset scale/alpha to rest; re-apply before the first draw
    }

    /**
     * Idle dim. Focused tile: scale FOCUS_SCALE + IDLE_SCALE_ADD (linear IDLE_FADE_MS) / back to FOCUS_SCALE.
     * Other tiles: alpha IDLE_ALPHA / 1. No-op to turn on while reduced motion is on.
     */
    public void setIdle(boolean on) {
        if (moving) return;                                             // Move mode: timer is off, scale and alpha are owned
        if (on && reduced) return;
        if (on == idle) return;
        idle = on;
        if (pressing) return;                                           // finishPress() applies the state
        if (isFocused()) {
            if (reducedMode) return;
            if (on) scaleTo(Ui.FOCUS_SCALE + Ui.IDLE_SCALE_ADD, LINEAR, Ui.ms(Ui.IDLE_FADE_MS));
            else scaleTo(Ui.FOCUS_SCALE, IN, Ui.ms(Ui.FOCUS_IN_MS));
        } else if (on) {
            alphaTo(Ui.IDLE_ALPHA, Ui.ms(Ui.IDLE_FADE_MS), LINEAR);
        } else {
            alphaTo(moveDimAlpha < 1f ? moveDimAlpha : 1f, Ui.ms(Ui.FOCUS_IN_MS), IN);
        }
    }

    // ---- idle sway --------------------------------------------------------------------------------------

    private void startSway() {
        if (reduced || reducedMode || pressing || moving || !isFocused() || !isAttachedToWindow()) return;
        long period = Ui.ms(Ui.SWAY_PERIOD_MS);
        if (period <= 0) return;
        if (relaxAnim != null) relaxAnim.cancel();
        if (!cameraSet) {
            setCameraDistance(Ui.SWAY_CAMERA * getResources().getDisplayMetrics().density);
            cameraSet = true;
        }
        if (swayAnim == null) {
            int cycles = Math.max(1, Math.min(Ui.SWAY_MAX_CYCLES, Ui.SWAY_MAX_MS / Ui.SWAY_PERIOD_MS));
            swayAnim = ValueAnimator.ofFloat(0f, 1f);
            swayAnim.setInterpolator(LINEAR);
            swayAnim.setRepeatCount(cycles - 1);
            swayAnim.addUpdateListener(a -> onSwayTick(a.getAnimatedFraction()));
            swayAnim.addListener(new EndHook(this::startRelax));
        }
        swayAnim.setDuration(period);
        swayT0 = AnimationUtils.currentAnimationTimeMillis();
        swayAnim.start();
    }

    private void onSwayTick(float fraction) {
        double phi = 2.0 * Math.PI * fraction;
        long ramp = Ui.ms(SWAY_RAMP_MS);
        float env = 1f;
        if (ramp > 0) {
            long el = AnimationUtils.currentAnimationTimeMillis() - swayT0;
            env = el >= ramp ? 1f : (el <= 0 ? 0f : el / (float) ramp);
        }
        setRotationY((float) (Ui.SWAY_ROT_Y * Math.sin(phi)) * env);
        setRotationX((float) (Ui.SWAY_ROT_X * Math.cos(phi)) * env);
        onTick();
    }

    /** Stop the sway (or cancel a pending start) and ease the tilt back to 0 over FOCUS_OUT_MS. */
    public void stopSway() {
        removeCallbacks(swayStart);
        if (swayAnim != null && swayAnim.isRunning()) swayAnim.cancel();   // its end hook starts the relax
    }

    private void startRelax() {
        float x = getRotationX(), y = getRotationY();
        if (x == 0f && y == 0f) return;
        long d = Ui.ms(Ui.FOCUS_OUT_MS);
        if (d <= 0) {
            setRotationX(0f);
            setRotationY(0f);
            onTick();
            return;
        }
        relaxX = x;
        relaxY = y;
        if (relaxAnim == null) {
            relaxAnim = ValueAnimator.ofFloat(0f, 1f);
            relaxAnim.setInterpolator(LINEAR);
            relaxAnim.addUpdateListener(a -> {
                float k = 1f - a.getAnimatedFraction();
                setRotationX(relaxX * k);
                setRotationY(relaxY * k);
                onTick();
            });
        }
        relaxAnim.setDuration(d);
        relaxAnim.start();
    }

    /** Dip to PRESS_SCALE, run {@code after} (the launch), spring back. A press while one runs is ignored. */
    public void press(Runnable after) {
        if (reduced) {                                                  // no dip in reduced motion
            if (pressing) return;
            if (after != null) after.run();
            return;
        }
        if (pressing) return;
        pressing = true;
        pressAfter = after;
        stopSway();
        long d = Ui.ms(Ui.PRESS_MS);
        animate().setInterpolator(LINEAR).setDuration(d).scaleX(Ui.PRESS_SCALE).scaleY(Ui.PRESS_SCALE);
        postDelayed(pressDone, d);
    }

    private void finishPress() {
        pressing = false;
        Runnable r = pressAfter;
        pressAfter = null;
        applyFocus(isFocused(), false);
        if (r != null) r.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(pressDone);
        removeCallbacks(swayStart);
        animate().cancel();
        if (swayAnim != null) swayAnim.cancel();
        if (relaxAnim != null) relaxAnim.cancel();
        if (alphaAnim != null) alphaAnim.cancel();
        if (redAnim != null) redAnim.cancel();
        if (pressing) {
            pressing = false;
            pressAfter = null;
        }
        idle = false;
        reducedMode = false;
        redP = 0f;
        setRotationX(0f);
        setRotationY(0f);
        setAlpha(1f);
        setScaleX(1f);
        setScaleY(1f);
    }

    private void onTick() {
        invalidate();                                                   // gloss/rim alpha follow the scale
        invalidateRow();                                                // the row draws shadow, glow, label
    }

    private void invalidateRow() {
        ViewParent p = getParent();
        if (p instanceof View) ((View) p).invalidate();
    }

    // ---- geometry -------------------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildGeometry();
    }

    private void buildGeometry() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float sw = Math.max(1f, Ui.pxf(1));
        float half = sw / 2f;
        radius = Ui.pxf(Ui.TILE_R);
        insetRadius = Math.max(0f, radius - half);
        outer.set(0, 0, w, h);
        inset.set(half, half, w - half, h - half);
        edge.setStrokeWidth(sw);
        rim.setStrokeWidth(sw);

        // Gloss: top-left corner to centre, GLOSS_ALPHA -> 0.
        glossShader = new LinearGradient(0, 0, w / 2f, h / 2f,
                Ui.argb(Ui.GLOSS_ALPHA, WHITE), Ui.argb(0f, WHITE), Shader.TileMode.CLAMP);
        gloss.setShader(glossShader);
        // Rim: top to bottom, RIM_TOP -> RIM_SIDE (paint alpha scales with focus progress).
        rim.setShader(new LinearGradient(0, 0, 0, h,
                Ui.argb(Ui.RIM_TOP, WHITE), Ui.argb(Ui.RIM_SIDE, WHITE), Shader.TileMode.CLAMP));
        updateShaderMatrix();

        if (fireTvHome && fthFill != null) {
            fthFill.setShader(cssGradient(w, h, Ui.FTH_FILL_ANGLE,
                    new int[] {Ui.argb(Ui.FTH_FILL_HI, WHITE), Ui.argb(Ui.FTH_FILL_LO, WHITE)}, null));
            fthRim.setStrokeWidth(sw);
            fthRim.setShader(cssGradient(w, h, Ui.GLASS_RIM_ANGLE,
                    new int[] {Ui.argb(Ui.GLASS_RIM_HI, WHITE), Ui.argb(Ui.GLASS_RIM_LO, WHITE),
                            Ui.argb(Ui.GLASS_RIM_HI * 0.5f, WHITE)},
                    new float[] {0f, Ui.FTH_RIM_MID_STOP, 1f}));
            // House glyph on a 48-unit viewBox scaled to Ui.FTH_GLYPH_BOX design px.
            float u = Ui.pxf(Ui.FTH_GLYPH_BOX) / Ui.FTH_VIEWBOX;
            float cx = w / 2f, cy = h / 2f - Ui.pxf(Ui.FTH_GLYPH_RAISE);
            glyph.reset();
            glyph.moveTo(cx + (6 - 24) * u, cy + (22 - 24) * u);
            glyph.lineTo(cx + (24 - 24) * u, cy + (7 - 24) * u);
            glyph.lineTo(cx + (42 - 24) * u, cy + (22 - 24) * u);
            glyph.moveTo(cx + (10 - 24) * u, cy + (19 - 24) * u);
            glyph.lineTo(cx + (10 - 24) * u, cy + (40 - 24) * u);
            glyph.lineTo(cx + (20 - 24) * u, cy + (40 - 24) * u);
            glyph.lineTo(cx + (20 - 24) * u, cy + (28 - 24) * u);
            glyph.lineTo(cx + (28 - 24) * u, cy + (28 - 24) * u);
            glyph.lineTo(cx + (28 - 24) * u, cy + (40 - 24) * u);
            glyph.lineTo(cx + (38 - 24) * u, cy + (40 - 24) * u);
            glyph.lineTo(cx + (38 - 24) * u, cy + (19 - 24) * u);
            glyphPaint.setStrokeWidth(Ui.FTH_STROKE_UNITS * u);
            captionBaseline = h - Ui.pxf(Ui.FTH_CAPTION_BOTTOM) - caption.getFontMetrics().descent;
        }
    }

    private void updateShaderMatrix() {
        if (shader == null || art == null) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float sx = w / (float) art.getWidth(), sy = h / (float) art.getHeight();
        float s = Math.max(sx, sy);                                      // cover
        shaderMatrix.setScale(s, s);
        shaderMatrix.postTranslate((w - art.getWidth() * s) / 2f, (h - art.getHeight() * s) / 2f);
        shader.setLocalMatrix(shaderMatrix);
    }

    /** CSS-style linear-gradient(angle, ...) over a w x h box. */
    private static LinearGradient cssGradient(float w, float h, float deg, int[] colors, float[] pos) {
        double a = Math.toRadians(deg);
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
        float len = Math.abs(w * dx) + Math.abs(h * dy);
        float cx = w / 2f, cy = h / 2f;
        return new LinearGradient(cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f,
                colors, pos, Shader.TileMode.CLAMP);
    }

    // ---- drawing (no allocation) ------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        float p = focusProgress();
        if (fireTvHome) {
            canvas.drawRoundRect(outer, radius, radius, fthFill);
            canvas.drawPath(glyph, glyphPaint);
            if (p < 1f) {                                                // caption hidden while focused
                caption.setColor(Ui.argb(Ui.TEXT2_ALPHA * (1f - p), WHITE));
                canvas.drawText(label, getWidth() / 2f, captionBaseline, caption);
            }
            canvas.drawRoundRect(inset, insetRadius, insetRadius, fthRim);
        } else if (shader != null) {
            artPaint.setColorFilter(p > 0f ? BRIGHT : null);
            canvas.drawRoundRect(outer, radius, radius, artPaint);
        } else {
            canvas.drawRoundRect(outer, radius, radius, plate);
        }
        canvas.drawRoundRect(inset, insetRadius, insetRadius, edge);     // static 1 px edge, always
        if (p > 0f) {
            int a = Math.round(p * 255f);
            gloss.setAlpha(a);
            if (glossShader != null) {                                   // gloss slides with the sway tilt
                glossMatrix.setTranslate(getRotationY() / Ui.SWAY_ROT_Y * GLOSS_SHIFT * getWidth(),
                        getRotationX() / Ui.SWAY_ROT_X * GLOSS_SHIFT * getHeight());
                glossShader.setLocalMatrix(glossMatrix);
            }
            canvas.drawRoundRect(outer, radius, radius, gloss);
            rim.setAlpha(a);
            canvas.drawRoundRect(inset, insetRadius, insetRadius, rim);
        }
    }
}
