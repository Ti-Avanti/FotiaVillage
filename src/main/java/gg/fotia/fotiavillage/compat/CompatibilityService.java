package gg.fotia.fotiavillage.compat;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.metadata.MetadataValue;

public final class CompatibilityService {
    private static final String SHOPKEEPERS_PLUGIN = "Shopkeepers";
    private static final String SHOPKEEPERS_METADATA_KEY = "shopkeeper";

    private final boolean shopkeepersPresent;

    public CompatibilityService(FotiaVillagePlugin plugin) {
        this.shopkeepersPresent = plugin.getServer().getPluginManager().getPlugin(SHOPKEEPERS_PLUGIN) != null;
        if (shopkeepersPresent) {
            plugin.getLogger().info("Shopkeepers compatibility enabled.");
        }
    }

    public boolean isShopkeepersVillager(Villager villager) {
        return isShopkeeper(villager);
    }

    public boolean isShopkeeper(Entity entity) {
        if (!shopkeepersPresent || entity == null || !entity.hasMetadata(SHOPKEEPERS_METADATA_KEY)) {
            return false;
        }
        for (MetadataValue value : entity.getMetadata(SHOPKEEPERS_METADATA_KEY)) {
            if (value.getOwningPlugin() != null && SHOPKEEPERS_PLUGIN.equals(value.getOwningPlugin().getName())) {
                return true;
            }
        }
        return false;
    }
}
