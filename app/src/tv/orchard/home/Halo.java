package tv.orchard.home;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dawn / dusk glyph halo: a soft glow in the current bg colour behind text and stroked icons, so they stay
 * readable while the sky crosses mid-luminance. Only active while Theme.ts() > 0 (about 30 min twice a day);
 * Text halos are baked once per string into cached ALPHA_8 blur masks (no per-frame blur); the caller supplies a scratch Paint for the uncached fallback and stroked paths.
 */
public final class Halo {
    private Halo() {}

    /** CSS blur 16 = sigma 8; Android shadow radius r gives sigma = 0.57735 r + 0.5. */
    public static float radiusPx() {
        return Math.max(1f, (Ui.pxf(Theme.HALO_BLUR) / 2f - 0.5f) / 0.57735f);
    }

    // ---- cached halo -----------------------------------------------------------------------------------
    // Twilight jank (dock sweep 50-57 % janky only while Theme.ts() > 0): the per-frame shadow-layer text, 4 passes
    // per string, re-blurs every glyph run on the GPU each frame. The blurred glyph mask is the same every frame, so
    // it is baked once per (string, paint metrics) into an ALPHA_8 bitmap and drawn HALO_PASSES times tinted with the
    // halo colour. Result = 1 - (1 - ts*m)^4, identical to the shadow-layer passes.

    private static final int BUDGET_BYTES = 6 * 1024 * 1024;

    private static final class Key {
        String s;
        float size, spacing, scaleX, radius;
        int face;
        boolean bold;

        void set(String s, Paint p, float radius) {
            this.s = s; size = p.getTextSize(); spacing = p.getLetterSpacing(); scaleX = p.getTextScaleX();
            this.radius = radius; face = System.identityHashCode(p.getTypeface()); bold = p.isFakeBoldText();
        }

        Key copy() {
            Key k = new Key();
            k.s = s; k.size = size; k.spacing = spacing; k.scaleX = scaleX; k.radius = radius; k.face = face; k.bold = bold;
            return k;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return size == k.size && spacing == k.spacing && scaleX == k.scaleX && radius == k.radius
                    && face == k.face && bold == k.bold && s.equals(k.s);
        }

        @Override public int hashCode() {
            int h = s.hashCode();
            h = h * 31 + Float.floatToIntBits(size);
            h = h * 31 + Float.floatToIntBits(spacing);
            h = h * 31 + Float.floatToIntBits(scaleX);
            h = h * 31 + Float.floatToIntBits(radius);
            return h * 31 + face + (bold ? 1 : 0);
        }
    }

    private static final class Sprite {
        final Bitmap bmp;
        final float dx, dy;      // bitmap top-left relative to (left edge, baseline)

        Sprite(Bitmap bmp, float dx, float dy) { this.bmp = bmp; this.dx = dx; this.dy = dy; }
    }

    private static final Key PROBE = new Key();
    private static final LinkedHashMap<Key, Sprite> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static int cacheBytes;
    private static final Paint BAKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint TINT = new Paint();

    /** Drops every baked halo (onStop; never recycled, a recorded display list may still reference one); the next halo draw re-bakes what it needs. */
    public static void clear() {
        for (Sprite sp : CACHE.values()) freeLater(sp.bmp);
        CACHE.clear();
        cacheBytes = 0;
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Recycles an evicted sprite one second later (a recorded display list may still reference it), so it does not wait for the GC. */
    private static void freeLater(final Bitmap b) {
        MAIN.postDelayed(() -> { if (!b.isRecycled()) b.recycle(); }, 1000L);
    }

    private static Sprite sprite(String s, Paint paint, float radius) {
        PROBE.set(s, paint, radius);
        Sprite sp = CACHE.get(PROBE);
        if (sp != null) return sp;
        BAKE.reset();
        BAKE.setAntiAlias(true);
        BAKE.setTypeface(paint.getTypeface());
        BAKE.setTextSize(paint.getTextSize());
        BAKE.setLetterSpacing(paint.getLetterSpacing());
        BAKE.setTextScaleX(paint.getTextScaleX());
        BAKE.setFakeBoldText(paint.isFakeBoldText());
        BAKE.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics fm = BAKE.getFontMetrics();
        int pad = HaloMath.pad(radius);
        int w = (int) Math.ceil(BAKE.measureText(s)) + 2 * pad;
        int h = (int) Math.ceil(fm.descent - fm.ascent) + 2 * pad;
        if (HaloMath.tooBig(w, h, BUDGET_BYTES)) return null;
        Bitmap b;
        try {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
        Canvas bc = new Canvas(b);
        float base = pad - fm.ascent;
        BAKE.setColor(0xFF000000);
        BAKE.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
        bc.drawText(s, pad, base, BAKE);
        BAKE.setMaskFilter(null);
        bc.drawText(s, pad, base, BAKE);        // the shadow-layer version also drew the sharp glyph in the halo colour
        sp = new Sprite(b, -pad, -base);
        int bytes = w * h;
        while (cacheBytes + bytes > BUDGET_BYTES && !CACHE.isEmpty()) {
            Iterator<Map.Entry<Key, Sprite>> it = CACHE.entrySet().iterator();
            Map.Entry<Key, Sprite> e = it.next();
            cacheBytes -= e.getValue().bmp.getByteCount();
            freeLater(e.getValue().bmp);
            it.remove();
        }
        CACHE.put(PROBE.copy(), sp);
        cacheBytes += bytes;
        return sp;
    }

    public static void drawText(Canvas c, String s, float x, float y, Paint paint, Paint scratch) {
        if (Theme.ts() > 0f && s != null && !s.isEmpty()) {
            int colour = Theme.haloColour();
            float r = radiusPx();
            Sprite sp = sprite(s, paint, r);
            if (sp != null) {
                float left = HaloMath.leftEdge(paint.getTextAlign().ordinal(), x, paint.measureText(s));
                float bx = left + sp.dx, by = y + sp.dy;
                TINT.setColor(colour);
                for (int i = 0; i < Theme.HALO_PASSES; i++) c.drawBitmap(sp.bmp, bx, by, TINT);
            } else {
                scratch.set(paint);
                scratch.setColor(colour);
                scratch.setShadowLayer(r, 0f, 0f, colour);
                for (int i = 0; i < Theme.HALO_PASSES; i++) c.drawText(s, x, y, scratch);
            }
        }
        c.drawText(s, x, y, paint);
    }

    /** Same for a stroked icon (API 28+ hardware canvases support shadow layers on paths). */
    public static void drawPath(Canvas c, Path p, Paint stroke, Paint scratch) {
        if (Theme.ts() > 0f) {
            int colour = Theme.haloColour();
            scratch.set(stroke);
            scratch.setColor(colour);
            scratch.setShadowLayer(radiusPx(), 0f, 0f, colour);
            for (int i = 0; i < Theme.HALO_PASSES; i++) c.drawPath(p, scratch);
        }
        c.drawPath(p, stroke);
    }
}
