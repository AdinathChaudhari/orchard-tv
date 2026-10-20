package tv.orchard.home;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Liquid Glass stand-in for one surface (PLAN 0.8): drop shadow, blurred-wallpaper slice, fill, inner
 * gradient, refraction band, top highlight, gradient rim. Build once per size with setSize(); draw()
 * allocates nothing (a new BitmapShader is made only when the wallpaper bitmap object changes).
 * Only LinearGradient / BitmapShader; no offscreen layers, no render effects.
 */
public final class Glass {
    private final int radiusDesign;
    private final int fillColor;
    private final float sliceAlpha;

    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint slicePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint refractPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF rimRect = new RectF();
    private final Matrix matrix = new Matrix();

    private int w, h;
    private float radius, rimRadius, edgeY, edgeX0, edgeX1;
    private Bitmap shadow;                 // ALPHA_8, (w + 2 pad) x (h + 2 pad)
    private Bitmap shaderBitmap;
    private BitmapShader shader;

    public Glass(int radiusDesignPx, int fillColor, float sliceAlpha) {
        this.radiusDesign = radiusDesignPx;
        this.fillColor = fillColor;
        this.sliceAlpha = sliceAlpha;
    }

    /** Device px the caller must leave on every side of the surface for the drop shadow. */
    public static int shadowPad() {
        return Ui.px(Ui.GLASS_DROP_Y + Ui.GLASS_DROP_BLUR);
    }

    /** Builds the gradients and the shadow bitmap; call once per size (needs Ui.init first). */
    public void setSize(int wPx, int hPx) {
        if (wPx < 1 || hPx < 1) return;
        w = wPx;
        h = hPx;
        radius = Math.min(Ui.pxf(radiusDesign), Math.min(w, h) / 2f);
        float stroke = Math.max(1f, Ui.pxf(1));
        rect.set(0, 0, w, h);
        rimRect.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f);
        rimRadius = Math.max(0f, radius - stroke / 2f);

        fillPaint.setColor(fillColor);
        slicePaint.setAlpha(Math.round(sliceAlpha * 255f));

        int white = 0xFFFFFF;
        innerPaint.setShader(new LinearGradient(0, 0, 0, h * Ui.GLASS_INNER_H,
                Ui.argb(Ui.GLASS_INNER_ALPHA, white), Ui.argb(0f, white), Shader.TileMode.CLAMP));
        refractPaint.setShader(new LinearGradient(0, 0, 0, Ui.pxf(Ui.GLASS_REFRACT_H),
                Ui.argb(Ui.GLASS_REFRACT_ALPHA, white), Ui.argb(0f, white), Shader.TileMode.CLAMP));

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(stroke);
        edgePaint.setColor(Ui.argb(Ui.GLASS_TOP_EDGE_ALPHA, white));
        edgeY = stroke / 2f;
        edgeX0 = radius;
        edgeX1 = w - radius;

        // CSS linear-gradient(115deg): direction (sin a, -cos a), line length |w sin a| + |h cos a|, through the centre
        double a = Math.toRadians(Ui.GLASS_RIM_ANGLE);
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
        float len = Math.abs(w * dx) + Math.abs(h * dy);
        float cx = w / 2f, cy = h / 2f;
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(stroke);
        rimPaint.setShader(new LinearGradient(cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f,
                new int[] { Ui.argb(Ui.GLASS_RIM_HI, white), Ui.argb(Ui.GLASS_RIM_LO, white),
                        Ui.argb(Ui.GLASS_RIM_LO, white), Ui.argb(Ui.GLASS_RIM_END, white) },
                new float[] { 0f, Ui.GLASS_RIM_MIDLO_STOP, Ui.GLASS_RIM_MIDHI_STOP, 1f }, Shader.TileMode.CLAMP));

        buildShadow(stroke);
    }

    /** One pre-rendered ALPHA_8 bitmap: blurred rounded rect, shifted down by the drop, interior cut out (CSS box-shadow). */
    private void buildShadow(float stroke) {
        int pad = shadowPad();
        if (shadow != null) shadow.recycle();
        shadow = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(shadow);
        float sigma = Ui.pxf(Ui.GLASS_DROP_BLUR) / 2f;             // CSS blur radius B = 2 sigma
        float skiaRadius = Math.max(1f, (sigma - 0.5f) / 0.57735f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setMaskFilter(new BlurMaskFilter(skiaRadius, BlurMaskFilter.Blur.NORMAL));
        float drop = Ui.pxf(Ui.GLASS_DROP_Y);
        c.drawRoundRect(new RectF(pad, pad + drop, pad + w, pad + drop + h), radius, radius, p);
        Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        c.drawRoundRect(new RectF(pad, pad, pad + w, pad + h), radius, radius, cut);
        shadowPaint.setColor(Ui.argb(Ui.GLASS_DROP_ALPHA, 0x000000));
    }

    /**
     * Draws the surface with its top-left at (left, top) in canvas coordinates (the drop shadow extends
     * shadowPad() beyond that). surfaceScreenX/Y = where that top-left is on the screen; the slice samples the
     * 240x135 wallpaper at the surface's screen rect (blurred is stretched over screenW x screenH).
     */
    public void draw(Canvas c, float left, float top, Bitmap blurredWallpaper, int surfaceScreenX, int surfaceScreenY,
                     int screenW, int screenH) {
        if (shadow == null) return;
        int save = c.save();
        c.translate(left, top);
        int pad = shadowPad();
        c.drawBitmap(shadow, -pad, -pad, shadowPaint);

        if (blurredWallpaper == null && shaderBitmap != null) {        // released in onStop: drop our reference too
            shaderBitmap = null;
            shader = null;
            slicePaint.setShader(null);
        }
        if (blurredWallpaper != null && screenW > 0 && screenH > 0) {
            if (blurredWallpaper != shaderBitmap) {
                shaderBitmap = blurredWallpaper;
                shader = new BitmapShader(blurredWallpaper, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                slicePaint.setShader(shader);
            }
            matrix.setScale(screenW / (float) shaderBitmap.getWidth(), screenH / (float) shaderBitmap.getHeight());
            matrix.postTranslate(-surfaceScreenX, -surfaceScreenY);
            shader.setLocalMatrix(matrix);
            c.drawRoundRect(rect, radius, radius, slicePaint);
        }
        c.drawRoundRect(rect, radius, radius, fillPaint);
        c.drawRoundRect(rect, radius, radius, innerPaint);
        c.drawRoundRect(rect, radius, radius, refractPaint);
        c.drawLine(edgeX0, edgeY, edgeX1, edgeY, edgePaint);
        c.drawRoundRect(rimRect, rimRadius, rimRadius, rimPaint);
        c.restoreToCount(save);
    }
}
