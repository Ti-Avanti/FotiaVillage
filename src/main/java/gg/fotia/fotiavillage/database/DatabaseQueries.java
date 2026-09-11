package gg.fotia.fotiavillage.database;

import gg.fotia.fotiavillage.config.FotiaSettings.Performance;
import gg.fotia.fotiavillage.stats.PlayerTradeStats;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

final class DatabaseQueries {
    private final DatabaseTaskQueue queue;
    private final SqliteStore store;
    private final AsyncQueryCache<UUID, Optional<PlayerTradeStats>> stats = new AsyncQueryCache<>();
    private final AsyncQueryCache<String, Optional<PlayerTradeStats>> names = new AsyncQueryCache<>();
    private final AsyncQueryCache<Integer, List<PlayerTradeStats>> top = new AsyncQueryCache<>();
    private final AsyncQueryCache<UUID, Integer> ranks = new AsyncQueryCache<>();

    DatabaseQueries(DatabaseTaskQueue queue, SqliteStore store) {
        this.queue = queue;
        this.store = store;
    }

    void configure(Performance settings) {
        int maximum = settings.databaseCacheMaxEntries();
        long readTtl = settings.databaseReadCacheSeconds() * 1000L;
        long rankTtl = settings.leaderboardCacheSeconds() * 1000L;
        long snapshotTtl = settings.databaseCacheCleanupIntervalSeconds() * 2000L;
        stats.configure(maximum, readTtl, snapshotTtl);
        names.configure(maximum, readTtl, snapshotTtl);
        top.configure(maximum, rankTtl, snapshotTtl);
        ranks.configure(maximum, rankTtl, snapshotTtl);
    }

    CompletableFuture<Optional<PlayerTradeStats>> stats(UUID uuid) {
        return stats.load(uuid, () -> read(() -> store.findStats(uuid)));
    }

    CompletableFuture<Optional<PlayerTradeStats>> named(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return names.load(key, () -> read(() -> store.findStatsByName(key)));
    }

    CompletableFuture<List<PlayerTradeStats>> top(int limit) {
        return top.load(limit, () -> read(() -> store.leaderboard(limit)));
    }

    int rank(UUID uuid) {
        return ranks.peekOrLoad(uuid, () -> read(() -> store.rank(uuid)), -1);
    }

    Optional<PlayerTradeStats> cachedStats(UUID uuid) {
        return stats.peekOrLoad(uuid, () -> read(() -> store.findStats(uuid)), Optional.empty());
    }

    Optional<PlayerTradeStats> cachedName(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        return names.peekOrLoad(key, () -> read(() -> store.findStatsByName(key)), Optional.empty());
    }

    List<PlayerTradeStats> cachedTop(int limit) {
        return top.peekOrLoad(limit, () -> read(() -> store.leaderboard(limit)), List.of());
    }

    void tradeRecorded(UUID uuid, String name) {
        stats.invalidate(uuid);
        names.invalidate(name.toLowerCase(Locale.ROOT));
        // 全服排名按配置的 TTL 更新，交易不会使所有玩家的排名缓存同时失效。
    }

    void clear() {
        stats.clear();
        names.clear();
        top.clear();
        ranks.clear();
    }

    void purgeExpired() {
        stats.purgeExpired();
        names.purgeExpired();
        top.purgeExpired();
        ranks.purgeExpired();
    }

    private <T> CompletableFuture<T> read(Callable<T> action) {
        try {
            return queue.submit(action, false);
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }
    }
}
