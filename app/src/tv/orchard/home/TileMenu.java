package tv.orchard.home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.util.Property;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Tile context menu (PLAN 5.3): a MATCH_PARENT overlay with a 50% black veil and a thick-glass card next to the
 * focused tile. The menu never takes view focus (the tile stays focused and scaled behind the veil); the activity
 * routes every key to {@link #handleKey} while it is open. UP/DOWN move between rows (bump at the ends), LEFT/RIGHT
 * are consumed, Select on UP fires the row (a disabled row only bumps), Back / Menu on UP close it.
 */
public class TileMenu extends FrameLayout {
    public static final int ADD_DOCK = 1, REMOVE_DOCK = 2, MOVE_LEFT = 3, MOVE_RIGHT = 4, MOVE = 5, HIDE = 6, APP_INFO = 7;

    public interface Listener {
        /** Called on Select-UP over an enabled row. The activity decides whether to dismiss. */
        void onMenuAction(int action);
    }

    private static final int COLOUR_MS = 160;                       // same as ConfirmSheet buttons
    private static final int HEADER_H = 40, ROW_TEXT_PAD = 28;       // design px

    private final View veil;
    private final Card card;
    private final TextView header;
    private Row[] rows = new Row[0];
    private int[] actions = new int[0];
    private int focus;
    private Listener listener;
    private Runnable onClosed;
    private boolean shown, dismissing, closedDone, selectDown;
    private ObjectAnimator bumpAnim;
    private Row bumpRow;
    private Theme appliedTheme;

    public TileMenu(Context c) {
        super(c);
        setFocusable(false);
        setClipChildren(false);

        veil = new View(c);
        veil.setBackgroundColor(Theme.cur().veil);
        veil.setAlpha(0f);
        addView(veil, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        card = new Card(c);
        card.setAlpha(0f);
        addView(card, new LayoutParams(1, 1, Gravity.TOP | Gravity.START));

        header = new TextView(c);
        header.setIncludeFontPadding(false);
        header.setSingleLine(true);
        header.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.setTextColor(Theme.cur().text2);
        header.setTypeface(Ui.medium());
        header.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(Ui.T_CAPTION2));
        header.setLetterSpacing(Ui.tracking(Ui.T_CAPTION2));
        header.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(header);
    }

    /**
     * Re-tints the veil (same as the quick panel), the thick-glass card, the header and the rows for Theme.cur()
     * (colours change only here, never animated). Idempotent (early return on the same palette), ends with invalidate().
     */
    public void applyTheme() {
        Theme t = Theme.cur();
        if (t == appliedTheme) return;
        appliedTheme = t;
        veil.setBackgroundColor(t.veil);                              // view alpha PANEL_VEIL_ALPHA is animated by playIn()
        header.setTextColor(t.text2);
        card.applyTheme();
        for (Row r : rows) r.applyTheme();
        invalidate();
    }

    public void setListener(Listener l) { listener = l; }

    /** Runs once after the menu faded out and was removed from its parent. */
    public void setOnClosed(Runnable r) { onClosed = r; }

    public boolean isShowing() { return shown && !dismissing; }

    /** Drops (or swaps) the blurred backdrop, e.g. onStop. */
    public void setWallpaper(Bitmap blurred) { card.setWallpaper(blurred); }

    /**
     * Lays out and fades in. actions/labels/enabled are parallel arrays (at least one row). tileOnScreen is the
     * tile's on-screen rect at its SCALED size (the menu overlay starts at screen 0,0). blurred may be null.
     */
    public void show(String title, int[] actions, String[] labels, boolean[] enabled, Rect tileOnScreen, Bitmap blurred) {
        int n = Math.min(actions.length, Math.min(labels.length, enabled.length));
        this.actions = actions;
        Context c = getContext();
        for (int i = 0; i < rows.length; i++) card.removeView(rows[i]);
        rows = new Row[n];

        int pad = Glass.shadowPad();
        int cardW = Ui.px(Ui.CTX_W);
        int cardH = Ui.px(2 * Ui.CTX_PAD + HEADER_H + n * Ui.CTX_ROW_H + Math.max(0, n - 1) * Ui.CTX_ROW_GAP);
        int rowW = Ui.px(Ui.CTX_W - 2 * Ui.CTX_PAD);

        header.setText(title == null ? "" : title);
        card.setLayoutParams(placed(tileOnScreen, cardW, cardH, pad));
        card.setWallpaper(blurred);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(rowW - Ui.px(ROW_TEXT_PAD), Ui.px(HEADER_H));
        hp.leftMargin = pad + Ui.px(Ui.CTX_PAD) + Ui.px(ROW_TEXT_PAD);
        hp.topMargin = pad + Ui.px(Ui.CTX_PAD);
        header.setLayoutParams(hp);

        int first = -1;
        for (int i = 0; i < n; i++) {
            Row r = new Row(c, labels[i], enabled[i], rowW, Ui.px(Ui.CTX_ROW_H));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(rowW, Ui.px(Ui.CTX_ROW_H));
            lp.leftMargin = pad + Ui.px(Ui.CTX_PAD);
            lp.topMargin = pad + Ui.px(Ui.CTX_PAD + HEADER_H + i * (Ui.CTX_ROW_H + Ui.CTX_ROW_GAP));
            card.addView(r, lp);
            rows[i] = r;
            if (first < 0 && enabled[i]) first = i;
        }
        focus = Math.max(0, first);
        applyTheme();
        for (int i = 0; i < n; i++) {
            rows[i].applyTheme();
            rows[i].setFocused(i == focus, false);
        }
        playIn();
    }

    /** Centred under the tile (below its scaled bottom + label), above it when that would pass y 1020; x clamped 80..1840. */
    private static LayoutParams placed(Rect t, int cardW, int cardH, int pad) {
        int labelH = Ui.px(Ui.LABEL_GAP + Ui.T_BODY);
        int gap = Ui.px(Ui.CTX_GAP);
        int left = t.centerX() - cardW / 2;
        left = Math.max(Ui.px(80), Math.min(Ui.px(1840) - cardW, left));
        int top = t.bottom + labelH + gap;
        if (top + cardH > Ui.px(1020)) top = t.top - gap - cardH;
        top = Math.max(0, top);
        LayoutParams lp = new LayoutParams(cardW + 2 * pad, cardH + 2 * pad, Gravity.TOP | Gravity.START);
        lp.leftMargin = left - pad;
        lp.topMargin = top - pad;
        return lp;
    }

    private void playIn() {
        if (shown) return;
        shown = true;
        boolean reduced = TileView.isReducedMotion();
        long fade = Ui.ms(reduced ? Ui.REDUCED_FADE_MS : Ui.CTX_IN_MS);
        veil.animate().alpha(Ui.PANEL_VEIL_ALPHA).setDuration(Ui.ms(reduced ? Ui.REDUCED_FADE_MS : Ui.VEIL_FADE_MS))
            .setInterpolator(null).start();
        card.setAlpha(0f);
        if (reduced) {
            card.setScaleX(1f);
            card.setScaleY(1f);
        } else {
            card.setScaleX(0.94f);
            card.setScaleY(0.94f);
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(fade).setInterpolator(Ui.focusIn()).start();
            return;
        }
        card.animate().alpha(1f).setDuration(fade).setInterpolator(null).start();
    }

    /** Fades out, removes itself from its parent, then runs the onClosed callback. */
    public void dismiss() { dismiss(true); }

    public void dismiss(boolean animate) {
        if (dismissing) return;
        dismissing = true;
        if (!animate || !shown || !isAttachedToWindow()) {
            closed();
            return;
        }
        boolean reduced = TileView.isReducedMotion();
        veil.animate().alpha(0f).setDuration(Ui.ms(reduced ? Ui.REDUCED_FADE_MS : Ui.VEIL_FADE_MS)).setInterpolator(null).start();
        card.animate().alpha(0f).setDuration(Ui.ms(reduced ? Ui.REDUCED_FADE_MS : Ui.SHEET_FADE_MS)).setInterpolator(null)
            .withEndAction(this::closed).start();
    }

    private void closed() {
        if (closedDone) return;
        closedDone = true;
        cancelBump();
        if (getParent() instanceof ViewGroup) ((ViewGroup) getParent()).removeView(this);
        Runnable r = onClosed;
        onClosed = null;
        if (r != null) r.run();
    }

    // ---- keys ---------------------------------------------------------------------------------------------------------

    /** Returns true when the key was consumed (all D-pad, Select, Back and Menu keys; others pass through). */
    public boolean handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                if (!down && !e.isCanceled()) dismiss();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    selectDown = true;                                // an UP without our DOWN is ignored
                } else {
                    boolean was = selectDown;
                    selectDown = false;
                    if (was && !e.isCanceled()) activate(focus);
                }
                return true;
            default:
                return false;
        }
    }

    private void move(int d) {
        if (dismissing || rows.length == 0) return;
        int n = focus + d;
        if (n < 0 || n >= rows.length) {
            bump(rows[focus], View.TRANSLATION_Y, d);
            return;
        }
        rows[focus].setFocused(false, true);
        focus = n;
        rows[n].setFocused(true, true);
    }

    private void activate(int i) {
        if (dismissing || i < 0 || i >= rows.length) return;
        if (!rows[i].enabled) {
            bump(rows[i], View.TRANSLATION_X, 1);
            return;
        }
        if (listener != null) listener.onMenuAction(actions[i]);
    }

    private void bump(Row r, Property<View, Float> axis, int d) {
        if (TileView.isReducedMotion()) return;
        cancelBump();
        bumpRow = r;
        bumpAnim = ObjectAnimator.ofFloat(r, axis, 0f, d * Ui.pxf(Ui.BUMP_PX), 0f);
        bumpAnim.setDuration(Ui.ms(Ui.BUMP_MS));
        bumpAnim.start();
    }

    private void cancelBump() {
        if (bumpAnim != null) {
            bumpAnim.cancel();
            bumpAnim = null;
        }
        if (bumpRow != null) {
            bumpRow.setTranslationX(0f);
            bumpRow.setTranslationY(0f);
            bumpRow = null;
        }
    }

    @Override
    public boolean hasOverlappingRendering() { return false; }

    // ---- the glass card ----------------------------------------------------------------------------------------------

    /** Thick glass; the view is larger than the card by Glass.shadowPad() on every side. Slice sampled at the LAYOUT rect. */
    private static final class Card extends FrameLayout {
        private final Glass glass = new Glass(Ui.CTX_R, Theme.cur().glassThick, 1f);
        private final int[] loc = new int[2];
        private final int screenW, screenH, pad;
        private Bitmap wallpaper;

        Card(Context c) {
            super(c);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
            pad = Glass.shadowPad();
        }

        void setWallpaper(Bitmap b) {
            wallpaper = b;
            invalidate();
        }

        void applyTheme() {
            glass.applyTheme(Theme.cur().glassThick);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            glass.setSize(w - 2 * pad, h - 2 * pad);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            View parent = (View) getParent();
            int px = 0, py = 0;
            if (parent != null) {
                parent.getLocationOnScreen(loc);
                px = loc[0];
                py = loc[1];
            }
            glass.draw(canvas, pad, pad, wallpaper, px + getLeft() + pad, py + getTop() + pad, screenW, screenH);
        }

        @Override
        public boolean hasOverlappingRendering() { return false; }
    }

    // ---- one row -----------------------------------------------------------------------------------------------------

    /** Glass row (ConfirmSheet button look): GLASS fill + gradient rim; focused = near-white fill, dark text, 1.02x. */
    private static final class Row extends View {
        private static final int DISABLED_FOCUS_TEXT = 0x80111111;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint.FontMetrics fm = new Paint.FontMetrics();
        private final RectF rect = new RectF();
        private final RectF rimRect = new RectF();
        private final String text;
        final boolean enabled;
        private final int w, h;
        private float radius, rimRadius, textY, textX;
        private Theme rimTheme;
        private float p;
        private boolean focused;
        private ValueAnimator colourAnim;

        Row(Context c, String label, boolean enabled, int wPx, int hPx) {
            super(c);
            text = label;
            this.enabled = enabled;
            w = wPx;
            h = hPx;
            textPaint.setTypeface(Ui.medium());
            textPaint.setTextSize(Ui.px(Ui.T_BODY));
            textPaint.setLetterSpacing(Ui.tracking(Ui.T_BODY));
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.getFontMetrics(fm);
            setContentDescription(label);
            setFocusable(false);
        }

        void setFocused(boolean f, boolean animate) {
            if (colourAnim != null) {
                colourAnim.cancel();
                colourAnim = null;
            }
            animate().cancel();
            boolean reduced = TileView.isReducedMotion();
            float target = f ? 1f : 0f;
            float scale = (f && !reduced) ? Ui.LIST_FOCUS_SCALE : 1f;
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
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onSizeChanged(int vw, int vh, int ow, int oh) {
            rect.set(0, 0, w, h);
            radius = Math.min(Ui.pxf(Ui.CTX_ROW_R), h / 2f);
            float stroke = Math.max(1f, Ui.pxf(1));
            rimRect.set(stroke / 2f, stroke / 2f, w - stroke / 2f, h - stroke / 2f);
            rimRadius = Math.max(0f, radius - stroke / 2f);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(stroke);
            rimTheme = null;
            applyTheme();
            textY = h / 2f - (fm.ascent + fm.descent) / 2f;
            textX = Ui.pxf(ROW_TEXT_PAD);
        }

        /** Rebuilds the rim gradient for Theme.cur(); idempotent. */
        void applyTheme() {
            Theme t = Theme.cur();
            if (t == rimTheme) return;
            rimTheme = t;
            if (rect.isEmpty()) return;                                // onSizeChanged applies it
            rimPaint.setShader(Glass.rimShader(t, w / 2f, h / 2f, w, h));
            invalidate();
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
            // light: unfocused rows are a faint ink wash (mockup :root[data-theme=light] .row:not(.focused))
            fillPaint.setColor(lerp(t.light ? Theme.argb(.06f, 0x1B1B1F) : t.glassThin, t.btnFocusFill, p));
            c.drawRoundRect(rect, radius, radius, fillPaint);
            if (p < 0.999f) {
                rimPaint.setAlpha(Math.round(255f * (1f - p)));
                c.drawRoundRect(rimRect, rimRadius, rimRadius, rimPaint);
            }
            int from = enabled ? t.text : t.text3;
            int to = enabled ? t.btnFocusText : DISABLED_FOCUS_TEXT;
            textPaint.setColor(lerp(from, to, p));
            c.drawText(text, textX, textY, textPaint);
        }

        @Override
        public boolean hasOverlappingRendering() { return false; }
    }
}
