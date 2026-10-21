package tv.orchard.home;

import android.view.View;

/**
 * Best-effort release of the render thread's caches (Skia scratch + GL texture pool) once the screen has settled.
 * Public API has no call for it and the Stick never sends UI_HIDDEN to a foreground app, so this tries the hidden
 * WindowManagerGlobal.trimMemory(TRIM_MEMORY_BACKGROUND) first and View.destroyHardwareResources() second; if the
 * platform blocks both (hidden-API enforcement) it remembers that and does nothing. Never throws.
 */
final class GpuTrim {
    private GpuTrim() {}

    private static final int TRIM_MEMORY_BACKGROUND = 40;
    private static boolean blockedTrim, blockedDestroy;

    /** Returns true when one of the two calls ran. Main thread. */
    static boolean trim(View decor) {
        if (!blockedTrim) {
            try {
                Class<?> c = Class.forName("android.view.WindowManagerGlobal");
                Object g = c.getMethod("getInstance").invoke(null);
                c.getMethod("trimMemory", int.class).invoke(g, TRIM_MEMORY_BACKGROUND);
                return true;
            } catch (Throwable t) {
                blockedTrim = true;
            }
        }
        if (!blockedDestroy && decor != null) {
            try {
                View.class.getMethod("destroyHardwareResources").invoke(decor);
                decor.invalidate();
                return true;
            } catch (Throwable t) {
                blockedDestroy = true;
            }
        }
        return false;
    }
}
