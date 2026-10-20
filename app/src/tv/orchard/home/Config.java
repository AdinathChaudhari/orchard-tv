package tv.orchard.home;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PURE JAVA (no android.* imports; host-tested). key=value settings file: parse, atomic write,
 * mtime signature refresh, unknown keys kept. State.java reuses parse()/serialize().
 */
public final class Config {

    /** Every key named anywhere in PLAN.md. Values noted in comments. */
    public static final class Keys {
        private Keys() {}
        // settings (orchard.conf)
        public static final String LABELS = "labels";                 // focus | always
        public static final String MOTION = "motion";                 // full | reduced
        public static final String CLOCK = "clock";                   // on | off
        public static final String CLOCK_24H = "clock_24h";           // on | off
        public static final String WALLPAPER = "wallpaper";           // sky | dusk | ember | fog | black | user (default sky)
        public static final String THEME = "theme";                   // auto | light | dark (default auto)
        public static final String WEATHER = "weather";               // on | off (default on)
        public static final String UNITS = "units";                   // auto | c | f (default auto)
        public static final String CITY_ID = "city_id";               // id from cities.csv (default bengaluru)
        public static final String CITY_LAT = "city_lat";             // custom city latitude, '%.4f'
        public static final String CITY_LON = "city_lon";             // custom city longitude, '%.4f'
        public static final String CITY_NAME = "city_name";           // custom city label, max 24 chars
        public static final String CITY_TZ = "city_tz";               // optional IANA id for a custom city; absent = the TV's own zone
        public static final String FIRETV_TILE = "firetv_tile";       // on | off (default on)
        public static final String LAUNCH_ANIM = "launch_anim";       // on | off (default on)
        public static final String HERO = "hero";                     // wash | blur
        public static final String CARD = "card";                     // emblem | banner | off (default emblem; legacy on = banner)
        public static final String DOCK = "dock";                     // ':'-less comma list of packages, max 6
        public static final String HIDDEN = "hidden";                 // comma list of packages
        public static final String SHOWN = "shown";                   // comma list of packages the user explicitly unhid
        public static final String ORDER = "order";                   // comma list of packages (grid order)
        public static final String RECENT_PREFIX = "recent.";         // recent.<pkg>=<epoch ms>
        // flags the manager sets over adb
        public static final String CRASHGUARD_STRIP = "crashguard_strip"; // on | off
        public static final String DEBUG_CRASH = "debug_crash";       // 1 enables --ez orchard.crash / orchard.hang
        public static final String DEBUG_SLOW_ANIM = "debug_slow_anim"; // multiplier, e.g. 10
        public static final String DEBUG_CARD = "debug_card";         // emblem | banner | off, overrides card (debug flag)
        public static final String DEBUG_MOTION = "debug_motion";     // reduced | full, overrides motion (debug flag)
        public static final String DEBUG_ART = "debug_art";           // placeholder = letter plates instead of real banners/icons (debug flag)
        public static final String CRASH_ON_HOME = "crash_on_home";   // 1, R6c test flag
        // runtime state (state.conf in getFilesDir)
        public static final String SAFE_MODE = "safe_mode";           // 1 = show SafeModeView
        public static final String BOOT_ATTEMPTS = "boot_attempts";
        public static final String LAST_HOME_LAUNCH_AT = "last_home_launch_at";
        public static final String LAUNCH_VIA_HOME = "launch_via_home"; // "1" when the current launch carried CATEGORY_HOME, else "0"
        public static final String LAST_CRASH = "last_crash";
        public static final String REDIRECT_OFF_AT = "redirect_off_at";
        public static final String HOME_PRESS_SEEN_AT = "home_press_seen_at"; // epoch ms, onUserLeaveHint
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long MAX_BYTES = 1L << 20;
    private static final String SIG_UNSET = "unset";

    private final File file;
    private final Map<String, String> values = new LinkedHashMap<String, String>();
    private String signature = SIG_UNSET;

    /** Loads the file immediately (a missing file is an empty config). */
    public Config(File file) {
        this.file = file;
        refresh();
    }

    public File file() { return file; }

    /**
     * Parse key=value lines. '#' comment lines and blank lines are skipped, keys and values are trimmed,
     * lines without '=' are ignored, a later duplicate key wins, unknown keys are kept in file order.
     * Comments are not preserved by serialize().
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.charAt(0) == '#') continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String k = t.substring(0, eq).trim();
            if (k.isEmpty()) continue;
            String v = t.substring(eq + 1).trim();
            out.put(k, v);
        }
        return out;
    }

    /** Inverse of parse(). Line breaks inside keys or values are flattened to spaces. */
    public static String serialize(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        if (map == null) return "";
        for (Map.Entry<String, String> e : map.entrySet()) {
            String k = flat(e.getKey());
            String v = e.getValue() == null ? "" : flat(e.getValue());
            if (k.isEmpty()) continue;
            sb.append(k).append('=').append(v).append('\n');
        }
        return sb.toString();
    }

    private static String flat(String s) {
        return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String sigOf() {
        return file.isFile() ? file.lastModified() + ":" + file.length() : "missing";
    }

    /**
     * Re-read the file if its mtime/length signature changed. Returns true when reloaded. Unsaved set()
     * calls are discarded on reload; a deleted file reloads as empty.
     */
    public synchronized boolean refresh() {
        String now = sigOf();
        if (now.equals(signature)) return false;
        Map<String, String> fresh = new LinkedHashMap<String, String>();
        if (file.isFile()) {
            try {
                fresh = parse(readAll(file));
            } catch (IOException e) {
                return false;       // keep the old values and retry next time
            }
        }
        values.clear();
        values.putAll(fresh);
        signature = now;
        return true;
    }

    private static String readAll(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_BYTES) throw new IOException("config too large");
            }
            return new String(bo.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }

    public synchronized String get(String key, String def) {
        String v = values.get(key);
        return v == null ? def : v;
    }

    /** on/1/true/yes -> true; off/0/false/no -> false (case-insensitive); anything else -> def. */
    public synchronized boolean getBool(String key, boolean def) {
        String v = values.get(key);
        if (v == null) return def;
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("on") || s.equals("1") || s.equals("true") || s.equals("yes")) return true;
        if (s.equals("off") || s.equals("0") || s.equals("false") || s.equals("no")) return false;
        return def;
    }

    public synchronized int getInt(String key, int def) {
        String v = values.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    public synchronized void set(String key, String value) { values.put(key, value); }

    /** Copy of the current values (for key scans). */
    public synchronized Map<String, String> snapshot() { return new LinkedHashMap<String, String>(values); }

    public synchronized void remove(String key) { values.remove(key); }

    /** Atomic write: temp file in the same dir, then rename. Updates the signature so our own write is not reloaded. */
    public synchronized void save() throws IOException {
        CrashGuard.writeAtomic(file, serialize(values).getBytes(UTF8));
        signature = sigOf();
    }
}
