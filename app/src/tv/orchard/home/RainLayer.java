package tv.orchard.home;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

/**
 * The falling-rain layer (Rev 6): ONE tall view, one drawRect with a REPEAT BitmapShader of the 384 design-px
 * tile, moved down by one tile per loop with a translationY animator (a RenderNode property: no redraw per frame).
 * The host places it with a top margin of -Ui.px(Ui.RAIN_TILE) and a height of screen height + Ui.px(Ui.RAIN_TILE).
 * GONE when off. The animator runs only between play() and pause() while the rain is on and motion is allowed.
 */
public final class RainLayer extends View {
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private Bitmap tile;
    private BitmapShader shader;
    private boolean tileLight;
    private boolean on, light, reduced, playing, loading;
    private float alpha = Ui.RAIN_ALPHA;
    private int gen;
    private ObjectAnimator anim;

    public RainLayer(Context c) {
        super(c);
        setWillNotDraw(false);
        setVisibility(GONE);
    }

    /** alpha: Ui.RAIN_ALPHA for the dark tile, frame.rainAlpha for the light one (SkyPhase.rainAlphaLight) (the host computes it). */
    public void setRain(boolean rainOn, boolean lightTile, float a) {
        on = rainOn;
        alpha = a < 0 ? 0 : a > 1 ? 1 : a;
        paint.setAlpha(Math.round(alpha * 255f));
        if (!on) {
            stop();
            setVisibility(GONE);
            return;
        }
        if (lightTile != light || tile == null) {
            light = lightTile;
            ensureTile();
        }
        setVisibility(tile != null && alpha > 0f ? VISIBLE : GONE);
        invalidate();
        if (playing) startAnim();
    }

    public void setReducedMotion(boolean r) {
        reduced = r;
        if (r) {
            cancelAnim();
            setTranslationY(Ui.pxf(Ui.RAIN_TILE / 2f));
        } else if (playing) {
            startAnim();
        } else {
            setTranslationY(0f);
        }
    }

    /** Start the loop (home is on screen, rain is on). No-op while off or reduced. */
    public void play() {
        playing = true;
        startAnim();
    }

    /** Stop the loop (screen left home / onStop). */
    public void pause() {
        playing = false;
        cancelAnim();
    }

    /** Drop the tile and the shader (onStop). setRain() brings it back. */
    public void release() {
        gen++;
        loading = false;
        cancelAnim();
        tile = null;
        shader = null;
        paint.setShader(null);
    }

    private void stop() {
        cancelAnim();
        if (!reduced) setTranslationY(0f);
    }

    private void startAnim() {
        if (!on || reduced || tile == null) return;
        if (anim != null && anim.isStarted()) return;
        anim = ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, 0f, Ui.pxf(Ui.RAIN_TILE));
        anim.setDuration(Ui.RAIN_LOOP_MS);
        final int steps = Math.max(1, Math.round(Ui.RAIN_LOOP_MS * 30f / 1000f));   // 30 fps: PLAN 9.x allows a quantised loop
        anim.setInterpolator(t -> RainText.step(t, steps));
        anim.setRepeatCount(ObjectAnimator.INFINITE);
        anim.setRepeatMode(ObjectAnimator.RESTART);
        anim.start();
    }

    private void cancelAnim() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    private void ensureTile() {
        if (loading && light == tileLight) return;
        loading = true;
        tileLight = light;
        final int g = ++gen;
        final boolean lt = light;
        final Context c = getContext().getApplicationContext();
        SkyAssets.background(() -> {
            final Bitmap b = SkyAssets.rainTile(c, lt);
            SkyAssets.post(() -> {
                if (g != gen) return;
                loading = false;
                if (b == null) return;
                tile = b;
                shader = new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
                float k = Ui.pxf(Ui.RAIN_TILE) / b.getWidth();
                matrix.setScale(k, k);
                shader.setLocalMatrix(matrix);
                paint.setShader(shader);
                paint.setAlpha(Math.round(alpha * 255f));
                if (on) {
                    setVisibility(alpha > 0f ? VISIBLE : GONE);
                    invalidate();
                    if (playing) startAnim();
                }
            });
        });
    }

    @Override
    protected void onDraw(Canvas c) {
        if (shader == null || !on) return;
        c.drawRect(0, 0, getWidth(), getHeight(), paint);
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnim();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (playing) startAnim();
    }
}
