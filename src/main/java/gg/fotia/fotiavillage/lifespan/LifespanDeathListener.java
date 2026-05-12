package gg.fotia.fotiavillage.lifespan;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;

public final class LifespanDeathListener implements Listener {
    private final FotiaVillagePlugin plugin;

    public LifespanDeathListener(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onVillagerDeath(EntityDeathEvent event) {
        cleanup(event.getEntity());
    }

    @EventHandler
    public void onVillagerRemove(EntityRemoveEvent event) {
        cleanup(event.getEntity());
    }

    private void cleanup(Entity entity) {
        if (entity instanceof Villager villager) {
            plugin.lifespan().cleanupDisplay(villager);
        }
    }
}
