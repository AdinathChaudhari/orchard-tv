package tv.orchard.home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Menu-key quick panel (PLAN 0.9): a MATCH_PARENT overlay (veil + thick-glass panel x 460..1460, y 60..376) with four
 * glass tiles (Settings, Return to Fire TV, Screensaver, Fire TV Settings) and a hint line. HomeActivity adds it once
 * and keeps it GONE while closed. The panel keeps its own focus index (tile 0 on open) and takes Android focus while
 * open (restored to the previous holder on close). LEFT/RIGHT move with a bump at the ends, UP/DOWN are consumed,
 * Select on UP closes the panel and then reports the tile. BACK and MENU are not consumed (HomeActivity closes).
 */
public class QuickPanel extends FrameLayout {
    /** Receives the tile the user selected (TILE_*). The panel closes itself first. */
    public interface Listener {
        void onPanelAction(int tile);
    }

    public static final int TILE_SETTINGS = 0, TILE_RESET = 1, TILE_SCREENSAVER = 2, TILE_FIRETV_SETTINGS = 3;
    private static final int TILES = 4;

    private static final int COLOUR_MS = 160;                       // -> Ui (mockup .ptile background/color transition)
    private static final float HINT_TOP_FACTOR = 0.97f;             // -> Ui (mockup #phint top = baseline - 23 * 0.97)
    private static final int LABEL_PAD_X_RELAXED = 8;               // -> Ui (the one-line sub may use a 184 px measure)
    private static final String HINT = "Menu or Back to close · hold Home for Amazon's menu";
    private static final String[] LABELS = {"Settings", "Return to\nFire TV", "Screensaver", "Fire TV\nSettings"};

    private final View veil;
    private final Body body;
    private final TextView hintView;
    private final Tile[] tiles = new Tile[TILES];
    private Listener listener;
    private boolean open;
    private int focus;
    private int gen;                                                  // bumped on open/close; stale end actions bail out
    private View prevFocus;
    private ObjectAnimator bumpAnim;
    private Tile bumpTile;
    private Theme appliedTheme;

    public QuickPanel(Context c) {
        super(c);
        setVisibility(GONE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setClipChildren(false);

        veil = new View(c);
        veil.setBackgroundColor(Theme.cur().veil);
        veil.setAlpha(0f);
        addView(veil, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        body = new Body(c);
        int pad = Glass.shadowPad();
        LayoutParams blp = new LayoutParams(Ui.px(Ui.PANEL_W) + 2 * pad, Ui.px(Ui.PANEL_H) + 2 * pad);
        blp.leftMargin = Ui.px(Ui.PANEL_X) - pad;
        blp.topMargin = Ui.px(Ui.PANEL_Y) - pad;
        addView(body, blp);

        int tpad = Tile.shadowPad();
        int tileSize = Ui.px(Ui.PANEL_TILE) + 2 * tpad;
        for (int i = 0; i < TILES; i++) {
            tiles[i] = new Tile(c, i);
            tiles[i].setContent(LABELS[i], null, null);
            LayoutParams tlp = new LayoutParams(tileSize, tileSize);
            tlp.leftMargin = pad + Ui.px(Ui.PANEL_TILE_X - Ui.PANEL_X + i * (Ui.PANEL_TILE + Ui.PANEL_GAP)) - tpad;
            tlp.topMargin = pad + Ui.px(Ui.PANEL_TILE_Y - Ui.PANEL_Y) - tpad;
            body.addView(tiles[i], tlp);
        }

        TextView hint = new TextView(c);
        hintView = hint;
        hint.setIncludeFontPadding(false);
        hint.setSingleLine(true);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(Ui.T_CAPTION2));
        hint.setTypeface(Ui.regular());
        hint.setLetterSpacing(Ui.tracking(Ui.T_CAPTION2));
        hint.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams hlp = new LayoutParams(Ui.px(Ui.PANEL_W), ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.leftMargin = pad;
        hlp.topMargin = pad + Math.round(Ui.pxf(Ui.PANEL_HINT_BASELINE - Ui.PANEL_Y - Ui.T_CAPTION2 * HINT_TOP_FACTOR));
        body.addView(hint, hlp);
        applyTheme();
    }

    /**
     * Re-tints the veil, the thick-glass panel, the hint line and the four tiles for Theme.cur() (colours change only
     * here, never animated). Idempotent (early return on the same palette) and ends with invalidate().
     */
    public void applyTheme() {
        Theme t = Theme.cur();
        if (t == appliedTheme) return;
        appliedTheme = t;
        veil.setBackgroundColor(t.veil);                              // the view alpha (PANEL_VEIL_ALPHA) is animated by open()
        hintView.setTextColor(t.text3);
        SpannableString s = new SpannableString(HINT);
        for (String key : new String[] {"Menu", "Back", "Home"}) {
            int at = HINT.indexOf(key);
            s.setSpan(new ForegroundColorSpan(t.text2), at, at + key.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new TypefaceSpan(Ui.medium()), at, at + key.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        hintView.setText(s);
        body.applyTheme();
        for (int i = 0; i < TILES; i++) tiles[i].applyTheme();
        invalidate();
    }

    public void setListener(Listener l) { listener = l; }

    /** Pre-blurred wallpaper used as the glass backdrop. */
    public void setWallpaper(Bitmap blurred) { body.setWallpaper(blurred); }

    /**
     * Reset tile state: redirect on/off, whether the WRITE_SECURE_SETTINGS grant exists, HoF target name.
     * Tile 2 label: "Return to\nFire TV" (redirect on) or one line "Turn redirect on" (so label + sub line stay at 2 lines); sub line "needs adb grant" without
     * the grant, else "goes to: <target>" while the redirect is off (falls back to an arrow form when it is too wide).
     */
    public void setResetState(boolean redirectOn, boolean hasGrant, String target) {
        String label = redirectOn ? "Return to\nFire TV" : "Turn redirect on";
        String sub = null, alt = null;
        if (!hasGrant) {
            sub = "needs adb grant";
        } else if (!redirectOn) {
            String t = target == null ? "" : target;
            sub = "goes to: " + t;
            alt = "→ " + t;
        }
        tiles[TILE_RESET].setContent(label, sub, alt);
    }

    public void open() {
        View root = getRootView();
        View f = root == null ? null : root.findFocus();
        if (f != null && f != this && !isDescendant(f)) prevFocus = f;
        cancelAnims();
        gen++;
        open = true;
        boolean reduced = TileView.isReducedMotion();
        for (int i = 0; i < TILES; i++) tiles[i].setFocused(i == 0, false);
        focus = 0;
        body.setFocusIndex(0);
        setVisibility(VISIBLE);

        veil.setAlpha(0f);
        veil.animate().alpha(Ui.PANEL_VEIL_ALPHA)
            .setDuration(Ui.ms(reduced ? Ui.REDUCED_FADE_MS : Ui.VEIL_FADE_MS))
            .setInterpolator(null).start();
        if (reduced) {
            body.setTranslationY(0f);
            body.setAlpha(0f);
            body.animate().alpha(1f).setDuration(Ui.ms(Ui.REDUCED_FADE_MS)).setInterpolator(null)
                .setUpdateListener(null).start();
        } else {
            body.setAlpha(1f);
            body.setTranslationY(Ui.pxf(Ui.PANEL_SLIDE_FROM));
            body.animate().translationY(0f).setDuration(Ui.ms(Ui.PANEL_IN_MS)).setInterpolator(Ui.panelIn())
                .setUpdateListener(a -> body.invalidate()).start();
        }
        requestFocus();
    }

    /** Slides out, then runs after (may be null). Called while already closed, it just runs after. */
    public void close(Runnable after) {
        if (!open) {
            if (after != null) after.run();
            return;
        }
        open = false;
        final int g = ++gen;
        boolean reduced = TileView.isReducedMotion();
        veil.animate().alpha(0f).setDuration(Ui.ms(reduced ? Ui.REDUCED_FADE_MS : Ui.VEIL_FADE_MS))
            .setInterpolator(null).start();
        if (reduced) {
            body.animate().alpha(0f).setDuration(Ui.ms(Ui.REDUCED_FADE_MS)).setInterpolator(null)
                .setUpdateListener(null).withEndAction(() -> finishClose(g, after)).start();
        } else {
            body.animate().translationY(Ui.pxf(Ui.PANEL_SLIDE_FROM)).setDuration(Ui.ms(Ui.PANEL_OUT_MS))
                .setInterpolator(Ui.panelOut()).setUpdateListener(a -> body.invalidate())
                .withEndAction(() -> finishClose(g, after)).start();
        }
    }

    public boolean isOpen() { return open; }

    /** The highlighted tile (TILE_*), valid while open. */
    public int focusedTile() { return focus; }

    private void finishClose(int g, Runnable after) {
        if (g != gen) return;
        cancelBump();
        View pf = prevFocus;
        prevFocus = null;
        if (pf != null && pf.isAttachedToWindow() && pf.getVisibility() == VISIBLE) pf.requestFocus();
        setVisibility(GONE);
        for (int i = 0; i < TILES; i++) tiles[i].setFocused(false, false);
        if (after != null) after.run();
    }

    private void cancelAnims() {
        veil.animate().cancel();
        body.animate().cancel();
        cancelBump();
    }

    private void cancelBump() {
        if (bumpAnim != null) {
            bumpAnim.cancel();
            bumpAnim = null;
        }
        if (bumpTile != null) {
            bumpTile.setTranslationX(0f);
            bumpTile = null;
        }
    }

    private boolean isDescendant(View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p == this) return true;
        }
        return false;
    }

    // ---- keys ---------------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!open) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) return false;   // HomeActivity closes
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!down && !e.isCanceled()) activate();
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void move(int d) {
        int n = focus + d;
        if (n < 0 || n >= TILES) {
            bump(tiles[focus], d);
            return;
        }
        tiles[focus].setFocused(false, true);
        focus = n;
        tiles[n].setFocused(true, true);
        body.setFocusIndex(n);
    }

    private void bump(Tile t, int d) {
        if (TileView.isReducedMotion()) return;
        cancelBump();
        bumpTile = t;
        bumpAnim = ObjectAnimator.ofFloat(t, View.TRANSLATION_X, 0f, d * Ui.pxf(Ui.BUMP_PX), 0f);
        bumpAnim.setDuration(Ui.ms(Ui.BUMP_MS));
        bumpAnim.start();
    }

    private void activate() {
        final int i = focus;
        final Listener l = listener;
        close(() -> {
            if (l != null) l.onPanelAction(i);
        });
    }

    @Override
    public boolean hasOverlappingRendering() { return false; }

    // ---- the glass panel ----------------------------------------------------------------------------------------

    /** Glass body (x 460..1460, y 60..376 plus the drop-shadow pad); samples the wallpaper at its real screen position. */
    private static final class Body extends FrameLayout {
        private final Glass glass = new Glass(Ui.PANEL_R, Theme.cur().glassThick, 1f);
        private final int[] loc = new int[2];
        private final int screenW, screenH, pad;
        private Bitmap wallpaper;
        private int focusIdx;

        Body(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            setChildrenDrawingOrderEnabled(true);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
            pad = Glass.shadowPad();
            glass.setSize(Ui.px(Ui.PANEL_W), Ui.px(Ui.PANEL_H));
        }

        void setWallpaper(Bitmap b) {
            wallpaper = b;
            invalidate();
        }

        void applyTheme() {
            glass.applyTheme(Theme.cur().glassThick);
            invalidate();
        }

        void setFocusIndex(int i) {
            focusIdx = i;
            invalidate();
        }

        @Override
        protected int getChildDrawingOrder(int childCount, int drawingPosition) {
            int f = focusIdx;
            if (f < 0 || f >= childCount) return drawingPosition;
            if (drawingPosition == childCount - 1) return f;                  // focused tile last
            return drawingPosition >= f ? drawingPosition + 1 : drawingPosition;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            getLocationOnScreen(loc);
            // layout rect: exclude the slide-in translation so the glass does not slide against the wallpaper
            glass.draw(canvas, pad, pad, wallpaper, loc[0] + pad, loc[1] - Math.round(getTranslationY()) + pad, screenW, screenH);
        }

        @Override
        public boolean hasOverlappingRendering() { return false; }
    }

    // ---- one tile ------------------------------------------------------------------------------------------------------

    /**
     * A 200x200 glass tile (glass_thin fill + gradient rim; focused = near-white fill, dark ink, 1.08x, rim off,
     * pre-rendered drop shadow). The view is larger than the tile by shadowPad() on every side for the shadow.
     */
    private static final class Tile extends View {
        private static Bitmap sharedShadow;
        private static int sharedShadowKey;

        private final int index;
        private final int pad, tilePx, iconBox;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint.FontMetrics fm = new Paint.FontMetrics();
        private final RectF rect = new RectF();
        private final RectF rimRect = new RectF();
        private final Path icon;
        private final float[] lineY = new float[3];
        private float radius, rimRadius, iconX, iconY, subY, textX;
        private String[] lines = {""};
        private String sub;
        private Bitmap shadow;
        private Theme rimTheme;
        private float p;                                               // focus colour / shadow progress 0..1
        private boolean focused;
        private ValueAnimator colourAnim;

        Tile(Context c, int index) {
            super(c);
            this.index = index;
            pad = shadowPad();
            tilePx = Ui.px(Ui.PANEL_TILE);
            iconBox = Ui.px(Ui.PANEL_ICON);
            icon = Glyphs.forTile(index, iconBox);
            outlinePaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(Glyphs.strokeWidth(iconBox));
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            iconPaint.setStrokeJoin(Paint.Join.ROUND);
            textPaint.setTypeface(Ui.medium());
            textPaint.setTextSize(Ui.px(Ui.T_CAPTION2));
            textPaint.setLetterSpacing(Ui.tracking(Ui.T_CAPTION2));
            textPaint.setTextAlign(Paint.Align.CENTER);
            subPaint.setTypeface(Ui.regular());
            subPaint.setTextSize(Ui.px(Ui.T_CAPTION2));
            subPaint.setLetterSpacing(Ui.tracking(Ui.T_CAPTION2));
            subPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.getFontMetrics(fm);
            setFocusable(false);
        }

        static int shadowPad() { return Ui.px(Ui.SHADOW_Y + Ui.SHADOW_BLUR); }

        void setContent(String label, String subText, String subAlt) {
            lines = label.split("\n");
            if (lines.length > 2) lines = new String[] {lines[0], lines[1]};
            String s = subText;
            if (s != null) {
                float full = Ui.px(Ui.PANEL_TILE - 2 * LABEL_PAD_X_RELAXED);
                if (subPaint.measureText(s) > full && subAlt != null) s = subAlt;
            }
            sub = s;
            StringBuilder d = new StringBuilder();
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) d.append(' ');
                d.append(lines[i]);
            }
            if (sub != null) d.append(" (").append(sub).append(')');
            setContentDescription(d.toString());
            layoutText();
            invalidate();
        }

        /** Baselines of the label lines and the sub line (mockup: icon 44 + 60, gap 12, line height 1.15, sub -8). */
        private void layoutText() {
            float lh = textPaint.getTextSize() * Ui.PANEL_LINE_HEIGHT;
            float top = pad + Ui.px(Ui.PANEL_ICON_TOP + Ui.PANEL_ICON + Ui.PANEL_LABEL_GAP);
            float inner = (lh - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            for (int i = 0; i < lines.length; i++) lineY[i] = top + i * lh + inner;
            subY = top + lines.length * lh + Ui.pxf(Ui.PANEL_SUB_GAP) + inner;
        }

        void setFocused(boolean f, boolean animate) {
            if (colourAnim != null) {
                colourAnim.cancel();
                colourAnim = null;
            }
            animate().cancel();
            boolean reduced = TileView.isReducedMotion();
            float target = f ? 1f : 0f;
            float scale = f ? (reduced ? Ui.REDUCED_SCALE : Ui.PANEL_FOCUS_SCALE) : 1f;
            boolean changed = f != focused;
            focused = f;
            if (!animate || reduced || !changed) {
                p = target;
                setScaleX(scale);
                setScaleY(scale);
                invalidate();
                return;
            }
            colourAnim = ValueAnimator.ofFloat(p, target);
            colourAnim.setDuration(Ui.ms(COLOUR_MS));
            colourAnim.addUpdateListener(a -> {
                p = (Float) a.getAnimatedValue();
                invalidate();
            });
            colourAnim.start();
            animate().scaleX(scale).scaleY(scale)
                .setDuration(Ui.ms(f ? Ui.FOCUS_IN_MS : Ui.FOCUS_OUT_MS))
                .setInterpolator(f ? Ui.focusIn() : Ui.focusOut()).start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = tilePx + 2 * pad;
            setMeasuredDimension(s, s);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            rect.set(pad, pad, pad + tilePx, pad + tilePx);
            radius = Ui.pxf(Ui.PANEL_TILE_R);
            float stroke = Math.max(1f, Ui.pxf(1));
            rimRect.set(rect.left + stroke / 2f, rect.top + stroke / 2f, rect.right - stroke / 2f, rect.bottom - stroke / 2f);
            rimRadius = Math.max(0f, radius - stroke / 2f);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(stroke);
            rimTheme = null;
            applyTheme();
            shadow = shadowBitmap(tilePx, pad, radius);
            iconX = rect.left + (tilePx - iconBox) / 2f;
            iconY = rect.top + Ui.px(Ui.PANEL_ICON_TOP);
            textX = rect.centerX();
            layoutText();
        }

        /** Rebuilds the rim gradient for Theme.cur() (the only palette-dependent paint object); idempotent. */
        void applyTheme() {
            Theme t = Theme.cur();
            if (t == rimTheme) return;
            rimTheme = t;
            if (rect.isEmpty()) return;                                // onSizeChanged applies it
            rimPaint.setShader(Glass.rimShader(t, rect.centerX(), rect.centerY(), tilePx, tilePx));
            invalidate();
        }

        /** One ALPHA_8 bitmap shared by the four tiles: blurred rounded rect, shifted down, interior cut out. */
        private static Bitmap shadowBitmap(int tilePx, int pad, float radius) {
            int key = tilePx * 31 + pad;
            if (sharedShadow != null && sharedShadowKey == key) return sharedShadow;
            int size = tilePx + 2 * pad;
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8);
            Canvas c = new Canvas(b);
            float sigma = Ui.pxf(Ui.SHADOW_BLUR) / 2f;
            float skiaRadius = Math.max(1f, (sigma - 0.5f) / 0.57735f);
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(0xFF000000);
            sp.setMaskFilter(new BlurMaskFilter(skiaRadius, BlurMaskFilter.Blur.NORMAL));
            float drop = Ui.pxf(Ui.SHADOW_Y);
            c.drawRoundRect(new RectF(pad, pad + drop, pad + tilePx, pad + drop + tilePx), radius, radius, sp);
            Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
            cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            c.drawRoundRect(new RectF(pad, pad, pad + tilePx, pad + tilePx), radius, radius, cut);
            sharedShadow = b;
            sharedShadowKey = key;
            return b;
        }

        private static int lerp(int a, int b, float t) {
            int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
            return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
        }

        @Override
        protected void onDraw(Canvas c) {
            Theme t = Theme.cur();
            if (p > Ui.DRAW_MIN_PROGRESS && shadow != null) {
                shadowPaint.setColor(t.shadow);
                shadowPaint.setAlpha(Math.round(Color.alpha(t.shadow) * p));
                c.drawBitmap(shadow, 0f, 0f, shadowPaint);
            }
            // light: unfocused tiles keep a visible shape (mockup: rgba(27,27,31,.07) + 2 px inset ctl-outline)
            fillPaint.setColor(lerp(t.light ? Theme.argb(.07f, 0x1B1B1F) : t.glassThin, t.btnFocusFill, p));
            c.drawRoundRect(rect, radius, radius, fillPaint);
            if (t.light && p < 0.999f) {
                float o = Math.max(2f, Ui.pxf(2));
                outlinePaint.setStrokeWidth(o);
                outlinePaint.setColor(Theme.argb(Color.alpha(t.ctlOutline) / 255f * (1f - p), t.ctlOutline));
                c.drawRoundRect(rect.left + o / 2f, rect.top + o / 2f, rect.right - o / 2f, rect.bottom - o / 2f,
                    Math.max(0f, radius - o / 2f), Math.max(0f, radius - o / 2f), outlinePaint);
            }
            if (p < 0.999f) {
                rimPaint.setAlpha(Math.round(255f * Ui.PANEL_RIM_ALPHA * (1f - p)));
                c.drawRoundRect(rimRect, rimRadius, rimRadius, rimPaint);
            }
            int ink = lerp(t.text, t.btnFocusText, p);
            iconPaint.setColor(ink);
            int save = c.save();
            c.translate(iconX, iconY);
            c.drawPath(icon, iconPaint);
            c.restoreToCount(save);
            textPaint.setColor(ink);
            for (int i = 0; i < lines.length; i++) c.drawText(lines[i], textX, lineY[i], textPaint);
            if (sub != null) {
                subPaint.setColor(Ui.argb(Ui.PANEL_SUB_ALPHA, ink));
                c.drawText(sub, textX, subY, subPaint);
            }
        }

        @Override
        public boolean hasOverlappingRendering() { return false; }

        @Override
        public String toString() { return "QuickPanel.Tile#" + index; }
    }
}
