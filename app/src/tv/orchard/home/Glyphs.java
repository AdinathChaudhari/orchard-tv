package tv.orchard.home;

import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Stroke glyphs of the Quick Panel (mockup mock.js GEAR / HOUSE / MOON / TVSET), authored on a 48-unit viewBox
 * and scaled to a square box of device px. Draw them with a STROKE paint of {@link #strokeWidth(float)}, round caps
 * and joins, no fill. Build each Path once per size (these builders allocate); never call them from onDraw.
 */
public final class Glyphs {
    private Glyphs() {}

    public static final float VIEWBOX = Ui.FTH_VIEWBOX;             // 48
    public static final float STROKE_UNITS = Ui.FTH_STROKE_UNITS;   // 2.6

    /** Stroke width in px for a glyph drawn into a box of boxPx. */
    public static float strokeWidth(float boxPx) {
        return STROKE_UNITS * boxPx / VIEWBOX;
    }

    /** Settings gear: 32-point star (radius 17 / 13) plus a centre circle r6. */
    public static Path gear(float boxPx) {
        Path p = new Path();
        for (int i = 0; i < 32; i++) {
            double a = i * Math.PI / 16.0 - Math.PI / 32.0;
            float r = (i % 4 < 2) ? 17f : 13f;
            float x = 24f + (float) (r * Math.cos(a));
            float y = 24f + (float) (r * Math.sin(a));
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        p.addCircle(24f, 24f, 6f, Path.Direction.CW);
        return scaled(p, boxPx);
    }

    /** House: M6 22 L24 7 L42 22 M10 19 V40 H20 V28 H28 V40 H38 V19. */
    public static Path house(float boxPx) {
        Path p = new Path();
        p.moveTo(6f, 22f);
        p.lineTo(24f, 7f);
        p.lineTo(42f, 22f);
        p.moveTo(10f, 19f);
        p.lineTo(10f, 40f);
        p.lineTo(20f, 40f);
        p.lineTo(20f, 28f);
        p.lineTo(28f, 28f);
        p.lineTo(28f, 40f);
        p.lineTo(38f, 40f);
        p.lineTo(38f, 19f);
        return scaled(p, boxPx);
    }

    /** Crescent: M33 30 A14 14 0 1 1 20 11 A11 11 0 0 0 33 30 Z (exact SVG arcs). */
    public static Path moon(float boxPx) {
        Path p = new Path();
        p.moveTo(33f, 30f);
        svgArc(p, 33f, 30f, 14f, 14f, true, true, 20f, 11f);
        svgArc(p, 20f, 11f, 11f, 11f, false, false, 33f, 30f);
        p.close();
        return scaled(p, boxPx);
    }

    /** TV set: rect 6,9 36x25 r4; M16 41 H32; M24 34 V41; circle 24,21.5 r4. */
    public static Path tv(float boxPx) {
        Path p = new Path();
        p.addRoundRect(new RectF(6f, 9f, 42f, 34f), 4f, 4f, Path.Direction.CW);
        p.moveTo(16f, 41f);
        p.lineTo(32f, 41f);
        p.moveTo(24f, 34f);
        p.lineTo(24f, 41f);
        p.addCircle(24f, 21.5f, 4f, Path.Direction.CW);
        return scaled(p, boxPx);
    }

    /** The glyph of a QuickPanel tile (QuickPanel.TILE_*). */
    public static Path forTile(int tile, float boxPx) {
        switch (tile) {
            case QuickPanel.TILE_SETTINGS: return gear(boxPx);
            case QuickPanel.TILE_RESET: return house(boxPx);
            case QuickPanel.TILE_SCREENSAVER: return moon(boxPx);
            default: return tv(boxPx);
        }
    }

    private static Path scaled(Path p, float boxPx) {
        Matrix m = new Matrix();
        float s = boxPx / VIEWBOX;
        m.setScale(s, s);
        p.transform(m);
        return p;
    }

    /** SVG endpoint-arc (x-axis rotation 0) appended to a path whose current point is (x0, y0). */
    private static void svgArc(Path p, float x0, float y0, float rxIn, float ryIn, boolean large, boolean sweep,
                               float x1, float y1) {
        double rx = Math.abs(rxIn), ry = Math.abs(ryIn);
        double dx2 = (x0 - x1) / 2.0, dy2 = (y0 - y1) / 2.0;
        double lambda = dx2 * dx2 / (rx * rx) + dy2 * dy2 / (ry * ry);
        if (lambda > 1.0) {
            double sc = Math.sqrt(lambda);
            rx *= sc;
            ry *= sc;
        }
        double num = rx * rx * ry * ry - rx * rx * dy2 * dy2 - ry * ry * dx2 * dx2;
        double den = rx * rx * dy2 * dy2 + ry * ry * dx2 * dx2;
        double coef = Math.sqrt(Math.max(0.0, num / den)) * (large == sweep ? -1.0 : 1.0);
        double cxp = coef * rx * dy2 / ry;
        double cyp = -coef * ry * dx2 / rx;
        double cx = cxp + (x0 + x1) / 2.0, cy = cyp + (y0 + y1) / 2.0;
        double t1 = Math.atan2((dy2 - cyp) / ry, (dx2 - cxp) / rx);
        double t2 = Math.atan2((-dy2 - cyp) / ry, (-dx2 - cxp) / rx);
        double dt = t2 - t1;
        if (sweep && dt < 0) dt += 2 * Math.PI;
        else if (!sweep && dt > 0) dt -= 2 * Math.PI;
        p.arcTo(new RectF((float) (cx - rx), (float) (cy - ry), (float) (cx + rx), (float) (cy + ry)),
            (float) Math.toDegrees(t1), (float) Math.toDegrees(dt), false);
    }
}
