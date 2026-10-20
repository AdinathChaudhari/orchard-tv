package tv.orchard.home;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Rev 6 sky bitmaps: decoding the 960x540 keyframes (R.drawable.sky_NAME and sky_NAME_oc), the 240x135 blurred
 * copies cached on disk, the glow and rain tiles, and the glass slice composite that Settings and the shelf
 * panels sample as their backdrop. Everything heavy runs on one low-priority background thread; results are
 * posted to the main thread. Nothing here is held between calls except the two slice buffers.
 */
public final class SkyAssets {
    private SkyAssets() {}

    /** Receives the composite blurred backdrop (240x135, ARGB_8888) on the main thread. Do not recycle it. */
    public interface SliceListener { void onSlice(Bitmap slice240x135); }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ExecutorService executor;
    // double buffer: a Glass BitmapShader still reading the previous slice never sees a half-drawn bitmap
    private static final Bitmap[] OUT = new Bitmap[2];
    private static int outNext;

    static synchronized ExecutorService executor() {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "orchard-sky");
                t.setPriority(Thread.MIN_PRIORITY);
                t.setDaemon(true);
                return t;
            });
        }
        return executor;
    }

    /** Runs r on the sky background thread. */
    static void background(Runnable r) { executor().execute(r); }

    /** Runs r on the main thread. */
    static void post(Runnable r) { MAIN.post(r); }

    // ---- decoding (background thread only) ---------------------------------------------------------------

    private static int skyId(Context c, int idx, boolean oc) {
        String n = "sky_" + SkyPhase.NAMES[idx] + (oc ? "_oc" : "");
        return c.getResources().getIdentifier(n, "drawable", c.getPackageName());
    }

    /** One 960x540 ARGB_8888 keyframe, or null. The GPU scales it to the screen. */
    static Bitmap decodeSky(Context c, int idx, boolean oc) {
        int id = skyId(c, idx, oc);
        return id == 0 ? null : decodeRes(c, id, 1);
    }

    /** A drawable resource as ARGB_8888 without density scaling; sample > 1 shrinks it. Null on failure. */
    static Bitmap decodeRes(Context c, int id, int sample) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            o.inSampleSize = Math.max(1, sample);
            o.inMutable = sample > 1;
            return BitmapFactory.decodeResource(c.getResources(), id, o);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    static Bitmap sunGlow(Context c) { return decodeRes(c, R.drawable.sun_glow, 1); }

    static Bitmap moonGlow(Context c) { return decodeRes(c, R.drawable.moon_glow, 1); }

    static Bitmap rainTile(Context c, boolean light) {
        return decodeRes(c, light ? R.drawable.rain_tile_light : R.drawable.rain_tile, 1);
    }

    private static long apkStamp(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.lastUpdateTime;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** The 240x135 blurred keyframe (stack blur, Ui.GLASS_BLUR_RADIUS), from the disk cache when it is newer than the APK. */
    static Bitmap blurSky(Context c, int idx, boolean oc) {
        File f = new File(c.getCacheDir(), "skyblur_" + SkyPhase.NAMES[idx] + (oc ? "_oc" : "") + "_v1.png");
        long stamp = apkStamp(c);
        if (f.isFile() && f.lastModified() >= stamp) {
            Bitmap hit = readPng(f);
            if (hit != null) return hit;
        }
        int id = skyId(c, idx, oc);
        if (id == 0) return null;
        Bitmap small = decodeRes(c, id, 4);
        if (small == null) return null;
        if (small.getWidth() != Ui.BLUR_W || small.getHeight() != Ui.BLUR_H) {
            Bitmap fit = Bitmap.createScaledBitmap(small, Ui.BLUR_W, Ui.BLUR_H, true);
            small = fit.isMutable() ? fit : fit.copy(Bitmap.Config.ARGB_8888, true);
        }
        blurInPlace(small);
        writePng(small, f);
        return small;
    }

    static void blurInPlace(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        Blur.stack(px, w, h, Ui.GLASS_BLUR_RADIUS);
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static Bitmap readPng(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            return b != null && b.getWidth() == Ui.BLUR_W && b.getHeight() == Ui.BLUR_H ? b : null;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
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

    // ---- slice -------------------------------------------------------------------------------------------

    /**
     * Composites the blurred backdrop for state s and delivers it on the main thread. Sky: blurred A (A_oc when
     * frame.oc >= 0.5, like mock --wallurl) plus blurred B at alpha frame.f. Static walls: night blur, day blur at p,
     * twilight at TW_PEAK*4p(1-p), golden at g (the WallStack layers). The state is copied here, so the caller may
     * keep mutating its own.
     */
    public static void slice(Context c, AmbientState s, SliceListener l) {
        if (l == null || s == null) return;
        final Context app = c.getApplicationContext();
        final AmbientState st = s.copy();
        background(() -> {
            Bitmap scratch = Bitmap.createBitmap(Ui.BLUR_W, Ui.BLUR_H, Bitmap.Config.ARGB_8888);
            try {
                if (st.sky && st.frame != null) compositeSky(app, st, scratch);
                else compositeWall(app, st, scratch);
            } catch (RuntimeException | OutOfMemoryError e) {
                scratch.eraseColor(Ui.BG);
            }
            post(() -> {
                Bitmap out = OUT[outNext];
                if (out == null) {
                    out = Bitmap.createBitmap(Ui.BLUR_W, Ui.BLUR_H, Bitmap.Config.ARGB_8888);
                    OUT[outNext] = out;
                }
                outNext ^= 1;
                new Canvas(out).drawBitmap(scratch, 0, 0, null);
                l.onSlice(out);
            });
        });
    }

    private static void compositeSky(Context c, AmbientState s, Bitmap dst) {
        SkyPhase.Frame fr = s.frame;
        boolean oc = fr.oc >= 0.5f;
        Canvas cv = new Canvas(dst);
        Bitmap a = blurSky(c, fr.a, oc);
        if (a != null) cv.drawBitmap(a, 0, 0, null); else dst.eraseColor(fr.scrimRgb);
        if (fr.b != fr.a && fr.f > 0f) {
            Bitmap b = blurSky(c, fr.b, oc);
            if (b != null) {
                Paint p = new Paint();
                p.setAlpha(Math.round(fr.f * 255f));
                cv.drawBitmap(b, 0, 0, p);
            }
        }
    }

    private static void compositeWall(Context c, AmbientState s, Bitmap dst) {
        float p = s.themeMode == 1 ? 1f : s.themeMode == 2 ? 0f : s.p;
        float g = s.themeMode == 0 ? s.g : 0f;
        Canvas cv = new Canvas(dst);
        cv.drawColor(Theme.DARK.bg);
        String name = Wallpaper.normalName(s.wallName);
        if (!"black".equals(name)) {
            Bitmap night = new Wallpaper(c).blurredSync(name);
            if (night != null) cv.drawBitmap(night, 0, 0, null);
        }
        Paint paint = new Paint();
        if (p > 0f) {
            Bitmap day = new Wallpaper(c).blurredSync("day");
            if (day != null) {
                paint.setAlpha(Math.round(p * 255f));
                cv.drawBitmap(day, 0, 0, paint);
            }
        }
        if (p > 0f && p < 1f) {
            paint.setAlpha(Math.round(Ui.TW_PEAK * 4f * p * (1f - p) * 255f));
            cv.drawBitmap(WallStack.bakeTwilight(), 0, 0, paint);
        }
        if (g > 0f) {
            paint.setAlpha(Math.round(Math.min(1f, g) * 255f));
            cv.drawBitmap(WallStack.bakeGolden(), 0, 0, paint);
        }
    }
}
