package tv.orchard.home;

import android.provider.Settings;
import android.service.dreams.DreamService;

/** Screensaver: black field, drifting clock with a slow colour glow (PLAN 0.11). Read-only: writes no settings. */
public class ClockDream extends DreamService {
    private ClockFace face;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        Ui.init(this);
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        Config config = new Config(FireTvBridge.settingsFile(this));
        face = new ClockFace(this);
        face.setClock24h(config.getBool(Config.Keys.CLOCK_24H, true));
        face.setReducedMotion(reducedMotion(config));
        setContentView(face);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (face != null) face.start();
    }

    @Override
    public void onDreamingStopped() {
        if (face != null) face.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (face != null) face.stop();
        super.onDetachedFromWindow();
    }

    /** Same rule as HomeActivity.computeReducedMotion, read-only. */
    private boolean reducedMotion(Config config) {
        String dbg = config.get(Config.Keys.DEBUG_MOTION, "");
        if ("full".equals(dbg)) return false;
        if ("reduced".equals(dbg)) return true;
        if ("reduced".equals(config.get(Config.Keys.MOTION, "full"))) return true;
        try {
            return Settings.Global.getFloat(getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
