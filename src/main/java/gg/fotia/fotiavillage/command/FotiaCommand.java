package gg.fotia.fotiavillage.command;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.gui.StatsGui;
import gg.fotia.fotiavillage.gui.TopGui;
import gg.fotia.fotiavillage.lifespan.LifespanService;
import gg.fotia.fotiavillage.stats.PlayerTradeStats;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class FotiaCommand implements CommandExecutor, TabCompleter {
    private final FotiaVillagePlugin plugin;
    private final Map<String, Long> clearConfirmations = new HashMap<>();

    public FotiaCommand(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "stats" -> stats(sender, args);
            case "top" -> top(sender);
            case "admin" -> admin(sender, args);
            case "perf" -> perf(sender, args);
            case "lifespan" -> lifespan(sender, args);
            case "item" -> item(sender, args);
            case "kill" -> kill(sender);
            default -> plugin.language().prefixed(sender, "unknown-command", Map.of("command", args[0]));
        }
        return true;
    }

    private void help(CommandSender sender) {
        plugin.language().send(sender, "help.title");
        plugin.language().send(sender, "help.reload");
        plugin.language().send(sender, "help.stats");
        plugin.language().send(sender, "help.top");
        plugin.language().send(sender, "help.admin");
        plugin.language().send(sender, "help.perf");
        plugin.language().send(sender, "help.lifespan");
        plugin.language().send(sender, "help.item");
        plugin.language().send(sender, "help.kill");
    }

    private void reload(CommandSender sender) {
        if (!require(sender, "fotiavillage.admin")) return;
        plugin.language().prefixed(sender, "reload-start");
        try {
            plugin.reloadRuntime();
            plugin.language().prefixed(sender, "reload-success");
        } catch (Exception ex) {
            plugin.language().prefixed(sender, "reload-failed", Map.of("error", ex.getMessage()));
            plugin.getLogger().log(Level.SEVERE, "Failed to reload FotiaVillage", ex);
        }
    }

    private void stats(CommandSender sender, String[] args) {
        if (!require(sender, "fotiavillage.stats")) return;
        PlayerTradeStats stats;
        if (args.length >= 2) {
            if (!require(sender, "fotiavillage.stats.others")) return;
            stats = plugin.stats().findByName(args[1]).orElse(null);
            if (stats == null) {
                plugin.language().prefixed(sender, "player-not-found");
                return;
            }
        } else {
            if (!(sender instanceof Player player)) {
                plugin.language().prefixed(sender, "player-only");
                return;
            }
            stats = plugin.stats().find(player.getUniqueId()).orElse(null);
            if (stats == null) {
                plugin.language().prefixed(sender, "stats.no-data", Map.of("player", player.getName()));
                return;
            }
        }
        if (sender instanceof Player player && plugin.settings().gui().enabled()) {
            plugin.gui().open(player, new StatsGui(plugin, player, stats));
            return;
        }
        sendStatsText(sender, stats);
    }

    private void sendStatsText(CommandSender sender, PlayerTradeStats stats) {
        plugin.language().send(sender, "stats.text-title");
        plugin.language().send(sender, "stats.player", Map.of("player", stats.playerName()));
        plugin.language().send(sender, "stats.total-trades", Map.of("trades", stats.totalTrades()));
        plugin.language().send(sender, "stats.total-exp", Map.of("exp", stats.totalExpSpent()));
        stats.itemCounts().entrySet().stream().limit(5).forEach(entry -> plugin.language().send(sender, "stats.item-line", Map.of("item", entry.getKey(), "count", entry.getValue())));
    }

    private void top(CommandSender sender) {
        if (!require(sender, "fotiavillage.top")) return;
        List<PlayerTradeStats> leaderboard = plugin.stats().leaderboard();
        if (leaderboard.isEmpty()) {
            plugin.language().prefixed(sender, "leaderboard.no-data");
            return;
        }
        if (sender instanceof Player player && plugin.settings().gui().enabled()) {
            plugin.gui().open(player, new TopGui(plugin, player, leaderboard));
            return;
        }
        plugin.language().send(sender, "leaderboard.text-title");
        for (int i = 0; i < leaderboard.size(); i++) {
            PlayerTradeStats stats = leaderboard.get(i);
            plugin.language().send(sender, "leaderboard.line", Map.of("rank", i + 1, "player", stats.playerName(), "trades", stats.totalTrades()));
        }
    }

    private void admin(CommandSender sender, String[] args) {
        if (!require(sender, "fotiavillage.admin")) return;
        if (args.length < 2) {
            help(sender);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "reset" -> {
                if (args.length < 3) {
                    plugin.language().prefixed(sender, "admin.reset-usage");
                    return;
                }
                PlayerTradeStats stats = plugin.stats().findByName(args[2]).orElse(null);
                Player onlineTarget = onlinePlayer(args[2]);
                if (stats == null && onlineTarget == null) {
                    plugin.language().prefixed(sender, "player-not-found");
                    return;
                }
                plugin.database().resetPlayer(stats != null ? stats.uuid() : onlineTarget.getUniqueId());
                plugin.language().prefixed(sender, "admin.reset-success", Map.of("player", stats != null ? stats.playerName() : onlineTarget.getName()));
            }
            case "clear" -> clear(sender, args);
            case "info" -> plugin.language().prefixed(sender, "admin.info", Map.of("version", plugin.getDescription().getVersion(), "database", databaseStatus()));
            default -> help(sender);
        }
    }

    private void clear(CommandSender sender, String[] args) {
        String key = sender.getName();
        long now = System.currentTimeMillis();
        if (args.length >= 3 && args[2].equalsIgnoreCase("confirm")) {
            Long requested = clearConfirmations.get(key);
            if (requested != null && now - requested <= 10_000L) {
                plugin.database().clearTradeData();
                clearConfirmations.remove(key);
                plugin.language().prefixed(sender, "admin.clear-success");
                return;
            }
        }
        clearConfirmations.put(key, now);
        plugin.language().prefixed(sender, "admin.clear-warning");
    }

    private void perf(CommandSender sender, String[] args) {
        if (!require(sender, "fotiavillage.admin")) return;
        String option = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "overview";
        switch (option) {
            case "database" -> plugin.language().send(sender, "perf.database", Map.of("status", databaseStatus(), "size", plugin.database().file().exists() ? plugin.database().file().length() + " bytes" : "0 bytes"));
            case "tracker" -> {
                var stats = plugin.villagerTracker().stats();
                plugin.language().send(sender, "perf.tracker", Map.of("chunks", stats.trackedChunks(), "villagers", stats.totalVillagers()));
            }
            case "cleanup" -> {
                plugin.language().prefixed(sender, "perf.cleanup-start");
                plugin.performance().cleanupExpiredData();
                plugin.language().prefixed(sender, "perf.cleanup-done");
            }
            case "memory", "overview" -> {
                plugin.language().send(sender, "perf.title");
                plugin.language().send(sender, "perf.overview", Map.of("tps", plugin.performance().tps(), "used", plugin.performance().usedMemoryMb(), "max", plugin.performance().maxMemoryMb(), "uptime", plugin.performance().uptime()));
            }
            default -> plugin.language().prefixed(sender, "unknown-command", Map.of("command", option));
        }
    }

    private void lifespan(CommandSender sender, String[] args) {
        if (!require(sender, "fotiavillage.admin")) return;
        if (args.length < 2) {
            plugin.language().send(sender, "lifespan.title");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "check" -> {
                LifespanService.LifespanScan scan = plugin.lifespan().scan();
                plugin.language().send(sender, "lifespan.check", Map.of("total", scan.total(), "with", scan.withLifespan(), "without", scan.withoutLifespan()));
            }
            case "add" -> {
                if (!plugin.settings().lifespan().enabled()) {
                    plugin.language().prefixed(sender, "lifespan.disabled");
                    return;
                }
                int days = plugin.settings().lifespan().days();
                if (args.length >= 3) {
                    try {
                        days = Math.max(1, Integer.parseInt(args[2]));
                    } catch (NumberFormatException ignored) {
                    }
                }
                int count = plugin.lifespan().addMissingLifespan(days);
                plugin.language().prefixed(sender, "lifespan.add-success", Map.of("count", count, "days", days));
            }
            case "addtarget" -> addTargetLifespan(sender, args);
            case "list" -> {
                List<String> lines = plugin.lifespan().missingVillagerLines();
                if (lines.isEmpty()) {
                    plugin.language().prefixed(sender, "lifespan.list-empty");
                    return;
                }
                lines.stream().limit(10).forEach(line -> {
                    String[] parts = line.split("\\|");
                    plugin.language().send(sender, "lifespan.list-line", Map.of("id", parts[0], "world", parts[1], "x", parts[2], "y", parts[3], "z", parts[4]));
                });
            }
            default -> plugin.language().prefixed(sender, "unknown-command", Map.of("command", args[1]));
        }
    }

    private void addTargetLifespan(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.language().prefixed(sender, "player-only");
            return;
        }
        if (!plugin.settings().lifespan().enabled()) {
            plugin.language().prefixed(sender, "lifespan.disabled");
            return;
        }
        if (args.length < 3) {
            plugin.language().prefixed(sender, "lifespan.addtarget-usage");
            return;
        }
        Integer days = parsePositiveInt(args[2]);
        if (days == null) {
            plugin.language().prefixed(sender, "lifespan.invalid-days");
            return;
        }
        Villager villager = targetVillager(player, plugin.settings().lifespan().commandTargetRange());
        if (villager == null) {
            plugin.language().prefixed(sender, "lifespan.target-not-found");
            return;
        }
        long remaining = plugin.lifespan().addLifespan(villager, days);
        if (remaining < 0L) {
            plugin.language().prefixed(sender, "lifespan.target-excluded");
            return;
        }
        plugin.language().prefixed(sender, "lifespan.target-success", Map.of(
            "days", days,
            "time", plugin.language().formatDuration(remaining),
            "id", villager.getUniqueId().toString()
        ));
    }

    private void item(CommandSender sender, String[] args) {
        if (!require(sender, "fotiavillage.item.give")) return;
        if (args.length < 2 || !args[1].equalsIgnoreCase("give")) {
            plugin.language().prefixed(sender, "item.give-usage");
            return;
        }
        if (args.length < 4) {
            plugin.language().prefixed(sender, "item.give-usage");
            return;
        }
        Player target = onlinePlayer(args[2]);
        if (target == null) {
            plugin.language().prefixed(sender, "player-not-found");
            return;
        }
        String itemId = args[3];
        if (plugin.lifespanItems().findItem(itemId).isEmpty()) {
            plugin.language().prefixed(sender, "item.unknown", Map.of("item", itemId));
            return;
        }
        int amount = 1;
        if (args.length >= 5) {
            Integer parsed = parsePositiveInt(args[4]);
            if (parsed == null) {
                plugin.language().prefixed(sender, "item.invalid-amount");
                return;
            }
            amount = Math.min(parsed, 2304);
        }
        int given = giveConfiguredItem(target, itemId, amount);
        if (given <= 0) {
            plugin.language().prefixed(sender, "item.unknown", Map.of("item", itemId));
            return;
        }
        plugin.language().prefixed(sender, "item.give-success", Map.of("player", target.getName(), "item", itemId, "amount", given));
    }

    private int giveConfiguredItem(Player target, String itemId, int amount) {
        int remaining = amount;
        int given = 0;
        while (remaining > 0) {
            ItemStack template = plugin.lifespanItems().createItem(itemId, 1).orElse(null);
            if (template == null) {
                break;
            }
            int stackAmount = Math.min(remaining, template.getMaxStackSize());
            template.setAmount(stackAmount);
            Map<Integer, ItemStack> leftovers = target.getInventory().addItem(template);
            for (ItemStack leftover : leftovers.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }
            given += stackAmount;
            remaining -= stackAmount;
        }
        target.updateInventory();
        return given;
    }

    private void kill(CommandSender sender) {
        if (!require(sender, "fotiavillage.kill")) return;
        if (!plugin.settings().commands().killEnabled()) {
            plugin.language().prefixed(sender, "kill.disabled");
            return;
        }
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                plugin.lifespan().cleanupDisplay(villager);
                villager.remove();
                count++;
            }
        }
        if (count > 0) {
            plugin.villagerTracker().initialize();
        }
        plugin.language().prefixed(sender, "kill.success", Map.of("count", count));
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        plugin.language().prefixed(sender, "no-permission");
        return false;
    }

    private String databaseStatus() {
        return plugin.language().plain(plugin.database().isConnected() ? "status.connected" : "status.disconnected");
    }

    private Player onlinePlayer(String name) {
        return Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.getName().equalsIgnoreCase(name))
            .findFirst()
            .orElse(null);
    }

    private Integer parsePositiveInt(String raw) {
        try {
            int value = Integer.parseInt(raw);
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Villager targetVillager(Player player, double maxDistance) {
        Vector eye = player.getEyeLocation().toVector();
        Vector direction = player.getEyeLocation().getDirection().normalize();
        Villager nearest = null;
        double nearestProjection = maxDistance + 1.0D;
        for (Entity entity : player.getWorld().getNearbyEntities(player.getEyeLocation(), maxDistance, maxDistance, maxDistance)) {
            if (!(entity instanceof Villager villager) || !villager.isValid() || villager.isDead()) {
                continue;
            }
            Vector target = villager.getLocation().add(0.0D, 1.0D, 0.0D).toVector();
            Vector toTarget = target.clone().subtract(eye);
            double projection = toTarget.dot(direction);
            if (projection < 0.0D || projection > maxDistance || projection >= nearestProjection) {
                continue;
            }
            Vector closest = eye.clone().add(direction.clone().multiply(projection));
            if (closest.distanceSquared(target) <= 0.85D) {
                nearest = villager;
                nearestProjection = projection;
            }
        }
        return nearest;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(args[0], "reload", "stats", "top", "admin", "perf", "lifespan", "item", "kill");
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "admin" -> filter(args[1], "reset", "clear", "info");
                case "perf" -> filter(args[1], "overview", "memory", "database", "tracker", "cleanup");
                case "lifespan" -> filter(args[1], "check", "add", "addtarget", "list");
                case "item" -> filter(args[1], "give");
                case "stats" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                default -> List.of();
            };
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("reset")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("clear")) return filter(args[2], "confirm");
        if (args.length == 3 && args[0].equalsIgnoreCase("lifespan") && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("addtarget"))) return filter(args[2], "1", "3", "7", "14", "30");
        if (args.length == 3 && args[0].equalsIgnoreCase("item") && args[1].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("item") && args[1].equalsIgnoreCase("give")) {
            String lower = args[3].toLowerCase(Locale.ROOT);
            return plugin.lifespanItems().itemIds().stream().filter(id -> id.toLowerCase(Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("item") && args[1].equalsIgnoreCase("give")) return filter(args[4], "1", "8", "16", "32", "64");
        return List.of();
    }

    private List<String> filter(String prefix, String... options) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return Arrays.stream(options).filter(option -> option.startsWith(lower)).collect(Collectors.toCollection(ArrayList::new));
    }
}
