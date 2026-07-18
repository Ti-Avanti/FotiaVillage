package gg.fotia.fotiavillage.villager;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import io.papermc.paper.event.entity.EntityMoveEvent;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class VillagerTracker implements Listener {
    private final FotiaVillagePlugin plugin;
    private final Map<ChunkKey, Integer> counts = new ConcurrentHashMap<>();

    public VillagerTracker(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        counts.clear();
        plugin.getServer().getWorlds().forEach(world -> {
            if (!plugin.isWorldAllowed(world)) {
                return;
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                scan(chunk);
            }
        });
    }

    public int countInRadius(Chunk center, int radius) {
        if (!plugin.isWorldAllowed(center.getWorld())) {
            return 0;
        }
        int total = 0;
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                total += counts.getOrDefault(new ChunkKey(center.getWorld().getName(), x, z), 0);
            }
        }
        return total;
    }

    public TrackerStats stats() {
        return new TrackerStats(counts.size(), counts.values().stream().mapToInt(Integer::intValue).sum());
    }

    public void removeTrackedVillager(Villager villager) {
        if (villager != null && plugin.isWorldAllowed(villager.getWorld())) {
            decrement(villager.getChunk());
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.isWorldAllowed(event.getWorld())) {
            return;
        }
        scan(event.getChunk());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        counts.remove(new ChunkKey(event.getChunk()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() == EntityType.VILLAGER && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CURED && plugin.isWorldAllowed(event.getLocation())) {
            increment(event.getEntity().getChunk());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (event.getEntity() instanceof Villager && !(event.getTransformedEntity() instanceof Villager)) {
            if (plugin.isWorldAllowed(event.getEntity().getWorld())) {
                decrement(event.getEntity().getChunk());
            }
            return;
        }
        if (!(event.getEntity() instanceof Villager) && event.getTransformedEntity() instanceof Villager && plugin.isWorldAllowed(event.getTransformedEntity().getWorld())) {
            increment(event.getTransformedEntity().getChunk());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(EntityMoveEvent event) {
        if (!(event.getEntity() instanceof Villager)) {
            return;
        }
        Chunk from = event.getFrom().getChunk();
        Chunk to = event.getTo().getChunk();
        if (from.getWorld().equals(to.getWorld()) && from.getX() == to.getX() && from.getZ() == to.getZ()) {
            return;
        }
        if (plugin.isWorldAllowed(from.getWorld())) {
            decrement(from);
        }
        if (plugin.isWorldAllowed(to.getWorld())) {
            increment(to);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Villager && plugin.isWorldAllowed(event.getEntity().getWorld())) {
            decrement(event.getEntity().getChunk());
        }
    }

    private void scan(Chunk chunk) {
        if (!plugin.isWorldAllowed(chunk.getWorld())) {
            counts.remove(new ChunkKey(chunk));
            return;
        }
        int count = 0;
        for (Entity entity : chunk.getEntities()) {
            if (entity instanceof Villager) {
                count++;
            }
        }
        ChunkKey key = new ChunkKey(chunk);
        if (count > 0) {
            counts.put(key, count);
        } else {
            counts.remove(key);
        }
    }

    private void increment(Chunk chunk) {
        counts.merge(new ChunkKey(chunk), 1, Integer::sum);
    }

    private void decrement(Chunk chunk) {
        counts.computeIfPresent(new ChunkKey(chunk), (key, value) -> value > 1 ? value - 1 : null);
    }

    private record ChunkKey(String world, int x, int z) {
        ChunkKey(Chunk chunk) {
            this(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        }
    }

    public record TrackerStats(int trackedChunks, int totalVillagers) {}
}
