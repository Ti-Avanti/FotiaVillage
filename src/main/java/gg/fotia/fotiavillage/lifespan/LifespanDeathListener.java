package gg.fotia.fotiavillage.lifespan;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;

public final class LifespanDeathListener implements Listener {
    private final FotiaVillagePlugin plugin;

    public LifespanDeathListener(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onVillagerDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Villager villager && plugin.lifespan().hasLifespan(villager)) {
            plugin.lifespan().cleanupDisplay(villager);
        }
    }
}
