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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class DatabaseService {
    private final FotiaVillagePlugin plugin;
    private final File file;
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
            execute("PRAGMA busy_timeout = 5000");
            createSchema();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to open SQLite database", ex);
        }
    }

    public synchronized void close() {
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
                throw ex;
            } catch (SQLException ex) {
                rollbackQuietly(ex);
                throw new IllegalStateException("Failed to commit database transaction", ex);
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
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to record trade", ex);
        }
    }

    public synchronized Optional<PlayerTradeStats> findStats(UUID uuid) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT uuid, player_name, total_trades, total_exp_spent, last_trade_time FROM player_stats WHERE uuid = ?")) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readStatsRow(rs, loadItemCounts(uuid)));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load player stats", ex);
        }
    }

    public synchronized Optional<PlayerTradeStats> findStatsByName(String playerName) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT uuid, player_name, total_trades, total_exp_spent, last_trade_time FROM player_stats WHERE player_name = ? COLLATE NOCASE ORDER BY updated_at DESC LIMIT 1")) {
            stmt.setString(1, playerName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                return Optional.of(readStatsRow(rs, loadItemCounts(uuid)));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load player stats by name", ex);
        }
    }

    public synchronized List<PlayerTradeStats> leaderboard(int limit) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT uuid, player_name, total_trades, total_exp_spent, last_trade_time FROM player_stats ORDER BY total_trades DESC, total_exp_spent DESC LIMIT ?")) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                java.util.ArrayList<PlayerTradeStats> result = new java.util.ArrayList<>();
                while (rs.next()) {
                    UUID uuid = UUID.fromString(rs.getString("uuid"));
                    result.add(readStatsRow(rs, loadItemCounts(uuid)));
                }
                return result;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load leaderboard", ex);
        }
    }

    public synchronized int rank(UUID uuid) {
        try (PreparedStatement stats = connection.prepareStatement("SELECT total_trades, total_exp_spent FROM player_stats WHERE uuid = ?")) {
            stats.setString(1, uuid.toString());
            try (ResultSet rs = stats.executeQuery()) {
                if (!rs.next()) {
                    return -1;
                }
                int trades = rs.getInt("total_trades");
                int exp = rs.getInt("total_exp_spent");
                try (PreparedStatement rank = connection.prepareStatement("SELECT COUNT(*) + 1 AS rank FROM player_stats WHERE total_trades > ? OR (total_trades = ? AND total_exp_spent > ?)")) {
                    rank.setInt(1, trades);
                    rank.setInt(2, trades);
                    rank.setInt(3, exp);
                    try (ResultSet rankRs = rank.executeQuery()) {
                        return rankRs.next() ? rankRs.getInt("rank") : -1;
                    }
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load player rank", ex);
        }
    }

    public synchronized int getTradeCount(UUID uuid, String limitType, String limitKey, String resetKey) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT count FROM trade_limits WHERE uuid = ? AND limit_type = ? AND limit_key = ? AND reset_key = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, limitType);
            stmt.setString(3, limitKey);
            stmt.setString(4, resetKey);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt("count") : 0;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load trade count", ex);
        }
    }

    public synchronized void incrementTradeCount(UUID uuid, String limitType, String limitKey, String resetKey) {
        try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO trade_limits (uuid, limit_type, limit_key, reset_key, count) VALUES (?, ?, ?, ?, 1) ON CONFLICT(uuid, limit_type, limit_key, reset_key) DO UPDATE SET count = count + 1")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, limitType);
            stmt.setString(3, limitKey);
            stmt.setString(4, resetKey);
            stmt.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to increment trade count", ex);
        }
    }

    public synchronized long getCooldownEnd(UUID uuid, String profession, String itemType) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT cooldown_end FROM trade_cooldowns WHERE uuid = ? AND profession = ? AND item_type = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, profession);
            stmt.setString(3, itemType);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getLong("cooldown_end") : 0L;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load cooldown", ex);
        }
    }

    public synchronized void setCooldown(UUID uuid, String profession, String itemType, long cooldownEnd) {
        try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO trade_cooldowns (uuid, profession, item_type, cooldown_end) VALUES (?, ?, ?, ?) ON CONFLICT(uuid, profession, item_type) DO UPDATE SET cooldown_end = excluded.cooldown_end")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, profession);
            stmt.setString(3, itemType);
            stmt.setLong(4, cooldownEnd);
            stmt.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to set cooldown", ex);
        }
    }

    public synchronized ScalingRecord getScaling(UUID uuid, String itemType) {
        try (PreparedStatement stmt = connection.prepareStatement("SELECT multiplier, trade_count, last_trade_time FROM trade_scaling WHERE uuid = ? AND item_type = ?")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, itemType);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return ScalingRecord.empty();
                }
                return new ScalingRecord(rs.getDouble("multiplier"), rs.getInt("trade_count"), rs.getLong("last_trade_time"));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to load scaling", ex);
        }
    }

    public synchronized void saveScaling(UUID uuid, String itemType, ScalingRecord record) {
        try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO trade_scaling (uuid, item_type, multiplier, trade_count, last_trade_time) VALUES (?, ?, ?, ?, ?) ON CONFLICT(uuid, item_type) DO UPDATE SET multiplier = excluded.multiplier, trade_count = excluded.trade_count, last_trade_time = excluded.last_trade_time")) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, itemType);
            stmt.setDouble(3, record.multiplier());
            stmt.setInt(4, record.tradeCount());
            stmt.setLong(5, record.lastTradeTime());
            stmt.executeUpdate();
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
    }

    public synchronized void clearTradeData() {
        runInTransaction(() -> {
            executeUnchecked("DELETE FROM player_stats", "Failed to clear player stats");
            executeUnchecked("DELETE FROM item_stats", "Failed to clear item stats");
            executeUnchecked("DELETE FROM trade_cooldowns", "Failed to clear trade cooldowns");
            executeUnchecked("DELETE FROM trade_limits", "Failed to clear trade limits");
            executeUnchecked("DELETE FROM trade_scaling", "Failed to clear trade scaling");
        });
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
}
