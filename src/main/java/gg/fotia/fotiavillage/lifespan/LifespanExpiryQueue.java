package gg.fotia.fotiavillage.lifespan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;

final class LifespanExpiryQueue {
    private final Map<UUID, Long> deadlines = new HashMap<>();
    private final PriorityQueue<ExpiryEntry> queue = new PriorityQueue<>((left, right) -> Long.compare(left.deadline(), right.deadline()));

    void track(UUID villagerId, long deadline) {
        deadlines.put(villagerId, deadline);
        queue.add(new ExpiryEntry(villagerId, deadline));
    }

    void untrack(UUID villagerId) {
        deadlines.remove(villagerId);
    }

    List<UUID> pollExpired(long now, int maxEntries) {
        if (maxEntries <= 0) {
            return List.of();
        }
        List<UUID> expired = new ArrayList<>(Math.min(maxEntries, deadlines.size()));
        while (expired.size() < maxEntries && !queue.isEmpty() && queue.peek().deadline() <= now) {
            ExpiryEntry entry = queue.poll();
            Long currentDeadline = deadlines.get(entry.villagerId());
            if (currentDeadline == null || currentDeadline.longValue() != entry.deadline()) {
                continue;
            }
            deadlines.remove(entry.villagerId());
            expired.add(entry.villagerId());
        }
        return expired;
    }

    void clear() {
        deadlines.clear();
        queue.clear();
    }

    private record ExpiryEntry(UUID villagerId, long deadline) {
    }
}
