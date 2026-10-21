package tv.orchard.home;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.List;

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
    // halo colour. Result = 1 - (1 - ts*m)^4, identical to the shadow-layer passes; that compound alpha is baked into the
    // sprite (HaloMath.compoundLut, per ts step of 1/64) so each sprite is drawn ONCE (4 draws were 4 full fills on the Stick).

    private static final int BUDGET_BYTES = 6 * 1024 * 1024;
    /** Hard ceilings: text sprites (one per string x metrics) and path sprites (a handful of icons). */
    private static final int TEXT_MAX_COUNT = 96, PATH_MAX_COUNT = 6;
    private static final long TEXT_MAX_BYTES = BUDGET_BYTES, PATH_MAX_BYTES = 512L * 1024;

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
        final Bitmap bmp;        // ALPHA_8: the compound halo alpha for ts step q (see HaloMath.compoundLut)
        final byte[] mask;       // the blurred glyph / stroke mask the bitmap is derived from
        final float dx, dy;      // bitmap top-left relative to (left edge, baseline)
        int q = -1;              // ts step the bitmap currently holds

        Sprite(Bitmap bmp, byte[] mask, float dx, float dy) { this.bmp = bmp; this.mask = mask; this.dx = dx; this.dy = dy; }

        int cost() { return mask.length * 2; }
    }

    private static byte[] scratchBytes = new byte[0];

    /** Pulls the mask out of a freshly baked ALPHA_8 bitmap; the bitmap then becomes the sprite's compound-alpha target. */
    private static byte[] maskOf(Bitmap b) {
        byte[] m = new byte[b.getByteCount()];
        b.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(m));
        return m;
    }

    /** Rewrites the sprite's bitmap for ts step q (no-op when it already holds it). Main thread, only when ts moved. */
    private static void compose(Sprite sp, int q) {
        if (sp.q == q) return;
        byte[] lut = HaloMath.compoundLut(q, Theme.HALO_PASSES);
        int n = sp.mask.length;
        if (scratchBytes.length < n) scratchBytes = new byte[n];
        for (int i = 0; i < n; i++) scratchBytes[i] = lut[sp.mask[i] & 0xFF];
        sp.bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(scratchBytes, 0, n));
        sp.q = q;
    }

    private static final Key PROBE = new Key();
    private static final SpriteLru<Key, Sprite> CACHE = new SpriteLru<>(TEXT_MAX_COUNT, TEXT_MAX_BYTES);
    private static final SpriteLru<PathKey, Sprite> PATHS = new SpriteLru<>(PATH_MAX_COUNT, PATH_MAX_BYTES);
    private static final PathKey PATH_PROBE = new PathKey();
    private static final Paint BAKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint TINT = new Paint();

    /** Drops every baked halo (onStop; never recycled, a recorded display list may still reference one); the next halo draw re-bakes what it needs. */
    public static void clear() {
        freeAll(CACHE.clear());
        freeAll(PATHS.clear());
    }

    /** Large phase jump (debug_time switch, clock set): the halo sprites of the old phase are dead weight. */
    public static void onPhaseJump() { clear(); }

    /** Bytes held by baked halos (text + path). */
    public static long bytes() { return CACHE.bytes() + PATHS.bytes(); }

    private static void freeAll(List<Sprite> l) {
        for (Sprite sp : l) freeLater(sp.bmp);
    }

    private static final class PathKey {
        int id;
        float stroke, radius;

        PathKey copy() { PathKey k = new PathKey(); k.id = id; k.stroke = stroke; k.radius = radius; return k; }

        @Override public boolean equals(Object o) {
            if (!(o instanceof PathKey)) return false;
            PathKey k = (PathKey) o;
            return id == k.id && stroke == k.stroke && radius == k.radius;
        }

        @Override public int hashCode() {
            return (id * 31 + Float.floatToIntBits(stroke)) * 31 + Float.floatToIntBits(radius);
        }
    }

    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Recycles an evicted sprite one second later (a recorded display list may still reference it), so it does not wait for the GC. */
    static void freeLater(final Bitmap b) {
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
        sp = new Sprite(b, maskOf(b), -pad, -base);
        freeAll(CACHE.put(PROBE.copy(), sp, sp.cost()));
        return sp;
    }

    public static void drawText(Canvas c, String s, float x, float y, Paint paint, Paint scratch) {
        if (Theme.ts() > 0f && s != null && !s.isEmpty()) {
            int colour = Theme.haloColour();
            float r = radiusPx();
            int q = HaloMath.quantTs(Theme.ts());
            Sprite sp = q > 0 ? sprite(s, paint, r) : null;
            if (sp != null) {
                compose(sp, q);
                float left = HaloMath.leftEdge(paint.getTextAlign().ordinal(), x, paint.measureText(s));
                float bx = left + sp.dx, by = y + sp.dy;
                TINT.setColor(0xFF000000 | (colour & 0xFFFFFF));
                c.drawBitmap(sp.bmp, bx, by, TINT);          // one draw: the 4-pass compound alpha is baked in
            } else if (q > 0) {
                scratch.set(paint);
                scratch.setColor(colour);
                scratch.setShadowLayer(r, 0f, 0f, colour);
                for (int i = 0; i < Theme.HALO_PASSES; i++) c.drawText(s, x, y, scratch);
            }
        }
        c.drawText(s, x, y, paint);
    }

    private static final RectF BOUNDS = new RectF();

    /** Bakes a stroked path's halo (blurred stroke + sharp stroke) once; bitmap origin is in the path's own coordinates. */
    private static Sprite pathSprite(int id, Path p, Paint stroke, float radius) {
        PATH_PROBE.id = id; PATH_PROBE.stroke = stroke.getStrokeWidth(); PATH_PROBE.radius = radius;
        Sprite sp = PATHS.get(PATH_PROBE);
        if (sp != null) return sp;
        p.computeBounds(BOUNDS, true);
        int pad = HaloMath.pad(radius) + (int) Math.ceil(stroke.getStrokeWidth() / 2f);
        int ox = (int) Math.floor(BOUNDS.left) - pad, oy = (int) Math.floor(BOUNDS.top) - pad;
        int w = (int) Math.ceil(BOUNDS.right) + pad - ox, h = (int) Math.ceil(BOUNDS.bottom) + pad - oy;
        if (HaloMath.tooBig(w, h, (int) PATH_MAX_BYTES)) return null;
        Bitmap b;
        try {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
        Canvas bc = new Canvas(b);
        BAKE.reset();
        BAKE.setAntiAlias(true);
        BAKE.setStyle(stroke.getStyle());
        BAKE.setStrokeCap(stroke.getStrokeCap());
        BAKE.setStrokeJoin(stroke.getStrokeJoin());
        BAKE.setStrokeWidth(stroke.getStrokeWidth());
        BAKE.setColor(0xFF000000);
        bc.translate(-ox, -oy);
        BAKE.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
        bc.drawPath(p, BAKE);
        BAKE.setMaskFilter(null);
        bc.drawPath(p, BAKE);
        sp = new Sprite(b, maskOf(b), ox, oy);
        freeAll(PATHS.put(PATH_PROBE.copy(), sp, sp.cost()));
        return sp;
    }

    /**
     * Same for a stroked icon, baked once per (id, stroke width, radius). id names the geometry: bump it whenever the
     * path is rebuilt (layout), so a stale sprite is never reused. Falls back to the shadow layer only if the bake fails.
     */
    public static void drawPath(Canvas c, int id, Path p, Paint stroke, Paint scratch) {
        if (Theme.ts() > 0f) {
            int colour = Theme.haloColour();
            float r = radiusPx();
            int q = HaloMath.quantTs(Theme.ts());
            Sprite sp = q > 0 ? pathSprite(id, p, stroke, r) : null;
            if (sp != null) {
                compose(sp, q);
                TINT.setColor(0xFF000000 | (colour & 0xFFFFFF));
                c.drawBitmap(sp.bmp, sp.dx, sp.dy, TINT);
            } else if (q > 0) {
                scratch.set(stroke);
                scratch.setColor(colour);
                scratch.setShadowLayer(r, 0f, 0f, colour);
                for (int i = 0; i < Theme.HALO_PASSES; i++) c.drawPath(p, scratch);
            }
        }
        c.drawPath(p, stroke);
    }
}
