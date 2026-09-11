package gg.fotia.fotiavillage.command;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.gui.StatsGui;
import gg.fotia.fotiavillage.gui.TopGui;
import gg.fotia.fotiavillage.stats.PlayerTradeStats;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 数据库命令在后台完成后回到主线程更新 GUI 和消息。 */
final class DatabaseCommands {
    private final FotiaVillagePlugin plugin;
    private final Map<String, Long> clearConfirmations = new HashMap<>();
    private final Map<String, UUID> viewRequests = new HashMap<>();

    DatabaseCommands(FotiaVillagePlugin plugin) { this.plugin = plugin; }

    void stats(CommandSender sender, String[] args) {
        if (!require(sender, "fotiavillage.stats")) return;
        if (args.length > 1 && !require(sender, "fotiavillage.stats.others")) return;
        if (args.length < 2 && !(sender instanceof Player)) {
            plugin.language().prefixed(sender, "player-only");
            return;
        }
        String target = args.length > 1 ? args[1] : sender.getName();
        Inventory original = currentInventory(sender);
        UUID request = beginView(sender);
        complete(sender, () -> args.length > 1 ? plugin.database().findStatsByNameAsync(target)
            : plugin.database().findStatsAsync(((Player) sender).getUniqueId()), result -> {
            if (!finishView(sender, request)) return;
            if (result.isEmpty()) {
                plugin.language().prefixed(sender, "stats.no-data", Map.of("player", target));
            } else if (sender instanceof Player player && plugin.settings().gui().enabled() && currentInventory(sender) == original) {
                plugin.gui().open(player, new StatsGui(plugin, player, result.get()));
            } else {
                showStats(sender, result.get());
            }
        }, request);
    }

    void top(CommandSender sender) {
        if (!require(sender, "fotiavillage.top")) return;
        Inventory original = currentInventory(sender);
        UUID request = beginView(sender);
        int limit = plugin.settings().tradeControl().statistics().leaderboardSize();
        complete(sender, () -> plugin.database().leaderboardAsync(limit), result -> {
            if (!finishView(sender, request)) return;
            if (result.isEmpty()) {
                plugin.language().prefixed(sender, "leaderboard.no-data");
            } else if (sender instanceof Player player && plugin.settings().gui().enabled() && currentInventory(sender) == original) {
                plugin.gui().open(player, new TopGui(plugin, player, result));
            } else {
                showTop(sender, result);
            }
        }, request);
    }

    void admin(CommandSender sender, String[] args) {
        if (!require(sender, "fotiavillage.admin")) return;
        if (args.length < 2) {
            plugin.language().send(sender, "help.admin");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reset" -> reset(sender, args);
            case "clear" -> clear(sender, args);
            case "info" -> plugin.language().prefixed(sender, "admin.info", Map.of("version", plugin.getDescription().getVersion(), "database", status()));
            default -> plugin.language().send(sender, "help.admin");
        }
    }

    private void reset(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.language().prefixed(sender, "admin.reset-usage");
            return;
        }
        String name = args[2];
        Player online = plugin.getServer().getPlayerExact(name);
        UUID onlineId = online == null ? null : online.getUniqueId();
        complete(sender, () -> plugin.database().findStatsByNameAsync(name), result -> {
            UUID uuid = result.map(PlayerTradeStats::uuid).orElse(onlineId);
            if (uuid == null) {
                plugin.language().prefixed(sender, "player-not-found");
                return;
            }
            complete(sender, () -> plugin.database().resetPlayer(uuid), ignored ->
                plugin.language().prefixed(sender, "admin.reset-success", Map.of("player", name)));
        });
    }

    private void clear(CommandSender sender, String[] args) {
        String key = sender.getName();
        long now = System.currentTimeMillis();
        clearConfirmations.values().removeIf(time -> now - time > 10_000L);
        if (args.length >= 3 && args[2].equalsIgnoreCase("confirm")) {
            Long requested = clearConfirmations.remove(key);
            if (requested != null && now - requested <= 10_000L) {
                complete(sender, plugin.database()::clearTradeData, ignored -> plugin.language().prefixed(sender, "admin.clear-success"));
                return;
            }
        }
        clearConfirmations.put(key, now);
        plugin.language().prefixed(sender, "admin.clear-warning");
    }

    void perf(CommandSender sender, String[] args) {
        if (!require(sender, "fotiavillage.admin")) return;
        String option = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "overview";
        switch (option) {
            case "database" -> complete(sender, plugin.database()::fileSizeAsync, size ->
                plugin.language().send(sender, "perf.database", Map.of("status", status(), "size", size + " bytes")));
            case "tracker" -> {
                var stats = plugin.villagerTracker().stats();
                plugin.language().send(sender, "perf.tracker", Map.of("chunks", stats.trackedChunks(), "villagers", stats.totalVillagers()));
            }
            case "cleanup" -> {
                plugin.language().prefixed(sender, "perf.cleanup-start");
                complete(sender, plugin.performance()::cleanupExpiredData, ignored -> plugin.language().prefixed(sender, "perf.cleanup-done"));
            }
            case "memory", "overview" -> {
                plugin.language().send(sender, "perf.title");
                plugin.language().send(sender, "perf.overview", Map.of("tps", plugin.performance().tps(), "used", plugin.performance().usedMemoryMb(), "max", plugin.performance().maxMemoryMb(), "uptime", plugin.performance().uptime()));
            }
            default -> plugin.language().prefixed(sender, "unknown-command", Map.of("command", option));
        }
    }

    private void showStats(CommandSender sender, PlayerTradeStats stats) {
        plugin.language().send(sender, "stats.text-title");
        plugin.language().send(sender, "stats.player", Map.of("player", stats.playerName()));
        plugin.language().send(sender, "stats.total-trades", Map.of("trades", stats.totalTrades()));
        plugin.language().send(sender, "stats.total-exp", Map.of("exp", stats.totalExpSpent()));
        stats.itemCounts().entrySet().stream().limit(5).forEach(entry -> plugin.language().send(sender, "stats.item-line", Map.of("item", entry.getKey(), "count", entry.getValue())));
    }

    private void showTop(CommandSender sender, List<PlayerTradeStats> rows) {
        plugin.language().send(sender, "leaderboard.text-title");
        for (int i = 0; i < rows.size(); i++) {
            PlayerTradeStats stats = rows.get(i);
            plugin.language().send(sender, "leaderboard.line", Map.of("rank", i + 1, "player", stats.playerName(), "trades", stats.totalTrades()));
        }
    }

    private UUID beginView(CommandSender sender) {
        UUID token = UUID.randomUUID();
        viewRequests.put(sender.getName(), token);
        return token;
    }

    private boolean finishView(CommandSender sender, UUID token) { return viewRequests.remove(sender.getName(), token); }
    private Inventory currentInventory(CommandSender sender) { return sender instanceof Player player ? player.getOpenInventory().getTopInventory() : null; }
    private String status() { return plugin.language().plain(plugin.database().isConnected() ? "status.connected" : "status.disconnected"); }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        plugin.language().prefixed(sender, "no-permission");
        return false;
    }

    private <T> void complete(CommandSender sender, Supplier<CompletableFuture<T>> operation, Consumer<T> success) {
        complete(sender, operation, success, null);
    }

    private <T> void complete(CommandSender sender, Supplier<CompletableFuture<T>> operation, Consumer<T> success, UUID viewRequest) {
        CompletableFuture<T> result;
        try {
            result = operation.get();
        } catch (RuntimeException ex) {
            if (viewRequest != null) viewRequests.remove(sender.getName(), viewRequest);
            plugin.language().prefixed(sender, "stats.query-failed");
            return;
        }
        result.whenComplete((value, error) -> {
            try {
                if (!plugin.isEnabled()) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (sender instanceof Player player && !player.isOnline()) {
                        if (viewRequest != null) viewRequests.remove(sender.getName(), viewRequest);
                        return;
                    }
                    if (error == null) success.accept(value);
                    else {
                        if (viewRequest != null) viewRequests.remove(sender.getName(), viewRequest);
                        plugin.language().prefixed(sender, "stats.query-failed");
                    }
                });
            } catch (IllegalPluginAccessException ignored) {
                // 关闭插件后不再投递 GUI 更新。
            }
        });
    }
}
