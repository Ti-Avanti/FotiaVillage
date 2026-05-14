package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import org.bukkit.entity.Player;

public final class PermissionGroupService {
    private static final FotiaSettings.PermissionGroup DEFAULT_GROUP = new FotiaSettings.PermissionGroup("default", "", 0, 1.0, 1.0, 0);
    private final FotiaVillagePlugin plugin;

    public PermissionGroupService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public FotiaSettings.PermissionGroup group(Player player) {
        if (!plugin.settings().tradeControl().enabled() || !plugin.settings().tradeControl().permissionGroupsEnabled()) {
            return defaultGroup();
        }
        for (FotiaSettings.PermissionGroup group : plugin.settings().tradeControl().permissionGroups()) {
            if (group.permission().isBlank() || player.hasPermission(group.permission())) {
                return group;
            }
        }
        return defaultGroup();
    }

    public FotiaSettings.PermissionGroup defaultGroup() {
        return plugin.settings().tradeControl().permissionGroups().stream()
            .filter(group -> group.name().equalsIgnoreCase("default"))
            .findFirst()
            .orElse(DEFAULT_GROUP);
    }
}
