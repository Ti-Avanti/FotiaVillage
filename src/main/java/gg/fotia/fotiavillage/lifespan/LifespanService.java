package gg.fotia.fotiavillage.lifespan;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LifespanService implements Listener {
    private final FotiaVillagePlugin plugin;
    private final NamespacedKey lifespanEndKey;
    private final NamespacedKey displayIdKey;
    private final NamespacedKey displayOwnerKey;
    private final Map<UUID, ArmorStand> displays = new ConcurrentHashMap<>();
    private final ArrayList<BukkitTask> tasks = new ArrayList<>();

    public LifespanService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
        this.lifespanEndKey = new NamespacedKey(plugin, "lifespan_end");
        this.displayIdKey = new NamespacedKey(plugin, "lifespan_display");
        this.displayOwnerKey = new NamespacedKey(plugin, "lifespan_display_owner");
    }

    public void start() {
        stop();
        if (!plugin.settings().lifespan().enabled()) {
            return;
        }
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkExpirations, 20L * 60L, 20L * 60L));
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateDisplays, 20L * 5L, 20L * 5L));
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::cleanupOrphanDisplays, 20L * 60L * 5L, 20L * 60L * 5L));
        if (plugin.settings().lifespan().autoAddEnabled() && plugin.settings().lifespan().autoAddCheckOnStartup()) {
            tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, this::autoAddMissingLifespan, 20L * 5L));
        }
        int interval = plugin.settings().lifespan().autoAddCheckInterval();
        if (plugin.settings().lifespan().autoAddEnabled() && interval > 0) {
            tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::autoAddMissingLifespan, 20L * interval, 20L * interval));
        }
        updateDisplays();
    }

    public void stop() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        displays.values().forEach(Entity::remove);
        displays.clear();
        removeOwnedDisplays();
    }

    public void setLifespan(Villager villager, int days) {
        long end = System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L;
        villager.getPersistentDataContainer().set(lifespanEndKey, PersistentDataType.LONG, end);
        createOrUpdateDisplay(villager);
    }

    public boolean hasLifespan(Villager villager) {
        return villager.getPersistentDataContainer().has(lifespanEndKey, PersistentDataType.LONG);
    }

    public long remaining(Villager villager) {
        Long end = villager.getPersistentDataContainer().get(lifespanEndKey, PersistentDataType.LONG);
        return end == null ? -1L : Math.max(0L, end - System.currentTimeMillis());
    }

    public void cleanupDisplay(Villager villager) {
        ArmorStand display = displays.remove(villager.getUniqueId());
        if (display != null && display.isValid()) {
            display.remove();
        }
        String id = villager.getPersistentDataContainer().get(displayIdKey, PersistentDataType.STRING);
        if (id != null) {
            try {
                Entity entity = plugin.getServer().getEntity(UUID.fromString(id));
                if (entity != null) {
                    entity.remove();
                }
            } catch (IllegalArgumentException ignored) {
            }
            villager.getPersistentDataContainer().remove(displayIdKey);
        }
    }

    public LifespanScan scan() {
        int total = 0;
        int without = 0;
        for (var world : plugin.getServer().getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                total++;
                if (!hasLifespan(villager)) {
                    without++;
                }
            }
        }
        return new LifespanScan(total, total - without, without);
    }

    public int addMissingLifespan(int days) {
        int count = 0;
        for (var world : plugin.getServer().getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (!hasLifespan(villager)) {
                    setLifespan(villager, days);
                    count++;
                }
            }
        }
        return count;
    }

    public ArrayList<String> missingVillagerLines() {
        ArrayList<String> lines = new ArrayList<>();
        for (var world : plugin.getServer().getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (!hasLifespan(villager)) {
                    lines.add(villager.getUniqueId().toString().substring(0, 8) + "|" + world.getName() + "|" + villager.getLocation().getBlockX() + "|" + villager.getLocation().getBlockY() + "|" + villager.getLocation().getBlockZ());
                }
            }
        }
        return lines;
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Villager villager) {
                cleanupDisplay(villager);
            }
        }
    }

    private void checkExpirations() {
        if (!plugin.settings().lifespan().enabled()) {
            return;
        }
        boolean removedAny = false;
        for (var world : plugin.getServer().getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (hasLifespan(villager) && remaining(villager) <= 0) {
                    cleanupDisplay(villager);
                    notifyExpired(villager);
                    villager.remove();
                    removedAny = true;
                }
            }
        }
        if (removedAny) {
            plugin.villagerTracker().initialize();
        }
    }

    private void updateDisplays() {
        if (!plugin.settings().lifespan().enabled()) {
            return;
        }
        for (var world : plugin.getServer().getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (hasLifespan(villager)) {
                    createOrUpdateDisplay(villager);
                }
            }
        }
    }

    private void autoAddMissingLifespan() {
        if (!plugin.settings().lifespan().enabled() || !plugin.settings().lifespan().autoAddEnabled()) {
            return;
        }
        int added = addMissingLifespan(plugin.settings().lifespan().days());
        if (added > 0 || plugin.settings().debug()) {
            plugin.getLogger().info("[寿命系统] 自动补全村民寿命数量: " + added);
        }
    }

    private void createOrUpdateDisplay(Villager villager) {
        ArmorStand display = displays.get(villager.getUniqueId());
        if (display == null || !display.isValid()) {
            cleanupDisplay(villager);
            display = (ArmorStand) villager.getWorld().spawnEntity(villager.getLocation().add(0, villager.getHeight() + 0.35, 0), EntityType.ARMOR_STAND);
            display.setPersistent(false);
            display.setInvisible(true);
            display.setMarker(true);
            display.setGravity(false);
            display.setSmall(true);
            display.setSilent(true);
            display.setInvulnerable(true);
            display.setCustomNameVisible(true);
            display.getPersistentDataContainer().set(displayOwnerKey, PersistentDataType.STRING, villager.getUniqueId().toString());
            villager.addPassenger(display);
            displays.put(villager.getUniqueId(), display);
            villager.getPersistentDataContainer().set(displayIdKey, PersistentDataType.STRING, display.getUniqueId().toString());
        }
        display.customName(plugin.language().component(formatKey(villager), formatValues(villager)));
    }

    private String formatKey(Villager villager) {
        long seconds = remaining(villager) / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        if (days > 0) return "lifespan.display-days";
        if (hours > 0) return "lifespan.display-hours";
        if (minutes > 0) return "lifespan.display-minutes";
        return "lifespan.display-seconds";
    }

    private Map<String, ?> formatValues(Villager villager) {
        long seconds = remaining(villager) / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        return Map.of("days", days, "hours", hours % 24L, "minutes", minutes, "seconds", seconds);
    }

    private void notifyExpired(Villager villager) {
        if (!plugin.settings().lifespan().notifyEnabled()) {
            return;
        }
        int range = plugin.settings().lifespan().notifyRange();
        if (range <= 0) {
            plugin.getServer().getOnlinePlayers().forEach(player -> plugin.language().prefixed(player, "lifespan.expired"));
            return;
        }
        for (Player player : villager.getWorld().getNearbyPlayers(villager.getLocation(), range)) {
            plugin.language().prefixed(player, "lifespan.expired");
        }
    }

    private void cleanupOrphanDisplays() {
        for (var world : plugin.getServer().getWorlds()) {
            for (ArmorStand display : world.getEntitiesByClass(ArmorStand.class)) {
                String owner = display.getPersistentDataContainer().get(displayOwnerKey, PersistentDataType.STRING);
                if (owner == null) {
                    continue;
                }
                Entity entity = null;
                try {
                    entity = plugin.getServer().getEntity(UUID.fromString(owner));
                } catch (IllegalArgumentException ignored) {
                }
                if (!(entity instanceof Villager villager) || !villager.isValid() || villager.isDead()) {
                    display.remove();
                }
            }
        }
    }

    private void removeOwnedDisplays() {
        for (var world : plugin.getServer().getWorlds()) {
            for (ArmorStand display : world.getEntitiesByClass(ArmorStand.class)) {
                if (display.getPersistentDataContainer().has(displayOwnerKey, PersistentDataType.STRING)) {
                    display.remove();
                }
            }
        }
    }

    public record LifespanScan(int total, int withLifespan, int withoutLifespan) {}
}
