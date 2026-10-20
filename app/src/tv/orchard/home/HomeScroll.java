package tv.orchard.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.KeyEvent;
import android.view.animation.PathInterpolator;
import android.widget.ScrollView;

/**
 * Vertical scroller of the home screen (PLAN 0.3). Never scrolls by itself: no scroll-to-focused-child, no
 * key scrolling, no scrollbar, no overscroll glow. HomeActivity drives it with animateTo()/jumpTo().
 */
public class HomeScroll extends ScrollView {
    private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
    private int from, to;
    private Runnable scrolledHook;
    private boolean reduced;

    public HomeScroll(Context context) {
        super(context);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setFocusable(false);
        setFillViewport(true);
        setClipChildren(false);
        setClipToPadding(false);
        anim.setInterpolator(new PathInterpolator(0.2f, 0.8f, 0.2f, 1f));
        anim.addUpdateListener(a -> scrollTo(0, from + Math.round((to - from) * a.getAnimatedFraction())));
    }

    /** Called after every scroll position change (HomeActivity: dockBand.onScrolled()). */
    public void setOnScrolled(Runnable r) { scrolledHook = r; }

    /** Reduced motion: animateTo() jumps instead of animating. A running animation is stopped where it is. */
    public void setReducedMotion(boolean on) {
        reduced = on;
        if (on) anim.cancel();
    }

    /** Animate to scrollY = y over Ui.SCROLL_MS; a new call cancels and retargets from the current position. */
    public void animateTo(int y) {
        anim.cancel();
        from = getScrollY();
        to = y;
        if (from == to) return;
        if (reduced) {                                                   // reduced motion: jump, hook fires via onScrollChanged
            scrollTo(0, y);
            return;
        }
        anim.setDuration(Ui.ms(Ui.SCROLL_MS));
        anim.start();
    }

    /** onPause: stop a running animation, leaving scrollY where it is. */
    public void stopAnimation() { anim.cancel(); }

    /** Set scrollY = y at once. */
    public void jumpTo(int y) {
        anim.cancel();
        scrollTo(0, y);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (scrolledHook != null) scrolledHook.run();
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) { return 0; }

    @Override
    public boolean requestChildRectangleOnScreen(android.view.View child, android.graphics.Rect rectangle,
                                                 boolean immediate) {
        return false;
    }

    @Override
    public boolean executeKeyEvent(KeyEvent event) { return false; }

    @Override
    public boolean arrowScroll(int direction) { return false; }

    @Override
    protected void onDetachedFromWindow() {
        anim.cancel();
        super.onDetachedFromWindow();
    }
}
