package tv.orchard.home;

import android.service.dreams.DreamService;
import android.util.Log;

/**
 * Screensaver (PLAN Rev 5/6): a giant Inter Thin 24 h clock on black with a date/weather line, shifted a few px
 * every minute (burn-in) and dimmed after 10 minutes. Any key wakes it. Read-only: no network, no Theme, writes no
 * settings; the weather line comes from the cache the home screen fills.
 */
public class ClockDream extends DreamService {
    private ClockFace face;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            Ui.init(this);
            setInteractive(false);
            setFullscreen(true);
            setScreenBright(false);
            face = new ClockFace(this);
            setContentView(face);
        } catch (RuntimeException e) {
            Log.w("OrchardDream", "attach failed", e);
            finish();
        }
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        try {
            if (face != null) face.start();
        } catch (RuntimeException e) {
            Log.w("OrchardDream", "start failed", e);
            finish();
        }
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
}
