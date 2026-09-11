package gg.fotia.fotiavillage.lifespan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.UUID;

final class LifespanExpiryQueue {
    private final Map<UUID, Long> deadlines = new HashMap<>();
    private final NavigableSet<ExpiryEntry> queue = new TreeSet<>((left, right) -> {
        int order = Long.compare(left.deadline(), right.deadline());
        return order != 0 ? order : left.villagerId().compareTo(right.villagerId());
    });

    void track(UUID villagerId, long deadline) {
        Long previous = deadlines.put(villagerId, deadline);
        // 每个村民只保留一个到期条目，延寿和卸载时同步移除旧条目。
        if (previous == null || previous.longValue() != deadline) {
            if (previous != null) {
                queue.remove(new ExpiryEntry(villagerId, previous));
            }
            queue.add(new ExpiryEntry(villagerId, deadline));
        }
    }

    void untrack(UUID villagerId) {
        Long deadline = deadlines.remove(villagerId);
        if (deadline != null) {
            queue.remove(new ExpiryEntry(villagerId, deadline));
        }
    }

    List<UUID> pollExpired(long now, int maxEntries) {
        if (maxEntries <= 0) {
            return List.of();
        }
        List<UUID> expired = new ArrayList<>(Math.min(maxEntries, deadlines.size()));
        while (expired.size() < maxEntries && !queue.isEmpty() && queue.first().deadline() <= now) {
            ExpiryEntry entry = queue.pollFirst();
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
