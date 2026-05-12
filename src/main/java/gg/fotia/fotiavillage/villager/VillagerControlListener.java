package gg.fotia.fotiavillage.villager;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTransformEvent;

import java.util.Map;

public final class VillagerControlListener implements Listener {
    private final FotiaVillagePlugin plugin;

    public VillagerControlListener(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() != EntityType.VILLAGER) {
            return;
        }
        FotiaSettings settings = plugin.settings();
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason == CreatureSpawnEvent.SpawnReason.CURED) {
            return;
        }
        if (isSpawnEgg(reason)) {
            if (!settings.spawnControl().allowSpawnEgg()) {
                cancel(event, "villager.spawn-egg-blocked");
                return;
            }
            if (limitReached(event.getLocation())) {
                cancel(event, "villager.limit-reached");
                return;
            }
            setLifespanLater(event);
            return;
        }
        if (reason == CreatureSpawnEvent.SpawnReason.BREEDING) {
            if (!settings.spawnControl().allowBreeding()) {
                cancel(event, "villager.breeding-blocked");
                return;
            }
            if (limitReached(event.getLocation())) {
                cancel(event, "villager.limit-reached");
                return;
            }
            setLifespanLater(event);
            return;
        }
        if (isNaturalSpawn(reason) && settings.spawnControl().blockNaturalSpawn()) {
            cancel(event, "villager.spawn-blocked");
            return;
        }
        if (limitReached(event.getLocation())) {
            cancel(event, "villager.limit-reached");
            return;
        }
        setLifespanLater(event);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onZombieVillagerCure(EntityTransformEvent event) {
        if (event.getEntity().getType() != EntityType.ZOMBIE_VILLAGER || event.getTransformedEntity().getType() != EntityType.VILLAGER) {
            return;
        }
        FotiaSettings settings = plugin.settings();
        if (!settings.spawnControl().allowCure()) {
            event.setCancelled(true);
            notifyNearby(event.getEntity().getLocation(), "villager.cure-blocked");
            return;
        }
        if (limitReached(event.getEntity().getLocation())) {
            event.setCancelled(true);
            notifyNearby(event.getEntity().getLocation(), "villager.limit-reached");
            return;
        }
        if (settings.lifespan().enabled()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (event.getTransformedEntity() instanceof Villager villager && villager.isValid() && !villager.isDead()) {
                    plugin.lifespan().setLifespan(villager, settings.lifespan().days());
                    notifyLifespanSet(villager, settings.lifespan().days());
                }
            }, 10L);
        }
    }

    private boolean limitReached(Location location) {
        FotiaSettings.VillagerLimit limit = plugin.settings().villagerLimit();
        return limit.enabled() && plugin.villagerTracker().countInRadius(location.getChunk(), limit.chunkRadius()) >= limit.maxVillagers();
    }

    private boolean isSpawnEgg(CreatureSpawnEvent.SpawnReason reason) {
        return switch (reason.name()) {
            case "SPAWNER_EGG", "DISPENSE_EGG", "EGG" -> true;
            default -> false;
        };
    }

    private boolean isNaturalSpawn(CreatureSpawnEvent.SpawnReason reason) {
        return switch (reason.name()) {
            case "NATURAL", "CHUNK_GEN", "DEFAULT" -> true;
            default -> false;
        };
    }

    private void setLifespanLater(CreatureSpawnEvent event) {
        FotiaSettings.Lifespan lifespan = plugin.settings().lifespan();
        if (!lifespan.enabled()) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.getEntity() instanceof Villager villager && villager.isValid() && !villager.isDead()) {
                plugin.lifespan().setLifespan(villager, lifespan.days());
                notifyLifespanSet(villager, lifespan.days());
            }
        });
    }

    private void cancel(CreatureSpawnEvent event, String messageKey) {
        event.setCancelled(true);
        notifyNearby(event.getLocation(), messageKey);
    }

    private void notifyLifespanSet(Villager villager, int days) {
        if (plugin.settings().lifespan().notifyEnabled()) {
            notifyNearby(villager.getLocation(), "lifespan.set", Map.of("days", days), plugin.settings().lifespan().notifyRange());
        }
    }

    private void notifyNearby(Location location, String key) {
        notifyNearby(location, key, Map.of(), 10);
    }

    private void notifyNearby(Location location, String key, Map<String, ?> replacements, int range) {
        if (location.getWorld() == null) {
            return;
        }
        if (range <= 0) {
            plugin.getServer().getOnlinePlayers().forEach(player -> plugin.language().prefixed(player, key, replacements));
            return;
        }
        for (Player player : location.getWorld().getNearbyPlayers(location, range)) {
            plugin.language().prefixed(player, key, replacements);
        }
    }
}
