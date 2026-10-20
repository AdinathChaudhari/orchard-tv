package tv.orchard.home;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

/**
 * The Rev 6 sky (mockup #sky): two sky keyframes cross-faded by alpha, the overcast pair, the weather veils and
 * the sun / moon glow tiles, all drawn in one onDraw from pre-rendered bitmaps. No children, no hardware layer,
 * no offscreen layer, no shader. It redraws only on setState, when a bitmap arrives, and during a 900 ms fade.
 *
 * <p>Draw order (mock order): clear pair [A opaque, B at f]; overcast pair on top; veils (fogDay, fogNight, rdim,
 * rainDay); sun glow; moon glow. When oc &gt;= 0.999 only the overcast pair is drawn (exact and cheaper). For
 * 0 &lt; oc &lt; 1 (haze, 0.30) the overcast pair is A_oc at oc then B_oc at oc*f; the mockup composites that pair as
 * a group at alpha oc, so the base shows through ~f*oc more than it should: at most ~0.30 * f of the clear sky, and
 * only on a hazy keyframe window.
 *
 * <p>Bake (twilight jank fix): a stack of more than one full-screen layer (A + B*f, the overcast pair) cost ~24 ms
 * of GPU per frame and janked the dock sweep. When the stack changes (at most once a minute) the layers are
 * composed once into a single 960x540 bitmap on the SkyAssets thread; onDraw then draws that one bitmap. Until a
 * bake lands, a bake of the same keyframe pair (up to a minute older, visually identical) or else the layers draw.
 *
 * <p>Transitions: SkyPhase.shouldSwap (a live window hand-over) swaps roles with no fade; otherwise animate &amp;&amp;
 * !reducedMotion fades 900 ms (old stack first, new stack on top at alpha t, veils/glows interpolated); else snap.
 * Without a fade the stack is exact. During a fade it is exact at both ends and a close blend between
 * (the new stack is drawn over the old at alpha t rather than as a group).
 */
public final class SkyView extends View {
    private static final int V_FOGD = 0, V_FOGN = 1, V_RDIM = 2, V_RAIND = 3, V_SUNX = 4, V_SUNY = 5, V_SUNA = 6,
            V_MOONX = 7, V_MOONY = 8, V_MOONA = 9, NV = 10;

    /** One sky state: keyframe indexes, mix, overcast amount and the veil / glow values. */
    private static final class St {
        int a = -1, b = -1, scrim = Ui.BG;
        float f, oc;
        final float[] v = new float[NV];

        void set(SkyPhase.Frame fr) {
            a = fr.a; b = fr.b; f = fr.f; oc = fr.oc; scrim = fr.scrimRgb;
            v[V_FOGD] = fr.fogDay; v[V_FOGN] = fr.fogNight; v[V_RDIM] = fr.rdim; v[V_RAIND] = fr.rainDay;
            v[V_SUNX] = fr.sunX; v[V_SUNY] = fr.sunY; v[V_SUNA] = fr.sunA;
            v[V_MOONX] = fr.moonX; v[V_MOONY] = fr.moonY; v[V_MOONA] = fr.moonA;
        }

        void copy(St o) {
            a = o.a; b = o.b; f = o.f; oc = o.oc; scrim = o.scrim;
            System.arraycopy(o.v, 0, v, 0, NV);
        }

        boolean hasB() { return b != a; }
    }

    private static int key(int idx, boolean oc) { return idx * 2 + (oc ? 1 : 0); }

    /** The bitmap keys a state draws (clear pair unless fully overcast, overcast pair when oc > 0), -1 padded. */
    private static int[] keys(St s, boolean onlyA) {
        int[] k = {-1, -1, -1, -1};
        int n = 0;
        if (s.oc < 0.999f) {
            k[n++] = key(s.a, false);
            if (!onlyA && s.hasB()) k[n++] = key(s.b, false);
        }
        if (s.oc > 0f) {
            k[n++] = key(s.a, true);
            if (!onlyA && s.hasB()) k[n++] = key(s.b, true);
        }
        return k;
    }

    private static boolean has(int[] ks, int k) {
        for (int x : ks) if (x == k) return true;
        return false;
    }

    /** A composed stack: keyframe pair, overcast-only flag and the quantised mix it was made for. */
    private static final class Bake {
        final Bitmap bmp;
        final int a, b, fq, oq;
        final boolean ocOnly;

        Bake(Bitmap bmp, St s) {
            this.bmp = bmp;
            a = s.a; b = s.hasB() ? s.b : s.a; ocOnly = s.oc >= 0.999f;
            fq = q(s.f); oq = q(s.oc);
        }

        boolean samePair(St s) { return a == s.a && b == (s.hasB() ? s.b : s.a) && ocOnly == (s.oc >= 0.999f); }
        boolean exact(St s) { return samePair(s) && fq == q(s.f) && oq == q(s.oc); }
    }

    private static int q(float v) { return Math.round(v * 255f); }

    /** Number of full-screen layers drawSet would draw for s (the base counts as one). */
    private static int layerCount(St s) {
        int mixB = s.hasB() && s.f > 0f ? 1 : 0, n = 0;
        if (s.oc < 0.999f) n += 1 + mixB;
        if (s.oc > 0f) n += 1 + mixB;
        return n;
    }

    private Bake baked, oldBaked;
    /** Every live bake (2 MB each). Capped; old ones are recycled on phase change so PSS stays flat. */
    private final BakeLedger<Bake> bakes = new BakeLedger<Bake>();
    private static final int MAX_BAKES = 2;
    private static final long MAX_BAKE_BYTES = 6L * 1024 * 1024;
    private boolean baking;
    private final Bitmap[] lib = new Bitmap[SkyPhase.NAMES.length * 2];
    private final boolean[] inflight = new boolean[SkyPhase.NAMES.length * 2];
    private final St cur = new St(), old = new St();
    private final float[] mix = new float[NV];
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private final RectF glowRect = new RectF();
    private Bitmap sunGlow, moonGlow;
    private boolean glowLoading;
    private St pend;
    private boolean pendAnimate;
    private AmbientState lastState;
    private boolean hasCur, fading, reduced;
    private float t = 1f;
    private ValueAnimator anim;
    private int gen;
    private SkyAssets.SliceListener sliceListener;
    private int lastSliceKey = Integer.MIN_VALUE;

    public SkyView(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    public void setReducedMotion(boolean r) {
        reduced = r;
        if (r && fading) {
            finishFade();
            invalidate();
        }
    }

    public void setSliceListener(SkyAssets.SliceListener l) {
        sliceListener = l;
        lastSliceKey = Integer.MIN_VALUE;
        if (l != null && hasCur) requestSlice();
    }

    public void setState(AmbientState s, boolean animate) {
        if (s == null) return;
        if (!s.sky || s.frame == null) {
            // not the sky wallpaper: hold nothing
            lastState = null;
            pend = null;
            release();
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        lastState = s.copy();
        pend = new St();
        pend.set(s.frame);
        pendAnimate = animate;
        ensureLoads(pend);
        tryCommit();
    }

    /** onStop: drop every bitmap (decoded keyframes, glows, in-flight results). restore() brings them back. */
    public void release() {
        gen++;
        finishFade();
        for (int i = 0; i < lib.length; i++) { freeLater(lib[i]); lib[i] = null; inflight[i] = false; }
        sunGlow = null;
        moonGlow = null;
        glowLoading = false;
        hasCur = false;
        pend = null;
        baked = null;
        oldBaked = null;
        baking = false;
        recycleLater(bakes.clear());
    }

    /** Frees evicted bakes' pixels. Delayed one second so a recorded display list that still references one has replayed. */
    private void recycleLater(java.util.List<Bake> dead) {
        if (dead.isEmpty()) return;
        final Bitmap[] bs = new Bitmap[dead.size()];
        for (int i = 0; i < bs.length; i++) bs[i] = dead.get(i).bmp;
        postDelayed(() -> { for (Bitmap b : bs) if (!b.isRecycled()) b.recycle(); }, 1000L);
    }

    /**
     * Frees a keyframe's pixels and GL texture instead of waiting for the GC (a 2 MB bitmap plus its texture each; ten
     * phase switches used to pile up ~40 MB of graphics memory). Delayed one second for recorded display lists, then run
     * on the sky thread so a queued bake that still reads it finishes first.
     */
    private void freeLater(final Bitmap b) {
        if (b == null) return;
        postDelayed(() -> SkyAssets.background(() -> { if (!b.isRecycled()) b.recycle(); }), 1000L);
    }

    /** onTrimMemory: drop everything not on screen (bakes, decoded keyframes nobody wants, glows); redraws re-decode on demand. */
    public void trim() {
        recycleLater(bakes.retainOnly(baked, oldBaked));
        prune();
    }

    /** Cap on count and bytes, never touching what is on screen or fading out. */
    private void trimBakes() {
        recycleLater(bakes.trim(MAX_BAKES, MAX_BAKE_BYTES, baked, oldBaked));
    }

    /** onStart: re-decode for the last state; the scrim colour shows until the first keyframe is ready. */
    public void restore() {
        if (lastState == null || lastState.frame == null) return;
        pend = new St();
        pend.set(lastState.frame);
        pendAnimate = false;
        ensureLoads(pend);
        tryCommit();
        invalidate();
    }

    // ---- loading -----------------------------------------------------------------------------------------

    private boolean wanted(int k) {
        return (hasCur && has(keys(cur, false), k)) || (pend != null && has(keys(pend, false), k))
                || (fading && has(keys(old, false), k));
    }

    private void ensureLoads(St s) {
        final Context c = getContext().getApplicationContext();
        final int g = gen;
        for (final int k : keys(s, false)) {
            if (k < 0 || lib[k] != null || inflight[k]) continue;
            inflight[k] = true;
            final int idx = k / 2;
            final boolean oc = (k & 1) == 1;
            SkyAssets.background(() -> {
                final Bitmap b = SkyAssets.decodeSky(c, idx, oc);
                SkyAssets.post(() -> {
                    if (g != gen) return;
                    inflight[k] = false;
                    if (b != null && wanted(k)) lib[k] = b;
                    else if (b != null) b.recycle();             // never drawn, never handed out
                    tryCommit();
                    requestBake();
                    invalidate();
                });
            });
        }
        if (sunGlow == null && !glowLoading) {
            glowLoading = true;
            SkyAssets.background(() -> {
                final Bitmap sun = SkyAssets.sunGlow(c), moon = SkyAssets.moonGlow(c);
                SkyAssets.post(() -> {
                    if (g != gen) return;
                    glowLoading = false;
                    sunGlow = sun;
                    moonGlow = moon;
                    invalidate();
                });
            });
        }
    }

    private void prune() {
        for (int i = 0; i < lib.length; i++) if (lib[i] != null && !wanted(i)) { freeLater(lib[i]); lib[i] = null; }
    }

    // ---- baking --------------------------------------------------------------------------------------------

    /** Compose cur's stack into one bitmap on the background thread when it has more than one layer. */
    private void requestBake() {
        if (!hasCur || baking || layerCount(cur) < 2) return;
        if (baked != null && baked.exact(cur)) return;
        final St s = new St();
        s.copy(cur);
        final Bitmap[] src = new Bitmap[4];       // A, B, A_oc, B_oc
        src[0] = lib[key(s.a, false)];
        src[2] = lib[key(s.a, true)];
        if (s.hasB()) { src[1] = lib[key(s.b, false)]; src[3] = lib[key(s.b, true)]; }
        boolean ocOnly = s.oc >= 0.999f;
        if ((!ocOnly && src[0] == null) || (s.oc > 0f && src[2] == null)) return;
        if (s.hasB() && s.f > 0f && ((!ocOnly && src[1] == null) || (s.oc > 0f && src[3] == null))) return;
        baking = true;
        final int g = gen;
        SkyAssets.background(() -> {
            final Bitmap out = compose(src, s);
            SkyAssets.post(() -> {
                if (g != gen) return;
                baking = false;
                if (out != null) {
                    baked = new Bake(out, s);
                    bakes.add(baked, out.getByteCount());
                    trimBakes();
                }
                invalidate();
                requestBake();
            });
        });
    }

    private static Bitmap compose(Bitmap[] src, St s) {
        try {
            Bitmap ref = src[0] != null ? src[0] : src[2];
            Bitmap out = Bitmap.createBitmap(ref.getWidth(), ref.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            boolean ocOnly = s.oc >= 0.999f, mixB = s.hasB() && s.f > 0f;
            if (!ocOnly) {
                c.drawBitmap(src[0], 0, 0, p);                  // opaque base
                if (mixB) { p.setAlpha(q(s.f)); c.drawBitmap(src[1], 0, 0, p); }
            }
            if (s.oc > 0f) {
                float ko = ocOnly ? 1f : s.oc;
                p.setAlpha(q(ko));
                c.drawBitmap(src[2], 0, 0, p);
                if (mixB) { p.setAlpha(q(ko * s.f)); c.drawBitmap(src[3], 0, 0, p); }
            }
            return out;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    // ---- state changes -----------------------------------------------------------------------------------

    private void tryCommit() {
        if (pend == null) return;
        boolean swap = hasCur && SkyPhase.shouldSwap(new SkyPhase.Pair(cur.a, cur.b, cur.f), new SkyPhase.Pair(pend.a, pend.b, pend.f));
        // a hand-over needs only A (it is the old B, already in memory); the new B may arrive later
        for (int k : keys(pend, swap)) if (k >= 0 && lib[k] == null) return;
        St next = pend;
        pend = null;
        finishFade();
        if (!hasCur || swap || !pendAnimate || reduced) {
            cur.copy(next);
        } else {
            old.copy(cur);
            oldBaked = baked;
            cur.copy(next);
            startFade();
        }
        hasCur = true;
        prune();
        if (baked != null && !baked.samePair(cur)) { baked = null; recycleLater(bakes.retainOnly(oldBaked, null)); }   // phase change: the old pair's bake is dead
        if (swap) {
            pend = null;
            St again = new St();
            again.copy(cur);
            ensureLoads(again);    // the next keyframe (B) decodes now; nothing visible waits on it (f is ~0)
        }
        requestSliceIfChanged();
        requestBake();
        invalidate();
    }

    private void startFade() {
        fading = true;
        t = 0f;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(Ui.SKY_FADE_MS);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        anim.addUpdateListener(a -> {
            t = (Float) a.getAnimatedValue();
            invalidate();
        });
        anim.addListener(new OnAnimEnd(a -> {
            if (anim != a) return;
            anim = null;
            fading = false;
            t = 1f;
            oldBaked = null;
            prune();
            trimBakes();
            invalidate();
        }));
        anim.start();
    }

    private void finishFade() {
        if (anim != null) {
            Animator a = anim;
            anim = null;
            a.cancel();
        }
        fading = false;
        t = 1f;
        oldBaked = null;
    }

    private void requestSliceIfChanged() {
        int k = ((cur.a * 10 + cur.b) * 65 + Math.round(cur.f * 64f)) * 2 + (cur.oc >= 0.5f ? 1 : 0);
        if (k == lastSliceKey) return;
        lastSliceKey = k;
        requestSlice();
    }

    private void requestSlice() {
        if (sliceListener == null || lastState == null) return;
        SkyAssets.slice(getContext(), lastState, sliceListener);
    }

    // ---- drawing -----------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        if (!hasCur) {
            c.drawColor(lastState != null && lastState.frame != null ? lastState.frame.scrimRgb : Ui.BG);
            return;
        }
        dst.set(0, 0, getWidth(), getHeight());
        float[] v = cur.v;
        if (fading) {
            drawSet(c, old, 1f, true, oldBaked);
            drawSet(c, cur, t, false, baked);
            for (int i = 0; i < NV; i++) mix[i] = old.v[i] + (cur.v[i] - old.v[i]) * t;
            v = mix;
        } else {
            drawSet(c, cur, 1f, true, baked);
        }
        veil(c, v[V_FOGD], 255, 255, 255);
        veil(c, v[V_FOGN], 128, 138, 166);
        veil(c, v[V_RDIM], 0, 0, 0);
        veil(c, v[V_RAIND], 96, 110, 136);
        glow(c, sunGlow, Ui.GLOW_SUN_SIZE, v[V_SUNX], v[V_SUNY], v[V_SUNA]);
        glow(c, moonGlow, Ui.GLOW_MOON_SIZE, v[V_MOONX], v[V_MOONY], v[V_MOONA]);
    }

    private void drawSet(Canvas c, St s, float k, boolean base, Bake bk) {
        if (bk != null && layerCount(s) > 1 && bk.samePair(s)) {
            paint.setAlpha(k >= 1f ? 255 : Math.round(k * 255f));
            c.drawBitmap(bk.bmp, null, dst, paint);
            return;
        }
        boolean ocOnly = s.oc >= 0.999f;
        boolean drewBase = false;
        if (!ocOnly) {
            drewBase = layer(c, s.a, false, k);
            if (s.hasB() && s.f > 0f) layer(c, s.b, false, k * s.f);
        }
        if (s.oc > 0f) {
            float ko = ocOnly ? k : k * s.oc;
            boolean d = layer(c, s.a, true, ko);
            if (ocOnly) drewBase = d;
            if (s.hasB() && s.f > 0f) layer(c, s.b, true, ko * s.f);
        }
        if (base && !drewBase) c.drawColor(s.scrim);      // keyframe not decoded yet: flat sky colour, never black
    }

    private boolean layer(Canvas c, int idx, boolean oc, float alpha) {
        Bitmap b = lib[key(idx, oc)];
        if (b == null || alpha <= 0f) return false;
        paint.setAlpha(alpha >= 1f ? 255 : Math.round(alpha * 255f));
        c.drawBitmap(b, null, dst, paint);
        return true;
    }

    private void veil(Canvas c, float a, int r, int g, int b) {
        if (a <= 0.0005f) return;
        int ai = Math.min(255, Math.round(a * 255f));
        if (ai > 0) c.drawColor((ai << 24) | (r << 16) | (g << 8) | b);
    }

    private void glow(Canvas c, Bitmap b, int sizeDesign, float x, float y, float a) {
        if (b == null || a <= 0.0005f) return;
        float sz = Ui.pxf(sizeDesign), cx = x * Ui.scale(), cy = y * Ui.scale();
        glowRect.set(cx - sz / 2f, cy - sz / 2f, cx + sz / 2f, cy + sz / 2f);
        paint.setAlpha(Math.min(255, Math.round(a * 255f)));
        c.drawBitmap(b, null, glowRect, paint);
    }

    @Override
    protected void onDetachedFromWindow() {
        finishFade();
        super.onDetachedFromWindow();
    }
}
