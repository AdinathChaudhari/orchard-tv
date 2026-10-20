package tv.orchard.home;

/**
 * PURE JAVA (host-tested). One-time nonce gate for the am-start debug channel in HomeActivity.onCreate.
 * Android keeps the extras of an {@code am start} as the task's ROOT intent and replays them when the process dies
 * and Home is recreated (for example after another app's memory use kills Orchard). Without a gate that replay
 * re-applied debug_art=placeholder and the real TV showed letter plates. onCreate therefore applies debug extras
 * only when the intent carries a nonce (EXTRA) that differs from the last one consumed (stored in State). A replay
 * carries the already consumed nonce, so it is refused. onNewIntent only ever sees fresh live deliveries and needs no nonce.
 */
public final class DebugNonce {
    private DebugNonce() {}

    public static final String EXTRA = "orchard.debug_nonce";
    public static final String KEY = "debug_nonce_consumed";

    /** Minimal key/value store; State implements it. */
    public interface Store {
        String get(String key, String def);
        void put(String key, String value);
    }

    /**
     * null/empty nonce: refuse. Same as the last consumed nonce: refuse. New nonce: record it and accept.
     * The nonce is recorded before the flags are applied, so a crash while applying cannot cause a second apply.
     */
    public static boolean consume(Store store, String nonce) {
        if (store == null || nonce == null) return false;
        String n = nonce.trim();
        if (n.isEmpty()) return false;
        if (n.equals(store.get(KEY, null))) return false;
        store.put(KEY, n);
        return true;
    }
}
