package gg.fotia.fotiavillage.database;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.stats.PlayerTradeStats;
import gg.fotia.fotiavillage.trade.ScalingRecord;
import gg.fotia.fotiavillage.util.TimeUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** 主线程交易快照与后台存储之间的边界；锁内不执行 SQL 或等待 Future。 */
public final class DatabaseService {
    private final FotiaVillagePlugin plugin;
    private final File file;
    private final SqliteStore store;
    private final DatabaseTaskQueue queue;
    private final DatabaseQueries queries;
    private final Map<UUID, PlayerTradeState> states = new HashMap<>();
    private final Map<UUID, StateLoad> loading = new HashMap<>();
    private Batch transaction;
    private volatile boolean initialized;
    private volatile boolean closed;

    public DatabaseService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "data.db");
        store = new SqliteStore(file);
        var settings = plugin.settings().performance();
        queue = new DatabaseTaskQueue(plugin.getLogger(), store::close, settings.databaseQueueCapacity(),
            settings.databaseRetryIntervalSeconds() * 1000L, settings.databaseShutdownTimeoutSeconds() * 1000L);
        queries = new DatabaseQueries(queue, store);
        queries.configure(settings);
    }

    public CompletableFuture<Void> open() {
        var mode = plugin.settings().performance().databaseSynchronous();
        return queue.submit(() -> {
            store.open(mode);
            initialized = true;
            return null;
        }, true);
    }

    public synchronized void close() {
        closed = true;
        states.clear();
        loading.clear();
        queries.clear();
        queue.close();
    }

    public synchronized void clearReadCaches() {
        queries.clear();
        states.clear();
        loading.clear();
    }

    public CompletableFuture<Void> applyRuntimeSettings() {
        var settings = plugin.settings().performance();
        queries.configure(settings);
        queue.configure(settings.databaseQueueCapacity(), settings.databaseRetryIntervalSeconds() * 1000L,
            settings.databaseShutdownTimeoutSeconds() * 1000L);
        return queue.submit(() -> { store.applySettings(settings.databaseSynchronous()); return null; }, true);
    }

    public boolean isConnected() { return initialized && !closed && queue.healthy(); }
    public File file() { return file; }

    public CompletableFuture<Long> fileSizeAsync() {
        return queue.submit(file::length, false);
    }

    public synchronized void runInTransaction(Runnable action) {
        if (!isConnected()) throw new IllegalStateException("Database is not ready");
        if (transaction != null) {
            action.run();
            return;
        }
        Batch batch = new Batch();
        transaction = batch;
        try {
            action.run();
            if (batch.writes.isEmpty()) return;
            List<Consumer<SqliteStore>> writes = List.copyOf(batch.writes);
            UUID receipt = UUID.randomUUID();
            // 入队拒绝时不发布快照，由交易入口取消本次交易。
            queue.submit(() -> { store.writeOnce(receipt, writes); return null; }, true);
            states.putAll(batch.states);
            batch.statistics.forEach(queries::tradeRecorded);
        } finally {
            transaction = null;
        }
    }

    public synchronized boolean primeTradeState(UUID uuid, String resetKey, boolean limits, boolean cooldowns, boolean scaling) {
        if (!isConnected()) {
            requestState(uuid, resetKey);
            return false;
        }
        if (!limits && !cooldowns && !scaling) return true;
        PlayerTradeState state = states.get(uuid);
        if (state != null && state.resetKey.equals(resetKey)) return true;
        requestState(uuid, resetKey);
        return false;
    }

    public synchronized void preload(UUID uuid) {
        requestState(uuid, TimeUtil.resetKey(plugin.settings().tradeControl().limit().resetPeriod()));
    }

    public synchronized void forgetPlayer(UUID uuid) {
        states.remove(uuid);
        loading.remove(uuid);
    }

    private void requestState(UUID uuid, String resetKey) {
        if (closed) return;
        StateLoad pending = loading.get(uuid);
        if (pending != null && pending.resetKey().equals(resetKey)) return;
        if (states.containsKey(uuid) && states.get(uuid).resetKey.equals(resetKey)) return;
        CompletableFuture<PlayerTradeState> result;
        try {
            result = queue.submit(() -> store.loadTradeState(uuid, resetKey), false);
        } catch (RuntimeException ex) {
            return;
        }
        StateLoad request = new StateLoad(resetKey, result);
        loading.put(uuid, request);
        result.whenComplete((state, error) -> {
            synchronized (this) {
                if (loading.remove(uuid, request) && error == null && !closed) states.put(uuid, state);
            }
        });
    }

    public synchronized int getTradeCount(UUID uuid, String type, String key, String resetKey) {
        PlayerTradeState state = readState(uuid);
        if (state == null || !state.resetKey.equals(resetKey)) {
            requestState(uuid, resetKey);
            return 0;
        }
        return state.limits.getOrDefault(new PlayerTradeState.LimitKey(type, key), 0);
    }

    public synchronized void incrementTradeCount(UUID uuid, String type, String key, String resetKey) {
        PlayerTradeState state = writeState(uuid);
        if (!state.resetKey.equals(resetKey)) throw new IllegalStateException("Trade reset period changed");
        state.limits.merge(new PlayerTradeState.LimitKey(type, key), 1, Integer::sum);
        transaction.writes.add(sql -> sql.incrementTradeCount(uuid, type, key, resetKey));
    }

    public synchronized long getCooldownEnd(UUID uuid, String profession, String item) {
        PlayerTradeState state = readState(uuid);
        return state == null ? 0L : state.cooldowns.getOrDefault(new PlayerTradeState.CooldownKey(profession, item), 0L);
    }

    public synchronized void setCooldown(UUID uuid, String profession, String item, long end) {
        writeState(uuid).cooldowns.put(new PlayerTradeState.CooldownKey(profession, item), end);
        transaction.writes.add(sql -> sql.setCooldown(uuid, profession, item, end));
    }

    public synchronized ScalingRecord getScaling(UUID uuid, String item) {
        PlayerTradeState state = readState(uuid);
        return state == null ? ScalingRecord.empty() : state.scaling.getOrDefault(item, ScalingRecord.empty());
    }

    public synchronized void saveScaling(UUID uuid, String item, ScalingRecord record) {
        writeState(uuid).scaling.put(item, record);
        transaction.writes.add(sql -> sql.saveScaling(uuid, item, record));
    }

    public synchronized void recordTrade(UUID uuid, String name, String item, int exp) {
        requireTransaction();
        long now = System.currentTimeMillis();
        transaction.writes.add(sql -> sql.recordTrade(uuid, name, item, exp, now));
        transaction.statistics.put(uuid, name);
    }

    private PlayerTradeState readState(UUID uuid) {
        return transaction != null && transaction.states.containsKey(uuid) ? transaction.states.get(uuid) : states.get(uuid);
    }

    private PlayerTradeState writeState(UUID uuid) {
        requireTransaction();
        return transaction.states.computeIfAbsent(uuid, key -> {
            PlayerTradeState state = states.get(key);
            if (state == null) throw new IllegalStateException("Player trade data is not loaded");
            return state.copy();
        });
    }

    private void requireTransaction() {
        if (transaction == null) throw new IllegalStateException("Trade writes require a transaction");
    }

    public CompletableFuture<Optional<PlayerTradeStats>> findStatsAsync(UUID uuid) { return queries.stats(uuid); }
    public CompletableFuture<Optional<PlayerTradeStats>> findStatsByNameAsync(String name) { return queries.named(name); }
    public CompletableFuture<List<PlayerTradeStats>> leaderboardAsync(int limit) { return queries.top(limit); }
    public Optional<PlayerTradeStats> findStats(UUID uuid) { return queries.cachedStats(uuid); }
    public Optional<PlayerTradeStats> findStatsByName(String name) { return queries.cachedName(name); }
    public List<PlayerTradeStats> leaderboard(int limit) { return queries.cachedTop(limit); }
    public int rank(UUID uuid) { return queries.rank(uuid); }

    public synchronized CompletableFuture<Void> resetPlayer(UUID uuid) {
        CompletableFuture<Void> result = queue.submit(() -> { store.resetPlayer(uuid); return null; }, true);
        forgetPlayer(uuid);
        queries.clear();
        return result;
    }

    public synchronized CompletableFuture<Void> clearTradeData() {
        CompletableFuture<Void> result = queue.submit(() -> { store.clearTradeData(); return null; }, true);
        clearReadCaches();
        return result;
    }

    public CompletableFuture<Void> cleanupExpired(long now, String resetKey, long scalingBefore) {
        return queue.submit(() -> { store.cleanupExpired(now, resetKey, scalingBefore); return null; }, true);
    }

    public synchronized void purgeExpiredCaches() {
        queries.purgeExpired();
        long now = System.currentTimeMillis();
        states.values().forEach(state -> state.cooldowns.values().removeIf(end -> end <= now));
    }

    private record StateLoad(String resetKey, CompletableFuture<PlayerTradeState> result) {}

    private static final class Batch {
        final List<Consumer<SqliteStore>> writes = new ArrayList<>();
        final Map<UUID, PlayerTradeState> states = new HashMap<>();
        final Map<UUID, String> statistics = new HashMap<>();
    }
}
