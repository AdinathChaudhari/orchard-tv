package tv.orchard.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Weather fetch and cache (PLAN section 7.2). One HTTPS GET to Open-Meteo, off the main thread.
 *
 * <p>Privacy rule: the only data that leaves the TV is the request URL, which carries the city
 * coordinates rounded to 2 decimals (about 1 km) and the fixed User-Agent. Nothing else is sent.
 * Weather OFF means no request at all.
 *
 * <p>Every failure is swallowed: no toast, no dialog, no retry loop. The cache is only replaced by a
 * body that WeatherParse accepts.
 */
public final class WeatherClient {
    private static final String TAG = "OrchardWx";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String BODY_FILE = "weather.json";
    private static final String CONF_FILE = "weather.conf";
    private static final String K_FETCHED = "fetched_at";
    private static final String K_CITY = "city_key";
    private static final long REFRESH_MS = WeatherParse.REFRESH_MIN * 60000L;

    /** Called on the main thread, only after a new valid cache was written. */
    public interface Callback { void onWeather(); }

    public static final class Cached {
        public final WeatherParse.Weather w;
        public final long fetchedAtMs;
        public final String cityKey;

        Cached(WeatherParse.Weather w, long fetchedAtMs, String cityKey) {
            this.w = w;
            this.fetchedAtMs = fetchedAtMs;
            this.cityKey = cityKey;
        }
    }

    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);
    private static final Object LOCK = new Object();

    // guarded by LOCK
    private static Cached mem;
    private static String memSig;
    private static String lastAttemptKey;
    private static long lastAttemptMs;

    private WeatherClient() {}

    private static String sig(File body, File conf) {
        if (!body.isFile() || !conf.isFile()) return null;
        return body.lastModified() + ":" + body.length() + "/" + conf.lastModified() + ":" + conf.length();
    }

    /** Cached weather, or null when either file is missing or unparsable. Cheap: re-reads only on change. */
    public static Cached cached(Context c) {
        File dir = c.getApplicationContext().getFilesDir();
        File body = new File(dir, BODY_FILE);
        File conf = new File(dir, CONF_FILE);
        String s = sig(body, conf);
        synchronized (LOCK) {
            if (s == null) {
                mem = null;
                memSig = null;
                return null;
            }
            if (s.equals(memSig)) return mem;
        }
        Cached k = null;
        try {
            String json = readSmall(body);
            Map<String, String> m = Config.parse(readSmall(conf));
            String city = m.get(K_CITY);
            String at = m.get(K_FETCHED);
            if (json != null && city != null && !city.isEmpty() && at != null) {
                WeatherParse.Weather w = WeatherParse.parse(json);
                if (w != null) k = new Cached(w, Long.parseLong(at), city);
            }
        } catch (IOException e) {
            k = null;
        } catch (RuntimeException e) {
            k = null;
        }
        synchronized (LOCK) {
            mem = k;
            memSig = s;
        }
        return k;
    }

    public static boolean usable(Cached k, double lat, double lon, long now) {
        return k != null && WeatherParse.cacheUsable(k.cityKey, lat, lon, k.fetchedAtMs, now);
    }

    /**
     * Start at most one background fetch. Returns at once. See class doc for the rules: off when
     * disabled, one in flight, no fetch while the cache for this city is fresh, and at most one attempt
     * per city per REFRESH_MIN (no retry loop).
     */
    public static void maybeFetch(Context c, double lat, double lon, boolean enabled, long now,
                                  final Callback cb) {
        if (!enabled || c == null) return;
        final Context app = c.getApplicationContext();
        final String key = WeatherParse.cityKey(lat, lon);
        Cached k = cached(app);
        if (k != null && k.cityKey.equals(key) && !WeatherParse.needsRefresh(k.fetchedAtMs, now)) return;
        synchronized (LOCK) {
            if (key.equals(lastAttemptKey) && now >= lastAttemptMs && now - lastAttemptMs < REFRESH_MS) return;
            if (!IN_FLIGHT.compareAndSet(false, true)) return;
            lastAttemptKey = key;
            lastAttemptMs = now;
        }
        final String url = WeatherParse.url(lat, lon);
        Thread t = new Thread(new Runnable() {
            @Override public void run() { fetch(app, url, key, cb); }
        }, "orchard-weather");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        try {
            t.start();
        } catch (RuntimeException e) {
            IN_FLIGHT.set(false);
        }
    }

    /** Delete both cache files and the memory copy (the city changed). */
    public static void invalidate(Context c) {
        File dir = c.getApplicationContext().getFilesDir();
        synchronized (LOCK) {
            new File(dir, BODY_FILE).delete();
            new File(dir, CONF_FILE).delete();
            new File(dir, BODY_FILE + ".tmp").delete();
            new File(dir, CONF_FILE + ".tmp").delete();
            mem = null;
            memSig = null;
        }
    }

    private static void fetch(Context app, String url, String key, final Callback cb) {
        HttpURLConnection conn = null;
        try {
            if (!url.startsWith("https://")) return;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(WeatherParse.CONNECT_MS);
            conn.setReadTimeout(WeatherParse.READ_MS);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Orchard (Fire TV launcher)");
            int rc = conn.getResponseCode();
            if (rc != 200) {
                Log.w(TAG, "fetch rejected: http " + rc);
                return;
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[2048];
                int limit = WeatherParse.MAX_BYTES + 1;
                int n;
                while (bo.size() < limit && (n = in.read(buf, 0, Math.min(buf.length, limit - bo.size()))) > 0) {
                    bo.write(buf, 0, n);
                }
            } finally {
                in.close();
            }
            if (bo.size() > WeatherParse.MAX_BYTES) {
                Log.w(TAG, "fetch rejected: oversize body");
                return;
            }
            byte[] raw = bo.toByteArray();
            String json = new String(raw, UTF8);
            WeatherParse.Weather parsed = WeatherParse.parse(json);
            if (parsed == null) {
                Log.w(TAG, "fetch rejected: parse failed");
                return;
            }
            long fetchedAt = System.currentTimeMillis();
            File dir = app.getFilesDir();
            Map<String, String> m = new LinkedHashMap<String, String>();
            m.put(K_FETCHED, Long.toString(fetchedAt));
            m.put(K_CITY, key);
            synchronized (LOCK) {
                CrashGuard.writeAtomic(new File(dir, BODY_FILE), raw);
                CrashGuard.writeAtomic(new File(dir, CONF_FILE), Config.serialize(m).getBytes(UTF8));
                mem = null;
                memSig = null;
            }
            Log.i(TAG, "fetch ok: " + parsed.tempC + " C, code " + parsed.code + ", key " + key);
            if (cb != null) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() { cb.onWeather(); }
                });
            }
        } catch (IOException e) {
            Log.w(TAG, "fetch failed: " + e.getClass().getSimpleName());
        } catch (SecurityException e) {
            Log.w(TAG, "fetch failed: SecurityException");
        } catch (RuntimeException e) {
            Log.w(TAG, "fetch failed: " + e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
            IN_FLIGHT.set(false);
        }
    }

    /** Read a file of at most MAX_BYTES; larger or missing returns null. */
    private static String readSmall(File f) throws IOException {
        if (!f.isFile() || f.length() > WeatherParse.MAX_BYTES) return null;
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() <= WeatherParse.MAX_BYTES) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }
}
