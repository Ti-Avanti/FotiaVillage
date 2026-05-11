package gg.fotia.fotiavillage.stats;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class StatsService {
    private final FotiaVillagePlugin plugin;

    public StatsService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public void record(Player player, String itemType, int expSpent) {
        if (!plugin.settings().tradeControl().statistics().enabled()) {
            return;
        }
        plugin.database().recordTrade(player.getUniqueId(), player.getName(), itemType, expSpent);
        if (plugin.settings().tradeControl().statistics().detailedLogging()) {
            plugin.getLogger().info("[交易] 玩家: " + player.getName() + ", 物品: " + itemType + ", 消耗经验: " + expSpent);
        }
    }

    public Optional<PlayerTradeStats> find(UUID uuid) {
        return plugin.database().findStats(uuid);
    }

    public Optional<PlayerTradeStats> findByName(String playerName) {
        return plugin.database().findStatsByName(playerName);
    }

    public List<PlayerTradeStats> leaderboard() {
        return plugin.database().leaderboard(plugin.settings().tradeControl().statistics().leaderboardSize());
    }
}
