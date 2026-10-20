package tv.orchard.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Bundled / user wallpaper: sharp RGB_565 bitmap at screen size plus a 240x135 pre-blurred copy (stack blur,
 * radius Ui.GLASS_BLUR_RADIUS) cached on disk. The blurred copy is never upscaled into a bitmap: glass
 * painters draw it with a source rect and let the GPU scale it. Decoding runs off the main thread.
 * Names: dusk | ember | fog | black | user | day (the light wall, WallStack). "sky" and unknown names map to Ui.NIGHT_WALL_DEFAULT.
 */
public final class Wallpaper {
    /** Called on the main thread when a load() finished (sharp() / blurred() are then current). */
    public interface Callback { void onWallpaper(Wallpaper w); }

    private static final String[] USER_FILES = { "wallpaper.jpg", "wallpaper.png", "wallpaper.webp" };

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final int screenW, screenH;
    private final WallDrawable drawable = new WallDrawable(this);

    private volatile Bitmap sharp;
    private volatile Bitmap blurred;
    private volatile int generation;          // bumped by every load(); a stale decode result is dropped
    private String lastName = Ui.DEFAULT_WALLPAPER;
    private Callback lastCb;

    public Wallpaper(Context ctx) {
        app = ctx.getApplicationContext();
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        screenW = Math.max(1, dm.widthPixels);
        screenH = Math.max(1, dm.heightPixels);
    }

    /** Sharp screen-size RGB_565 wallpaper, or null for "black", before the first load, or after release(). */
    public Bitmap sharp() { return sharp; }

    /** 240x135 ARGB_8888 pre-blurred copy, or null before the first load completes. */
    public Bitmap blurred() { return blurred; }

    public void reload(String name) { load(name, lastCb); }

    /** Decode on a background thread; cb (may be null) runs on the main thread. */
    public void load(String name, Callback cb) {
        final String n = (name == null || name.isEmpty()) ? Ui.DEFAULT_WALLPAPER : name;
        lastName = n;
        lastCb = cb;
        final int gen = ++generation;
        Thread t = new Thread(new Job(this, n, gen, cb), "orchard-wallpaper");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Root background: draws sharp() stretched to its bounds, or Ui.BG when there is none. */
    public Drawable drawable() { return drawable; }

    /** onStop: drop the big bitmap and the small blurred one (the blur stays on disk). load() restores both. */
    public void release() {
        generation++;                     // a decode still in flight must not republish after the release
        sharp = null;
        blurred = null;
        drawable.invalidateSelf();
    }

    // ---- background decode ---------------------------------------------------------------------------

    private static final class Job implements Runnable {
        private final Wallpaper w;
        private final String name;
        private final int gen;
        private final Callback cb;

        Job(Wallpaper w, String name, int gen, Callback cb) {
            this.w = w;
            this.name = name;
            this.gen = gen;
            this.cb = cb;
        }

        @Override
        public void run() {
            Result r = w.decode(name);
            if (r == null) r = w.decode(Ui.NIGHT_WALL_DEFAULT);
            if (r == null) r = new Result(null, w.blackBlur());
            w.main.post(new Publish(w, r, gen, cb));
        }
    }

    private static final class Publish implements Runnable {
        private final Wallpaper w;
        private final Result r;
        private final int gen;
        private final Callback cb;

        Publish(Wallpaper w, Result r, int gen, Callback cb) {
            this.w = w;
            this.r = r;
            this.gen = gen;
            this.cb = cb;
        }

        @Override
        public void run() {
            if (gen != w.generation) return;
            w.sharp = r.sharp;
            w.blurred = r.blurred;
            w.drawable.invalidateSelf();
            if (cb != null) cb.onWallpaper(w);
        }
    }

    private static final class Result {
        final Bitmap sharp, blurred;
        Result(Bitmap sharp, Bitmap blurred) {
            this.sharp = sharp;
            this.blurred = blurred;
        }
    }

    /** Maps any wallpaper name onto one decode() can produce: "sky" (the Rev 6 default, which is no static wall) and unknown names become Ui.NIGHT_WALL_DEFAULT. */
    static String normalName(String name) {
        if ("user".equals(name) || "black".equals(name) || "day".equals(name)
                || "dusk".equals(name) || "ember".equals(name) || "fog".equals(name)) return name;
        return Ui.NIGHT_WALL_DEFAULT;
    }

    /** Background-thread helper: the 240x135 blurred copy of a static wall ("day" included), never null (falls back to the default night wall, then flat bg). */
    Bitmap blurredSync(String name) {
        Result r = decode(name);
        if (r == null) r = decode(Ui.NIGHT_WALL_DEFAULT);
        return r != null && r.blurred != null ? r.blurred : blackBlur();
    }

    /** Returns null when this name cannot be produced (caller falls back to the default). */
    private Result decode(String rawName) {
        String name = normalName(rawName);
        try {
            if ("black".equals(name)) return new Result(null, blackBlur());
            Bitmap src;
            long stamp = 0, len = 0;
            if ("user".equals(name)) {
                File f = userFile();
                if (f == null) return null;
                stamp = f.lastModified();
                len = f.length();
                src = decodeUser(f);
            } else {
                int id = R.drawable.wall_dusk;
                if ("ember".equals(name)) id = R.drawable.wall_ember;
                else if ("fog".equals(name)) id = R.drawable.wall_fog;
                else if ("day".equals(name)) id = R.drawable.wall_day;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inScaled = false;
                src = BitmapFactory.decodeResource(app.getResources(), id, o);
                stamp = apkStamp();
            }
            if (src == null) return null;
            return new Result(src, blurFor(name, stamp, len, src));
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private File userFile() { return findUserFile(app); }

    /** True when a user wallpaper file (wallpaper.jpg / .png / .webp in the app's external files dir) exists. */
    public static boolean hasUserFile(Context c) { return findUserFile(c.getApplicationContext()) != null; }

    private static File findUserFile(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return null;
        for (String n : USER_FILES) {
            File f = new File(dir, n);
            if (f.isFile() && f.length() > 0) return f;
        }
        return null;
    }

    /** Decode with a sample size that keeps it >= screen size, then centre-crop/scale to screen size. */
    private Bitmap decodeUser(File f) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), b);
        if (b.outWidth <= 0 || b.outHeight <= 0) return null;
        int s = 1;
        while (b.outWidth / (s * 2) >= screenW && b.outHeight / (s * 2) >= screenH) s *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = s;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inScaled = false;
        Bitmap raw = BitmapFactory.decodeFile(f.getPath(), o);
        if (raw == null) return null;
        Bitmap out = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.RGB_565);
        float k = Math.max(screenW / (float) raw.getWidth(), screenH / (float) raw.getHeight());
        float dw = raw.getWidth() * k, dh = raw.getHeight() * k;
        Rect dst = new Rect(Math.round((screenW - dw) / 2f), Math.round((screenH - dh) / 2f),
                Math.round((screenW + dw) / 2f), Math.round((screenH + dh) / 2f));
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        new Canvas(out).drawBitmap(raw, null, dst, p);
        raw.recycle();
        return out;
    }

    /** Bundled wallpapers change only with an app update: tie the cache to the APK's timestamp. */
    private long apkStamp() {
        try {
            return new File(app.getApplicationInfo().sourceDir).lastModified();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    // ---- blur --------------------------------------------------------------------------------------------

    private Bitmap blackBlur() {
        Bitmap b = Bitmap.createBitmap(Ui.BLUR_W, Ui.BLUR_H, Bitmap.Config.ARGB_8888);
        b.eraseColor(Ui.BG);
        return b;
    }

    private Bitmap blurFor(String name, long stamp, long len, Bitmap src) {
        File cache = new File(app.getCacheDir(),
                "blur_" + Integer.toHexString((name + "|" + stamp + "|" + len + "|" + Ui.GLASS_BLUR_RADIUS).hashCode()) + ".png");
        if (cache.isFile()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            Bitmap c = BitmapFactory.decodeFile(cache.getPath(), o);
            if (c != null && c.getWidth() == Ui.BLUR_W && c.getHeight() == Ui.BLUR_H) return c;
        }
        Bitmap small = downscale(src);
        int[] px = new int[Ui.BLUR_W * Ui.BLUR_H];
        small.getPixels(px, 0, Ui.BLUR_W, 0, 0, Ui.BLUR_W, Ui.BLUR_H);
        Blur.stack(px, Ui.BLUR_W, Ui.BLUR_H, Ui.GLASS_BLUR_RADIUS);
        small.setPixels(px, 0, Ui.BLUR_W, 0, 0, Ui.BLUR_W, Ui.BLUR_H);
        writePng(small, cache);
        return small;
    }

    /** Filtered halving down to 240x135 (a single bilinear tap from 1920 wide would alias). */
    private static Bitmap downscale(Bitmap src) {
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        Bitmap cur = src;
        while (cur.getWidth() > Ui.BLUR_W * 2 && cur.getHeight() > Ui.BLUR_H * 2) {
            Bitmap next = Bitmap.createBitmap(cur.getWidth() / 2, cur.getHeight() / 2, Bitmap.Config.ARGB_8888);
            new Canvas(next).drawBitmap(cur, null, new Rect(0, 0, next.getWidth(), next.getHeight()), p);
            if (cur != src) cur.recycle();
            cur = next;
        }
        Bitmap out = Bitmap.createBitmap(Ui.BLUR_W, Ui.BLUR_H, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(cur, null, new Rect(0, 0, Ui.BLUR_W, Ui.BLUR_H), p);
        if (cur != src) cur.recycle();
        return out;
    }

    private static void writePng(Bitmap b, File f) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            if (!b.compress(Bitmap.CompressFormat.PNG, 100, os)) { tmp.delete(); return; }
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    // ---- drawable ---------------------------------------------------------------------------------------

    private static final class WallDrawable extends Drawable {
        private final Wallpaper w;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src = new Rect();

        WallDrawable(Wallpaper w) { this.w = w; }

        @Override
        public void draw(Canvas c) {
            Bitmap b = w.sharp;
            Rect d = getBounds();
            if (b == null) {
                c.drawColor(Ui.BG);
                return;
            }
            src.set(0, 0, b.getWidth(), b.getHeight());
            c.drawBitmap(b, src, d, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
        @Override @SuppressWarnings("deprecation") public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
