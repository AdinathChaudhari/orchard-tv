package tv.orchard.home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Glass confirm / message sheet (PLAN 0.10, 2.1): a MATCH_PARENT overlay with a black veil and a centred thick-glass
 * card (880 wide, min 420 tall, wraps content). It owns focus while it exists: LEFT/RIGHT move between the buttons
 * (bump at the ends), UP/DOWN/MENU are consumed, Select on UP activates, BACK cancels.
 *
 * <p>Use the static flows ({@link #showReset}, {@link #showRedirectOn}, {@link #message}) from every caller so the
 * wording and the grant check live in one place.
 */
public class ConfirmSheet extends FrameLayout {
    /**
     * Two-button sheet callbacks. The sheet dismisses itself after the callback unless the callback changed it
     * (show / showMessage / setBusy / dismiss called from inside), so a plain listener needs no cleanup.
     */
    public interface Listener {
        void onConfirm();
        void onCancel();
    }

    private static final int COLOUR_MS = 160;                       // -> Ui (mockup .btn background/color transition)
    private static final int BTN_SHADOW_Y = 12, BTN_SHADOW_BLUR = 32;   // -> Ui (mockup .btn.focused box-shadow 0 12px 32px)

    public static final String TITLE_RESET = "Return to Fire TV home?";
    public static final String TITLE_REDIRECT_ON = "Turn Home redirect back on?";
    private static final String BODY_RESET =
        "Home will open Amazon's home screen until you switch Orchard back on (Apps row → Orchard → Options).";
    private static final String KEEPS_RESET =
        "Keeps: Home on Fire, Orchard and your other apps. "
            + "For a fully stock Stick run launcher.sh factory-stock on the Mac.";
    private static final String BODY_BUSY = "Switching to Fire TV home…";

    private final View veil;
    private final Card card;
    private final TextView titleView, bodyView, keepsView;
    private final LinearLayout row;
    private Btn[] btns = new Btn[0];
    private int focus;
    private Listener listener;
    private Runnable onOk;
    private boolean twoButton;
    private boolean shown, dismissing, busy, closedDone, selectDown;
    private int gen;                                                  // bumped by every content change / dismiss
    private Runnable onClosed;
    private ViewGroup host;
    private View restoreFocus;
    private ObjectAnimator bumpAnim;
    private Btn bumpBtn;

    public ConfirmSheet(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setClipChildren(false);

        veil = new View(c);
        veil.setBackgroundColor(Color.BLACK);
        veil.setAlpha(0f);
        addView(veil, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        card = new Card(c);
        card.setAlpha(0f);
        int pad = Glass.shadowPad();
        addView(card, new LayoutParams(Ui.px(Ui.SHEET_W) + 2 * pad, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        titleView = label(c, Ui.T_HEADLINE, true, Ui.TEXT);
        bodyView = label(c, Ui.T_BODY, false, Ui.TEXT2);
        keepsView = label(c, Ui.T_CAPTION1, false, Ui.TEXT3);
        card.addView(titleView, lp(0));
        card.addView(bodyView, lp(Ui.SHEET_TITLE_GAP));
        card.addView(keepsView, lp(Ui.SHEET_KEEPS_GAP));
        View spacer = new android.widget.Space(c);   // a plain View is greedy under AT_MOST and stretched the card to full height
        card.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.gravity = Gravity.CENTER_HORIZONTAL;
        rlp.topMargin = Ui.px(Ui.SHEET_BTN_TOP);
        card.addView(row, rlp);
    }

    private static TextView label(Context c, int designPx, boolean medium, int color) {
        TextView t = new TextView(c);
        t.setIncludeFontPadding(false);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(designPx));
        t.setTypeface(medium ? Ui.medium() : Ui.regular());
        t.setLetterSpacing(Ui.tracking(designPx));
        if (designPx != Ui.T_HEADLINE) t.setLineSpacing(0f, Ui.SHEET_LINE_HEIGHT);
        return t;
    }

    private static LinearLayout.LayoutParams lp(int topDesignPx) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        l.topMargin = Ui.px(topDesignPx);
        return l;
    }

    /** Pre-blurred wallpaper used as the glass backdrop (null = fill only). */
    public void setWallpaper(Bitmap blurred) { card.setWallpaper(blurred); }

    /** Two buttons: cancel (default focus) and confirm; keeps = the "what stays" line (may be null). */
    public void show(String title, String body, String keeps, String cancelLabel, String confirmLabel, Listener l) {
        gen++;
        busy = false;
        twoButton = true;
        listener = l;
        onOk = null;
        setTexts(title, body, keeps);
        Context c = getContext();
        setButtons(new Btn(c, cancelLabel, Ui.BTN_W), new Btn(c, confirmLabel, Ui.BTN_W));
        playIn();
    }

    /** One wide button; onOk may be null. */
    public void showMessage(String title, String body, String keeps, String okLabel, Runnable onOk) {
        gen++;
        busy = false;
        twoButton = false;
        listener = null;
        this.onOk = onOk;
        setTexts(title, body, keeps);
        setButtons(new Btn(getContext(), okLabel, Ui.BTN_WIDE_W));
        playIn();
    }

    /** Replaces the body with a progress line, drops the keeps line, and makes the buttons ignore input. */
    public void setBusy(String body) {
        gen++;
        busy = true;
        bodyView.setText(body);
        keepsView.setVisibility(GONE);
    }

    /** Dismiss as if Cancel was chosen (Cancel semantics for two buttons, OK semantics for a message). Ignored while busy. */
    public void cancel() {
        if (dismissing || busy) return;
        if (twoButton) {
            Listener l = listener;
            int g = gen;
            if (l != null) l.onCancel();
            if (g == gen) dismiss();
        } else {
            activateOk();
        }
    }

    /** Fades the sheet out, removes it from its host, then runs onClosed. */
    public void dismiss() { dismiss(true); }

    /** dismiss(false) removes it at once (use when the app is about to leave the screen). */
    public void dismiss(boolean animate) {
        if (dismissing) return;
        dismissing = true;
        gen++;
        if (!animate || !shown || !isAttachedToWindow()) {
            closed();
            return;
        }
        boolean reduced = TileView.isReducedMotion();
        veil.animate().alpha(0f).setDuration(Ui.ms(reduced ? Ui.REDUCED_FADE_MS : Ui.VEIL_FADE_MS)).setInterpolator(null).start();
        card.animate().alpha(0f).setDuration(Ui.ms(reduced ? Ui.REDUCED_FADE_MS : Ui.SHEET_FADE_MS)).setInterpolator(null)
            .withEndAction(this::closed).start();
    }

    public boolean isShowing() { return shown && !dismissing; }

    // ---- content ------------------------------------------------------------------------------------------------

    private void setTexts(String title, String body, String keeps) {
        titleView.setText(title);
        bodyView.setText(body);
        if (keeps == null || keeps.isEmpty()) {
            keepsView.setVisibility(GONE);
        } else {
            keepsView.setText(keeps);
            keepsView.setVisibility(VISIBLE);
        }
    }

    private void setButtons(Btn... list) {
        cancelBump();
        row.removeAllViews();
        btns = list;
        int bp = Btn.shadowPad();
        for (int i = 0; i < list.length; i++) {
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(list[i].viewW(), list[i].viewH());
            l.leftMargin = -bp + (i > 0 ? Ui.px(Ui.BTN_GAP) : 0);
            l.rightMargin = -bp;
            l.topMargin = -bp;
            l.bottomMargin = -bp;
            row.addView(list[i], l);
        }
        focus = 0;
        for (int i = 0; i < list.length; i++) list[i].setFocused(i == 0, false);
    }

    private void playIn() {
        requestFocus();
        if (shown) return;
        shown = true;
        boolean reduced = TileView.isReducedMotion();
        veil.animate().alpha(Ui.SHEET_VEIL_ALPHA).setDuration(Ui.ms(reduced ? Ui.REDUCED_FADE_MS : Ui.VEIL_FADE_MS))
            .setInterpolator(null).start();
        card.setAlpha(0f);
        if (reduced) {
            card.setScaleX(1f);
            card.setScaleY(1f);
            card.animate().alpha(1f).setDuration(Ui.ms(Ui.REDUCED_FADE_MS)).setInterpolator(null).start();
        } else {
            card.setScaleX(Ui.SHEET_SCALE_FROM);
            card.setScaleY(Ui.SHEET_SCALE_FROM);
            card.animate().alpha(1f).setDuration(Ui.ms(Ui.SHEET_FADE_MS)).setInterpolator(null).start();
            card.animate().scaleX(1f).scaleY(1f).setDuration(Ui.ms(Ui.FOCUS_IN_MS)).setInterpolator(Ui.focusIn()).start();
        }
    }

    private void closed() {
        if (closedDone) return;
        closedDone = true;
        cancelBump();
        ViewGroup h = host != null ? host : (getParent() instanceof ViewGroup ? (ViewGroup) getParent() : null);
        if (h != null) h.removeView(this);
        Runnable oc = onClosed;
        onClosed = null;
        if (oc != null) oc.run();
        View rf = restoreFocus;
        restoreFocus = null;
        if (h != null && rf != null && h.findFocus() == null && rf.isAttachedToWindow()
            && rf.getVisibility() == VISIBLE) {
            rf.requestFocus();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
    }

    // ---- keys ---------------------------------------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
                if (!down && !e.isCanceled()) cancel();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    selectDown = true;                                // an UP without our DOWN (key held while we opened) is ignored
                } else if (selectDown && !e.isCanceled()) {
                    selectDown = false;
                    activate(focus);
                } else {
                    selectDown = false;
                }
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void move(int d) {
        if (busy || dismissing || btns.length == 0) return;
        int n = focus + d;
        if (n < 0 || n >= btns.length) {
            bump(btns[focus], d);
            return;
        }
        btns[focus].setFocused(false, true);
        focus = n;
        btns[n].setFocused(true, true);
    }

    private void bump(Btn b, int d) {
        if (TileView.isReducedMotion()) return;
        cancelBump();
        bumpBtn = b;
        bumpAnim = ObjectAnimator.ofFloat(b, View.TRANSLATION_X, 0f, d * Ui.pxf(Ui.BUMP_PX), 0f);
        bumpAnim.setDuration(Ui.ms(Ui.BUMP_MS));
        bumpAnim.start();
    }

    private void cancelBump() {
        if (bumpAnim != null) {
            bumpAnim.cancel();
            bumpAnim = null;
        }
        if (bumpBtn != null) {
            bumpBtn.setTranslationX(0f);
            bumpBtn = null;
        }
    }

    private void activate(int i) {
        if (busy || dismissing) return;
        if (!twoButton) {
            activateOk();
            return;
        }
        Listener l = listener;
        int g = gen;
        if (i == 0) {
            if (l != null) l.onCancel();
        } else if (l != null) {
            l.onConfirm();
        }
        if (g == gen) dismiss();
    }

    private void activateOk() {
        Runnable ok = onOk;
        int g = gen;
        if (ok != null) ok.run();
        if (g == gen) dismiss();
    }

    @Override
    public boolean hasOverlappingRendering() { return false; }

    // ---- static flows (one place for every caller) ----------------------------------------------------------------------

    /** Creates a sheet, adds it to host MATCH_PARENT; onClosed runs after it is dismissed and removed (may be null). */
    public static ConfirmSheet attach(ViewGroup host, Bitmap blurred, Runnable onClosed) {
        ConfirmSheet s = new ConfirmSheet(host.getContext());
        s.host = host;
        s.onClosed = onClosed;
        s.restoreFocus = host.findFocus();
        s.setWallpaper(blurred);
        host.addView(s, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        return s;
    }

    /** Reset-to-Fire-TV flow (strip the redirect, open Amazon's home). Checks bridge.hasGrant() first, always. */
    public static ConfirmSheet showReset(ViewGroup host, FireTvBridge bridge, Bitmap blurred, Runnable onClosed) {
        ConfirmSheet s = attach(host, blurred, onClosed);
        if (!bridge.hasGrant()) {
            s.showGrantMessage(TITLE_RESET);
            return s;
        }
        s.show(TITLE_RESET, BODY_RESET, KEEPS_RESET, "Cancel", "Return to Fire TV", new ResetFlow(s, bridge, false));
        return s;
    }

    /** "Switch the redirect back on" flow. Checks bridge.hasGrant() first, always. */
    public static ConfirmSheet showRedirectOn(ViewGroup host, FireTvBridge bridge, Bitmap blurred, Runnable onClosed) {
        ConfirmSheet s = attach(host, blurred, onClosed);
        if (!bridge.hasGrant()) {
            s.showGrantMessage(TITLE_REDIRECT_ON);
            return s;
        }
        s.show(TITLE_REDIRECT_ON, "The next Home press goes to " + bridge.hofTargetName() + ".", null,
            "Cancel", "Turn on", new ResetFlow(s, bridge, true));
        return s;
    }

    /** Single-button message sheet. */
    public static ConfirmSheet message(ViewGroup host, Bitmap blurred, String title, String body, Runnable onClosed) {
        ConfirmSheet s = attach(host, blurred, onClosed);
        s.showMessage(title, body, null, "OK", null);
        return s;
    }

    private void showGrantMessage(String title) {
        showMessage(title, FireTvBridge.GRANT_HELP, FireTvBridge.GRANT_ALT, "OK", null);
    }

    /** Confirm handler of the reset (redirectOn=false) and redirect-on (true) flows. */
    private static final class ResetFlow implements Listener {
        private final ConfirmSheet sheet;
        private final FireTvBridge bridge;
        private final boolean redirectOn;

        ResetFlow(ConfirmSheet sheet, FireTvBridge bridge, boolean redirectOn) {
            this.sheet = sheet;
            this.bridge = bridge;
            this.redirectOn = redirectOn;
        }

        @Override
        public void onCancel() { }

        @Override
        public void onConfirm() {
            String title = redirectOn ? TITLE_REDIRECT_ON : TITLE_RESET;
            if (!bridge.hasGrant()) {                                  // the grant can disappear between show and confirm
                sheet.showGrantMessage(title);
                return;
            }
            if (redirectOn) {
                if (bridge.switchBackOn()) {
                    sheet.showMessage("Home redirect is back on",
                        "The next Home press goes to " + bridge.hofTargetName() + ".", null, "OK", null);
                } else {
                    sheet.showGrantMessage(title);
                }
                return;
            }
            sheet.setBusy(BODY_BUSY);
            bridge.returnToFireTv(ok -> {
                if (sheet.closedDone || sheet.dismissing) return;
                if (ok) {
                    sheet.dismiss(false);
                } else {
                    sheet.showMessage(TITLE_RESET, FireTvBridge.HOLD_HOME_HELP, null, "OK", null);
                }
            });
        }
    }

    // ---- the glass card -------------------------------------------------------------------------------------------------

    /**
     * Thick-glass card. The view is larger than the visible sheet by Glass.shadowPad() on every side (drop shadow);
     * the wallpaper slice is taken at the card's LAYOUT rect, so scale-in does not shift it.
     */
    private static final class Card extends LinearLayout {
        private final Glass glass = new Glass(Ui.SHEET_R, Ui.GLASS_THICK, 1f);
        private final int[] loc = new int[2];
        private final int screenW, screenH, pad;
        private Bitmap wallpaper;

        Card(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setWillNotDraw(false);
            setClipChildren(false);
            setClipToPadding(false);
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            screenW = dm.widthPixels;
            screenH = dm.heightPixels;
            pad = Glass.shadowPad();
            int inner = Ui.px(Ui.SHEET_PAD);
            setPadding(pad + inner, pad + inner, pad + inner, pad + inner);
            setMinimumHeight(Ui.px(Ui.SHEET_H_MIN) + 2 * pad);
        }

        void setWallpaper(Bitmap b) {
            wallpaper = b;
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

    // ---- one button -------------------------------------------------------------------------------------------------------

    /**
     * Glass pill button (default Ui.GLASS fill + gradient rim; focused = near-white fill, dark text, 1.04x, shadow).
     * The view is larger than the pill by shadowPad(); the row lays it out with negative margins.
     */
    private static final class Btn extends View {
        private final int pad, w, h;
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint.FontMetrics fm = new Paint.FontMetrics();
        private final RectF rect = new RectF();
        private final RectF rimRect = new RectF();
        private final String text;
        private float radius, rimRadius, textY;
        private Bitmap shadow;
        private float p;
        private boolean focused;
        private ValueAnimator colourAnim;

        Btn(Context c, String label, int wDesign) {
            super(c);
            text = label;
            pad = shadowPad();
            w = Ui.px(wDesign);
            h = Ui.px(Ui.BTN_H);
            textPaint.setTypeface(Ui.medium());
            textPaint.setTextSize(Ui.px(Ui.T_BODY));
            textPaint.setLetterSpacing(Ui.tracking(Ui.T_BODY));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.getFontMetrics(fm);
            setContentDescription(label);
            setFocusable(false);
        }

        static int shadowPad() { return Ui.px(BTN_SHADOW_Y + BTN_SHADOW_BLUR); }

        int viewW() { return w + 2 * pad; }

        int viewH() { return h + 2 * pad; }

        void setFocused(boolean f, boolean animate) {
            if (colourAnim != null) {
                colourAnim.cancel();
                colourAnim = null;
            }
            animate().cancel();
            boolean reduced = TileView.isReducedMotion();
            float target = f ? 1f : 0f;
            float scale = (f && !reduced) ? Ui.BTN_FOCUS_SCALE : 1f;
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
            setMeasuredDimension(viewW(), viewH());
        }

        @Override
        protected void onSizeChanged(int vw, int vh, int ow, int oh) {
            rect.set(pad, pad, pad + w, pad + h);
            radius = Math.min(Ui.pxf(Ui.BTN_R), h / 2f);
            float stroke = Math.max(1f, Ui.pxf(1));
            rimRect.set(rect.left + stroke / 2f, rect.top + stroke / 2f, rect.right - stroke / 2f, rect.bottom - stroke / 2f);
            rimRadius = Math.max(0f, radius - stroke / 2f);
            rimPaint.setStyle(Paint.Style.STROKE);
            rimPaint.setStrokeWidth(stroke);
            double a = Math.toRadians(Ui.GLASS_RIM_ANGLE);
            float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
            float len = Math.abs(w * dx) + Math.abs(h * dy);
            float cx = rect.centerX(), cy = rect.centerY();
            int white = 0xFFFFFF;
            rimPaint.setShader(new LinearGradient(cx - dx * len / 2f, cy - dy * len / 2f, cx + dx * len / 2f, cy + dy * len / 2f,
                new int[] {Ui.argb(Ui.GLASS_RIM_HI, white), Ui.argb(Ui.GLASS_RIM_LO, white),
                    Ui.argb(Ui.GLASS_RIM_LO, white), Ui.argb(Ui.GLASS_RIM_END, white)},
                new float[] {0f, Ui.GLASS_RIM_MIDLO_STOP, Ui.GLASS_RIM_MIDHI_STOP, 1f}, Shader.TileMode.CLAMP));
            textY = rect.centerY() - (fm.ascent + fm.descent) / 2f;
            buildShadow();
        }

        private void buildShadow() {
            if (shadow != null) shadow.recycle();
            shadow = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ALPHA_8);
            Canvas c = new Canvas(shadow);
            float sigma = Ui.pxf(BTN_SHADOW_BLUR) / 2f;
            float skiaRadius = Math.max(1f, (sigma - 0.5f) / 0.57735f);
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setColor(0xFF000000);
            sp.setMaskFilter(new BlurMaskFilter(skiaRadius, BlurMaskFilter.Blur.NORMAL));
            float drop = Ui.pxf(BTN_SHADOW_Y);
            c.drawRoundRect(new RectF(pad, pad + drop, pad + w, pad + drop + h), radius, radius, sp);
            shadowPaint.setColor(Ui.SHADOW);
        }

        private static int lerp(int a, int b, float t) {
            int aa = (a >>> 24), ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int ba = (b >>> 24), br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
            return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (p > Ui.DRAW_MIN_PROGRESS && shadow != null) {
                shadowPaint.setAlpha(Math.round(Color.alpha(Ui.SHADOW) * p));
                c.drawBitmap(shadow, 0f, 0f, shadowPaint);
            }
            fillPaint.setColor(lerp(Ui.GLASS, Ui.BTN_FOCUS_FILL, p));
            c.drawRoundRect(rect, radius, radius, fillPaint);
            if (p < 0.999f) {
                rimPaint.setAlpha(Math.round(255f * (1f - p)));
                c.drawRoundRect(rimRect, rimRadius, rimRadius, rimPaint);
            }
            textPaint.setColor(lerp(Ui.TEXT, Ui.BTN_FOCUS_TEXT, p));
            c.drawText(text, rect.centerX(), textY, textPaint);
        }

        @Override
        public boolean hasOverlappingRendering() { return false; }
    }
}
