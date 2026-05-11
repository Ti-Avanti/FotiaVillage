package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import gg.fotia.fotiavillage.util.TimeUtil;
import org.bukkit.entity.Player;

public final class TradeLimitService {
    private final FotiaVillagePlugin plugin;
    private final PermissionGroupService groups;

    public TradeLimitService(FotiaVillagePlugin plugin, PermissionGroupService groups) {
        this.plugin = plugin;
        this.groups = groups;
    }

    public boolean canTrade(Player player, String profession, String itemType) {
        FotiaSettings.Limit config = plugin.settings().tradeControl().limit();
        if (!config.enabled()) {
            return true;
        }
        String resetKey = TimeUtil.resetKey(config.resetPeriod());
        int globalLimit = config.globalLimit() + groups.group(player).dailyLimitBonus();
        if (plugin.database().getTradeCount(player.getUniqueId(), "global", "all", resetKey) >= globalLimit) {
            return false;
        }
        int professionLimit = config.perProfession().getOrDefault(profession, 0);
        if (professionLimit > 0 && plugin.database().getTradeCount(player.getUniqueId(), "profession", profession, resetKey) >= professionLimit) {
            return false;
        }
        int itemLimit = config.perItem().getOrDefault(itemType, 0);
        return itemLimit <= 0 || plugin.database().getTradeCount(player.getUniqueId(), "item", itemType, resetKey) < itemLimit;
    }

    public void record(Player player, String profession, String itemType) {
        FotiaSettings.Limit config = plugin.settings().tradeControl().limit();
        if (!config.enabled()) {
            return;
        }
        String resetKey = TimeUtil.resetKey(config.resetPeriod());
        plugin.database().incrementTradeCount(player.getUniqueId(), "global", "all", resetKey);
        plugin.database().incrementTradeCount(player.getUniqueId(), "profession", profession, resetKey);
        plugin.database().incrementTradeCount(player.getUniqueId(), "item", itemType, resetKey);
    }

    public int globalUsed(Player player) {
        FotiaSettings.Limit config = plugin.settings().tradeControl().limit();
        return plugin.database().getTradeCount(player.getUniqueId(), "global", "all", TimeUtil.resetKey(config.resetPeriod()));
    }

    public int globalLimit(Player player) {
        return plugin.settings().tradeControl().limit().globalLimit() + groups.group(player).dailyLimitBonus();
    }
}
