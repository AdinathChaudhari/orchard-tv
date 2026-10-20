package tv.orchard.home;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * PURE JAVA (no android.* imports; host-tested). Crash timestamps file + window rule + the
 * uncaught-exception handler. The Android-side policy (strip HoF) is injected as a Listener.
 * PLAN section 2.2.
 */
public final class CrashGuard {
    public static final int WINDOW_COUNT = 3;
    public static final long WINDOW_MS = 60000L;
    public static final int KEEP = 10;
    public static final int BOOT_ATTEMPT_LIMIT = 3;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Called inside the dying process after the crash timestamp was recorded. Must not throw. */
    public interface Listener {
        /**
         * @param t              the uncaught exception
         * @param windowLooping  true when >= WINDOW_COUNT crashes fall within WINDOW_MS of now
         *                       (the listener combines this with boot_attempts for the full policy)
         */
        void onCrash(Throwable t, boolean windowLooping);
    }

    private CrashGuard() {}

    /** True when at least WINDOW_COUNT timestamps fall within WINDOW_MS of now (inclusive; future ones ignored). */
    public static boolean window(long[] timestamps, long now) {
        if (timestamps == null) return false;
        int n = 0;
        for (long t : timestamps) {
            long d = now - t;
            if (d >= 0 && d <= WINDOW_MS) n++;
        }
        return n >= WINDOW_COUNT;
    }

    /** Strip HoF on a crash loop only when looping, granted, armed (crashguard_strip=on) and the launch came via Home. */
    public static boolean stripAllowed(boolean looping, boolean granted, boolean armed, boolean viaHome) {
        return looping && granted && armed && viaHome;
    }

    /** Append a timestamp to the crashes file, keeping the last KEEP. Never throws. */
    public static void record(File crashesFile, long now) {
        try {
            long[] old = read(crashesFile);
            int keepOld = Math.min(old.length, KEEP - 1);
            StringBuilder sb = new StringBuilder();
            for (int i = old.length - keepOld; i < old.length; i++) sb.append(old[i]).append('\n');
            sb.append(now).append('\n');
            writeAtomic(crashesFile, sb.toString().getBytes(UTF8));
        } catch (IOException e) {
            // best effort: we are usually inside a dying process
        } catch (RuntimeException e) {
            // same
        }
    }

    /** Read the crashes file, oldest first (empty array when missing or unreadable). */
    public static long[] read(File crashesFile) {
        if (crashesFile == null || !crashesFile.isFile()) return new long[0];
        try {
            InputStream in = new FileInputStream(crashesFile);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 65536) bo.write(buf, 0, n);
            } finally {
                in.close();
            }
            String[] lines = new String(bo.toByteArray(), UTF8).split("\n");
            long[] tmp = new long[lines.length];
            int c = 0;
            for (String l : lines) {
                String s = l.trim();
                if (s.isEmpty()) continue;
                try { long v = Long.parseLong(s); tmp[c++] = v; } catch (NumberFormatException e) { /* skip junk */ }
            }
            int from = Math.max(0, c - KEEP);
            long[] out = new long[c - from];
            System.arraycopy(tmp, from, out, 0, out.length);
            return out;
        } catch (IOException e) {
            return new long[0];
        }
    }

    /** Install the handler; records the crash, tells the listener, then chains to the previous handler. */
    public static void install(final File crashesFile, final Listener listener) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            private boolean busy;

            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                if (!busy) {
                    busy = true;
                    try {
                        long now = System.currentTimeMillis();
                        record(crashesFile, now);
                        boolean looping = window(read(crashesFile), now);
                        if (listener != null) listener.onCrash(ex, looping);
                    } catch (Throwable ignored) {
                        // never let the guard mask the original crash
                    }
                }
                if (prev != null) prev.uncaughtException(thread, ex);
            }
        });
    }

    /** Atomic file replace: temp file in the same directory, fsync, rename. */
    static void writeAtomic(File target, byte[] data) throws IOException {
        File dir = target.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create " + dir);
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(target)) {
            if (target.exists() && !target.delete()) {
                tmp.delete();
                throw new IOException("cannot replace " + target);
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("cannot rename to " + target);
            }
        }
    }
}
