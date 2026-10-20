package tv.orchard.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.view.View;

/**
 * The dock's glass band (x 40..1880, y 576..772, radius 32). The view is larger than the band by
 * Glass.shadowPad() on every side so the drop shadow stays inside its bounds: place it at
 * (Ui.px(40) - pad(), Ui.px(576) - pad()) with size viewWidth() x viewHeight(). Not focusable.
 */
public class DockBand extends View {
    private final Glass glass = new Glass(Ui.DOCK_BAND_R, Ui.DOCK_FILL, Ui.DOCK_SLICE_ALPHA);
    private final int[] loc = new int[2];
    private final int screenW, screenH;
    private Bitmap wallpaper;

    public DockBand(Context context) {
        super(context);
        setFocusable(false);
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        glass.setSize(bandWidth(), bandHeight());
    }

    /** Alpha fade (P4 4.1) without an offscreen layer, like TileView. */
    @Override
    public boolean hasOverlappingRendering() { return false; }

    public static int pad() { return Glass.shadowPad(); }
    public static int bandWidth() { return Ui.px(Ui.DOCK_BAND_X1 - Ui.DOCK_BAND_X0); }
    public static int bandHeight() {
        return Ui.px(Ui.DOCK_BAND_PAD + Ui.TILE_H + Ui.DOCK_BAND_PAD_BOTTOM);
    }
    public static int viewWidth() { return bandWidth() + 2 * pad(); }
    public static int viewHeight() { return bandHeight() + 2 * pad(); }

    /** The 240x135 pre-blurred wallpaper (Wallpaper.blurred()); null draws no slice. */
    public void setWallpaper(Bitmap blurred) {
        wallpaper = blurred;
        invalidate();
    }

    /** Call when the band moved on screen (vertical scroll): the wallpaper slice under it changes. */
    public void onScrolled() { invalidate(); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(viewWidth(), viewHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        getLocationOnScreen(loc);
        int p = pad();
        glass.draw(canvas, p, p, wallpaper, loc[0] + p, loc[1] + p, screenW, screenH);
    }
}
