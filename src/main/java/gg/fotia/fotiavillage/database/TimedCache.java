package gg.fotia.fotiavillage.database;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

final class TimedCache<K, V> {
    private final Map<K, CacheEntry<V>> entries = new LinkedHashMap<>(16, 0.75F, true);
    private final LongSupplier clock;
    private int maxEntries = 4096;

    TimedCache() {
        this(System::currentTimeMillis);
    }

    TimedCache(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized V get(K key) {
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

    synchronized void put(K key, V value, long ttlMillis) {
        if (ttlMillis <= 0L) {
            entries.remove(key);
            return;
        }
        entries.put(key, new CacheEntry<>(value, safeAdd(clock.getAsLong(), ttlMillis)));
        trim();
    }

    synchronized void invalidate(K key) {
        entries.remove(key);
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized void configure(int maximum) {
        maxEntries = Math.max(1, maximum);
        purgeExpired();
        trim();
    }

    synchronized void purgeExpired() {
        long now = clock.getAsLong();
        entries.values().removeIf(entry -> now >= entry.expiresAt());
    }

    private void trim() {
        var iterator = entries.keySet().iterator();
        while (entries.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
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
