package gg.fotia.fotiavillage.placeholder;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.stats.PlayerTradeStats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

public final class FotiaPlaceholderExpansion extends PlaceholderExpansion {
    private final FotiaVillagePlugin plugin;

    public FotiaPlaceholderExpansion(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "fotiavillage";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Ti_Avanti";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String normalized = params.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("top_")) {
            return top(normalized);
        }
        if (player == null || !player.isOnline()) {
            return "";
        }
        Player online = player.getPlayer();
        if (online == null) {
            return "";
        }
        PlayerTradeStats stats = plugin.stats().find(online.getUniqueId()).orElse(null);
        return switch (normalized) {
            case "trades" -> stats == null ? "0" : String.valueOf(stats.totalTrades());
            case "exp_spent" -> stats == null ? "0" : String.valueOf(stats.totalExpSpent());
            case "rank" -> String.valueOf(plugin.stats().rank(online.getUniqueId()));
            case "group" -> plugin.permissionGroups().group(online).name();
            case "group_priority" -> String.valueOf(plugin.permissionGroups().group(online).priority());
            case "daily_limit" -> String.valueOf(plugin.tradeLimits().globalLimit(online));
            case "daily_used" -> String.valueOf(plugin.tradeLimits().globalUsed(online));
            case "daily_remaining" -> String.valueOf(Math.max(0, plugin.tradeLimits().globalLimit(online) - plugin.tradeLimits().globalUsed(online)));
            case "player_level" -> String.valueOf(online.getLevel());
            default -> null;
        };
    }

    private String top(String params) {
        String[] parts = params.split("_");
        if (parts.length < 3) {
            return "";
        }
        int rank;
        try {
            rank = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            return "";
        }
        var leaderboard = plugin.stats().leaderboard();
        if (rank < 1 || rank > leaderboard.size()) {
            return "-";
        }
        PlayerTradeStats stats = leaderboard.get(rank - 1);
        return switch (parts[2].toLowerCase()) {
            case "name" -> stats.playerName();
            case "trades" -> String.valueOf(stats.totalTrades());
            case "exp" -> String.valueOf(stats.totalExpSpent());
            default -> "";
        };
    }
}
