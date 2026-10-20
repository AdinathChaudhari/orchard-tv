package tv.orchard.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Weather glyphs pre-rendered once per (glyph, theme) into Ui.px(Ui.WX_GLYPH) square bitmaps. The geometry is the
 * mockup's GLYPH svg (viewBox 48) converted by hand: lines, circles, and the cloud's circular arcs through a small
 * SVG-arc-to-arcTo helper (endpoint to centre parametrisation, so the curve matches the SVG exactly). Colours come
 * from Theme.cur(); a theme flip drops the cache and the next get() redraws. Main thread only.
 */
public final class WxGlyph {
    private WxGlyph() {}

    private static final float VB = 48f;
    private static final int N = 5;
    private static final Bitmap[][] cache = new Bitmap[2][N];   // [dark, light][glyph]
    private static int cachedSize;

    private static final Path MOON = new Path(), CLOUD = new Path(), RAIN_CLOUD = new Path();
    private static final RectF OVAL = new RectF();

    static {
        // moon: M35 31.5 A15 15 0 1 1 19.5 8.5  12 12 0 0 0 35 31.5 Z
        MOON.moveTo(35f, 31.5f);
        arc(MOON, 35f, 31.5f, 15f, true, true, 19.5f, 8.5f);
        arc(MOON, 19.5f, 8.5f, 12f, false, false, 35f, 31.5f);
        MOON.close();
        // cloud: M13 36 H34 A7.5 .. 35.5 21.2  A11 .. 14.2 19.4  A8.9 .. 13 36 Z
        CLOUD.moveTo(13f, 36f);
        CLOUD.lineTo(34f, 36f);
        arc(CLOUD, 34f, 36f, 7.5f, false, false, 35.5f, 21.2f);
        arc(CLOUD, 35.5f, 21.2f, 11f, false, false, 14.2f, 19.4f);
        arc(CLOUD, 14.2f, 19.4f, 8.9f, false, false, 13f, 36f);
        CLOUD.close();
        // rain cloud: M13 31 H34 A7 .. 35.4 17.2  A10.5 .. 14.2 15.6  A8.7 .. 13 31 Z
        RAIN_CLOUD.moveTo(13f, 31f);
        RAIN_CLOUD.lineTo(34f, 31f);
        arc(RAIN_CLOUD, 34f, 31f, 7f, false, false, 35.4f, 17.2f);
        arc(RAIN_CLOUD, 35.4f, 17.2f, 10.5f, false, false, 14.2f, 15.6f);
        arc(RAIN_CLOUD, 14.2f, 15.6f, 8.7f, false, false, 13f, 31f);
        RAIN_CLOUD.close();
    }

    /** Pre-rendered glyph for the live theme (WeatherParse.GLYPH_*); unknown ids draw the cloud. */
    public static Bitmap get(int glyph) {
        int g = glyph >= 0 && glyph < N ? glyph : WeatherParse.GLYPH_CLOUD;
        int size = Math.max(1, Ui.px(Ui.WX_GLYPH));
        if (size != cachedSize) {
            for (Bitmap[] row : cache) for (int i = 0; i < N; i++) row[i] = null;
            cachedSize = size;
        }
        int t = Theme.cur() == Theme.LIGHT ? 1 : 0;
        Bitmap b = cache[t][g];
        if (b == null) {
            b = render(g, size, Theme.cur());
            cache[t][g] = b;
        }
        return b;
    }

    private static Bitmap render(int glyph, int size, Theme th) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.scale(size / VB, size / VB);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        switch (glyph) {
            case WeatherParse.GLYPH_SUN: {
                stroke.setColor(th.wxSun);
                stroke.setStrokeWidth(3.4f);
                float[] rays = {
                    24, 4, 24, 9, 24, 39, 24, 44, 4, 24, 9, 24, 39, 24, 44, 24,
                    9.9f, 9.9f, 13.4f, 13.4f, 34.6f, 34.6f, 38.1f, 38.1f,
                    9.9f, 38.1f, 13.4f, 34.6f, 34.6f, 13.4f, 38.1f, 9.9f };
                c.drawLines(rays, stroke);
                fill.setColor(th.wxSun);
                c.drawCircle(24f, 24f, 10.5f, fill);
                fill.setColor(scaleAlpha(th.wxSunCore, 0.7f));
                c.drawCircle(21.5f, 21.5f, 5.5f, fill);
                break;
            }
            case WeatherParse.GLYPH_MOON:
                fill.setColor(th.wxMoon);
                c.drawPath(MOON, fill);
                break;
            case WeatherParse.GLYPH_RAIN: {
                cloud(c, RAIN_CLOUD, th, fill, stroke);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setColor(th.wxRain);
                stroke.setStrokeWidth(3.2f);
                float[] drops = { 17, 36, 14.6f, 42, 25, 36, 22.6f, 42, 33, 36, 30.6f, 42 };
                c.drawLines(drops, stroke);
                break;
            }
            case WeatherParse.GLYPH_HAZE: {
                fill.setColor(scaleAlpha(th.wxSun, 0.85f));
                c.drawCircle(24f, 18f, 9.5f, fill);
                stroke.setColor(th.wxHaze);
                stroke.setStrokeWidth(3.2f);
                float[] bars = { 8, 31, 40, 31, 12, 37, 36, 37, 17, 43, 31, 43 };
                c.drawLines(bars, stroke);
                break;
            }
            default:
                cloud(c, CLOUD, th, fill, stroke);
        }
        return bmp;
    }

    private static void cloud(Canvas c, Path p, Theme th, Paint fill, Paint stroke) {
        fill.setColor(th.wxCloud);
        c.drawPath(p, fill);
        if ((th.wxCloudEdge >>> 24) != 0) {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(1f);
            stroke.setColor(th.wxCloudEdge);
            c.drawPath(p, stroke);
        }
    }

    private static int scaleAlpha(int argb, float k) {
        int a = Math.round((argb >>> 24) * k);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** SVG circular arc (no rotation) from (x0,y0) to (x,y), appended to p whose current point is (x0,y0). */
    private static void arc(Path p, float x0, float y0, float r, boolean large, boolean sweep, float x, float y) {
        double dx = (x0 - x) / 2.0, dy = (y0 - y) / 2.0;
        double rr = r;
        double lam = (dx * dx + dy * dy) / (rr * rr);
        if (lam > 1.0) rr *= Math.sqrt(lam);
        double num = rr * rr * rr * rr - rr * rr * (dy * dy) - rr * rr * (dx * dx);
        double den = rr * rr * (dy * dy) + rr * rr * (dx * dx);
        double coef = (large == sweep ? -1.0 : 1.0) * Math.sqrt(Math.max(0.0, num / den));
        double cxp = coef * dy, cyp = -coef * dx;
        double cx = cxp + (x0 + x) / 2.0, cy = cyp + (y0 + y) / 2.0;
        double a1 = Math.atan2((dy - cyp) / rr, (dx - cxp) / rr);
        double a2 = Math.atan2((-dy - cyp) / rr, (-dx - cxp) / rr);
        double d = a2 - a1;
        if (sweep && d < 0) d += 2 * Math.PI;
        if (!sweep && d > 0) d -= 2 * Math.PI;
        OVAL.set((float) (cx - rr), (float) (cy - rr), (float) (cx + rr), (float) (cy + rr));
        p.arcTo(OVAL, (float) Math.toDegrees(a1), (float) Math.toDegrees(d), false);
    }
}
