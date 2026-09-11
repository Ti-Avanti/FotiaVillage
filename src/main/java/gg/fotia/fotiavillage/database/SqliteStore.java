package gg.fotia.fotiavillage.database;

import gg.fotia.fotiavillage.config.FotiaSettings.DatabaseSynchronous;
import gg.fotia.fotiavillage.stats.PlayerTradeStats;
import gg.fotia.fotiavillage.trade.ScalingRecord;

import java.io.File;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** SQL 和数据映射集中于此，不访问 Bukkit 实体或在线状态。 */
final class SqliteStore {
    private final SqliteDatabase database;

    SqliteStore(File file) {
        database = new SqliteDatabase(file);
    }

    void open(DatabaseSynchronous mode) {
        database.open(mode);
        database.update("CREATE TABLE IF NOT EXISTS schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), version INTEGER NOT NULL)");
        database.update("INSERT OR IGNORE INTO schema_version (id, version) VALUES (1, 1)");
        database.update("CREATE TABLE IF NOT EXISTS player_stats (uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, total_trades INTEGER NOT NULL DEFAULT 0, total_exp_spent INTEGER NOT NULL DEFAULT 0, last_trade_time INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)");
        database.update("CREATE TABLE IF NOT EXISTS item_stats (uuid TEXT NOT NULL, item_type TEXT NOT NULL, trade_count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, item_type))");
        database.update("CREATE TABLE IF NOT EXISTS trade_cooldowns (uuid TEXT NOT NULL, profession TEXT NOT NULL, item_type TEXT NOT NULL, cooldown_end INTEGER NOT NULL, PRIMARY KEY (uuid, profession, item_type))");
        database.update("CREATE TABLE IF NOT EXISTS trade_limits (uuid TEXT NOT NULL, limit_type TEXT NOT NULL, limit_key TEXT NOT NULL, reset_key TEXT NOT NULL, count INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, limit_type, limit_key, reset_key))");
        database.update("CREATE TABLE IF NOT EXISTS trade_scaling (uuid TEXT NOT NULL, item_type TEXT NOT NULL, multiplier REAL NOT NULL DEFAULT 1.0, trade_count INTEGER NOT NULL DEFAULT 0, last_trade_time INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, item_type))");
        database.update("CREATE TABLE IF NOT EXISTS write_receipts (id TEXT PRIMARY KEY, created_at INTEGER NOT NULL)");
        database.update("CREATE INDEX IF NOT EXISTS idx_write_receipts_created ON write_receipts (created_at)");
        database.update("CREATE INDEX IF NOT EXISTS idx_player_stats_name_updated ON player_stats (player_name COLLATE NOCASE, updated_at DESC)");
        database.update("CREATE INDEX IF NOT EXISTS idx_player_stats_rank ON player_stats (total_trades DESC, total_exp_spent DESC)");
        database.update("CREATE INDEX IF NOT EXISTS idx_item_stats_uuid_count ON item_stats (uuid, trade_count DESC)");
        database.update("CREATE INDEX IF NOT EXISTS idx_trade_scaling_last_trade_time ON trade_scaling (last_trade_time)");
        database.update("CREATE INDEX IF NOT EXISTS idx_trade_cooldowns_end ON trade_cooldowns (cooldown_end)");
    }

    void close() { database.close(); }
    void applySettings(DatabaseSynchronous mode) { database.applySettings(mode); }

    void writeOnce(UUID receipt, List<Consumer<SqliteStore>> changes) {
        database.transaction(() -> {
            // 重试同一事务时不重复累计，覆盖提交结果不明确的异常情况。
            if (database.update("INSERT OR IGNORE INTO write_receipts (id, created_at) VALUES (?, ?)", receipt, System.currentTimeMillis()) == 0) return;
            changes.forEach(change -> change.accept(this));
        });
    }

    void recordTrade(UUID uuid, String name, String item, int exp, long now) {
        database.update("INSERT INTO player_stats (uuid, player_name, total_trades, total_exp_spent, last_trade_time, created_at, updated_at) VALUES (?, ?, 1, ?, ?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET player_name = excluded.player_name, total_trades = total_trades + 1, total_exp_spent = total_exp_spent + excluded.total_exp_spent, last_trade_time = excluded.last_trade_time, updated_at = excluded.updated_at", uuid, name, exp, now, now, now);
        database.update("INSERT INTO item_stats (uuid, item_type, trade_count) VALUES (?, ?, 1) ON CONFLICT(uuid, item_type) DO UPDATE SET trade_count = trade_count + 1", uuid, item);
    }

    Optional<PlayerTradeStats> findStats(UUID uuid) {
        return database.query("SELECT uuid, player_name, total_trades, total_exp_spent, last_trade_time FROM player_stats WHERE uuid = ?", rs -> rs.next() ? Optional.of(readStats(rs, loadItems(uuid))) : Optional.empty(), uuid);
    }

    Optional<PlayerTradeStats> findStatsByName(String name) {
        return database.query("SELECT uuid, player_name, total_trades, total_exp_spent, last_trade_time FROM player_stats WHERE player_name = ? COLLATE NOCASE ORDER BY updated_at DESC LIMIT 1", rs -> rs.next() ? Optional.of(readStats(rs, loadItems(UUID.fromString(rs.getString("uuid"))))) : Optional.empty(), name);
    }

    List<PlayerTradeStats> leaderboard(int limit) {
        return database.query("SELECT uuid, player_name, total_trades, total_exp_spent, last_trade_time FROM player_stats ORDER BY total_trades DESC, total_exp_spent DESC LIMIT ?", rs -> {
            List<PlayerTradeStats> rows = new ArrayList<>();
            while (rs.next()) rows.add(readStats(rs, Map.of()));
            return List.copyOf(rows);
        }, limit);
    }

    int rank(UUID uuid) {
        return database.query("SELECT total_trades, total_exp_spent FROM player_stats WHERE uuid = ?", rs -> {
            if (!rs.next()) return -1;
            int trades = rs.getInt("total_trades");
            int exp = rs.getInt("total_exp_spent");
            return database.query("SELECT COUNT(*) + 1 AS rank FROM player_stats WHERE total_trades > ? OR (total_trades = ? AND total_exp_spent > ?)", ranks -> ranks.next() ? ranks.getInt("rank") : -1, trades, trades, exp);
        }, uuid);
    }

    PlayerTradeState loadTradeState(UUID uuid, String resetKey) {
        PlayerTradeState state = new PlayerTradeState(resetKey);
        database.query("SELECT limit_type, limit_key, count FROM trade_limits WHERE uuid = ? AND reset_key = ?", rs -> {
            while (rs.next()) state.limits.put(new PlayerTradeState.LimitKey(rs.getString("limit_type"), rs.getString("limit_key")), rs.getInt("count"));
            return null;
        }, uuid, resetKey);
        database.query("SELECT profession, item_type, cooldown_end FROM trade_cooldowns WHERE uuid = ? AND cooldown_end > ?", rs -> {
            while (rs.next()) state.cooldowns.put(new PlayerTradeState.CooldownKey(rs.getString("profession"), rs.getString("item_type")), rs.getLong("cooldown_end"));
            return null;
        }, uuid, System.currentTimeMillis());
        database.query("SELECT item_type, multiplier, trade_count, last_trade_time FROM trade_scaling WHERE uuid = ?", rs -> {
            while (rs.next()) state.scaling.put(rs.getString("item_type"), new ScalingRecord(rs.getDouble("multiplier"), rs.getInt("trade_count"), rs.getLong("last_trade_time")));
            return null;
        }, uuid);
        return state;
    }

    void incrementTradeCount(UUID uuid, String type, String key, String resetKey) {
        database.update("INSERT INTO trade_limits (uuid, limit_type, limit_key, reset_key, count) VALUES (?, ?, ?, ?, 1) ON CONFLICT(uuid, limit_type, limit_key, reset_key) DO UPDATE SET count = count + 1", uuid, type, key, resetKey);
    }

    void setCooldown(UUID uuid, String profession, String item, long end) {
        database.update("INSERT INTO trade_cooldowns (uuid, profession, item_type, cooldown_end) VALUES (?, ?, ?, ?) ON CONFLICT(uuid, profession, item_type) DO UPDATE SET cooldown_end = excluded.cooldown_end", uuid, profession, item, end);
    }

    void saveScaling(UUID uuid, String item, ScalingRecord record) {
        database.update("INSERT INTO trade_scaling (uuid, item_type, multiplier, trade_count, last_trade_time) VALUES (?, ?, ?, ?, ?) ON CONFLICT(uuid, item_type) DO UPDATE SET multiplier = excluded.multiplier, trade_count = excluded.trade_count, last_trade_time = excluded.last_trade_time", uuid, item, record.multiplier(), record.tradeCount(), record.lastTradeTime());
    }

    void resetPlayer(UUID uuid) {
        database.transaction(() -> tables().forEach(table -> database.update("DELETE FROM " + table + " WHERE uuid = ?", uuid)));
    }

    void clearTradeData() {
        database.transaction(() -> tables().forEach(table -> database.update("DELETE FROM " + table)));
    }

    void cleanupExpired(long now, String resetKey, long scalingBefore) {
        database.transaction(() -> {
            database.update("DELETE FROM trade_cooldowns WHERE cooldown_end < ?", now);
            database.update("DELETE FROM trade_limits WHERE reset_key <> ?", resetKey);
            if (scalingBefore > 0) database.update("DELETE FROM trade_scaling WHERE last_trade_time > 0 AND last_trade_time <= ?", scalingBefore);
            database.update("DELETE FROM write_receipts WHERE created_at < ?", now - 24L * 60L * 60L * 1000L);
        });
    }

    private List<String> tables() {
        return List.of("player_stats", "item_stats", "trade_cooldowns", "trade_limits", "trade_scaling");
    }

    private Map<String, Integer> loadItems(UUID uuid) {
        return database.query("SELECT item_type, trade_count FROM item_stats WHERE uuid = ? ORDER BY trade_count DESC", rs -> {
            Map<String, Integer> items = new LinkedHashMap<>();
            while (rs.next()) items.put(rs.getString("item_type"), rs.getInt("trade_count"));
            return Collections.unmodifiableMap(items);
        }, uuid);
    }

    private PlayerTradeStats readStats(ResultSet rs, Map<String, Integer> items) throws SQLException {
        return new PlayerTradeStats(UUID.fromString(rs.getString("uuid")), rs.getString("player_name"), rs.getInt("total_trades"), rs.getInt("total_exp_spent"), rs.getLong("last_trade_time"), items);
    }
}
