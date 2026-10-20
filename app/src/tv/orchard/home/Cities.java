package tv.orchard.home;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Loads res/raw/cities.csv once and resolves the active city. Never returns an empty list. */
public final class Cities {
    private Cities() {}

    private static List<CityCommand.City> cache;

    /** All known cities, cached after the first read; falls back to a one-element Bengaluru list on any failure. */
    public static synchronized List<CityCommand.City> all(Context c) {
        if (cache != null) return cache;
        List<CityCommand.City> out = null;
        try {
            InputStream in = c.getApplicationContext().getResources().openRawResource(R.raw.cities);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                out = CityCommand.parseCsv(new String(bo.toByteArray(), Charset.forName("UTF-8")));
            } finally {
                try { in.close(); } catch (IOException e) { /* nothing to do */ }
            }
        } catch (IOException | RuntimeException e) {
            out = null;
        }
        if (out == null || out.isEmpty()) {
            out = new ArrayList<CityCommand.City>();
            out.add(new CityCommand.City("bengaluru", "Bengaluru", "IN", 12.9716, 77.5946, "Asia/Kolkata"));
        }
        cache = Collections.unmodifiableList(out);
        return cache;
    }

    /** The active city from the given config's current contents. */
    public static CityCommand.Resolved current(Context c, Config cfg) {
        return CityCommand.resolve(cfg.snapshot(), all(c));
    }
}
