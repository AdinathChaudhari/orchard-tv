package tv.orchard.home;

import java.util.ArrayList;
import java.util.List;

/**
 * PURE JAVA (no android.* imports; host-tested). Bookkeeping for baked full-screen bitmaps (SkyView's composed
 * sky stacks, 2 MB each): which ones are alive, which must be released. The caller does the actual recycling.
 * Oldest bakes go first; the protected ones (the bake on screen and the one fading out) are never returned.
 */
public final class BakeLedger<T> {
    private final List<T> items = new ArrayList<T>();
    private final List<Integer> sizes = new ArrayList<Integer>();
    private long bytes;

    public int count() { return items.size(); }
    public long bytes() { return bytes; }

    /** Registers a new bake (newest). */
    public void add(T item, int size) {
        items.add(item);
        sizes.add(size);
        bytes += size;
    }

    /** Removes and returns the oldest bakes (never keepA / keepB) until count <= maxCount and bytes <= maxBytes. */
    public List<T> trim(int maxCount, long maxBytes, T keepA, T keepB) {
        List<T> out = new ArrayList<T>();
        int i = 0;
        while ((items.size() > maxCount || bytes > maxBytes) && i < items.size()) {
            T it = items.get(i);
            if (it == keepA || it == keepB) { i++; continue; }
            out.add(it);
            drop(i);
        }
        return out;
    }

    /** Phase change: removes and returns every bake except keepA / keepB. */
    public List<T> retainOnly(T keepA, T keepB) {
        List<T> out = new ArrayList<T>();
        int i = 0;
        while (i < items.size()) {
            T it = items.get(i);
            if (it == keepA || it == keepB) { i++; continue; }
            out.add(it);
            drop(i);
        }
        return out;
    }

    /** Removes and returns everything (onStop / release). */
    public List<T> clear() {
        List<T> out = new ArrayList<T>(items);
        items.clear();
        sizes.clear();
        bytes = 0;
        return out;
    }

    private void drop(int i) {
        items.remove(i);
        bytes -= sizes.remove(i);
    }
}
