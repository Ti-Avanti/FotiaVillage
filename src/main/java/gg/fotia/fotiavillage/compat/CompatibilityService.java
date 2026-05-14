package gg.fotia.fotiavillage.compat;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.metadata.MetadataValue;

public final class CompatibilityService {
    private static final String SHOPKEEPERS_PLUGIN = "Shopkeepers";
    private static final String SHOPKEEPERS_METADATA_KEY = "shopkeeper";
    private static final String CITIZENS_PLUGIN = "Citizens";
    private static final String CITIZENS_METADATA_KEY = "NPC";

    private final FotiaVillagePlugin plugin;
    private final boolean shopkeepersPresent;
    private final boolean citizensPresent;

    public CompatibilityService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
        this.shopkeepersPresent = plugin.getServer().getPluginManager().getPlugin(SHOPKEEPERS_PLUGIN) != null;
        this.citizensPresent = plugin.getServer().getPluginManager().getPlugin(CITIZENS_PLUGIN) != null;
        if (shopkeepersPresent) {
            plugin.getLogger().info("Shopkeepers compatibility enabled.");
        }
        if (citizensPresent) {
            plugin.getLogger().info("Citizens compatibility enabled.");
        }
    }

    public boolean isShopkeepersVillager(Villager villager) {
        return isShopkeeper(villager);
    }

    public boolean isExcludedFromLifespan(Villager villager) {
        var compatibility = plugin.settings().compatibility();
        if (isShopkeeper(villager)) {
            return compatibility.excludeShopkeepersFromLifespan();
        }
        if (isCitizensNpc(villager)) {
            return compatibility.excludeCitizensFromLifespan();
        }
        return compatibility.excludeGenericNpcMetadataFromLifespan()
            && hasMetadata(villager, compatibility.genericNpcMetadataKey());
    }

    public boolean isShopkeeper(Entity entity) {
        if (!shopkeepersPresent) {
            return false;
        }
        return hasMetadataOwnedBy(entity, SHOPKEEPERS_METADATA_KEY, SHOPKEEPERS_PLUGIN);
    }

    private boolean isCitizensNpc(Entity entity) {
        if (!citizensPresent) {
            return false;
        }
        return hasMetadataOwnedBy(entity, CITIZENS_METADATA_KEY, CITIZENS_PLUGIN);
    }

    private boolean hasMetadata(Entity entity, String key) {
        return entity != null && key != null && !key.isBlank() && entity.hasMetadata(key);
    }

    private boolean hasMetadataOwnedBy(Entity entity, String key, String pluginName) {
        if (!hasMetadata(entity, key)) {
            return false;
        }
        for (MetadataValue value : entity.getMetadata(key)) {
            if (value.getOwningPlugin() != null && pluginName.equals(value.getOwningPlugin().getName())) {
                return true;
            }
        }
        return false;
    }
}
