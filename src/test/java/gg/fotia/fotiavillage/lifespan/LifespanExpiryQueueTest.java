package gg.fotia.fotiavillage.lifespan;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifespanExpiryQueueTest {
    @Test
    void returnsOnlyEntriesThatHaveExpired() {
        LifespanExpiryQueue queue = new LifespanExpiryQueue();
        UUID expired = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        queue.track(expired, 1_000L);
        queue.track(active, 2_000L);

        assertEquals(List.of(expired), queue.pollExpired(1_500L, 10));
        assertTrue(queue.pollExpired(1_500L, 10).isEmpty());
    }

    @Test
    void ignoresStaleDeadlineAfterLifespanIsExtended() {
        LifespanExpiryQueue queue = new LifespanExpiryQueue();
        UUID villager = UUID.randomUUID();
        queue.track(villager, 1_000L);
        queue.track(villager, 3_000L);

        assertTrue(queue.pollExpired(2_000L, 10).isEmpty());
        assertEquals(List.of(villager), queue.pollExpired(3_000L, 10));
    }

    @Test
    void respectsPerCheckBudget() {
        LifespanExpiryQueue queue = new LifespanExpiryQueue();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        queue.track(first, 1_000L);
        queue.track(second, 1_000L);

        assertEquals(1, queue.pollExpired(1_000L, 1).size());
        assertEquals(1, queue.pollExpired(1_000L, 1).size());
    }

    @Test
    void untrackedEntryNeverExpires() {
        LifespanExpiryQueue queue = new LifespanExpiryQueue();
        UUID villager = UUID.randomUUID();
        queue.track(villager, 1_000L);

        queue.untrack(villager);

        assertTrue(queue.pollExpired(2_000L, 10).isEmpty());
    }
}
