package gg.fotia.fotiavillage.database;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

final class TimedCache<K, V> {
    private final Map<K, CacheEntry<V>> entries = new HashMap<>();
    private final LongSupplier clock;

    TimedCache() {
        this(System::currentTimeMillis);
    }

    TimedCache(LongSupplier clock) {
        this.clock = clock;
    }

    V get(K key) {
        CacheEntry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (clock.getAsLong() >= entry.expiresAt()) {
            entries.remove(key);
            return null;
        }
        return entry.value();
    }

    void put(K key, V value, long ttlMillis) {
        if (ttlMillis <= 0L) {
            entries.remove(key);
            return;
        }
        entries.put(key, new CacheEntry<>(value, safeAdd(clock.getAsLong(), ttlMillis)));
    }

    void invalidate(K key) {
        entries.remove(key);
    }

    void clear() {
        entries.clear();
    }

    private long safeAdd(long value, long increment) {
        if (Long.MAX_VALUE - value < increment) {
            return Long.MAX_VALUE;
        }
        return value + increment;
    }

    private record CacheEntry<V>(V value, long expiresAt) {
    }
}
