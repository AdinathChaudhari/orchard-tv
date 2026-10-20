package tv.orchard.home;

/** PURE JAVA (no android.* imports; host-tested). Remote key rules from PLAN 0.9. */
public final class KeyRules {
    private KeyRules() {}

    /**
     * Menu key "tap" detector. onDown arms only on a first DOWN (repeatCount == 0) and disarms on any repeat;
     * onUp returns true only when armed, not canceled and 0 <= eventTime - downTime < maxTapMs, then always disarms.
     */
    public static final class MenuTap {
        private final long maxTapMs;
        private boolean armed;

        public MenuTap(long maxTapMs) { this.maxTapMs = maxTapMs; }

        public void onDown(int repeatCount) { armed = repeatCount == 0; }

        public boolean onUp(boolean canceled, long downTime, long eventTime) {
            boolean was = armed;
            armed = false;
            long d = eventTime - downTime;
            return was && !canceled && d >= 0 && d < maxTapMs;
        }

        public void reset() { armed = false; }
    }

    /**
     * Long press on Select: a repeat DOWN that carries FLAG_LONG_PRESS or has been held thresholdMs.
     * (Injected "input keyevent --longpress 23" sends one repeat DOWN with the flag before 600 ms.)
     */
    public static boolean isLongSelect(int repeatCount, boolean longPressFlag, long downTime, long eventTime,
                                       long thresholdMs) {
        return repeatCount > 0 && (longPressFlag || eventTime - downTime >= thresholdMs);
    }

    /**
     * Alpha of an unfocused tile: the Move-mode dim wins over the idle dim, which wins over 1. (A tile that held focus
     * when Move mode started used to fade back to 1 on focus loss and stay bright.)
     */
    public static float restAlpha(float moveDim, boolean idle, float idleAlpha) {
        if (moveDim < 1f) return moveDim;
        return idle ? idleAlpha : 1f;
    }
}
