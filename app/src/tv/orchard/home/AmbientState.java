package tv.orchard.home;

/**
 * PURE JAVA (no android.* imports; host-tested). One snapshot of everything ambient (clock, date, city,
 * sun, theme phase, sky frame, weather chip, screensaver line). Filled by Ambient, read by views.
 * Plain public fields on purpose: a value holder, copied before it crosses threads.
 */
public final class AmbientState {
    public int minute, year, month, day;
    /** The instant the TV-zone clocks show: real now, or today at the debug_time minute when clockPinned. */
    public long clockMs;
    public boolean clockPinned;
    public String dateLabel = "", greeting = "";
    public String cityKey = "", cityName = "", cityCc = "", tzId = "";
    public double lat, lon;
    /** Sunrise, sunset, solar noon in minutes of the local day; NaN when polar. */
    public double rise = Double.NaN, set = Double.NaN, noon = Double.NaN;
    /** 0 auto, 1 light, 2 dark. */
    public int themeMode;
    public String phase = "";
    public float p, g;
    public boolean light;
    public float ts, tn;
    /** Theme auto and wallpaper sky. */
    public boolean sky;
    /** Static night wall: dusk|ember|fog|black|user. */
    public String wallName = "dusk";
    /** Null unless sky. */
    public SkyPhase.Frame frame;
    public boolean wxShown;
    public int wxGlyph;
    public String wxTemp = "", wxRain = "", wxHighLow = "", wxCaption = "", dreamLine = "";
    public boolean raining;
    /** SkyPhase.WX_*. */
    public int cond = SkyPhase.WX_NONE;
    /** Fade 900 ms instead of snapping. */
    public boolean jump;

    /** Shallow copy (Strings and the Frame are treated as immutable). */
    public AmbientState copy() {
        AmbientState c = new AmbientState();
        c.minute = minute; c.year = year; c.month = month; c.day = day;
        c.clockMs = clockMs; c.clockPinned = clockPinned;
        c.dateLabel = dateLabel; c.greeting = greeting;
        c.cityKey = cityKey; c.cityName = cityName; c.cityCc = cityCc; c.tzId = tzId;
        c.lat = lat; c.lon = lon;
        c.rise = rise; c.set = set; c.noon = noon;
        c.themeMode = themeMode; c.phase = phase;
        c.p = p; c.g = g; c.light = light; c.ts = ts; c.tn = tn;
        c.sky = sky; c.wallName = wallName; c.frame = frame;
        c.wxShown = wxShown; c.wxGlyph = wxGlyph;
        c.wxTemp = wxTemp; c.wxRain = wxRain; c.wxHighLow = wxHighLow; c.wxCaption = wxCaption; c.dreamLine = dreamLine;
        c.raining = raining; c.cond = cond; c.jump = jump;
        return c;
    }
}
