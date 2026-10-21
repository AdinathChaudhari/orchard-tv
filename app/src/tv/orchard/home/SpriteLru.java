package tv.orchard.home;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PURE JAVA (no android.* imports; host-tested). Least-recently-used cache bounded by BOTH entry count and bytes.
 * Halo uses one per sprite kind (text, path) so a long session of changing strings can never grow the heap past a
 * fixed ceiling. put() and trim() return the evicted values; the caller recycles them (it may need to delay that).
 */
public final class SpriteLru<K, V> {
    private final LinkedHashMap<K, V> map = new LinkedHashMap<K, V>(32, 0.75f, true);
    private final Map<K, Integer> sizes = new java.util.HashMap<K, Integer>();
    private final int maxCount;
    private final long maxBytes;
    private long bytes;

    public SpriteLru(int maxCount, long maxBytes) {
        this.maxCount = Math.max(1, maxCount);
        this.maxBytes = Math.max(1L, maxBytes);
    }

    public int count() { return map.size(); }
    public long bytes() { return bytes; }
    public long maxBytes() { return maxBytes; }
    public int maxCount() { return maxCount; }

    /** The cached value (marks it most recently used) or null. */
    public V get(K key) { return map.get(key); }

    /**
     * Adds (or replaces) an entry and returns every value evicted to make room (never the new one). An entry bigger
     * than the whole byte budget is refused: it is returned as the only evicted value and not stored.
     */
    public List<V> put(K key, V value, int size) {
        List<V> out = new ArrayList<V>();
        if (size > maxBytes) { out.add(value); return out; }
        V old = map.remove(key);
        if (old != null) { bytes -= sizes.remove(key); if (old != value) out.add(old); }
        while (!map.isEmpty() && (map.size() + 1 > maxCount || bytes + size > maxBytes)) {
            Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
            Map.Entry<K, V> e = it.next();
            out.add(e.getValue());
            bytes -= sizes.remove(e.getKey());
            it.remove();
        }
        map.put(key, value);
        sizes.put(key, size);
        bytes += size;
        return out;
    }

    /** Evicts least-recently-used entries until count <= count and bytes <= byteLimit; returns them. */
    public List<V> trimTo(int count, long byteLimit) {
        List<V> out = new ArrayList<V>();
        while (!map.isEmpty() && (map.size() > count || bytes > byteLimit)) {
            Iterator<Map.Entry<K, V>> it = map.entrySet().iterator();
            Map.Entry<K, V> e = it.next();
            out.add(e.getValue());
            bytes -= sizes.remove(e.getKey());
            it.remove();
        }
        return out;
    }

    /** Removes one entry; returns its value or null. */
    public V remove(K key) {
        V old = map.remove(key);
        if (old != null) bytes -= sizes.remove(key);
        return old;
    }

    /** Removes and returns everything. */
    public List<V> clear() {
        List<V> out = new ArrayList<V>(map.values());
        map.clear();
        sizes.clear();
        bytes = 0;
        return out;
    }
}
