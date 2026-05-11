package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import org.bukkit.entity.Player;

public final class CooldownService {
    private final FotiaVillagePlugin plugin;
    private final PermissionGroupService groups;

    public CooldownService(FotiaVillagePlugin plugin, PermissionGroupService groups) {
        this.plugin = plugin;
        this.groups = groups;
    }

    public long remaining(Player player, String profession, String itemType) {
        FotiaSettings.Cooldown config = plugin.settings().tradeControl().cooldown();
        if (!config.enabled()) {
            return 0L;
        }
        long end = plugin.database().getCooldownEnd(player.getUniqueId(), profession, itemType);
        return Math.max(0L, end - System.currentTimeMillis());
    }

    public void record(Player player, String profession, String itemType) {
        FotiaSettings.Cooldown config = plugin.settings().tradeControl().cooldown();
        if (!config.enabled()) {
            return;
        }
        int seconds = config.defaultCooldown();
        int professionSeconds = config.perProfession().getOrDefault(profession, 0);
        if (professionSeconds > 0) {
            seconds = professionSeconds;
        }
        int itemSeconds = config.perItem().getOrDefault(itemType, 0);
        if (itemSeconds > 0) {
            seconds = Math.max(seconds, itemSeconds);
        }
        seconds = (int) Math.ceil(seconds * groups.group(player).cooldownMultiplier());
        if (seconds > 0) {
            plugin.database().setCooldown(player.getUniqueId(), profession, itemType, System.currentTimeMillis() + seconds * 1000L);
        }
    }
}
