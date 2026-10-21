package tv.orchard.home;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.DisplayMetrics;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tile art (325x195 RGB_565), shelf/glow/full-bleed sets, per-app colour meta. One background thread
 * (created on first use), callbacks on the main thread, WebP disk cache for tiles. The constructor does
 * no disk or PackageManager work.
 */
public final class ArtCache {
    public interface Callback { void onArt(AppEntry e); }

    private static final int SAMPLE = 12;
    private static final String META_REV = "_m5";   // m5: default-robot icon rejected; m4: flat-art rejection (m3: icon fallback chain + letter plate)
    private static final int SAMPLE_OVER = 4;            // draw at 48x48, box-average to 12x12
    private static final int DEFAULT_VIVID = 0xFF5B6477;
    // Hero glow (P4 r2): the banner sits inside a transparent 16:9 margin of the 240x135 bitmap so the blur fades to
    // nothing at the edges (without it the glow drew as a hard grey box). Radius 12 on the 176x99 inner area, drawn
    // 3.56x up (627 px), is sigma ~19 design px, i.e. CSS blur 40 (sigma 20) of --hero-glow-blur.
    private static final int GLOW_STACK_RADIUS = 12;
    public static final int GLOW_PAD_X = 32, GLOW_PAD_Y = 18;
    private static final int BLEED_STACK_RADIUS = 11;     // = Ui.HERO_BL_BLUR
    // Shelf sets cover the whole dock row (6 apps + slack): with 2, every key of a dock sweep re-decoded the xxxhdpi
    // banner (~3.7 MB of native garbage per key) and the sweep peaked at 121 MB PSS before the GC caught up. A set is
    // <= ~1.2 MB (banner <= 640x360 + two 240x135 blurs), so 8 sets stay under the 10 MB byte ceiling.
    private static final int MAX_SHELF_SETS = 8;
    private static final long MAX_SHELF_BYTES = 10L << 20;  // hard byte ceiling for the shelf sets (count AND bytes are capped)
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Per-package colour facts; never evicted, immutable once built. */
    private static final class Meta {
        final int vivid;
        final int glowColor;
        final float glowAlpha;
        final boolean dark;
        final boolean nearWhite;
        final boolean iconOnly;

        Meta(int vivid, int glowColor, float glowAlpha, boolean dark, boolean nearWhite, boolean iconOnly) {
            this.vivid = vivid;
            this.glowColor = glowColor;
            this.glowAlpha = glowAlpha;
            this.dark = dark;
            this.nearWhite = nearWhite;
            this.iconOnly = iconOnly;
        }
    }

    /**
     * Byte-bounded LRU (getByteCount). Hand-written because d8 8.2.2 crashes on a subclass of android.util.LruCache
     * that overrides sizeOf.
     */
    private static final class TileLru {
        private final LinkedHashMap<String, Bitmap> map = new LinkedHashMap<String, Bitmap>(32, 0.75f, true);
        private final int maxBytes;
        private int bytes;

        TileLru(int maxBytes) { this.maxBytes = maxBytes; }

        synchronized Bitmap get(String key) { return map.get(key); }

        synchronized void put(String key, Bitmap b) {
            Bitmap old = map.put(key, b);
            if (old != null) bytes -= old.getByteCount();
            bytes += b.getByteCount();
            java.util.Iterator<Map.Entry<String, Bitmap>> it = map.entrySet().iterator();
            while (bytes > maxBytes && map.size() > 1 && it.hasNext()) {
                Map.Entry<String, Bitmap> eldest = it.next();
                if (eldest.getValue() == b) continue;
                bytes -= eldest.getValue().getByteCount();
                it.remove();
            }
        }

        synchronized void remove(String key) {
            Bitmap old = map.remove(key);
            if (old != null) bytes -= old.getByteCount();
        }

        synchronized void evictAll() {
            map.clear();
            bytes = 0;
        }
    }

    private static final class Rendered {
        final Bitmap bmp;
        final Meta meta;
        Rendered(Bitmap bmp, Meta meta) { this.bmp = bmp; this.meta = meta; }
    }

    private static final class ShelfSet {
        final String pkg;
        final Bitmap emblem;      // RGB_565 plate; null when this set was rendered for banner mode of a banner app
        final Bitmap shelf;       // banner (uncropped); null unless rendered for banner mode / icon-only apps
        final Bitmap glow;
        final Bitmap bleed;
        final boolean hasBanner;  // the banner part was rendered (shelf/glow may still be null: icon-only, near-white)
        final boolean hasBleed;
        ShelfSet(String pkg, Bitmap emblem, Bitmap shelf, Bitmap glow, Bitmap bleed, boolean hasBanner, boolean hasBleed) {
            this.pkg = pkg;
            this.emblem = emblem;
            this.shelf = shelf;
            this.glow = glow;
            this.bleed = bleed;
            this.hasBanner = hasBanner;
            this.hasBleed = hasBleed;
        }
    }

    /** Everything drawPlate needs to know; all design values are on a baseW x baseH card. */
    private static final class PlateSpec {
        final int baseW, baseH;
        final float lumA, lumB, haloLum, haloAlpha, maxSat;
        final int icon, iconR, shadowY, shadowBlur;
        final float shadowAlpha, edgeAlpha, maxUpscale;
        final boolean exactBlur;      // CSS blur -> BlurMaskFilter radius as TileRow does
        final boolean alphaAware;     // shadow from the icon's alpha; opaque icons get round corners
        PlateSpec(int baseW, int baseH, float lumA, float lumB, float haloLum, float haloAlpha, float maxSat,
                  int icon, int iconR, int shadowY, int shadowBlur, float shadowAlpha, float edgeAlpha,
                  float maxUpscale, boolean exactBlur, boolean alphaAware) {
            this.baseW = baseW; this.baseH = baseH;
            this.lumA = lumA; this.lumB = lumB; this.haloLum = haloLum; this.haloAlpha = haloAlpha; this.maxSat = maxSat;
            this.icon = icon; this.iconR = iconR; this.shadowY = shadowY; this.shadowBlur = shadowBlur;
            this.shadowAlpha = shadowAlpha; this.edgeAlpha = edgeAlpha; this.maxUpscale = maxUpscale;
            this.exactBlur = exactBlur; this.alphaAware = alphaAware;
        }
    }

    /** Icon-only tile art (PLAN 0.4, R3): design values for a 260x156 tile. */
    private static final PlateSpec TILE_PLATE = new PlateSpec(Ui.TILE_W, Ui.TILE_H,
            Ui.TILE_FALLBACK_LUM_A, Ui.TILE_FALLBACK_LUM_B, Ui.TILE_FALLBACK_HALO_LUM, Ui.TILE_FALLBACK_HALO_ALPHA, 1f,
            Ui.TILE_FALLBACK_ICON, Ui.TILE_FALLBACK_ICON_R, Ui.FALLBACK_ICON_SHADOW_Y, Ui.FALLBACK_ICON_SHADOW_BLUR,
            Ui.FALLBACK_ICON_SHADOW_ALPHA, Ui.FALLBACK_ICON_EDGE_ALPHA, 0f, false, false);
    /** Top Shelf emblem (P4): design values for the 560x315 card. */
    private static final PlateSpec EMBLEM_PLATE = new PlateSpec(Ui.HERO_ART_W, Ui.HERO_ART_H,
            Ui.HERO_EMBLEM_LUM_A, Ui.HERO_EMBLEM_LUM_B, Ui.HERO_EMBLEM_HALO_LUM, Ui.HERO_EMBLEM_HALO_ALPHA, Ui.HERO_SAT_MAX,
            Ui.HERO_EMBLEM_ICON, Ui.HERO_EMBLEM_ICON_R, Ui.HERO_EMBLEM_ICON_SHADOW_Y, Ui.HERO_EMBLEM_ICON_SHADOW_BLUR,
            Ui.HERO_EMBLEM_ICON_SHADOW_ALPHA, Ui.HERO_EMBLEM_ICON_EDGE_ALPHA, Ui.HERO_EMBLEM_ICON_MAX_UPSCALE, true, true);

    private static final class Waiter {
        final AppEntry entry;
        final Callback cb;
        Waiter(AppEntry entry, Callback cb) { this.entry = entry; this.cb = cb; }
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TileLru tiles;
    private final Map<String, Meta> metas = new ConcurrentHashMap<String, Meta>();
    private final Map<String, List<Waiter>> pending = new HashMap<String, List<Waiter>>();
    private final SpriteLru<String, ShelfSet> shelves = new SpriteLru<String, ShelfSet>(MAX_SHELF_SETS, MAX_SHELF_BYTES);
    private final AtomicInteger generation = new AtomicInteger();
    private final Meta defaultMeta;
    private ExecutorService executor;
    private volatile boolean needBanner;   // setShelfNeeds: banner shelf bitmap + glow wanted (card=banner)
    private volatile boolean needBleed;    // setShelfNeeds: full-bleed bitmap wanted (hero=blur)
    private volatile boolean placeholder;  // debug_art=placeholder: letter plates only, no banner/icon pixels drawn

    public ArtCache(Context ctx) {
        Context app = ctx.getApplicationContext();
        this.ctx = app != null ? app : ctx;
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        int classMb = am != null ? am.getMemoryClass() : 64;
        final int maxBytes = Math.max(1 << 20, classMb * (1 << 20) / 8);
        tiles = new TileLru(maxBytes);
        defaultMeta = new Meta(DEFAULT_VIVID, ColorMath.withLightness(DEFAULT_VIVID, Ui.GLOW_LUM, 1f),
                Ui.GLOW_ALPHA, false, false, false);
    }

    // ---- synchronous getters (null / defaults until the art is ready) -------------------------------
    public Bitmap tile(AppEntry e) { return e == null ? null : tiles.get(e.packageName); }

    /** Emblem plate (640x360 RGB_565); null while loading. In banner mode only icon-only apps have one. */
    public Bitmap emblem(AppEntry e) {
        ShelfSet s = shelfSet(e);
        return s == null ? null : s.emblem;
    }

    /** Uncropped banner (<= 640x360); null while loading, in emblem mode, and for icon-only apps. */
    public Bitmap shelf(AppEntry e) {
        ShelfSet s = shelfSet(e);
        return s == null ? null : s.shelf;
    }

    /** Blurred 240x135 glow copy; null while loading, for near-white banners and for icon-only apps. */
    public Bitmap glow(AppEntry e) {
        ShelfSet s = shelfSet(e);
        return s == null ? null : s.glow;
    }

    /** Blurred 240x135 full-bleed copy for the "blur" hero style; null for icon-only apps. */
    public Bitmap fullBleed(AppEntry e) {
        ShelfSet s = shelfSet(e);
        return s == null ? null : s.bleed;
    }

    /**
     * Which shelf parts the current card/hero modes need. A cached set that lacks a now-needed part counts as
     * missing (shelfSet returns null, requestShelf re-renders), so a debug_card / hero switch works at runtime.
     */
    public void setShelfNeeds(boolean banner, boolean bleed) {
        needBanner = banner;
        needBleed = bleed;
    }

    /**
     * debug_art=placeholder (logo-free screenshots): every tile, emblem and dock tile becomes a letter plate (label's
     * first letter on the app's vivid colour); no banner or icon pixels are drawn, so there is no shelf banner, glow
     * or full-bleed either. Placeholder art never touches the disk cache. A switch drops all in-memory art.
     */
    public void setPlaceholder(boolean on) {
        if (placeholder == on) return;
        placeholder = on;
        generation.incrementAndGet();
        tiles.evictAll();
        metas.clear();
        synchronized (shelves) { shelves.clear(); }
    }

    public int vivid(AppEntry e) { return meta(e).vivid; }
    public boolean nearWhite(AppEntry e) { return meta(e).nearWhite; }
    public int glowColor(AppEntry e) { return meta(e).glowColor; }
    public float glowAlpha(AppEntry e) { return meta(e).glowAlpha; }
    public boolean darkArt(AppEntry e) { return meta(e).dark; }
    public boolean iconOnly(AppEntry e) { return meta(e).iconOnly; }

    private Meta meta(AppEntry e) {
        Meta m = e == null ? null : metas.get(e.packageName);
        return m != null ? m : defaultMeta;
    }

    private ShelfSet shelfSet(AppEntry e) {
        if (e == null) return null;
        synchronized (shelves) {
            ShelfSet s = shelves.get(e.packageName);
            return s != null && complete(s) ? s : null;
        }
    }

    private boolean complete(ShelfSet s) {
        boolean ok = needBanner ? s.hasBanner : s.emblem != null;
        return ok && (!needBleed || s.hasBleed);
    }

    // ---- async requests -------------------------------------------------------------------------------
    /** Tile art (and meta). cb runs on the main thread, also when rendering failed (tile() stays null). */
    public void request(AppEntry e, Callback cb) {
        if (e == null) return;
        if (tiles.get(e.packageName) != null && metas.containsKey(e.packageName)) {
            if (cb != null) postOne(e, cb);
            return;
        }
        enqueue("t:" + e.packageName, e, cb, false);
    }

    /** Shelf art + glow + full-bleed set for the focused app (keeps the last two sets). */
    public void requestShelf(AppEntry e, Callback cb) {
        if (e == null) return;
        if (shelfSet(e) != null) {
            if (cb != null) postOne(e, cb);
            return;
        }
        enqueue("s:" + e.packageName + (needBanner ? "b" : "e") + (needBleed ? "l" : ""), e, cb, true);
    }

    private void postOne(AppEntry e, Callback cb) {
        main.post(() -> cb.onArt(e));
    }

    private void enqueue(String key, AppEntry e, Callback cb, boolean shelf) {
        boolean start;
        synchronized (pending) {
            List<Waiter> list = pending.get(key);
            start = list == null;
            if (start) {
                list = new ArrayList<Waiter>();
                pending.put(key, list);
            }
            if (cb != null) list.add(new Waiter(e, cb));
        }
        if (!start) return;
        final AppEntry snap = e;
        executor().execute(() -> {
            try {
                if (shelf) doShelf(snap); else loadTile(snap);
            } catch (RuntimeException | OutOfMemoryError ex) {
                // leave the art missing; callbacks still fire so the caller can stop waiting
            }
            List<Waiter> done;
            synchronized (pending) { done = pending.remove(key); }
            if (done == null || done.isEmpty()) return;
            main.post(() -> {
                for (Waiter w : done) w.cb.onArt(w.entry);
            });
        });
    }

    private synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, "orchard-art");
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    private static long bytesOf(Bitmap b) { return b == null ? 0L : b.getByteCount(); }

    /** Evict the tile LRU and both shelf sets; colour meta stays. */
    public void trim() {
        tiles.evictAll();
        synchronized (shelves) { shelves.clear(); }
    }

    /** Drop memory and disk art for a package (install, update, removal). */
    public void invalidate(String pkg) {
        if (pkg == null) return;
        generation.incrementAndGet();
        tiles.remove(pkg);
        metas.remove(pkg);
        synchronized (shelves) { shelves.remove(pkg); }
        executor().execute(() -> deleteDisk(pkg, null));
    }

    // ---- tile pipeline (executor thread) --------------------------------------------------------------
    private Rendered loadTile(AppEntry e) {
        String pkg = e.packageName;
        Bitmap cached = tiles.get(pkg);
        Meta known = metas.get(pkg);
        if (cached != null && known != null) return new Rendered(cached, known);
        int gen = generation.get();

        String base = pkg + "_" + e.versionCode + "_" + e.lastUpdateTime + META_REV;   // META_REV: re-render after a meta rule change
        final boolean ph = placeholder;
        Rendered r = ph ? null : readDisk(base);
        if (r == null) {
            r = render(e, ph);
            if (r != null && !ph) writeDisk(pkg, base, r);
        }
        if (r != null && gen == generation.get()) {
            tiles.put(pkg, r.bmp);
            metas.put(pkg, r.meta);
        }
        return r;
    }

    private Rendered render(AppEntry e, boolean ph) {
        PackageManager pm = ctx.getPackageManager();
        Drawable banner = fresh(loadBanner(pm, e));
        if (ph) {
            // placeholder: the real art is only sampled for its colour; the tile is a letter plate
            Drawable src = banner != null ? banner : fresh(loadIcon(pm, e));
            int vivid;
            int[] sample;
            if (src != null) {
                sample = sample(src);
                vivid = ColorMath.vivid(sample);
            } else {
                vivid = letterColor(e);
                sample = new int[SAMPLE * SAMPLE];
                java.util.Arrays.fill(sample, 0xFF000000 | vivid);
            }
            Bitmap out = Bitmap.createBitmap(Ui.ART_W, Ui.ART_H, Bitmap.Config.RGB_565);
            drawPlate(new Canvas(out), Ui.ART_W, Ui.ART_H, null, vivid, TILE_PLATE, e.label, e.packageName);
            return new Rendered(out, makeMeta(vivid, sample, true));
        }
        Bitmap out = Bitmap.createBitmap(Ui.ART_W, Ui.ART_H, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        boolean iconOnly = banner == null;
        int[] sample;
        int vivid;
        if (!iconOnly) {
            drawCover(banner, c, Ui.ART_W, Ui.ART_H);
            sample = sample(banner);
            vivid = ColorMath.vivid(sample);
        } else {
            Drawable icon = fresh(loadIcon(pm, e));
            if (icon != null) {
                sample = sample(icon);
                vivid = ColorMath.vivid(sample);
            } else {
                vivid = letterColor(e);                               // no usable art at all: a letter plate in a stable hue
                sample = new int[SAMPLE * SAMPLE];
                java.util.Arrays.fill(sample, 0xFF000000 | vivid);
            }
            drawPlate(c, Ui.ART_W, Ui.ART_H, icon, vivid, TILE_PLATE, e.label, e.packageName);
        }
        return new Rendered(out, makeMeta(vivid, sample, iconOnly));
    }

    private static Meta makeMeta(int vivid, int[] sample, boolean iconOnly) {
        float avgL = ColorMath.averageLightness(sample);
        float avgS = ColorMath.averageSaturation(sample);
        boolean dark = avgL < Ui.GLOW_DARK_THRESHOLD_LUM;
        boolean white = !iconOnly && ((avgL > Ui.NEAR_WHITE_LUM && avgS < Ui.NEAR_WHITE_SAT)
                || ColorMath.whiteFraction(sample, Ui.NEAR_WHITE_PIX_LUM, Ui.NEAR_WHITE_PIX_SAT) >= Ui.NEAR_WHITE_FRACTION);
        int glowColor = ColorMath.withLightness(vivid, dark ? Ui.GLOW_DARK_LUM : Ui.GLOW_LUM, 1f);
        float glowAlpha = dark ? Ui.GLOW_DARK_ALPHA : Ui.GLOW_ALPHA;
        return new Meta(vivid, glowColor, glowAlpha, dark, white, iconOnly);
    }

    // ---- sources ----------------------------------------------------------------------------------------
    private static Drawable loadBanner(PackageManager pm, AppEntry e) {
        try {
            ComponentName cn = e.component != null ? e.component : pm.getLaunchIntentForPackage(e.packageName).getComponent();
            ActivityInfo ai = pm.getActivityInfo(cn, 0);
            Drawable d = ai.loadBanner(pm);
            if (usable(d)) return d;
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            // try the application banner
        }
        try {
            Drawable d = pm.getApplicationBanner(e.packageName);
            return usable(d) ? d : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Icon chain (banners come first, see loadBanner): activity icon, then application icon, each at the highest
     * density the APK ships, then the PackageManager's own loaders. A drawable that is missing, empty or fully
     * transparent is skipped. Returns null when nothing usable is left (the caller draws a letter plate).
     */
    private static Drawable loadIcon(PackageManager pm, AppEntry e) {
        ActivityInfo ai = null;
        try {
            if (e.component != null) ai = pm.getActivityInfo(e.component, 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            // application-level icons below
        }
        if (ai != null) {
            Drawable d = highDensity(pm, ai.applicationInfo, ai.icon);
            if (usable(d)) return d;
            try {
                d = ai.loadIcon(pm);
                if (usable(d)) return d;
            } catch (RuntimeException ex) {
                // next
            }
        }
        try {
            ApplicationInfo app = ai != null ? ai.applicationInfo : pm.getApplicationInfo(e.packageName, 0);
            Drawable d = highDensity(pm, app, app.icon);
            if (usable(d)) return d;
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            // next
        }
        try {
            Drawable d = pm.getApplicationIcon(e.packageName);
            // With no icon resource at all the PackageManager hands back Android's default robot: use the letter plate.
            return usable(d) && !isDefaultIcon(pm, d) ? d : null;
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            return null;
        }
    }

    /** True when d renders the same 24x24 pixels as pm.getDefaultActivityIcon() (Android's robot). */
    private static boolean isDefaultIcon(PackageManager pm, Drawable d) {
        try {
            int[] a = render24(d);
            int[] b = render24(pm.getDefaultActivityIcon());
            return a != null && java.util.Arrays.equals(a, b);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static int[] render24(Drawable d) {
        if (d == null) return null;
        Bitmap b = null;
        try {
            final int n = 24;
            b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Rect saved = new Rect(d.getBounds());
            d.setBounds(0, 0, n, n);
            d.draw(c);
            d.setBounds(saved);
            int[] px = new int[n * n];
            b.getPixels(px, 0, n, 0, 0, n, n);
            return px;
        } catch (RuntimeException | OutOfMemoryError ex) {
            return null;
        } finally {
            if (b != null) b.recycle();
        }
    }

    /** The resource at DENSITY_XXXHIGH from the app's own Resources; null when res is 0 or it cannot be loaded. */
    private static Drawable highDensity(PackageManager pm, ApplicationInfo app, int res) {
        if (app == null || res == 0) return null;
        try {
            Resources r = pm.getResourcesForApplication(app);
            return r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            return null;
        }
    }

    /** False for null, a zero-size ColorDrawable-less empty drawable, one whose 24x24 render is (almost) transparent, or flat single-colour art. */
    private static boolean usable(Drawable d) {
        if (d == null) return false;
        Bitmap b = null;
        try {
            final int n = 24;
            b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Rect saved = new Rect(d.getBounds());
            d.setBounds(0, 0, n, n);
            d.draw(c);
            d.setBounds(saved);
            int[] px = new int[n * n];
            b.getPixels(px, 0, n, 0, 0, n, n);
            int solid = 0;
            for (int p : px) if ((p >>> 24) >= 32) solid++;
            return solid >= 12 && !ColorMath.isFlat(px);
        } catch (RuntimeException | OutOfMemoryError ex) {
            return false;
        } finally {
            if (b != null) b.recycle();
        }
    }

    /** Stable vivid colour for the letter plate, from the package name. */
    private static int letterColor(AppEntry e) {
        String key = e.packageName != null ? e.packageName : (e.label != null ? e.label : "?");
        float hue = (key.hashCode() & 0x7FFFFFFF) % 360;
        return Color.HSVToColor(new float[] { hue, 0.62f, 0.82f }) & 0xFFFFFF;
    }

    /** A private copy, so changing bounds never touches a drawable another view might show. */
    private static Drawable fresh(Drawable d) {
        if (d == null) return null;
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable().mutate() : d;
    }

    /** 12x12 box-filtered ARGB sample of the whole drawable. */
    private static int[] sample(Drawable d) {
        int big = SAMPLE * SAMPLE_OVER;
        Bitmap b = Bitmap.createBitmap(big, big, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Rect saved = new Rect(d.getBounds());
        d.setBounds(0, 0, big, big);
        d.draw(c);
        d.setBounds(saved);
        int[] px = new int[big * big];
        b.getPixels(px, 0, big, 0, 0, big, big);
        b.recycle();
        int[] out = new int[SAMPLE * SAMPLE];
        int n = SAMPLE_OVER * SAMPLE_OVER;
        for (int y = 0; y < SAMPLE; y++) {
            for (int x = 0; x < SAMPLE; x++) {
                int a = 0, r = 0, g = 0, bl = 0;
                for (int dy = 0; dy < SAMPLE_OVER; dy++) {
                    for (int dx = 0; dx < SAMPLE_OVER; dx++) {
                        int p = px[(y * SAMPLE_OVER + dy) * big + x * SAMPLE_OVER + dx];
                        a += p >>> 24;
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        bl += p & 0xFF;
                    }
                }
                out[y * SAMPLE + x] = ((a / n) << 24) | ((r / n) << 16) | ((g / n) << 8) | (bl / n);
            }
        }
        return out;
    }

    /** Centre-crop fill: the drawable covers w x h, overflow is cropped. */
    private static void drawCover(Drawable d, Canvas c, int w, int h) {
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) { iw = 16; ih = 9; }
        float s = Math.max(w / (float) iw, h / (float) ih);
        int dw = Math.round(iw * s), dh = Math.round(ih * s);
        int l = (w - dw) / 2, t = (h - dh) / 2;
        d.setBounds(l, t, l + dw, t + dh);
        c.save();
        c.clipRect(0, 0, w, h);
        d.draw(c);
        c.restore();
    }

    // ---- icon plate (PLAN 0.4 R3 tile fallback; P4 Top Shelf emblem) ---------------------------------------------
    private static void drawPlate(Canvas c, int w, int h, Drawable icon, int vivid, PlateSpec sp, String label, String pkg) {
        // design values are for a baseW x baseH card; k scales them into this raster
        float k = Math.min(w / (float) sp.baseW, h / (float) sp.baseH);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setDither(true);   // the plate is RGB_565: without dither the gradients band in 8-level steps (P4 r2 device shot)
        p.setShader(new LinearGradient(0, 0, 0, h,
                ColorMath.withLightness(vivid, sp.lumA, sp.maxSat),
                ColorMath.withLightness(vivid, sp.lumB, sp.maxSat), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        // halo: elliptical radial, centre 18% / 0%, radii 90% x 110%
        int halo = ColorMath.withLightness(vivid, sp.haloLum, sp.maxSat);
        float rx = 0.90f * w, ry = 1.10f * h, cx = 0.18f * w;
        RadialGradient rg = new RadialGradient(cx, 0, rx,
                Ui.argb(sp.haloAlpha, halo), Ui.argb(0f, halo), Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(1f, ry / rx, cx, 0f);
        rg.setLocalMatrix(m);
        p.setShader(rg);
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        if (icon == null) {
            // letter glyph (label's first letter, Inter SemiBold) instead of an empty coloured plate
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(Ui.semibold());
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(sp.icon * k * 0.78f);
            t.setColor(Ui.argb(0.94f, 0xFFFFFF));
            Paint.FontMetrics fm = t.getFontMetrics();
            c.drawText(ShelfText.initial(label, pkg), w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, t);
            return;
        }

        int size = Math.round(sp.icon * k);
        if (sp.maxUpscale > 0f) {
            int nat = Math.min(icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
            if (nat > 0) size = Math.min(size, Math.round(nat * sp.maxUpscale));
        }
        size = Math.max(2, size);
        float nominal = Math.max(1, Math.round(sp.icon * k));
        float radius = sp.iconR * k * (size / nominal);
        float left = (w - size) / 2f, top = (h - size) / 2f;
        Bitmap ib = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas ic = new Canvas(ib);
        icon.setBounds(0, 0, size, size);
        icon.draw(ic);

        boolean square = !sp.alphaAware || opaqueSquare(ib);
        float blurR = sp.exactBlur ? Math.max(1f, (sp.shadowBlur * k / 2f - 0.5f) / 0.57735f) : sp.shadowBlur * k * 0.5f;
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Ui.argb(sp.shadowAlpha, 0x000000));
        BlurMaskFilter blur = new BlurMaskFilter(blurR, BlurMaskFilter.Blur.NORMAL);
        float dy = sp.shadowY * k;
        RectF rect = new RectF(left, top, left + size, top + size);
        if (square) {
            shadow.setMaskFilter(blur);
            RectF sr = new RectF(rect);
            sr.offset(0, dy);
            c.drawRoundRect(sr, radius, radius, shadow);

            BitmapShader shader = new BitmapShader(ib, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix sm = new Matrix();
            sm.setTranslate(left, top);
            shader.setLocalMatrix(sm);
            p.setShader(shader);
            c.drawRoundRect(rect, radius, radius, p);
            p.setShader(null);

            float line = Math.max(1f, k);
            p.setColor(Ui.argb(sp.edgeAlpha, 0xFFFFFF));
            p.setStrokeWidth(line);
            c.drawLine(left + radius, top + 0.5f * line, left + size - radius, top + 0.5f * line, p);
        } else {
            // round / irregular icon: the shadow follows the icon's own alpha
            Paint ap = new Paint();
            ap.setMaskFilter(blur);
            int[] off = new int[2];
            Bitmap a = ib.extractAlpha(ap, off);
            c.drawBitmap(a, left + off[0], top + off[1] + dy, shadow);
            a.recycle();
            Paint ip = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            c.drawBitmap(ib, left, top, ip);
        }
        ib.recycle();
    }

    /** True when the icon bitmap's four corners and edge midpoints are fully opaque (a plain square icon). */
    private static boolean opaqueSquare(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] xs = {0, w - 1, w / 2, 0, w - 1, w / 2, 0, w - 1};
        int[] ys = {0, 0, 0, h - 1, h - 1, h - 1, h / 2, h / 2};
        for (int i = 0; i < xs.length; i++) if ((b.getPixel(xs[i], ys[i]) >>> 24) < 250) return false;
        return true;
    }

    // ---- shelf pipeline (executor thread) -----------------------------------------------------------------------
    private void doShelf(AppEntry e) {
        int gen = generation.get();
        final boolean nb = needBanner, nbl = needBleed;
        PackageManager pm = ctx.getPackageManager();
        Rendered tile = loadTile(e);
        Meta m = tile != null ? tile.meta : defaultMeta;

        final boolean ph = placeholder;
        Drawable banner = ph || m.iconOnly || !(nb || nbl) ? null : shelfBanner(pm, e);
        Bitmap emblem = null;
        Bitmap shelf = null;
        Bitmap glow = null;
        Bitmap bleed = null;
        if (banner != null) {
            int iw = banner.getIntrinsicWidth(), ih = banner.getIntrinsicHeight();
            boolean alpha = true;
            if (banner instanceof BitmapDrawable) {
                Bitmap bb = ((BitmapDrawable) banner).getBitmap();
                if (bb != null) { iw = bb.getWidth(); ih = bb.getHeight(); alpha = bb.hasAlpha(); }
            }
            if (iw <= 0 || ih <= 0) { iw = Ui.SHELF_ART_MAX_W; ih = Ui.SHELF_ART_MAX_H; }
            float s = Math.min(1f, Math.min(Ui.SHELF_ART_MAX_W / (float) iw, Ui.SHELF_ART_MAX_H / (float) ih));
            int ow = Math.max(1, Math.round(iw * s)), oh = Math.max(1, Math.round(ih * s));
            Bitmap full = Bitmap.createBitmap(ow, oh, alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
            banner.setBounds(0, 0, ow, oh);
            banner.draw(new Canvas(full));
            if (nb && !m.nearWhite) glow = blurred(full, Ui.HERO_GLOW_SAT, GLOW_STACK_RADIUS, GLOW_PAD_X, GLOW_PAD_Y);
            if (nbl) bleed = blurred(full, Ui.HERO_BL_SAT, BLEED_STACK_RADIUS, 0, 0);
            if (nb) shelf = full;
        }
        // the emblem serves emblem mode, and icon-only apps (or a banner that failed to load) in banner mode
        if (!nb || shelf == null) {
            emblem = Bitmap.createBitmap(Ui.SHELF_ART_MAX_W, Ui.SHELF_ART_MAX_H, Bitmap.Config.RGB_565);
            drawPlate(new Canvas(emblem), emblem.getWidth(), emblem.getHeight(), ph ? null : emblemIcon(pm, e), m.vivid, EMBLEM_PLATE, e.label, e.packageName);
        }
        if (gen != generation.get()) return;
        long bytes = bytesOf(emblem) + bytesOf(shelf) + bytesOf(glow) + bytesOf(bleed);
        synchronized (shelves) {
            shelves.put(e.packageName, new ShelfSet(e.packageName, emblem, shelf, glow, bleed, nb, nbl),
                    (int) Math.min(Integer.MAX_VALUE, bytes));     // evicted sets are only dropped: a view may still hold the bitmap
        }
    }

    /** Highest-density icon the APK ships (activity, then application), else the normal icon; null = letter plate. */
    private static Drawable emblemIcon(PackageManager pm, AppEntry e) {
        return fresh(loadIcon(pm, e));
    }

    /** Highest-density banner the APK ships, else the normal banner. */
    private static Drawable shelfBanner(PackageManager pm, AppEntry e) {
        try {
            ComponentName cn = e.component;
            if (cn != null) {
                ActivityInfo ai = pm.getActivityInfo(cn, 0);
                int res = ai.banner != 0 ? ai.banner : ai.applicationInfo.banner;
                if (res != 0) {
                    Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                    Drawable d = r.getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null);
                    if (usable(d)) return fresh(d);
                }
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            // fall back below
        }
        return fresh(loadBanner(pm, e));
    }

    /** 240x135 copy (cover-cropped to 16:9) with a saturation boost, then stack blur. */
    private static Bitmap blurred(Bitmap src, float saturation, int radius, int padX, int padY) {
        int w = Ui.BLUR_W, h = Ui.BLUR_H;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(saturation);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        int sw = src.getWidth(), sh = src.getHeight();
        int iw = w - 2 * padX, ih = h - 2 * padY;
        float want = iw / (float) ih;
        int cw = sw, ch = sh;
        if (sw / (float) sh > want) cw = Math.round(sh * want); else ch = Math.round(sw / want);
        int cl = (sw - cw) / 2, ct = (sh - ch) / 2;
        new Canvas(out).drawBitmap(src, new Rect(cl, ct, cl + cw, ct + ch), new Rect(padX, padY, padX + iw, padY + ih), p);
        int[] px = new int[w * h];
        out.getPixels(px, 0, w, 0, 0, w, h);
        boolean pad = padX > 0 || padY > 0;
        if (pad) premultiply(px, true);    // blur premultiplied so the transparent margin does not darken the fringe
        Blur.stack(px, w, h, radius);
        if (pad) premultiply(px, false);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    /** In place: straight ARGB -> premultiplied (to = true) or back. */
    private static void premultiply(int[] px, boolean to) {
        for (int i = 0; i < px.length; i++) {
            int c = px[i], a = c >>> 24;
            if (a == 255) continue;
            if (a == 0) { px[i] = 0; continue; }
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            if (to) { r = r * a / 255; g = g * a / 255; b = b * a / 255; }
            else { r = Math.min(255, r * 255 / a); g = Math.min(255, g * 255 / a); b = Math.min(255, b * 255 / a); }
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    // ---- disk cache ---------------------------------------------------------------------------------------------------
    private File dir() { return new File(ctx.getCacheDir(), "art"); }

    private static String ext() { return Build.VERSION.SDK_INT >= 30 ? ".webp" : ".png"; }

    private Rendered readDisk(String base) {
        try {
            File img = new File(dir(), base + ext());
            File mf = new File(dir(), base + ext() + ".meta");
            if (!img.isFile() || !mf.isFile()) return null;
            Meta m = parseMeta(Config.parse(new String(readAll(mf), UTF8)));
            if (m == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(img.getPath(), o);
            return b == null ? null : new Rendered(b, m);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private void writeDisk(String pkg, String base, Rendered r) {
        try {
            File d = dir();
            if (!d.isDirectory() && !d.mkdirs()) return;
            deleteDisk(pkg, base + ext());
            File img = new File(d, base + ext());
            File tmp = new File(d, base + ".tmp");
            OutputStream os = new FileOutputStream(tmp);
            boolean ok;
            try {
                Bitmap.CompressFormat f = Build.VERSION.SDK_INT >= 30 ? Bitmap.CompressFormat.WEBP_LOSSY
                        : Bitmap.CompressFormat.PNG;
                ok = r.bmp.compress(f, Ui.ART_WEBP_QUALITY, os);
            } finally {
                os.close();
            }
            if (!ok || !tmp.renameTo(img)) { tmp.delete(); return; }
            CrashGuard.writeAtomic(new File(d, base + ext() + ".meta"), Config.serialize(metaMap(r.meta)).getBytes(UTF8));
        } catch (IOException | RuntimeException ex) {
            // the in-memory copy still works; the next launch re-renders
        }
    }

    /** Delete this package's cache files (all of them, or all except keepName and its .meta). */
    private void deleteDisk(String pkg, String keepName) {
        File[] files = dir().listFiles();
        if (files == null) return;
        String prefix = pkg + "_";
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith(prefix)) continue;
            if (!n.substring(prefix.length()).matches("-?\\d+_-?\\d+(_m\\d+)?\\.(webp|png)(\\.meta)?")) continue;
            if (keepName != null && (n.equals(keepName) || n.equals(keepName + ".meta"))) continue;
            f.delete();
        }
    }

    private static Map<String, String> metaMap(Meta m) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("vivid", String.valueOf(m.vivid));
        out.put("glow_color", String.valueOf(m.glowColor));
        out.put("glow_alpha", String.valueOf(m.glowAlpha));
        out.put("flags", (m.dark ? "d" : "") + (m.nearWhite ? "w" : "") + (m.iconOnly ? "i" : ""));
        return out;
    }

    private static Meta parseMeta(Map<String, String> map) {
        try {
            String v = map.get("vivid"), g = map.get("glow_color"), a = map.get("glow_alpha"), f = map.get("flags");
            if (v == null || g == null || a == null || f == null) return null;
            return new Meta(Integer.parseInt(v), Integer.parseInt(g), Float.parseFloat(a),
                    f.indexOf('d') >= 0, f.indexOf('w') >= 0, f.indexOf('i') >= 0);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static byte[] readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }
}
