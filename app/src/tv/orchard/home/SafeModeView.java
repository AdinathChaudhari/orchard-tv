package tv.orchard.home;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Black fallback screen (PLAN 2.2): a D-pad list with three rows (Try Orchard again, Return to Fire TV,
 * Reset Orchard settings) plus status lines (grant, redirect, Home on Fire, version) and the last crash.
 * Plain views only: it must work when everything else is broken. Reset rows go through ConfirmSheet (grant check,
 * confirm, progress, result), the same flow as the Quick Panel.
 */
public class SafeModeView extends FrameLayout {
    /** Optional: HomeActivity swaps back to the home screen here. Default: Activity.recreate(). */
    public interface Listener {
        void onRetry();
    }

    private final FireTvBridge bridge;
    private Listener listener;
    private final TextView statusGrant, statusRedirect, statusHof, statusVersion, message;
    private final Row rowRetry, rowReturn, rowReset, rowRedirect;
    private final boolean setup;
    private ConfirmSheet sheet;                 // non-null while a confirm/message sheet is attached; it owns focus and keys

    private static final int MODE_SAFE = 0, MODE_SETUP = 1;

    /** Safe-mode variant (crash loop). */
    public SafeModeView(Context context) {
        this(context, MODE_SAFE);
    }

    /**
     * @param setup true for the Phase 1-2 setup build: titled "Orchard setup build" and, while the Home
     *              redirect is off, shows a row that turns it back on (matrix row R4).
     */
    public SafeModeView(Context context, boolean setup) {
        this(context, setup ? MODE_SETUP : MODE_SAFE);
    }

    private SafeModeView(Context context, int mode) {
        super(context);
        this.setup = mode == MODE_SETUP;
        setBackgroundColor(Color.BLACK);
        bridge = new FireTvBridge(context);

        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            Ui.px(900), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.leftMargin = Ui.px(Ui.SAFE_X);
        lp.topMargin = Ui.px(Ui.SAFE_Y);
        addView(col, lp);

        col.addView(text(context, setup ? "Orchard setup build" : "Orchard is in safe mode",
            38, Ui.TEXT, true), plain(0));
        String crash = bridge.lastCrash();
        col.addView(text(context, crash.isEmpty() ? "No crash recorded." : "Last crash: " + crash, 25,
            Ui.TEXT2, false), plain(8));

        rowReturn = new Row(context, "Return to Fire TV");
        rowRedirect = new Row(context, "Turn Home redirect back on");
        rowRetry = new Row(context, "Try Orchard again");
        rowReset = new Row(context, "Reset Orchard settings");
        col.addView(rowRetry, rowLp(40));
        col.addView(rowReturn, rowLp(16));
        col.addView(rowReset, rowLp(16));
        col.addView(rowRedirect, rowLp(16));
        rowRedirect.setVisibility(View.GONE);

        message = text(context, "", 25, Ui.TEXT, false);
        message.setMinLines(3);
        col.addView(message, plain(24));

        statusGrant = text(context, "", 23, Ui.TEXT2, false);
        statusRedirect = text(context, "", 23, Ui.TEXT2, false);
        statusHof = text(context, "", 23, Ui.TEXT2, false);
        statusVersion = text(context, "", 23, Ui.TEXT3, false);
        col.addView(statusGrant, plain(16));
        col.addView(statusRedirect, plain(4));
        col.addView(statusHof, plain(4));
        col.addView(statusVersion, plain(4));

        rowReturn.setOnClickListener(v -> returnToFireTv());
        rowRetry.setOnClickListener(v -> retry());
        rowReset.setOnClickListener(v -> resetSettings());
        rowRedirect.setOnClickListener(v -> turnRedirectOn());

        refreshStatus();
    }

    public void setListener(Listener l) { listener = l; }

    // ---- actions ---------------------------------------------------------------------------------

    private void retry() {
        bridge.clearSafeMode();
        if (listener != null) {
            listener.onRetry();
        } else if (getContext() instanceof Activity) {
            ((Activity) getContext()).recreate();
        }
    }

    /** Return to Fire TV: one ConfirmSheet flow (grant check, confirm, progress, result) shared with the Quick Panel. */
    private void returnToFireTv() {
        if (sheet != null) return;
        message.setText("");
        sheet = ConfirmSheet.showReset(this, bridge, null, this::sheetClosed);
    }

    private void turnRedirectOn() {
        if (sheet != null) return;
        message.setText("");
        sheet = ConfirmSheet.showRedirectOn(this, bridge, null, this::sheetClosed);
    }

    private void sheetClosed() {
        sheet = null;
        refreshStatus();
    }

    private void resetSettings() {
        boolean ok = bridge.resetOrchardSettings();
        message.setText(ok ? "Orchard settings reset. Choose Try Orchard again."
                           : "Could not delete orchard.conf. Safe-mode flags were cleared.");
        refreshStatus();
    }

    // ---- status ------------------------------------------------------------------------------------

    private void refreshStatus() {
        statusGrant.setText("Permission: " + (bridge.hasGrant() ? "granted"
            : "missing (run scripts/launcher.sh grant on the Mac)"));
        statusRedirect.setText("Home redirect: " + (bridge.redirectOn() ? "on (Home goes through Home on Fire)"
            : "off (Fire TV home is active)"));
        rowRedirect.setVisibility(setup && !bridge.redirectOn() ? View.VISIBLE : View.GONE);
        statusHof.setText("Home on Fire: " + (bridge.hofInstalled() ? "installed" : "not installed"));
        statusVersion.setText("Orchard " + versionName());
    }

    private String versionName() {
        try {
            String v = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        refreshStatus();
        rowRetry.requestFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (sheet != null) return sheet.dispatchKeyEvent(event);   // the sheet consumes the D-pad; Back -> sheet.cancel()
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasWindowFocus) refreshStatus();
    }

    // ---- view helpers --------------------------------------------------------------------------------

    private static TextView text(Context c, String s, int designPx, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(designPx));
        t.setTypeface(medium ? Ui.medium() : Ui.regular());
        t.setLetterSpacing(Ui.tracking(designPx));
        t.setIncludeFontPadding(false);
        return t;
    }

    private static LinearLayout.LayoutParams plain(int topDesignPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.px(topDesignPx);
        return lp;
    }

    private static LinearLayout.LayoutParams rowLp(int topDesignPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            Ui.px(Ui.BTN_W * 2), Ui.px(Ui.BTN_H));
        lp.topMargin = Ui.px(topDesignPx);
        return lp;
    }

    /** One focusable row: glass pill, inverted (white fill, dark text) when focused (PLAN 0.7). */
    private static final class Row extends TextView {
        Row(Context c, String label) {
            super(c);
            setText(label);
            setTypeface(Ui.medium());
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(29));
            setLetterSpacing(Ui.tracking(29));
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.px(40), 0, Ui.px(40), 0);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            paint(false);
        }

        private void paint(boolean focused) {
            GradientDrawable d = new GradientDrawable();
            d.setCornerRadius(Ui.px(Ui.BTN_R));
            d.setColor(focused ? Ui.BTN_FOCUS_FILL : Ui.GLASS);
            setBackground(d);
            setTextColor(focused ? Ui.BTN_FOCUS_TEXT : Ui.TEXT);
        }

        @Override
        protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect prev) {
            super.onFocusChanged(gainFocus, direction, prev);
            paint(gainFocus);
        }
    }
}
