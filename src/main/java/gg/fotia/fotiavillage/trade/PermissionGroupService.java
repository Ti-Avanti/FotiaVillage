package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import org.bukkit.entity.Player;

public final class PermissionGroupService {
    private final FotiaVillagePlugin plugin;

    public PermissionGroupService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public FotiaSettings.PermissionGroup group(Player player) {
        for (FotiaSettings.PermissionGroup group : plugin.settings().tradeControl().permissionGroups()) {
            if (group.permission().isBlank() || player.hasPermission(group.permission())) {
                return group;
            }
        }
        return new FotiaSettings.PermissionGroup("default", "", 0, 1.0, 1.0, 0);
    }
}
