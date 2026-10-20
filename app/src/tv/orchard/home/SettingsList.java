package tv.orchard.home;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable settings list (PLAN 0.5-0.8 and the mockup #slist / .row): a vertical ScrollView of glass rows, D-pad
 * UP/DOWN between rows with a bump at the ends (no wrap), the focused row always fully visible. Rows are built
 * from {@link Item}s made by the static factories {@link #toggle}, {@link #choice}, {@link #action}, {@link #check}.
 * Place it with {@link #padX()} / {@link #padY()} of slack around the 880 px column (the focus shadow lives there).
 */
public final class SettingsList extends FrameLayout {

    // ---- public API types -----------------------------------------------------------------------------

    public interface BoolGetter { boolean get(); }
    public interface BoolSetter { void set(boolean value); }
    public interface Getter { String get(); }
    public interface Setter { void set(String value); }
    /** LEFT = -1, RIGHT = +1. */
    public interface IntConsumerLike { void accept(int direction); }

    /** One row. Immutable apart from the chainable disabled() / onHorizontal(). Safe to reuse across setItems calls. */
    public static final class Item {
        static final int TOGGLE = 0, CHOICE = 1, ACTION = 2, CHECK = 3;
        final int kind;
        final String label;
        BoolGetter boolGet;
        BoolSetter boolSet;
        String[] values, labels;
        Getter get;
        Setter set;
        String detail = "";
        Getter detailGet;
        Runnable run;
        boolean disabled;
        IntConsumerLike horizontal;
        String hint;

        Item(int kind, String label) {
            this.kind = kind;
            this.label = label == null ? "" : label;
        }

        /** White 42% text, still focusable; Select / LEFT / RIGHT only bump. */
        public Item disabled(boolean d) {
            disabled = d;
            return this;
        }

        /** Text the host may show (e.g. as the subtitle) while this row is focused. */
        public Item hint(String h) {
            hint = h;
            return this;
        }

        /** LEFT/RIGHT call this instead of the default behaviour (p5-2b: reorder). Ignored while disabled. */
        public Item onHorizontal(IntConsumerLike h) {
            horizontal = h;
            return this;
        }
    }

    /** Called with the row index each time a row gains focus. */
    public interface FocusListener { void onFocus(int index, Item item); }

    public static Item toggle(String label, BoolGetter g, BoolSetter s) {
        Item i = new Item(Item.TOGGLE, label);
        i.boolGet = g;
        i.boolSet = s;
        return i;
    }

    /** Shows '‹  Label  ›'. LEFT/RIGHT step (bump at the ends), Select cycles. values = stored strings, labels = shown. */
    public static Item choice(String label, String[] values, String[] labels, Getter g, Setter s) {
        Item i = new Item(Item.CHOICE, label);
        i.values = values;
        i.labels = labels;
        i.get = g;
        i.set = s;
        return i;
    }

    /** Detail text (may be empty) then '›'. Select runs r. */
    public static Item action(String label, String detail, Runnable r) {
        Item i = new Item(Item.ACTION, label);
        i.detail = detail == null ? "" : detail;
        i.run = r;
        return i;
    }

    /** Check mark when g is true, then the detail text (detail may be null). Select runs onSelect (may be null). */
    public static Item check(String label, BoolGetter g, Runnable onSelect, Getter detail) {
        Item i = new Item(Item.CHECK, label);
        i.boolGet = g;
        i.run = onSelect;
        i.detailGet = detail;
        return i;
    }

    // ---- state ----------------------------------------------------------------------------------------------

    private final ScrollView scroll;
    private final Column column;
    private final ArrayList<Row> rows = new ArrayList<Row>();
    private boolean reduced;
    private int focusIdx;
    private boolean pendingJump;
    private FocusListener focusListener;
    private final ArrayList<Item> itemList = new ArrayList<Item>();

    public SettingsList(Context c) {
        super(c);
        setClipChildren(false);
        setClipToPadding(false);
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        column = new Column(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(padX(), padY(), padX(), padY());
        scroll.addView(column, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Slack around the 880 px column, in screen px: room for the focus shadow. */
    public static int padX() { return Ui.px(48); }
    public static int padY() { return Ui.px(40); }

    /** Full width of this view for an 880 design-px column. */
    public static int viewWidth() { return Ui.px(Ui.LIST_W) + 2 * padX(); }

    // ---- public API ---------------------------------------------------------------------------------------------

    /** Replace all rows; focus goes to focusIndex (clamped). Scroll jumps there on the next layout. */
    public void setItems(List<Item> items, int focusIndex) {
        column.removeAllViews();
        rows.clear();
        itemList.clear();
        if (items != null) itemList.addAll(items);
        int n = items == null ? 0 : items.size();
        for (int i = 0; i < n; i++) {
            Row r = new Row(getContext(), this, items.get(i), i);
            r.setReducedMotion(reduced);
            rows.add(r);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.px(Ui.LIST_W), Ui.px(Ui.LIST_ROW_H));
            lp.bottomMargin = Ui.px(Ui.LIST_ROW_GAP);
            column.addView(r, lp);
        }
        column.setFront(-1);
        if (n == 0) {
            focusIdx = 0;
            return;
        }
        int f = Math.max(0, Math.min(n - 1, focusIndex));
        focusIdx = f;
        pendingJump = true;
        rows.get(f).requestFocus();
        requestLayout();
    }

    public void setFocusListener(FocusListener l) { focusListener = l; }

    /** Re-reads the live Theme palette into every row (call after Theme.set). */
    public void applyTheme() {
        for (int i = 0; i < rows.size(); i++) rows.get(i).invalidate();
    }

    /** Index of the focused row (the last focused one while focus is elsewhere, e.g. under a sheet). */
    public int focusedIndex() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).hasFocus()) return i;
        return focusIdx;
    }

    /** Re-reads every getter (toggle knobs animate), keeps focus. */
    public void refresh() {
        for (int i = 0; i < rows.size(); i++) rows.get(i).bind(true);
    }

    /** Move focus to row i (clamped), scrolling it into view. */
    public void focusIndex(int i) {
        if (rows.isEmpty()) return;
        int f = Math.max(0, Math.min(rows.size() - 1, i));
        focusIdx = f;
        Row r = rows.get(f);
        if (!r.requestFocus()) ensureVisible(f, false);
    }

    public int itemCount() { return rows.size(); }

    /** Bump the focused row sideways (a refused change). No-op under reduced motion. */
    public void bumpFocused() {
        if (rows.isEmpty()) return;
        rows.get(Math.max(0, Math.min(rows.size() - 1, focusedIndex()))).bump(View.FOCUS_RIGHT);
    }

    /** Reduced motion: no scale, no bump, instant scroll and toggle. */
    public void setReducedMotion(boolean r) {
        reduced = r;
        for (int i = 0; i < rows.size(); i++) rows.get(i).setReducedMotion(r);
    }

    // ---- internals -----------------------------------------------------------------------------------------------

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (pendingJump && scroll.getHeight() > 0 && !rows.isEmpty()) {
            pendingJump = false;
            ensureVisible(Math.max(0, Math.min(rows.size() - 1, focusIdx)), false);
        }
    }

    void onRowFocused(int index) {
        focusIdx = index;
        if (focusListener != null && index >= 0 && index < itemList.size()) focusListener.onFocus(index, itemList.get(index));
        column.setFront(index);
        if (scroll.getHeight() == 0) {
            pendingJump = true;
            return;
        }
        ensureVisible(index, !reduced);
    }

    void move(int from, int dir) {
        int to = from + dir;
        if (to < 0 || to >= rows.size()) {
            rows.get(from).bump(dir < 0 ? View.FOCUS_UP : View.FOCUS_DOWN);
            return;
        }
        rows.get(to).requestFocus();
    }

    private void ensureVisible(int index, boolean smooth) {
        if (index < 0 || index >= rows.size()) return;
        View r = rows.get(index);
        int viewH = scroll.getHeight();
        if (viewH <= 0) return;
        int margin = Ui.px(16);
        int top = r.getTop(), bottom = r.getBottom();
        int sy = scroll.getScrollY();
        int target = sy;
        if (index == 0) target = 0;
        else if (top - margin < sy) target = top - margin;
        else if (bottom + margin > sy + viewH) target = bottom + margin - viewH;
        if (index == rows.size() - 1) {
            int max = Math.max(0, column.getHeight() - viewH);
            if (bottom + margin > sy + viewH) target = max;
        }
        target = Math.max(0, target);
        if (target == sy) return;
        if (smooth) scroll.smoothScrollTo(0, target);
        else scroll.scrollTo(0, target);
    }

    /** Vertical column that draws the focused row last (its shadow must sit over the next row). */
    private static final class Column extends LinearLayout {
        private int front = -1;

        Column(Context c) {
            super(c);
            setChildrenDrawingOrderEnabled(true);
        }

        void setFront(int i) {
            front = i;
            invalidate();
        }

        @Override
        protected int getChildDrawingOrder(int childCount, int drawingPosition) {
            if (front < 0 || front >= childCount) return drawingPosition;
            if (drawingPosition == childCount - 1) return front;
            return drawingPosition >= front ? drawingPosition + 1 : drawingPosition;
        }
    }

    // ---- row view ------------------------------------------------------------------------------------------------------

    private static Bitmap shadowBmp;
    private static float shadowScale;

    private static int shadowPad() { return Ui.px(Ui.LIST_SHADOW_BLUR + 16); }

    /** Pre-rendered once: rounded-rect shadow (blur LIST_SHADOW_BLUR) at its own centre; the row offsets it by LIST_SHADOW_Y. */
    private static void ensureShadow() {
        if (shadowBmp != null && shadowScale == Ui.scale()) return;
        int pad = shadowPad();
        int w = Ui.px(Ui.LIST_W) + 2 * pad, h = Ui.px(Ui.LIST_ROW_H) + 2 * pad;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Ui.SHADOW);
        float blurPx = Ui.pxf(Ui.LIST_SHADOW_BLUR);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1f, (blurPx / 2f - 0.5f) / 0.57735f), BlurMaskFilter.Blur.NORMAL));
        float rad = Ui.pxf(Ui.LIST_ROW_R);
        new Canvas(b).drawRoundRect(new RectF(pad, pad, w - pad, h - pad), rad, rad, p);
        shadowBmp = b;
        shadowScale = Ui.scale();
    }

    private static int lerpColor(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aa = (a >>> 24) & 255, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
            | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    private static int mulAlpha(int c, float m) {
        return (Math.round(((c >>> 24) & 255) * m) << 24) | (c & 0xFFFFFF);
    }

    private static final int LIGHT_ROW_FILL = Ui.argb(0.06f, 0x1B1B1F);      // mockup :root[data-theme=light] .row:not(.focused)
    private static final int DARK_OFF = Ui.argb(0.25f, 0x111111);
    private static final int DARK_VAL = Ui.argb(Ui.LIST_VAL_DARK_ALPHA, 0x111111);
    private static final int DARK_DISABLED = Ui.argb(0.42f, 0x111111);

    private static final class Row extends View {
        private final SettingsList list;
        private final Item item;
        private final int index;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint valuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint detailPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);      // CHECK detail: caption1, like .val .dim
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF rect = new RectF();
        private final Path checkPath = new Path();

        private float fp;                   // eased focus progress (may overshoot 1)
        private float tp;                   // toggle knob 0..1
        private boolean on, checked;
        private String valueText = "";
        private String labelDraw = "";
        private boolean reduced;
        private final ValueAnimator fpAnim = ValueAnimator.ofFloat(0f, 1f);
        private final ValueAnimator tpAnim = ValueAnimator.ofFloat(0f, 1f);
        private ObjectAnimator bumpAnim;

        Row(Context c, SettingsList list, Item item, int index) {
            super(c);
            this.list = list;
            this.item = item;
            this.index = index;
            setFocusable(true);
            setFocusableInTouchMode(false);
            setDefaultFocusHighlightEnabled(false);
            ensureShadow();
            labelPaint.setTypeface(Ui.medium());
            labelPaint.setTextSize(Ui.pxf(Ui.T_BODY));
            labelPaint.setLetterSpacing(Ui.tracking(Ui.T_BODY));
            valuePaint.setTypeface(Ui.regular());
            valuePaint.setTextSize(Ui.pxf(Ui.T_BODY));
            valuePaint.setLetterSpacing(Ui.tracking(Ui.T_BODY));
            detailPaint.setTypeface(Ui.regular());
            detailPaint.setTextSize(Ui.pxf(Ui.T_CAPTION1));
            detailPaint.setLetterSpacing(Ui.tracking(Ui.T_CAPTION1));
            checkPaint.setStyle(Paint.Style.STROKE);
            checkPaint.setStrokeCap(Paint.Cap.ROUND);
            checkPaint.setStrokeJoin(Paint.Join.ROUND);
            checkPaint.setStrokeWidth(Ui.pxf(3.4f));
            knob.setColor(0xFFFFFFFF);
            shadowPaint.setAlpha(0);
            fpAnim.addUpdateListener(a -> {
                fp = (Float) a.getAnimatedValue();
                applyFocus();
            });
            tpAnim.addUpdateListener(a -> {
                tp = (Float) a.getAnimatedValue();
                invalidate();
            });
            bind(false);
        }

        void setReducedMotion(boolean r) {
            reduced = r;
            if (r) {
                fpAnim.cancel();
                fp = hasFocus() ? 1f : 0f;
                applyFocus();
            }
        }

        /** Re-read the item's getters; animate the toggle knob when requested and the state changed. */
        void bind(boolean animate) {
            boolean oldOn = on;
            switch (item.kind) {
                case Item.TOGGLE:
                    on = item.boolGet != null && item.boolGet.get();
                    valueText = "";
                    break;
                case Item.CHOICE: {
                    String cur = item.get == null ? "" : item.get.get();
                    int i = indexOf(cur);
                    String shown = i >= 0 && item.labels != null && i < item.labels.length ? item.labels[i] : String.valueOf(cur);
                    valueText = "‹  " + shown + "  ›";
                    break;
                }
                case Item.ACTION:
                    valueText = item.detail.isEmpty() ? "›" : item.detail + "  ›";
                    break;
                default: {
                    checked = item.boolGet != null && item.boolGet.get();
                    String d = item.detailGet == null ? null : item.detailGet.get();
                    valueText = d == null ? "" : d;
                    break;
                }
            }
            if (item.kind == Item.TOGGLE) {
                float target = on ? 1f : 0f;
                tpAnim.cancel();
                if (animate && oldOn != on && !reduced && isAttachedToWindow()) {
                    tpAnim.setFloatValues(tp, target);
                    tpAnim.setDuration(Ui.ms(Ui.TOGGLE_MS));
                    tpAnim.start();
                } else {
                    tp = target;
                }
            }
            layoutText();
            invalidate();
        }

        private int indexOf(String cur) {
            if (item.values == null) return -1;
            for (int i = 0; i < item.values.length; i++) if (item.values[i].equals(cur)) return i;
            return -1;
        }

        private void layoutText() {
            int w = getWidth();
            if (w <= 0) {
                labelDraw = item.label;
                return;
            }
            float padX = Ui.pxf(Ui.LIST_PAD_X);
            float right = w - padX;
            float reserve = 0f;
            if (item.kind == Item.TOGGLE) {
                reserve = Ui.pxf(Ui.TOGGLE_W) + Ui.pxf(Ui.LIST_VAL_GAP);
            } else {
                if (!valueText.isEmpty()) reserve += valueWidth() + Ui.pxf(Ui.LIST_VAL_GAP);
                if (item.kind == Item.CHECK && checked) reserve += Ui.pxf(26f) + Ui.pxf(Ui.LIST_VAL_GAP);
            }
            float avail = Math.max(Ui.pxf(80), right - padX - reserve);
            labelDraw = TextUtils.ellipsize(item.label, labelPaint, avail, TextUtils.TruncateAt.END).toString();
            checkPath.reset();
            if (item.kind == Item.CHECK && checked) {
                float sz = Ui.pxf(26f);
                float vw = valueText.isEmpty() ? 0f : valueWidth() + Ui.pxf(Ui.LIST_VAL_GAP);
                float x0 = right - vw - sz, cy = getHeight() / 2f;
                checkPath.moveTo(x0, cy + sz * 0.05f);
                checkPath.lineTo(x0 + sz * 0.38f, cy + sz * 0.42f);
                checkPath.lineTo(x0 + sz, cy - sz * 0.40f);
            }
        }

        private float valueWidth() {
            return (item.kind == Item.CHECK ? detailPaint : valuePaint).measureText(valueText);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            layoutText();
        }

        // ---- focus ----

        @Override
        protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect prev) {
            super.onFocusChanged(gained, direction, prev);
            if (gained) list.onRowFocused(index);
            float target = gained ? 1f : 0f;
            fpAnim.cancel();
            if (reduced || !isAttachedToWindow()) {
                fp = target;
                applyFocus();
                return;
            }
            fpAnim.setFloatValues(fp, target);
            fpAnim.setInterpolator(gained ? Ui.focusIn() : Ui.focusOut());
            fpAnim.setDuration(Ui.ms(gained ? Ui.FOCUS_IN_MS : Ui.FOCUS_OUT_MS));
            fpAnim.start();
        }

        private void applyFocus() {
            float s = reduced ? 1f : 1f + (Ui.LIST_FOCUS_SCALE - 1f) * fp;
            setScaleX(s);
            setScaleY(s);
            invalidate();
        }

        // ---- keys ----

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    list.move(index, -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    list.move(index, 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    horizontal(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    horizontal(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                case KeyEvent.KEYCODE_BUTTON_A:
                    if (e.getRepeatCount() == 0) select();
                    return true;
                default:
                    return super.onKeyDown(keyCode, e);
            }
        }

        private void select() {
            if (item.disabled) {
                bump(View.FOCUS_RIGHT);
                return;
            }
            switch (item.kind) {
                case Item.TOGGLE:
                    if (item.boolSet != null) item.boolSet.set(!on);
                    break;
                case Item.CHOICE:
                    step(0);
                    return;
                case Item.ACTION:
                case Item.CHECK:
                    if (item.run != null) item.run.run();
                    break;
                default:
                    break;
            }
            bind(true);
        }

        private void horizontal(int dir) {
            if (item.disabled) {
                bump(dir < 0 ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                return;
            }
            if (item.horizontal != null) {
                item.horizontal.accept(dir);
                list.refresh();
                return;
            }
            switch (item.kind) {
                case Item.TOGGLE:
                    if (item.boolSet != null) item.boolSet.set(!on);
                    bind(true);
                    break;
                case Item.CHOICE:
                    step(dir);
                    break;
                default:
                    break;
            }
        }

        /** dir 0 = Select (cycle), +/-1 = step with a bump at the ends. */
        private void step(int dir) {
            String[] v = item.values;
            if (v == null || v.length == 0 || item.set == null) return;
            int i = indexOf(item.get == null ? "" : item.get.get());
            int n;
            if (dir == 0) n = (i + 1) % v.length;
            else n = Math.max(0, Math.min(v.length - 1, i < 0 ? (dir > 0 ? 0 : -1) : i + dir));
            if (n < 0 || (dir != 0 && n == i)) {
                bump(dir < 0 ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                return;
            }
            item.set.set(v[n]);
            bind(true);
        }

        void bump(int direction) {
            if (reduced) return;
            boolean vertical = direction == View.FOCUS_UP || direction == View.FOCUS_DOWN;
            if (bumpAnim != null) {
                bumpAnim.cancel();
                setTranslationX(0f);
                setTranslationY(0f);
            }
            float d = Ui.pxf(Ui.BUMP_PX) * (direction == View.FOCUS_LEFT || direction == View.FOCUS_UP ? -1f : 1f);
            bumpAnim = ObjectAnimator.ofFloat(this, vertical ? View.TRANSLATION_Y : View.TRANSLATION_X, 0f, d, 0f);
            bumpAnim.setDuration(Ui.ms(Ui.BUMP_MS));
            bumpAnim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            fpAnim.cancel();
            tpAnim.cancel();
            if (bumpAnim != null) bumpAnim.cancel();
        }

        // ---- drawing (no allocation) ----

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float f = fp < 0f ? 0f : (fp > 1f ? 1f : fp);
            if (f > Ui.DRAW_MIN_PROGRESS && shadowBmp != null) {
                shadowPaint.setAlpha(Math.round(255f * f * (Theme.cur().light ? 0.55f : 1f)));
                c.drawBitmap(shadowBmp, -shadowPad() + (w - Ui.px(Ui.LIST_W)) / 2f,
                    -shadowPad() + Ui.pxf(Ui.LIST_SHADOW_Y), shadowPaint);
            }
            float r = Ui.pxf(Ui.LIST_ROW_R);
            rect.set(0f, 0f, w, h);
            Theme th = Theme.cur();
            fill.setColor(lerpColor(th.light ? LIGHT_ROW_FILL : th.glassThin, th.btnFocusFill, f));
            c.drawRoundRect(rect, r, r, fill);

            boolean dis = item.disabled;
            int labelCol = dis ? lerpColor(th.text3, DARK_DISABLED, f) : lerpColor(th.text, th.btnFocusText, f);
            int valCol = dis ? labelCol : lerpColor(th.text2, DARK_VAL, f);
            float cy = h / 2f;
            float padX = Ui.pxf(Ui.LIST_PAD_X);

            labelPaint.setColor(labelCol);
            float lb = cy - (labelPaint.ascent() + labelPaint.descent()) / 2f;
            c.drawText(labelDraw, padX, lb, labelPaint);

            float right = w - padX;
            if (item.kind == Item.TOGGLE) {
                float pw = Ui.pxf(Ui.TOGGLE_W), ph = Ui.pxf(Ui.TOGGLE_H);
                float px0 = right - pw, py0 = cy - ph / 2f;
                int off = lerpColor(th.togOff, DARK_OFF, f);
                pill.setColor(mulAlpha(lerpColor(off, Ui.ACCENT, tp), dis ? 0.5f : 1f));
                rect.set(px0, py0, px0 + pw, py0 + ph);
                c.drawRoundRect(rect, ph / 2f, ph / 2f, pill);
                float k = Ui.pxf(Ui.TOGGLE_KNOB), inset = Ui.pxf(Ui.TOGGLE_INSET);
                float kx = px0 + inset + tp * (pw - k - 2f * inset);
                knob.setAlpha(dis ? 128 : 255);
                c.drawCircle(kx + k / 2f, cy, k / 2f, knob);
            } else {
                TextPaint vp = item.kind == Item.CHECK ? detailPaint : valuePaint;
                vp.setColor(valCol);
                if (!valueText.isEmpty()) {
                    float vw = vp.measureText(valueText);
                    float vb = cy - (vp.ascent() + vp.descent()) / 2f;
                    c.drawText(valueText, right - vw, vb, vp);
                }
                if (item.kind == Item.CHECK && checked) {
                    checkPaint.setColor(dis ? labelCol : lerpColor(Ui.ACCENT, th.btnFocusText, f));
                    c.drawPath(checkPath, checkPaint);
                }
            }
        }
    }
}
