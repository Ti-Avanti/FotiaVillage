package gg.fotia.fotiavillage.database;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TimedCacheTest {
    @Test
    void returnsCachedValueUntilItExpires() {
        AtomicLong now = new AtomicLong(1_000L);
        TimedCache<String, Integer> cache = new TimedCache<>(now::get);

        cache.put("trades", 12, 500L);

        assertEquals(12, cache.get("trades"));
        now.set(1_499L);
        assertEquals(12, cache.get("trades"));
        now.set(1_500L);
        assertNull(cache.get("trades"));
    }

    @Test
    void invalidationRemovesCachedValue() {
        TimedCache<String, Integer> cache = new TimedCache<>(() -> 1_000L);
        cache.put("trades", 12, 500L);

        cache.invalidate("trades");

        assertNull(cache.get("trades"));
    }

    @Test
    void clearRemovesEveryCachedValue() {
        TimedCache<String, Integer> cache = new TimedCache<>(() -> 1_000L);
        cache.put("first", 1, 500L);
        cache.put("second", 2, 500L);

        cache.clear();

        assertNull(cache.get("first"));
        assertNull(cache.get("second"));
    }

    @Test
    void nonPositiveTtlDisablesCaching() {
        TimedCache<String, Integer> cache = new TimedCache<>(() -> 1_000L);

        cache.put("trades", 12, 0L);

        assertNull(cache.get("trades"));
    }
}
