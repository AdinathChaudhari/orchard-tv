package tv.orchard.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Ambient engine (PLAN 8.3, 9.1, 9.3, 9.4, 9.6): once per minute it works out the city, the sun, the
 * theme phase, the text halo, the sky frame, the weather strings and the greeting, and hands views one
 * {@link AmbientState}. Main-thread singleton.
 *
 * <p>Minute-tick rule: evaluation runs only at minute boundaries (ClockFormat.delayToNextMinute), on a
 * time/zone/date change, on a settings change and when a weather fetch lands. There is no 30 s tick, so
 * an idle home redraws only once a minute, and the listener fires only when a visible field changed
 * (or the evaluation is a jump). The tick stops in {@link #stop()} (onPause): no wake-ups behind
 * another app or the screensaver.
 *
 * <p>City zone versus TV zone: sun times, theme phase and the sky use the CITY's clock (its IANA zone,
 * the TV's zone for a custom city without one). The greeting, the date label and the screensaver line
 * use the TV's own local clock, because the strip clock shows TV time.
 *
 * <p>Weather OFF makes no request: maybeFetch is only called when the weather switch is on, and a cached
 * forecast is ignored while it is off. Never blocks: no network and no large IO on this thread.
 */
public final class Ambient {
    public interface Listener { void onAmbient(AmbientState s); }

    private static Ambient instance;

    public static Ambient get(Context c) {
        if (instance == null) instance = new Ambient(c.getApplicationContext());
        return instance;
    }

    private static final long STALE_MS = 2 * 60 * 1000L;
    private static final float EPS = 1e-3f;

    private final Context app;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean started;
    private AmbientState last;
    private AmbientState delivered;
    private long lastEvalMs;
    private String lastCityKey;
    private BroadcastReceiver receiver;

    // Sun cache per (cityKey, date).
    private String sunKey = "";
    private Solar.Times sunTimes;
    private double sunEmax;
    private double sunTzHours;

    private final Runnable tick = this::onTick;   // method ref, not an anonymous class (d8 8.2.2 crash, see OnAnimEnd)

    private void onTick() {
        if (!started) return;
        long now = System.currentTimeMillis();
        AmbientState s = evaluate(now, false);
        deliver(s, false);
        handler.postDelayed(tick, ClockFormat.delayToNextMinute(System.currentTimeMillis()));
    }

    /** Named static class, not an anonymous one: d8 8.2.2 crashed (NPE in String.length) on the anonymous receiver. */
    private static final class TimeReceiver extends BroadcastReceiver {
        private final Ambient owner;
        TimeReceiver(Ambient owner) { this.owner = owner; }
        @Override public void onReceive(Context c, Intent i) { owner.onTimeChanged(); }
    }

    void onTimeChanged() {
        if (!started) return;
        AmbientState st = evaluate(System.currentTimeMillis(), true);
        deliver(st, true);
    }

    private Ambient(Context app) { this.app = app; }

    public AmbientState last() { return last; }

    public void start(Listener l) {
        listener = l;
        started = true;
        long now = System.currentTimeMillis();
        boolean jump = last == null || now - lastEvalMs > STALE_MS || now < lastEvalMs;
        AmbientState s = evaluate(now, jump);
        deliver(s, true);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, ClockFormat.delayToNextMinute(now));
        if (receiver == null) {
            receiver = new TimeReceiver(this);
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(Intent.ACTION_DATE_CHANGED);
            app.registerReceiver(receiver, f);
        }
    }

    public void stop() {
        started = false;
        handler.removeCallbacks(tick);
        if (receiver != null) {
            try { app.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) { }
            receiver = null;
        }
        listener = null;
    }

    /** Config may have changed (theme, weather, units, wallpaper, city). */
    public void settingsChanged() {
        Config cfg = ((OrchardApp) app).config();
        cfg.refresh();
        CityCommand.Resolved city = Cities.current(app, cfg);
        String key = city.key();
        if (lastCityKey != null && !lastCityKey.equals(key)) WeatherClient.invalidate(app);
        AmbientState s = evaluate(System.currentTimeMillis(), true);
        if (started) deliver(s, true);
    }

    public AmbientState evaluate(long nowMs, boolean jump) {
        Config cfg = ((OrchardApp) app).config();
        cfg.refresh();
        AmbientState s = new AmbientState();
        s.jump = jump;

        String themeStr = cfg.get(Config.Keys.THEME, "auto");
        s.themeMode = "light".equals(themeStr) ? 1 : "dark".equals(themeStr) ? 2 : 0;
        boolean weatherOn = cfg.getBool(Config.Keys.WEATHER, true);
        String units = cfg.get(Config.Keys.UNITS, "auto");
        String wallpaper = cfg.get(Config.Keys.WALLPAPER, Ui.DEFAULT_WALLPAPER);

        CityCommand.Resolved city = Cities.current(app, cfg);
        String cityKey = city.key();
        lastCityKey = cityKey;
        String tzId = city.tz != null ? city.tz : TimeZone.getDefault().getID();
        String cc = city.cc != null ? city.cc : Locale.getDefault().getCountry();
        TimeZone tz = TimeZone.getTimeZone(tzId);
        s.cityKey = cityKey; s.cityName = city.name; s.cityCc = cc; s.tzId = tzId;
        s.lat = city.lat; s.lon = city.lon;

        // City-zone local date and minute.
        Calendar cal = Calendar.getInstance(tz);
        cal.setTimeInMillis(nowMs);
        s.year = cal.get(Calendar.YEAR);
        s.month = cal.get(Calendar.MONTH) + 1;
        s.day = cal.get(Calendar.DAY_OF_MONTH);
        int minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        // Debug overrides (debug_* keys only; set via the nonce'd am-start channel or DebugReceiver).
        int dbgMinute = DebugFlags.timeMinute(cfg.get(DebugFlags.KEY_TIME, null));
        int dbgWx = DebugFlags.wxCode(cfg.get(DebugFlags.KEY_WX, null));
        boolean dbgOffline = "1".equals(cfg.get(DebugFlags.KEY_OFFLINE, "").trim());
        if (dbgMinute >= 0) minute = dbgMinute;
        s.minute = minute;
        s.clockPinned = dbgMinute >= 0;
        s.clockMs = ClockFormat.pinnedMillis(nowMs, dbgMinute, TimeZone.getDefault());   // the strip shows TV time

        String dayKey = cityKey + "|" + s.year + "-" + s.month + "-" + s.day;
        if (!dayKey.equals(sunKey)) {
            Calendar noon = Calendar.getInstance(tz);
            noon.clear();
            noon.set(s.year, s.month - 1, s.day, 12, 0, 0);
            sunTzHours = tz.getOffset(noon.getTimeInMillis()) / 3.6e6;
            sunTimes = Solar.times(city.lat, city.lon, s.year, s.month, s.day, sunTzHours);
            sunEmax = Solar.elevationMax(city.lat, city.lon, s.year, s.month, s.day, sunTzHours);
            sunKey = dayKey;
        }
        Solar.Times times = sunTimes;
        s.rise = times.rise; s.set = times.set; s.noon = times.noon;

        // Theme phase and halo.
        boolean auto = s.themeMode == 0;
        ThemePhase.Phase ph;
        if (auto) {
            ph = ThemePhase.at(minute, times);
            s.light = ThemePhase.light(ph.p);
            float[] h = ThemePhase.halo(ph, s.light, Theme.HALO_CAP_DARK, Theme.HALO_CAP_LIGHT, Theme.TN_RAMP);
            s.ts = h[0]; s.tn = h[1];
        } else if (s.themeMode == 1) {
            ph = new ThemePhase.Phase("light", 1f, 0f);
            s.light = true;
        } else {
            ph = new ThemePhase.Phase("dark", 0f, 0f);
            s.light = false;
        }
        s.phase = ph.name; s.p = ph.p; s.g = ph.g;

        s.sky = auto && "sky".equals(wallpaper);
        s.wallName = "sky".equals(wallpaper) ? Ui.NIGHT_WALL_DEFAULT : wallpaper;

        // Weather (cache read only; the fetch runs on its own thread).
        WeatherClient.Cached k = WeatherClient.cached(app);
        // usable() compares against the coordinate key (WeatherParse.cacheUsable), not the city id string.
        boolean usable = !dbgOffline && weatherOn && WeatherClient.usable(k, city.lat, city.lon, nowMs);
        if (weatherOn && !dbgOffline) {
            WeatherClient.maybeFetch(app, city.lat, city.lon, true, nowMs, () -> {
                AmbientState st = evaluate(System.currentTimeMillis(), true);
                if (started) deliver(st, true);
            });
        }
        WeatherParse.Weather w = usable ? k.w : null;
        int code = dbgWx >= 0 ? dbgWx : w != null ? w.code : 0;
        s.cond = usable || dbgWx >= 0 ? WeatherParse.condition(code) : SkyPhase.WX_NONE;
        s.raining = (usable || dbgWx >= 0) && WeatherParse.raining(code);
        String today = s.year + "-" + (s.month < 10 ? "0" : "") + s.month + "-" + (s.day < 10 ? "0" : "") + s.day;
        // debug_wx pins the whole chip: no real rain chance (it would read "Rain 1%" under debug rain).
        int[] pop = usable && dbgWx < 0 ? WeatherParse.popFor(w, today) : null;
        int hour = minute / 60;
        boolean useF = RainText.useF(units, cc);
        s.wxShown = usable;
        int likely = RainText.likelyHour(pop, hour);
        int pct = pop != null && hour >= 0 && hour < pop.length ? pop[hour] : -1;
        if (dbgWx >= 0) pct = DebugFlags.debugRainPct(s.raining, pct);   // debug rain reads "Rain 70%", never a bare "Rain"
        if (usable) {
            s.wxTemp = RainText.temp(w.tempC, useF);
            s.wxRain = RainText.rainNowPct(s.raining, pct);
            s.wxHighLow = RainText.highLow(w.maxC, w.minC, useF);
            // debug_time / debug_wx pin day versus night from the pinned minute's sun elevation (not the real fetch's
            // isDay), so the glyph matches the pinned scene.
            boolean isDay = w.isDay;
            if (dbgMinute >= 0 || dbgWx >= 0) {
                isDay = DebugFlags.isDayAtElevation(
                        Solar.position(city.lat, city.lon, s.year, s.month, s.day, sunTzHours, minute).elevation);
            }
            s.wxGlyph = WeatherParse.glyph(code, isDay);
        }
        s.wxCaption = s.raining && usable ? RainText.RAINING_NOW : RainText.caption(city.name, likely, times.rise, times.set, minute);

        // Sky frame.
        if (s.sky) {
            Solar.Pos pos = Solar.position(city.lat, city.lon, s.year, s.month, s.day, sunTzHours, minute);
            boolean rising = minute < times.noon;
            s.frame = SkyPhase.frame(pos.elevation, pos.azimuth, rising, sunEmax, minute, times.rise, times.set, ph.p, s.cond);
        }

        // TV-local strings.
        Calendar tv = Calendar.getInstance(TimeZone.getDefault());
        tv.setTimeInMillis(nowMs);
        int tvMinute = dbgMinute >= 0 ? dbgMinute : tv.get(Calendar.HOUR_OF_DAY) * 60 + tv.get(Calendar.MINUTE);
        s.greeting = Greeting.at(tvMinute);
        s.dateLabel = RainText.dateLabel(tv.get(Calendar.YEAR), tv.get(Calendar.MONTH) + 1, tv.get(Calendar.DAY_OF_MONTH));
        s.dreamLine = RainText.dreamLine(s.dateLabel, usable, s.wxTemp, s.raining, pct, likely, city.name);

        lastEvalMs = nowMs;
        last = s;
        return s;
    }

    private void deliver(AmbientState s, boolean force) {
        Listener l = listener;
        if (l == null) return;
        if (!force && !s.jump && delivered != null && !differs(delivered, s)) return;
        delivered = s;
        l.onAmbient(s);
    }

    private static boolean near(float a, float b) { return Math.abs(a - b) <= EPS; }

    private static boolean differs(AmbientState a, AmbientState b) {
        if (a.light != b.light || a.clockPinned != b.clockPinned || a.sky != b.sky || a.wxShown != b.wxShown || a.wxGlyph != b.wxGlyph
                || a.cond != b.cond || a.raining != b.raining) return true;
        if (!near(a.ts, b.ts) || !near(a.tn, b.tn) || !near(a.p, b.p) || !near(a.g, b.g)) return true;
        if (!a.wallName.equals(b.wallName) || !a.wxTemp.equals(b.wxTemp) || !a.wxRain.equals(b.wxRain)
                || !a.wxHighLow.equals(b.wxHighLow) || !a.wxCaption.equals(b.wxCaption)
                || !a.greeting.equals(b.greeting) || !a.dateLabel.equals(b.dateLabel)
                || !a.dreamLine.equals(b.dreamLine)) return true;
        SkyPhase.Frame x = a.frame, y = b.frame;
        if (x == null || y == null) return x != y;
        if (x.a != y.a || x.b != y.b || x.rainOn != y.rainOn || x.scrimRgb != y.scrimRgb) return true;
        return !near(x.f, y.f) || !near(x.oc, y.oc) || !near(x.fogDay, y.fogDay) || !near(x.fogNight, y.fogNight)
                || !near(x.rdim, y.rdim) || !near(x.rainAlpha, y.rainAlpha) || !near(x.rainDay, y.rainDay) || !near(x.rainLightFactor, y.rainLightFactor)
                || !near(x.sunX, y.sunX) || !near(x.sunY, y.sunY) || !near(x.sunA, y.sunA)
                || !near(x.moonX, y.moonX) || !near(x.moonY, y.moonY) || !near(x.moonA, y.moonA);
    }
}
