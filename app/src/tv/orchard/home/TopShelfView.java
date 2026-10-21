package tv.orchard.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Top Shelf (PLAN 0.3, P4): the focused app's colour wash, card (emblem plate or banner), glow, shadows, rim,
 * title and subtitle. Two layers (A/B) cross-fade; only paint alphas change per frame, every shader, bitmap and
 * string is built in apply()/onSizeChanged(). Idle = zero redraws. Unbound (bind() not called) it draws nothing.
 */
public class TopShelfView extends View {
    private static final int STYLE_EMBLEM = 0, STYLE_BANNER = 1, STYLE_OFF = 2;
    private static final int GLOW_NONE = 0, GLOW_BITMAP = 1, GLOW_FLAT = 2;
    private static final int TIER_BITMAP = 0, TIER_FLAT = 1, TIER_EMBLEM = 2;
    private static final float EPS = 1e-3f;
    private static final float FADE_START = (Ui.HERO_BLEED_H - Ui.HERO_FADE_H) / (float) Ui.HERO_BLEED_H;
    private static final int GLOW_FLAT_PAD = 64;     // design px around the flat glow (3 sigma of blur 40)
    private static final float BAKE_SCALE = 0.25f;   // wash / bleed / overlay bitmaps are a quarter of the view size
    private static final int SHADOW_PAD = 140;       // design px around the card (3 sigma of blur 72 + y 32)

    /** One app's full composition. */
    private static final class Layer {
        AppEntry e;
        int vivid = 0xFF5B6477;
        Bitmap art;              // emblem or banner
        Bitmap glowBmp;          // pre-blurred banner glow
        Bitmap bleedBmp;         // hero=blur
        Bitmap bakeD;            // dark-token wash x fade (+ bleed when hero=blur), quarter resolution ARGB; null until needed
        Bitmap bakeL;            // the same from the light tokens; null until needed
        Bitmap bakeMix;          // settled dusk / dawn: bakeD x darkA over bakeL x lightA pre-composited (bakeD / bakeL are then freed)
        float mixDA, mixLA;      // the alphas bakeMix was composed with
        int glowKind = GLOW_NONE;
        int glowTier = TIER_BITMAP;   // which Theme hero*GlowAlpha applies
        float cardA = 1f;        // card fade-in (late art)
        String title = "", sub = "";
        final Paint bleed = new Paint();
        final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Paint glow = new Paint(Paint.FILTER_BITMAP_FLAG);
        final Paint bakeP = new Paint(Paint.FILTER_BITMAP_FLAG);   // draws the bake; only its alpha changes per frame

        Layer() {
            bleed.setFilterBitmap(true);
        }

        void clear() {
            e = null;
            dropBitmaps();
            title = "";
            sub = "";
        }

        void dropBitmaps() {
            art = null;
            glowBmp = null;
            bleedBmp = null;
            freeBake(bakeD);
            freeBake(bakeL);
            freeBake(bakeMix);
            bakeD = null;
            bakeL = null;
            bakeMix = null;
            glowKind = GLOW_NONE;
            bleed.setShader(null);
            card.setShader(null);
        }
    }

    private final Layer[] layers = {new Layer(), new Layer()};
    private int cur;                       // index of the visible (or outgoing) layer
    private boolean fading;
    private float fadeT;
    private float sf = 1f;                 // suppress factor: 1 visible, 0 hidden
    private float sfTarget = 1f;
    private boolean reduced;
    private boolean released;
    private boolean bound;
    private int cardStyle = STYLE_EMBLEM;
    private boolean heroBlur;
    private ArtCache art;
    private Config config;
    private AppEntry pending;
    private AppEntry wanted;               // last requested app (kept so bind() can place it)
    private String targetPkg;

    private ValueAnimator fadeAnim, sfAnim, cardAnim;
    private Layer cardAnimLayer;
    private final Runnable debounced = this::fireDebounce;

    // shared, built in onSizeChanged
    private final Paint overlayP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap overlay;                // dark tokens: light leak + both vignettes in one quarter-resolution ARGB bitmap (null until needed)
    private Bitmap overlayL;               // the same from the light tokens (null until needed)
    private Bitmap overlayMix;             // settled dusk / dawn: overlay x darkA over overlayL x lightA in one bitmap
    private final RectF fullDst = new RectF();
    private final Paint shadowP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titleP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint haloP = new TextPaint(Paint.ANTI_ALIAS_FLAG);   // Halo scratch
    private int subBase = Ui.TEXT2;        // Theme.rise(text2), alpha included
    private float builtShadowA = -1f, builtContactA = -1f;   // card shadow alphas the shared shadow bitmap was drawn with
    private boolean themeApplied;
    private boolean appliedLight;
    private float appliedTs, appliedTn;
    // theme mix (Rev 4/6): current and target alphas of the dark and light backdrops, and the light share for the suppress rule
    private float darkA = 1f, lightA = 0f, mixP = 0f;
    private float tDarkA = 1f, tLightA = 0f, tMixP = 0f;
    private float fromD, fromL, fromP;
    private ValueAnimator mixAnim;
    private Shader fadeShader;
    private Bitmap flatGlow, shadowBmp;
    private final RectF cardRect = new RectF();
    private final RectF rimRect = new RectF();
    private final RectF glowDst = new RectF();
    private final RectF glowBmpDst = new RectF();   // glowDst grown by the glow bitmap's transparent margin
    private final RectF flatDst = new RectF();
    private final RectF shadowDst = new RectF();
    private float cardR;
    private int vw, vh;

    public TopShelfView(Context context) {
        super(context);
        setWillNotDraw(false);
        shadowP.setColor(0xFF000000);
        rim.setStyle(Paint.Style.STROKE);
        applyFonts();
        paintTheme();
    }

    /** Colours and shadows that follow the theme: title / subtitle ink, title shadow, card shadow tint. No invalidate. */
    private void paintTheme() {
        Theme t = Theme.cur();
        titleP.setColor(Theme.rise(t.text));
        subBase = Theme.rise(t.text2);
        subP.setColor(subBase);
        if ((t.titleShadow >>> 24) == 0) {
            titleP.clearShadowLayer();
        } else {
            float sigma = Ui.pxf(Ui.HERO_TITLE_SHADOW_BLUR) / 2f;
            titleP.setShadowLayer(Math.max(1f, (sigma - 0.5f) / 0.57735f), 0f, Ui.pxf(Ui.HERO_TITLE_SHADOW_Y), t.titleShadow);
        }
        shadowP.setColor(0xFF000000 | (t.dropRgb & 0xFFFFFF));
    }

    /**
     * Theme tokens changed (Theme.set already ran): title / subtitle ink and shadow, card shadow and contact alphas
     * and tint, glow alphas. Cheap and idempotent (key = light + ts + tn); one invalidate.
     */
    public void applyTheme() {
        Theme t = Theme.cur();
        if (themeApplied && appliedLight == t.light && appliedTs == Theme.ts() && appliedTn == Theme.tn()) return;
        themeApplied = true;
        appliedLight = t.light;
        appliedTs = Theme.ts();
        appliedTn = Theme.tn();
        paintTheme();
        if (!released && !shadowDst.isEmpty() && (builtShadowA != t.heroArtShadowAlpha || builtContactA != t.heroArtContactAlpha)) {
            buildShadow();
        }
        invalidate();
    }

    /**
     * Backdrop mix. p = 0 dark .. 1 light (the day wallpaper's own mix), skyOn = Wallpaper Sky is driving the light
     * theme. hbk = 1 - HERO_TW_CUT * 4p(1-p) dips the wash while the two cross over. The dark bake is drawn at
     * (1-p)*hbk and the light bake over it at p*hbk*(skyOn ? hbSky : 1); a bake whose alpha is 0 is not built or drawn.
     * animate (a user Theme change only) cross-fades the two alphas over THEME_HERO_FADE_MS; otherwise they snap.
     */
    public void setThemeMix(float p, boolean skyOn, boolean animate) {
        p = p < 0f ? 0f : (p > 1f ? 1f : p);
        float hbk = 1f - Ui.HERO_TW_CUT * 4f * p * (1f - p);
        float d = (1f - p) * hbk;
        float l = p * hbk * (skyOn ? Theme.LIGHT.hbSky : 1f);
        if (Math.abs(d - tDarkA) < EPS && Math.abs(l - tLightA) < EPS && Math.abs(p - tMixP) < EPS) return;
        if (mixAnim != null) mixAnim.cancel();
        mixAnim = null;
        tDarkA = d;
        tLightA = l;
        tMixP = p;
        freeBake(overlayMix);
        overlayMix = null;                                             // the targets moved: the pre-composite is stale
        for (Layer ly : layers) { freeBake(ly.bakeMix); ly.bakeMix = null; }
        ensureAllBakes();                                              // the target's bakes exist before the first frame of the fade
        long dur = Ui.ms(Ui.THEME_HERO_FADE_MS);
        if (!animate || dur <= 0 || !bound || vw <= 0 || !isAttachedToWindow()) {
            darkA = d;
            lightA = l;
            mixP = p;
            dropUnusedBakes();
            invalidate();
            return;
        }
        fromD = darkA;
        fromL = lightA;
        fromP = mixP;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setInterpolator(new LinearInterpolator());
        a.setDuration(dur);
        a.addUpdateListener(va -> {
            float f = (Float) va.getAnimatedValue();
            darkA = fromD + (tDarkA - fromD) * f;
            lightA = fromL + (tLightA - fromL) * f;
            mixP = fromP + (tMixP - fromP) * f;
            if (f >= 1f) {
                mixAnim = null;
                dropUnusedBakes();
            }
            invalidate();
        });
        mixAnim = a;
        a.start();
    }

    /** True when the mix is not animating and the live alphas equal the targets (the pre-composite may be used). */
    private boolean mixSettled() {
        return mixAnim == null && Math.abs(darkA - tDarkA) < EPS && Math.abs(lightA - tLightA) < EPS;
    }

    private boolean useMix() {
        return OverlayRules.useMixBake(mixAnim != null, darkA, lightA, tDarkA, tLightA, EPS);
    }

    private boolean needDark() { return darkA > EPS || tDarkA > EPS; }
    private boolean needLight() { return lightA > EPS || tLightA > EPS; }

    private void ensureAllBakes() {
        ensureOverlays();
        for (Layer l : layers) ensureBakes(l);
    }

    /** After the mix settled: free the quarter-res bitmaps whose alpha is 0 (they are rebuilt if the mix moves again). */
    private void dropUnusedBakes() {
        boolean nd = needDark(), nl = needLight();
        if (!nd) { freeBake(overlay); overlay = null; }
        if (!nl) { freeBake(overlayL); overlayL = null; }
        for (Layer l : layers) {
            if (!nd) { freeBake(l.bakeD); l.bakeD = null; }
            if (!nl) { freeBake(l.bakeL); l.bakeL = null; }
        }
        settleMix();
    }

    /**
     * Settled dusk / dawn (both alphas above EPS): composite the dark and light bakes (and overlays) once and draw that
     * single bitmap per layer. Two full-screen bitmaps per layer plus two overlays were four fills during a
     * crossfade that is nearly invisible at 18:10 (p = 0.03); the Stick is fill-bound. The two-bitmap path stays
     * while mixAnim runs.
     */
    private void settleMix() {
        if (vw <= 0 || vh <= 0 || fadeShader == null || released || !useMix()) return;
        if (overlayMix == null) {
            ensureOverlays();
            if (overlay != null && overlayL != null) {
                overlayMix = composeMix(overlay, darkA, overlayL, lightA);
                freeBake(overlay);
                freeBake(overlayL);
                overlay = null;
                overlayL = null;
            }
        }
        for (Layer l : layers) settleMix(l);
    }

    private void settleMix(Layer l) {
        if (l.e == null || !useMix()) return;
        if (l.bakeMix != null && Math.abs(l.mixDA - darkA) < EPS && Math.abs(l.mixLA - lightA) < EPS) return;
        if (l.bakeD == null) l.bakeD = bakeFor(l, Theme.DARK);
        if (l.bakeL == null) l.bakeL = bakeFor(l, Theme.LIGHT);
        l.bakeMix = composeMix(l.bakeD, darkA, l.bakeL, lightA);
        l.mixDA = darkA;
        l.mixLA = lightA;
        freeBake(l.bakeD);
        freeBake(l.bakeL);
        l.bakeD = null;
        l.bakeL = null;
    }

    /** Recycles a bake this view built itself, one second later (a recorded display list may still draw it). */
    private static void freeBake(Bitmap b) {
        if (b != null) Halo.freeLater(b);
    }

    private static Bitmap composeMix(Bitmap d, float da, Bitmap l, float la) {
        Bitmap b = Bitmap.createBitmap(d.getWidth(), d.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setAlpha(Math.round(255f * da));
        c.drawBitmap(d, 0f, 0f, p);
        p.setAlpha(Math.round(255f * la));
        c.drawBitmap(l, 0f, 0f, p);
        return b;
    }

    private void applyFonts() {
        titleP.setTypeface(Ui.medium());
        titleP.setTextSize(Ui.pxf(Ui.T_TITLE2));
        titleP.setLetterSpacing(Ui.tracking(Ui.T_TITLE2));
        subP.setTypeface(Ui.regular());
        subP.setTextSize(Ui.pxf(Ui.T_BODY));
        subP.setLetterSpacing(Ui.tracking(Ui.T_BODY));
    }

    // ---- public API -------------------------------------------------------------------------------------

    /** Reads debug_card/card, hero and debug_art, tells the art cache what to render, redraws the current app. */
    public void bind(ArtCache artCache, Config cfg) {
        art = artCache;
        config = cfg;
        bound = art != null && config != null;
        if (!bound) { invalidate(); return; }
        applyFonts();
        String v = config.get(Config.Keys.DEBUG_CARD, "");
        if (v.isEmpty()) v = config.get(Config.Keys.CARD, "emblem");
        if ("banner".equals(v) || "on".equals(v)) cardStyle = STYLE_BANNER;
        else if ("off".equals(v)) cardStyle = STYLE_OFF;
        else cardStyle = STYLE_EMBLEM;
        heroBlur = "blur".equals(config.get(Config.Keys.HERO, "wash"));
        art.setPlaceholder(DebugFlags.placeholderArt(config));       // debug_art=placeholder: logo-free letter plates
        art.setShelfNeeds(cardStyle == STYLE_BANNER, heroBlur);
        AppEntry now = layers[cur].e != null ? layers[cur].e : wanted;
        if (fading) now = layers[1 - cur].e != null ? layers[1 - cur].e : now;
        if (now != null) showNow(now);
        else invalidate();
    }

    /** Debounced (120 ms), then a linear cross-fade (280 ms). null keeps the current app; same app is a no-op. */
    public void show(AppEntry e) {
        if (e == null) return;
        wanted = e;
        if (e.packageName != null && e.packageName.equals(targetPkg)) return;
        targetPkg = e.packageName;
        pending = e;
        removeCallbacks(debounced);
        postDelayed(debounced, Ui.ms(Ui.HERO_DEBOUNCE_MS));
    }

    /** No debounce, no cross-fade (first placement, restore). */
    public void showNow(AppEntry e) {
        if (e == null) return;
        wanted = e;
        targetPkg = e.packageName;
        pending = null;
        removeCallbacks(debounced);
        settleFade(false);
        apply(layers[cur], e);
        invalidate();
    }

    /** Card, glow, shadows, rim, title and subtitle fade out/in; the wash stays. */
    public void setSuppressed(boolean s) {
        float target = s ? 0f : 1f;
        if (target == sfTarget) return;
        sfTarget = target;
        if (sfAnim != null) sfAnim.cancel();
        if (!bound || !isAttachedToWindow()) { sf = target; invalidate(); return; }
        ValueAnimator a = ValueAnimator.ofFloat(sf, target);
        a.setInterpolator(new LinearInterpolator());
        a.setDuration(Ui.ms(reduced ? Ui.REDUCED_FADE_MS : Ui.OVERLAY_HIDE_MS));
        a.addUpdateListener(va -> {
            sf = (Float) va.getAnimatedValue();
            invalidate();
        });
        sfAnim = a;
        a.start();
    }

    /** Cross-fade and suppress fades become 150 ms. */
    public void setReducedMotion(boolean r) { reduced = r; }

    /** Re-reads entry.lastOpened into the subtitle (after returning from an app). */
    public void refreshText() {
        for (Layer l : layers) if (l.e != null) l.sub = subtitle(l.e);
        invalidate();
    }

    /** onStop: drop every bitmap reference (baked wash and overlay included); only text is drawn until restore(). */
    public void release() {
        removeCallbacks(debounced);
        settleFade(true);
        if (cardAnim != null) cardAnim.cancel();
        released = true;
        for (Layer l : layers) l.dropBitmaps();
        flatGlow = null;
        shadowBmp = null;
        freeBake(overlay);
        freeBake(overlayL);
        freeBake(overlayMix);
        overlay = null;
        overlayL = null;
        overlayMix = null;
        if (mixAnim != null) { mixAnim.cancel(); mixAnim = null; darkA = tDarkA; lightA = tLightA; mixP = tMixP; }
        invalidate();
    }

    /** onStart: rebuild the shared bitmaps and re-request the current app's art. */
    public void restore() {
        if (!released) return;
        released = false;
        buildShared();
        Layer l = layers[cur];
        if (l.e != null) apply(l, l.e);
        invalidate();
    }

    // ---- debounce / fade --------------------------------------------------------------------------------

    private void fireDebounce() {
        AppEntry e = pending;
        pending = null;
        if (e == null || !bound || released) return;
        settleFade(false);
        Layer from = layers[cur];
        if (from.e != null && from.e.packageName != null && from.e.packageName.equals(e.packageName)) return;
        if (from.e == null || vw == 0 || !isAttachedToWindow()) {
            apply(from, e);
            invalidate();
            return;
        }
        Layer to = layers[1 - cur];
        apply(to, e);
        fading = true;
        fadeT = 0f;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setInterpolator(new LinearInterpolator());
        a.setDuration(Ui.ms(reduced ? Ui.REDUCED_FADE_MS : Ui.HERO_FADE_MS));
        a.addUpdateListener(va -> {
            if (!fading) return;
            fadeT = (Float) va.getAnimatedValue();
            if (fadeT >= 1f) endFade(true);
            invalidate();
        });
        fadeAnim = a;
        a.start();
    }

    /** Stops a running cross-fade; the layer with the larger weight stays (or the incoming one when asked). */
    private void settleFade(boolean preferIncoming) {
        if (fadeAnim != null) fadeAnim.cancel();
        if (!fading) return;
        endFade(preferIncoming || fadeT >= 0.5f);
    }

    private void endFade(boolean incomingWins) {
        fading = false;
        fadeT = 0f;
        if (incomingWins) {
            layers[cur].clear();
            cur = 1 - cur;
        } else {
            layers[1 - cur].clear();
        }
    }

    // ---- layer construction (never in onDraw) -------------------------------------------------------------

    private static boolean same(AppEntry a, AppEntry b) {
        return a != null && b != null && a.packageName != null && a.packageName.equals(b.packageName);
    }

    private String subtitle(AppEntry e) {
        return ellipsize(ShelfText.opened(e.lastOpened, System.currentTimeMillis()), subP);
    }

    private static String ellipsize(String text, TextPaint p) {
        return TextUtils.ellipsize(text == null ? "" : text, p, Ui.pxf(Ui.HERO_TITLE_MAX_W),
                TextUtils.TruncateAt.END).toString();
    }

    private void apply(Layer l, AppEntry e) {
        l.e = e;
        l.title = ellipsize(e.label, titleP);
        l.sub = subtitle(e);
        l.dropBitmaps();
        if (art == null) return;
        l.vivid = art.vivid(e);
        fetch(l);
        l.cardA = (cardStyle == STYLE_OFF || l.art != null) ? 1f : 0f;
        build(l);
        if (cardStyle != STYLE_OFF && l.art == null) art.requestShelf(e, ent -> onArt(l, ent));
        else if (cardStyle == STYLE_OFF) art.request(e, ent -> onArt(l, ent));
    }

    /** Pull whatever the cache holds for the layer's app into the layer. */
    private void fetch(Layer l) {
        AppEntry e = l.e;
        l.vivid = art.vivid(e);
        l.art = null;
        l.glowBmp = null;
        l.bleedBmp = null;
        l.glowKind = GLOW_NONE;
        if (heroBlur) l.bleedBmp = art.fullBleed(e);
        if (cardStyle == STYLE_OFF) return;
        Bitmap shelf = cardStyle == STYLE_BANNER ? art.shelf(e) : null;
        if (shelf != null) {
            l.art = shelf;
            if (art.nearWhite(e)) {
                l.glowKind = GLOW_FLAT;
                l.glowTier = TIER_BITMAP;
            } else {
                l.glowBmp = art.glow(e);
                l.glowKind = l.glowBmp != null ? GLOW_BITMAP : GLOW_FLAT;
                l.glowTier = l.glowBmp != null ? TIER_BITMAP : TIER_FLAT;
            }
        } else {
            l.art = art.emblem(e);
            l.glowKind = GLOW_FLAT;
            l.glowTier = cardStyle == STYLE_BANNER ? TIER_FLAT : TIER_EMBLEM;
        }
        if (l.glowKind == GLOW_FLAT || l.glowKind == GLOW_BITMAP) {
            l.glow.setColor(ColorMath.withLightness(l.vivid, Ui.HERO_GLOW_FLAT_LUM, 1f));
        }
    }

    private void onArt(Layer l, AppEntry ent) {
        if (released || !same(l.e, ent)) return;
        boolean had = l.art != null;
        fetch(l);
        build(l);
        if (!had && l.art != null) startCardFade(l);
        invalidate();
    }

    private void startCardFade(Layer l) {
        if (cardAnim != null) cardAnim.cancel();
        if (cardAnimLayer != null && cardAnimLayer != l) cardAnimLayer.cardA = 1f;
        cardAnimLayer = l;
        l.cardA = 0f;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setInterpolator(new LinearInterpolator());
        a.setDuration(Ui.ms(Ui.HERO_CARD_FADE_IN_MS));
        a.addUpdateListener(va -> {
            l.cardA = (Float) va.getAnimatedValue();
            invalidate();
        });
        cardAnim = a;
        a.start();
    }

    /** Shaders for one layer: optional full-bleed, card, then the bakes that can be seen. Needs the view size. */
    private void build(Layer l) {
        if (vw <= 0 || vh <= 0 || fadeShader == null) return;
        float w = vw, h = vh;
        if (l.bleedBmp != null) {
            BitmapShader bs = new BitmapShader(l.bleedBmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float b = Ui.pxf(160);
            float rw = w + 2 * b, rh = h + 2 * b;
            float s = Math.max(rw / l.bleedBmp.getWidth(), rh / l.bleedBmp.getHeight());
            Matrix bm = new Matrix();
            bm.setScale(s, s);
            bm.postTranslate(-b + (rw - l.bleedBmp.getWidth() * s) / 2f, -b + (rh - l.bleedBmp.getHeight() * s) / 2f);
            bs.setLocalMatrix(bm);
            l.bleed.setShader(new ComposeShader(bs, fadeShader, PorterDuff.Mode.DST_IN));
        } else {
            l.bleed.setShader(null);
        }
        freeBake(l.bakeD);                                             // the app (or its art) changed: rebuild whichever are visible
        freeBake(l.bakeL);
        freeBake(l.bakeMix);
        l.bakeD = null;
        l.bakeL = null;
        l.bakeMix = null;
        ensureBakes(l);

        if (l.art != null) {
            BitmapShader cs = new BitmapShader(l.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float s = Math.max(cardRect.width() / l.art.getWidth(), cardRect.height() / l.art.getHeight());
            Matrix cm = new Matrix();
            cm.setScale(s, s);
            cm.postTranslate(cardRect.centerX() - l.art.getWidth() * s / 2f, cardRect.centerY() - l.art.getHeight() * s / 2f);
            cs.setLocalMatrix(cm);
            l.card.setShader(cs);
        } else {
            l.card.setShader(null);
        }
    }

    /** Builds the dark and / or light bake of a layer when its alpha can be above 0 (lazy; never in onDraw). */
    private void ensureBakes(Layer l) {
        if (l.e == null || vw <= 0 || vh <= 0 || fadeShader == null) return;
        if (l.bakeMix != null && mixSettled()) return;                 // the settled pre-composite stands in for both
        if (l.bakeD == null && needDark()) l.bakeD = bakeFor(l, Theme.DARK);
        if (l.bakeL == null && needLight()) l.bakeL = bakeFor(l, Theme.LIGHT);
        settleMix(l);
    }

    /**
     * Wash x fade (and the full-bleed image, at its final alpha) of one token set painted once into a
     * quarter-resolution bitmap, so onDraw is one filtered bitmap per layer and theme instead of two ComposeShader
     * full-width fills (Stick GPU-bound, P4).
     */
    private Bitmap bakeFor(Layer l, Theme t) {
        float w = vw, h = vh;
        float cx = Ui.HERO_WASH_X * w, cy = Ui.HERO_WASH_Y * h, rx = Ui.HERO_WASH_RX * w, ry = Ui.HERO_WASH_RY * h;
        int c0 = ColorMath.withLightness(l.vivid, t.heroLum, t.heroSatMax);
        int c1 = ColorMath.withLightness(l.vivid, t.heroWashMidLum, t.heroSatMax);
        RadialGradient rg = new RadialGradient(cx, cy, rx, new int[] {c0, c1, t.bg},
                new float[] {0f, t.heroWashMid, 1f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f, ry / rx, cx, cy);
        rg.setLocalMatrix(m);
        Paint wash = new Paint();
        wash.setDither(true);
        wash.setShader(new ComposeShader(rg, fadeShader, PorterDuff.Mode.DST_IN));

        int bw = Math.max(1, Math.round(vw * BAKE_SCALE)), bh = Math.max(1, Math.round(vh * BAKE_SCALE));
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(bw / (float) vw, bh / (float) vh);
        c.drawRect(0, 0, vw, vh, wash);
        if (l.bleedBmp != null && l.bleed.getShader() != null) {
            l.bleed.setAlpha(Math.round(255f * t.heroBlAlpha));
            c.drawRect(0, 0, vw, vh, l.bleed);
        }
        return b;
    }

    private void ensureOverlays() {
        if (vw <= 0 || vh <= 0 || fadeShader == null) return;
        if (overlayMix != null && mixSettled()) return;
        if (overlay == null && needDark()) overlay = buildOverlay(Theme.DARK);
        if (overlayL == null && needLight()) overlayL = buildOverlay(Theme.LIGHT);
    }

    /** Light leak, horizontal and bottom vignettes (all x fade) of one token set composited once, in the order onDraw used to draw them. */
    private Bitmap buildOverlay(Theme t) {
        int bw = Math.max(1, Math.round(vw * BAKE_SCALE)), bh = Math.max(1, Math.round(vh * BAKE_SCALE));
        float w = vw, h = vh;
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(bw / w, bh / h);

        RadialGradient lk = new RadialGradient(0.08f * w, 0f, 0.60f * w, Ui.argb(t.heroLeakAlpha, 0xFFFFFF),
                Ui.argb(0f, 0xFFFFFF), Shader.TileMode.CLAMP);
        Matrix lm = new Matrix();
        lm.setScale(1f, (0.80f * h) / (0.60f * w), 0.08f * w, 0f);
        lk.setLocalMatrix(lm);
        Paint p = new Paint();
        p.setShader(new ComposeShader(lk, fadeShader, PorterDuff.Mode.DST_IN));
        c.drawRect(0, 0, w, h, p);

        p.setShader(new ComposeShader(new LinearGradient(0, 0, 0.60f * w, 0,
                Ui.argb(t.heroDimLeft, t.bg), Ui.argb(t.heroDimRight, t.bg), Shader.TileMode.CLAMP),
                fadeShader, PorterDuff.Mode.DST_IN));
        c.drawRect(0, 0, w, h, p);

        p.setShader(new ComposeShader(new LinearGradient(0, 0.5f * h, 0, h,
                Ui.argb(0f, t.bg), Ui.argb(t.heroDimBottom, t.bg), Shader.TileMode.CLAMP),
                fadeShader, PorterDuff.Mode.DST_IN));
        c.drawRect(0, 0, w, h, p);
        return b;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        vw = w;
        vh = h;
        if (w <= 0 || h <= 0) return;
        fullDst.set(0, 0, w, h);
        fadeShader = new LinearGradient(0, 0, 0, h, new int[] {0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[] {0f, FADE_START, 1f}, Shader.TileMode.CLAMP);

        float x0 = Ui.pxf(Ui.HERO_ART_X), y0 = Ui.pxf(Ui.HERO_ART_Y);
        cardRect.set(x0, y0, x0 + Ui.pxf(Ui.HERO_ART_W), y0 + Ui.pxf(Ui.HERO_ART_H));
        cardR = Ui.pxf(Ui.HERO_ART_R);
        float stroke = Math.max(1f, Ui.pxf(1));
        rim.setStrokeWidth(stroke);
        rimRect.set(cardRect.left + stroke / 2f, cardRect.top + stroke / 2f,
                cardRect.right - stroke / 2f, cardRect.bottom - stroke / 2f);
        float twoPx = Math.min(1f, 2f / cardRect.height());
        rim.setShader(new LinearGradient(0, cardRect.top, 0, cardRect.bottom,
                new int[] {Ui.argb(Ui.HERO_RIM_TOP, 0xFFFFFF), Ui.argb(Ui.HERO_RIM_BOTTOM, 0xFFFFFF),
                        Ui.argb(Ui.HERO_RIM_BOTTOM, 0xFFFFFF)},
                new float[] {0f, twoPx, 1f}, Shader.TileMode.CLAMP));

        float gw = cardRect.width() * Ui.HERO_GLOW_SCALE, gh = cardRect.height() * Ui.HERO_GLOW_SCALE;
        glowDst.set(cardRect.centerX() - gw / 2f, cardRect.centerY() - gh / 2f + Ui.pxf(Ui.HERO_GLOW_Y),
                cardRect.centerX() + gw / 2f, cardRect.centerY() + gh / 2f + Ui.pxf(Ui.HERO_GLOW_Y));
        float mx = glowDst.width() * ArtCache.GLOW_PAD_X / (Ui.BLUR_W - 2f * ArtCache.GLOW_PAD_X);
        float my = glowDst.height() * ArtCache.GLOW_PAD_Y / (Ui.BLUR_H - 2f * ArtCache.GLOW_PAD_Y);
        glowBmpDst.set(glowDst.left - mx, glowDst.top - my, glowDst.right + mx, glowDst.bottom + my);
        float gp = Ui.pxf(GLOW_FLAT_PAD);
        flatDst.set(glowDst.left - gp, glowDst.top - gp, glowDst.right + gp, glowDst.bottom + gp);
        float sp = Ui.pxf(SHADOW_PAD);
        shadowDst.set(cardRect.left - sp, cardRect.top - sp, cardRect.right + sp, cardRect.bottom + sp);

        if (!released) buildShared();
        for (Layer l : layers) if (l.e != null) build(l);
    }

    /** The two shared ALPHA_8 bitmaps (flat glow; contact + lift shadow), half resolution. */
    private void buildShared() {
        if (vw <= 0 || flatDst.isEmpty()) return;
        freeBake(overlay);
        freeBake(overlayL);
        freeBake(overlayMix);
        overlay = null;
        overlayL = null;
        overlayMix = null;
        ensureOverlays();
        settleMix();
        float half = 0.5f;
        int fw = Math.max(1, Math.round(flatDst.width() * half)), fh = Math.max(1, Math.round(flatDst.height() * half));
        Bitmap fg = Bitmap.createBitmap(fw, fh, Bitmap.Config.ALPHA_8);
        float gp = Ui.pxf(GLOW_FLAT_PAD) * half;
        RectF gr = new RectF(gp, gp, fw - gp, fh - gp);
        blurredRoundRect(new Canvas(fg), gr, Ui.pxf(2 * Ui.HERO_ART_R) * half, Ui.pxf(Ui.HERO_GLOW_BLUR) * half, 1f);
        flatGlow = fg;
        buildShadow();
    }

    /** The contact + lift shadow (ALPHA_8, half resolution) at the current theme's hero card shadow alphas. */
    private void buildShadow() {
        float half = 0.5f;
        Theme th = Theme.cur();
        int sw = Math.max(1, Math.round(shadowDst.width() * half)), sh = Math.max(1, Math.round(shadowDst.height() * half));
        Bitmap sb = Bitmap.createBitmap(sw, sh, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(sb);
        float sp = Ui.pxf(SHADOW_PAD) * half;
        RectF r = new RectF(sp, sp, sw - sp, sh - sp);
        float rad = Ui.pxf(Ui.HERO_ART_R) * half;
        r.offset(0f, Ui.pxf(Ui.HERO_ART_SHADOW_Y) * half);
        blurredRoundRect(c, r, rad, Ui.pxf(Ui.HERO_ART_SHADOW_BLUR) * half, th.heroArtShadowAlpha);
        r.offset(0f, (Ui.pxf(Ui.HERO_ART_CONTACT_Y) - Ui.pxf(Ui.HERO_ART_SHADOW_Y)) * half);
        blurredRoundRect(c, r, rad, Ui.pxf(Ui.HERO_ART_CONTACT_BLUR) * half, th.heroArtContactAlpha);
        shadowBmp = sb;
        builtShadowA = th.heroArtShadowAlpha;
        builtContactA = th.heroArtContactAlpha;
    }

    /** CSS blur (px, already in bitmap pixels) -> BlurMaskFilter radius, as TileRow does (sigma = blur / 2). */
    private static void blurredRoundRect(Canvas c, RectF r, float radius, float blurPx, float alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setAlpha(Math.round(alpha * 255f));
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, (blurPx / 2f - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(r, radius, radius, p);
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        if (!bound || vw <= 0) return;
        Layer a = layers[cur];
        Layer b = layers[1 - cur];
        if (fading) {
            backdrop(c, a, 1f);
            backdrop(c, b, fadeT);
        } else {
            backdrop(c, a, 1f);
        }
        if (a.e != null) {
            float wk = washK();
            if (overlayMix != null) {
                overlayP.setAlpha(Math.round(255f * wk));
                c.drawBitmap(overlayMix, null, fullDst, overlayP);
            }
            if (overlay != null && darkA > EPS) {
                overlayP.setAlpha(Math.round(255f * darkA * wk));
                c.drawBitmap(overlay, null, fullDst, overlayP);
            }
            if (overlayL != null && lightA > EPS) {
                overlayP.setAlpha(Math.round(255f * lightA * wk));
                c.drawBitmap(overlayL, null, fullDst, overlayP);
            }
        }
        if (fading) {
            front(c, a, 1f - fadeT);
            front(c, b, fadeT);
        } else {
            front(c, a, 1f);
        }
    }

    /** The wash stays under the suppressed shelf in the dark theme and hides with it in the light one (mock: light hides #shelf). */
    private float washK() { return 1f - mixP * (1f - sf); }

    private void backdrop(Canvas c, Layer l, float w) {
        if (l.e == null || w <= 0f) return;
        float wk = washK();
        if (l.bakeMix != null) {
            l.bakeP.setAlpha(Math.round(255f * w * wk));
            c.drawBitmap(l.bakeMix, null, fullDst, l.bakeP);
            return;
        }
        if (l.bakeD != null && darkA > EPS) {
            l.bakeP.setAlpha(Math.round(255f * w * darkA * wk));
            c.drawBitmap(l.bakeD, null, fullDst, l.bakeP);
        }
        if (l.bakeL != null && lightA > EPS) {
            l.bakeP.setAlpha(Math.round(255f * w * lightA * wk));
            c.drawBitmap(l.bakeL, null, fullDst, l.bakeP);
        }
    }

    private float glowBase(Layer l) {
        Theme t = Theme.cur();
        return l.glowTier == TIER_BITMAP ? t.heroGlowAlpha : (l.glowTier == TIER_FLAT ? t.heroGlowFlatAlpha : t.heroEmblemGlowAlpha);
    }

    private void front(Canvas c, Layer l, float w) {
        float k = w * sf;
        if (l.e == null || k <= 0.002f) return;
        float ca = k * l.cardA;
        if (l.art != null && ca > 0.002f) {
            if (l.glowKind == GLOW_FLAT && flatGlow != null) {
                l.glow.setAlpha(Math.round(255f * glowBase(l) * ca));
                c.drawBitmap(flatGlow, null, flatDst, l.glow);
            } else if (l.glowKind == GLOW_BITMAP && l.glowBmp != null) {
                l.glow.setAlpha(Math.round(255f * glowBase(l) * ca));
                c.drawBitmap(l.glowBmp, null, glowBmpDst, l.glow);
            }
            if (shadowBmp != null) {
                shadowP.setAlpha(Math.round(255f * ca));
                c.drawBitmap(shadowBmp, null, shadowDst, shadowP);
            }
            l.card.setAlpha(Math.round(255f * ca));
            c.drawRoundRect(cardRect, cardR, cardR, l.card);
            rim.setAlpha(Math.round(255f * ca));
            c.drawRoundRect(rimRect, cardR, cardR, rim);
        }
        int al = Math.round(255f * k);
        titleP.setAlpha(al);
        Halo.drawText(c, l.title, Ui.pxf(Ui.HERO_TITLE_X), Ui.pxf(Ui.HERO_TITLE_BASELINE), titleP, haloP);
        subP.setAlpha(Math.round((subBase >>> 24) * k));
        Halo.drawText(c, l.sub, Ui.pxf(Ui.HERO_TITLE_X), Ui.pxf(Ui.HERO_SUB_BASELINE), subP, haloP);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(debounced);
        if (fadeAnim != null) fadeAnim.cancel();
        if (sfAnim != null) sfAnim.cancel();
        if (cardAnim != null) cardAnim.cancel();
        if (mixAnim != null) {
            mixAnim.cancel();
            mixAnim = null;
            darkA = tDarkA;
            lightA = tLightA;
            mixP = tMixP;
        }
        if (fading) endFade(true);
        if (cardAnimLayer != null) cardAnimLayer.cardA = 1f;
        sf = sfTarget;
        super.onDetachedFromWindow();
    }
}
