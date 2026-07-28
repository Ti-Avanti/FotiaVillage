package gg.fotia.fotiavillage.database;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.stats.PlayerTradeStats;
import gg.fotia.fotiavillage.trade.ScalingRecord;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class DatabaseService {
    private final FotiaVillagePlugin plugin;
    private final File file;
    private final TimedCache<UUID, Optional<PlayerTradeStats>> statsCache = new TimedCache<>();
    private final TimedCache<String, Optional<PlayerTradeStats>> statsByNameCache = new TimedCache<>();
    private final TimedCache<Integer, List<PlayerTradeStats>> leaderboardCache = new TimedCache<>();
    private final TimedCache<UUID, Integer> rankCache = new TimedCache<>();
    private final TimedCache<TradeCountKey, Integer> tradeCountCache = new TimedCache<>();
    private final TimedCache<CooldownKey, Long> cooldownCache = new TimedCache<>();
    private final TimedCache<ScalingKey, ScalingRecord> scalingCache = new TimedCache<>();
    private final TimedCache<TradeLimitScopeKey, Boolean> tradeLimitScopes = new TimedCache<>();
    private final TimedCache<UUID, Boolean> cooldownScopes = new TimedCache<>();
    private final TimedCache<UUID, Boolean> scalingScopes = new TimedCache<>();
    private Connection connection;

    public DatabaseService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.db");
    }

    public synchronized void open() {
        try {
            close();
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            execute("PRAGMA foreign_keys = ON");
            execute("PRAGMA journal_mode = WAL");
            applySynchronousMode();
            execute("PRAGMA busy_timeout = 5000");
            createSchema();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to open SQLite database", ex);
        }
    }

    public synchronized void close() {
        clearCaches();
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            plugin.getLogger().warning("Failed to close database: " + ex.getMessage());
        } finally {
            connection = null;
        }
    }

    public synchronized void clearReadCaches() {
        clearCaches();
    }

    public synchronized void applyRuntimeSettings() {
        if (!isConnected()) {
            return;
        }
        try {
            applySynchronousMode();
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to apply database runtime settings", ex);
        }
    }

    public synchronized boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException ex) {
            return false;
        }
    }

    public synchronized void runInTransaction(Runnable action) {
        try {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                action.run();
                connection.commit();
            } catch (RuntimeException ex) {
                rollbackQuietly(ex);
                clearCaches();
                throw ex;
            } catch (SQLException ex) {
                rollbackQuietly(ex);
                clearCaches();
                throw new IllegalStateException("Failed to commit database transaction", ex);
            } catch (Error ex) {
                rollbackQuietly(ex);
                clearCaches();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to run database transaction", ex);
        }
    }

    public File file() {
        return file;
    }

    private void createSchema() throws SQLException {
        execute("CREATE TABLE IF NOT EXISTS schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL)");
        execute("INSERT OR IGNORE INTO schema_version (id, version) VALUES (1, 1)");
        execute("CREATE TABLE IF NOT EXISTS player_stats (uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, total_trades INTEGER NOT NULL DEFAULT 0, total_exp_spent INTEGER NOT NULL DEFAULT 0, last_trade_time INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)");
        execute("CREATE TABLE IF NOT EXISTS item_stats (uuid TEXT NOT NULL, item_type TEXT NOT NULL, trade_count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, item_type))");
        execute("CREATE TABLE IF NOT EXISTS trade_cooldowns (uuid TEXT NOT NULL, profession TEXT NOT NULL, item_type TEXT NOT NULL, cooldown_end INTEGER NOT NULL, PRIMARY KEY (uuid, profession, item_type))");
        execute("CREATE TABLE IF NOT EXISTS trade_limits (uuid TEXT NOT NULL, limit_type TEXT NOT NULL, limit_key TEXT NOT NULL, reset_key TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, limit_type, limit_key, reset_key))");
        execute("CREATE TABLE IF NOT EXISTS trade_scaling (uuid TEXT NOT NULL, item_type TEXT NOT NULL, multiplier REAL NOT NULL DEFAULT 1.0, trade_count INTEGER NOT NULL DEFAULT 0, last_trade_time INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, item_type))");
        execute("CREATE INDEX IF NOT EXISTS idx_player_stats_name_updated ON player_stats (player_name COLLATE NOCASE, updated_at DESC)");
        execute("CREATE INDEX IF NOT EXISTS idx_player_stats_rank ON player_stats (total_trades DESC, total_exp_spent DESC)");
        execute("CREATE INDEX IF NOT EXISTS idx_item_stats_uuid_count ON item_stats (uuid, trade_count DESC)");
        execute("CREATE INDEX IF NOT EXISTS idx_trade_scaling_last_trade_time ON trade_scaling (last_trade_time)");
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void applySynchronousMode() throws SQLException {
        execute("PRAGMA synchronous = " + plugin.settings().performance().databaseSynchronous().name());
    }

    private void rollbackQuietly(Throwable cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackEx) {
            cause.addSuppressed(rollbackEx);
        }
    }

    public synchronized void recordTrade(UUID uuid, String playerName, String itemType, int expSpent) {
        long now = System.currentTimeMillis();
        try {
            try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO player_stats (uuid, player_name, total_trades, total_exp_spent, last_trade_time, created_at, updated_at) VALUES (?, ?, 1, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET player_name = excluded.player_name, total_trades = total_trades + 1, total_exp_spent = total_exp_spent + excluded.total_exp_spent, last_trade_time = excluded.last_trade_time, updated_at = excluded.updated_at")) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, playerName);
                stmt.setInt(3, expSpent);
                stmt.setLong(4, now);
                stmt.setLong(5, now);
                stmt.setLong(6, now);
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO item_stats (uuid, item_type, trade_count) VALUES (?, ?, 1) ON CONFLICT(uuid, item_type) DO UPDATE SET trade_count = trade_count + 1")) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, itemType);
                stmt.executeUpdate();
            }
            invalidateStatsCaches(uuid);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to record trade", ex);
        }
    }

    public synchronized Optional<PlayerTradeStats> findStats(UUID uuid) {
        Optional<PlayerTradeStats> cached = statsCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT uuid, player_name, total_trades, total_exp_spent, last_trade_time FROM player_stats WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    Optional<PlayerTradeStats> result = Optional.empty();
                    statsCache.put(uuid, result, readCacheMillis());
                    return result;
                }
                Optional<PlayerTradeStats> result = Optional.of(readStatsRow(rs, loadItemCounts(uuid)));
                cacheStats(result.get());
                return result;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load player stats", ex);
        }
    }

    public synchronized Optional<PlayerTradeStats> findStatsByName(String playerName) {
        String normalizedName = playerName.toLowerCase(Locale.ROOT);
        Optional<PlayerTradeStats> cached = statsByNameCache.get(normalizedName);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT uuid, player_name, total_trades, total_exp_spent, last_trade_time FROM player_stats WHERE player_name = ? COLLATE NOCASE ORDER BY updated_at DESC LIMIT 1")) {
            stmt.setString(1, playerName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    Optional<PlayerTradeStats> result = Optional.empty();
                    statsByNameCache.put(normalizedName, result, readCacheMillis());
                    return result;
                }
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                Optional<PlayerTradeStats> result = Optional.of(readStatsRow(rs, loadItemCounts(uuid)));
                cacheStats(result.get());
                return result;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load player stats by name", ex);
        }
    }

    public synchronized List<PlayerTradeStats> leaderboard(int limit) {
        List<PlayerTradeStats> cached = leaderboardCache.get(limit);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT uuid, player_name, total_trades, total_exp_spent, last_trade_time FROM player_stats ORDER BY total_trades DESC, total_exp_spent DESC LIMIT ?")) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                java.util.ArrayList<PlayerTradeStats> result = new java.util.ArrayList<>();
                while (rs.next()) {
                    result.add(readStatsRow(rs, Map.of()));
                }
                List<PlayerTradeStats> snapshot = List.copyOf(result);
                leaderboardCache.put(limit, snapshot, leaderboardCacheMillis());
                return snapshot;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load leaderboard", ex);
        }
    }

    public synchronized int rank(UUID uuid) {
        Integer cached = rankCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        try (PreparedStatement stats = connection.prepareStatement("SELECT total_trades, total_exp_spent FROM player_stats WHERE uuid = ?")) {
            stats.setString(1, uuid.toString());
            try (ResultSet rs = stats.executeQuery()) {
                if (!rs.next()) {
                    rankCache.put(uuid, -1, leaderboardCacheMillis());
                    return -1;
                }
                int trades = rs.getInt("total_trades");
                int exp = rs.getInt("total_exp_spent");
                try (PreparedStatement rank = connection.prepareStatement("SELECT COUNT(*) + 1 AS rank FROM player_stats WHERE total_trades > ? OR (total_trades = ? AND total_exp_spent > ?)")) {
                    rank.setInt(1, trades);
                    rank.setInt(2, trades);
                    rank.setInt(3, exp);
                    try (ResultSet rankRs = rank.executeQuery()) {
                        int result = rankRs.next() ? rankRs.getInt("rank") : -1;
                        rankCache.put(uuid, result, leaderboardCacheMillis());
                        return result;
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load player rank", ex);
        }
    }

    public synchronized void primeTradeState(UUID uuid, String resetKey, boolean loadLimits, boolean loadCooldowns, boolean loadScaling) {
        long ttlMillis = readCacheMillis();
        if (ttlMillis <= 0L) {
            return;
        }
        TradeLimitScopeKey limitScope = new TradeLimitScopeKey(uuid, resetKey);
        if (loadLimits && tradeLimitScopes.get(limitScope) == null) {
            tradeLimitScopes.put(limitScope, true, ttlMillis);
            try {
                loadTradeLimitScope(uuid, resetKey, ttlMillis);
            } catch (RuntimeException ex) {
                tradeLimitScopes.invalidate(limitScope);
                throw ex;
            }
        }
        if (loadCooldowns && cooldownScopes.get(uuid) == null) {
            cooldownScopes.put(uuid, true, ttlMillis);
            try {
                loadCooldownScope(uuid, ttlMillis);
            } catch (RuntimeException ex) {
                cooldownScopes.invalidate(uuid);
                throw ex;
            }
        }
        if (loadScaling && scalingScopes.get(uuid) == null) {
            scalingScopes.put(uuid, true, ttlMillis);
            try {
                loadScalingScope(uuid, ttlMillis);
            } catch (RuntimeException ex) {
                scalingScopes.invalidate(uuid);
                throw ex;
            }
        }
    }

    public synchronized int getTradeCount(UUID uuid, String limitType, String limitKey, String resetKey) {
        TradeCountKey cacheKey = new TradeCountKey(uuid, limitType, limitKey, resetKey);
        Integer cached = tradeCountCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (tradeLimitScopes.get(new TradeLimitScopeKey(uuid, resetKey)) != null) {
            return 0;
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT count FROM trade_limits WHERE uuid = ? AND limit_type = ? AND limit_key = ? AND reset_key = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, limitType);
            stmt.setString(3, limitKey);
            stmt.setString(4, resetKey);
            try (ResultSet rs = stmt.executeQuery()) {
                int result = rs.next() ? rs.getInt("count") : 0;
                tradeCountCache.put(cacheKey, result, readCacheMillis());
                return result;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load trade count", ex);
        }
    }

    public synchronized void incrementTradeCount(UUID uuid, String limitType, String limitKey, String resetKey) {
        TradeCountKey cacheKey = new TradeCountKey(uuid, limitType, limitKey, resetKey);
        Integer cached = tradeCountCache.get(cacheKey);
        if (cached == null && tradeLimitScopes.get(new TradeLimitScopeKey(uuid, resetKey)) != null) {
            cached = 0;
        }
        try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO trade_limits (uuid, limit_type, limit_key, reset_key, count) VALUES (?, ?, ?, ?, 1) ON CONFLICT(uuid, limit_type, limit_key, reset_key) DO UPDATE SET count = count + 1")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, limitType);
            stmt.setString(3, limitKey);
            stmt.setString(4, resetKey);
            stmt.executeUpdate();
            if (cached != null) {
                tradeCountCache.put(cacheKey, cached + 1, readCacheMillis());
            } else {
                tradeCountCache.invalidate(cacheKey);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to increment trade count", ex);
        }
    }

    public synchronized long getCooldownEnd(UUID uuid, String profession, String itemType) {
        CooldownKey cacheKey = new CooldownKey(uuid, profession, itemType);
        Long cached = cooldownCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (cooldownScopes.get(uuid) != null) {
            return 0L;
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT cooldown_end FROM trade_cooldowns WHERE uuid = ? AND profession = ? AND item_type = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, profession);
            stmt.setString(3, itemType);
            try (ResultSet rs = stmt.executeQuery()) {
                long result = rs.next() ? rs.getLong("cooldown_end") : 0L;
                cooldownCache.put(cacheKey, result, readCacheMillis());
                return result;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load cooldown", ex);
        }
    }

    public synchronized void setCooldown(UUID uuid, String profession, String itemType, long cooldownEnd) {
        CooldownKey cacheKey = new CooldownKey(uuid, profession, itemType);
        try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO trade_cooldowns (uuid, profession, item_type, cooldown_end) VALUES (?, ?, ?, ?) ON CONFLICT(uuid, profession, item_type) DO UPDATE SET cooldown_end = excluded.cooldown_end")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, profession);
            stmt.setString(3, itemType);
            stmt.setLong(4, cooldownEnd);
            stmt.executeUpdate();
            cooldownCache.put(cacheKey, cooldownEnd, readCacheMillis());
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to set cooldown", ex);
        }
    }

    public synchronized ScalingRecord getScaling(UUID uuid, String itemType) {
        ScalingKey cacheKey = new ScalingKey(uuid, itemType);
        ScalingRecord cached = scalingCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (scalingScopes.get(uuid) != null) {
            return ScalingRecord.empty();
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT multiplier, trade_count, last_trade_time FROM trade_scaling WHERE uuid = ? AND item_type = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, itemType);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    ScalingRecord result = ScalingRecord.empty();
                    scalingCache.put(cacheKey, result, readCacheMillis());
                    return result;
                }
                ScalingRecord result = new ScalingRecord(rs.getDouble("multiplier"), rs.getInt("trade_count"), rs.getLong("last_trade_time"));
                scalingCache.put(cacheKey, result, readCacheMillis());
                return result;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load scaling", ex);
        }
    }

    public synchronized void saveScaling(UUID uuid, String itemType, ScalingRecord record) {
        ScalingKey cacheKey = new ScalingKey(uuid, itemType);
        try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO trade_scaling (uuid, item_type, multiplier, trade_count, last_trade_time) VALUES (?, ?, ?, ?, ?) ON CONFLICT(uuid, item_type) DO UPDATE SET multiplier = excluded.multiplier, trade_count = excluded.trade_count, last_trade_time = excluded.last_trade_time")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, itemType);
            stmt.setDouble(3, record.multiplier());
            stmt.setInt(4, record.tradeCount());
            stmt.setLong(5, record.lastTradeTime());
            stmt.executeUpdate();
            scalingCache.put(cacheKey, record, readCacheMillis());
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to save scaling", ex);
        }
    }

    public synchronized void resetPlayer(String playerName) {
        findStatsByName(playerName).ifPresent(stats -> resetPlayer(stats.uuid()));
    }

    public synchronized void resetPlayer(UUID uuid) {
        String id = uuid.toString();
        runInTransaction(() -> {
            deleteByUuidUnchecked("player_stats", id);
            deleteByUuidUnchecked("item_stats", id);
            deleteByUuidUnchecked("trade_cooldowns", id);
            deleteByUuidUnchecked("trade_limits", id);
            deleteByUuidUnchecked("trade_scaling", id);
        });
        clearCaches();
    }

    public synchronized void clearTradeData() {
        runInTransaction(() -> {
            executeUnchecked("DELETE FROM player_stats", "Failed to clear player stats");
            executeUnchecked("DELETE FROM item_stats", "Failed to clear item stats");
            executeUnchecked("DELETE FROM trade_cooldowns", "Failed to clear trade cooldowns");
            executeUnchecked("DELETE FROM trade_limits", "Failed to clear trade limits");
            executeUnchecked("DELETE FROM trade_scaling", "Failed to clear trade scaling");
        });
        clearCaches();
    }

    public synchronized void cleanupExpired(long now, String currentResetKey, long scalingExpiresBefore) {
        try (PreparedStatement cooldown = connection.prepareStatement("DELETE FROM trade_cooldowns WHERE cooldown_end < ?");
             PreparedStatement limits = connection.prepareStatement("DELETE FROM trade_limits WHERE reset_key <> ?")) {
            cooldown.setLong(1, now);
            cooldown.executeUpdate();
            limits.setString(1, currentResetKey);
            limits.executeUpdate();
            if (scalingExpiresBefore > 0) {
                try (PreparedStatement scaling = connection.prepareStatement("DELETE FROM trade_scaling WHERE last_trade_time > 0 AND last_trade_time <= ?")) {
                    scaling.setLong(1, scalingExpiresBefore);
                    scaling.executeUpdate();
                }
            }
            tradeCountCache.clear();
            cooldownCache.clear();
            scalingCache.clear();
            tradeLimitScopes.clear();
            cooldownScopes.clear();
            scalingScopes.clear();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to cleanup expired data", ex);
        }
    }

    private PlayerTradeStats readStatsRow(ResultSet rs, Map<String, Integer> items) throws SQLException {
        return new PlayerTradeStats(UUID.fromString(rs.getString("uuid")), rs.getString("player_name"), rs.getInt("total_trades"), rs.getInt("total_exp_spent"), rs.getLong("last_trade_time"), items);
    }

    private Map<String, Integer> loadItemCounts(UUID uuid) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT item_type, trade_count FROM item_stats WHERE uuid = ? ORDER BY trade_count DESC")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                Map<String, Integer> result = new LinkedHashMap<>();
                while (rs.next()) {
                    result.put(rs.getString("item_type"), rs.getInt("trade_count"));
                }
                return Collections.unmodifiableMap(result);
            }
        }
    }

    private void loadTradeLimitScope(UUID uuid, String resetKey, long ttlMillis) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT limit_type, limit_key, count FROM trade_limits WHERE uuid = ? AND reset_key = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, resetKey);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    TradeCountKey key = new TradeCountKey(uuid, rs.getString("limit_type"), rs.getString("limit_key"), resetKey);
                    tradeCountCache.put(key, rs.getInt("count"), ttlMillis);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to preload trade limits", ex);
        }
    }

    private void loadCooldownScope(UUID uuid, long ttlMillis) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT profession, item_type, cooldown_end FROM trade_cooldowns WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    CooldownKey key = new CooldownKey(uuid, rs.getString("profession"), rs.getString("item_type"));
                    cooldownCache.put(key, rs.getLong("cooldown_end"), ttlMillis);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to preload trade cooldowns", ex);
        }
    }

    private void loadScalingScope(UUID uuid, long ttlMillis) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT item_type, multiplier, trade_count, last_trade_time FROM trade_scaling WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ScalingKey key = new ScalingKey(uuid, rs.getString("item_type"));
                    ScalingRecord record = new ScalingRecord(rs.getDouble("multiplier"), rs.getInt("trade_count"), rs.getLong("last_trade_time"));
                    scalingCache.put(key, record, ttlMillis);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to preload trade scaling", ex);
        }
    }

    private void cacheStats(PlayerTradeStats stats) {
        Optional<PlayerTradeStats> value = Optional.of(stats);
        long ttlMillis = readCacheMillis();
        statsCache.put(stats.uuid(), value, ttlMillis);
        statsByNameCache.put(stats.playerName().toLowerCase(Locale.ROOT), value, ttlMillis);
    }

    private void invalidateStatsCaches(UUID uuid) {
        statsCache.invalidate(uuid);
        statsByNameCache.clear();
        leaderboardCache.clear();
        rankCache.clear();
    }

    private long readCacheMillis() {
        return plugin.settings().performance().databaseReadCacheSeconds() * 1000L;
    }

    private long leaderboardCacheMillis() {
        return plugin.settings().performance().leaderboardCacheSeconds() * 1000L;
    }

    private void clearCaches() {
        statsCache.clear();
        statsByNameCache.clear();
        leaderboardCache.clear();
        rankCache.clear();
        tradeCountCache.clear();
        cooldownCache.clear();
        scalingCache.clear();
        tradeLimitScopes.clear();
        cooldownScopes.clear();
        scalingScopes.clear();
    }

    private void deleteByUuid(String table, String uuid) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
            stmt.setString(1, uuid);
            stmt.executeUpdate();
        }
    }

    private void deleteByUuidUnchecked(String table, String uuid) {
        try {
            deleteByUuid(table, uuid);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to delete player data from " + table, ex);
        }
    }

    private void executeUnchecked(String sql, String message) {
        try {
            execute(sql);
        } catch (SQLException ex) {
            throw new IllegalStateException(message, ex);
        }
    }

    private record TradeCountKey(UUID uuid, String limitType, String limitKey, String resetKey) {
    }

    private record CooldownKey(UUID uuid, String profession, String itemType) {
    }

    private record ScalingKey(UUID uuid, String itemType) {
    }

    private record TradeLimitScopeKey(UUID uuid, String resetKey) {
    }
}
