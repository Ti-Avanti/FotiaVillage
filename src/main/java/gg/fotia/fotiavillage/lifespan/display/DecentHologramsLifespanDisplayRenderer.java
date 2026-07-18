package gg.fotia.fotiavillage.lifespan.display;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DecentHologramsLifespanDisplayRenderer implements LifespanDisplayRenderer {
    private static final String NAME_PREFIX = "fotiavillage_lifespan_";

    private final FotiaVillagePlugin plugin;
    private final NamespacedKey displayIdKey;
    private final double heightOffset;
    private final Map<UUID, TrackedHologram> holograms = new HashMap<>();

    public DecentHologramsLifespanDisplayRenderer(FotiaVillagePlugin plugin, NamespacedKey displayIdKey, double heightOffset) {
        this.plugin = plugin;
        this.displayIdKey = displayIdKey;
        this.heightOffset = heightOffset;
    }

    @Override
    public void createOrUpdate(Villager villager, LifespanDisplayText text) {
        UUID ownerId = villager.getUniqueId();
        String name = hologramName(ownerId);
        villager.getPersistentDataContainer().set(displayIdKey, PersistentDataType.STRING, name);

        try {
            TrackedHologram tracked = holograms.get(ownerId);
            boolean created = false;
            Hologram registered = DHAPI.getHologram(name);
            if (tracked == null || registered != tracked.hologram) {
                Hologram hologram = registered;
                if (hologram == null) {
                    hologram = DHAPI.createHologram(name, displayLocation(villager), false, text.hologramLines());
                    created = true;
                }
                tracked = new TrackedHologram(name, villager, hologram);
                holograms.put(ownerId, tracked);
            } else {
                tracked.villager = villager;
            }

            moveHologram(tracked, displayLocation(villager));
            if (created) {
                tracked.lines = List.copyOf(text.hologramLines());
            } else if (!text.hologramLines().equals(tracked.lines)) {
                DHAPI.setHologramLines(tracked.hologram, text.hologramLines());
                tracked.lines = List.copyOf(text.hologramLines());
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Failed to update DecentHolograms lifespan display " + name + ": " + ex.getMessage());
        }
    }

    @Override
    public void tick() {
        Iterator<Map.Entry<UUID, TrackedHologram>> iterator = holograms.entrySet().iterator();
        while (iterator.hasNext()) {
            TrackedHologram tracked = iterator.next().getValue();
            Villager villager = tracked.villager;
            if (!villager.isValid() || villager.isDead()) {
                removeHologram(tracked.name);
                iterator.remove();
                continue;
            }
            try {
                moveHologram(tracked, displayLocation(villager));
            } catch (RuntimeException ex) {
                if (plugin.settings().debug()) {
                    plugin.getLogger().warning("Failed to move DecentHolograms lifespan display " + tracked.name + ": " + ex.getMessage());
                }
            }
        }
    }

    @Override
    public void cleanup(Villager villager) {
        TrackedHologram tracked = holograms.remove(villager.getUniqueId());
        String name = tracked == null ? null : tracked.name;
        String storedName = villager.getPersistentDataContainer().get(displayIdKey, PersistentDataType.STRING);
        if (storedName != null && storedName.startsWith(NAME_PREFIX)) {
            name = storedName;
        }
        removeHologram(name);
        villager.getPersistentDataContainer().remove(displayIdKey);
    }

    @Override
    public void cleanupOrphans() {
        Iterator<Map.Entry<UUID, TrackedHologram>> iterator = holograms.entrySet().iterator();
        while (iterator.hasNext()) {
            TrackedHologram tracked = iterator.next().getValue();
            if (!tracked.villager.isValid() || tracked.villager.isDead()) {
                removeHologram(tracked.name);
                iterator.remove();
            }
        }
    }

    @Override
    public void removeAll() {
        for (TrackedHologram tracked : holograms.values()) {
            removeHologram(tracked.name);
        }
        holograms.clear();
        for (var world : plugin.getServer().getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                String storedName = villager.getPersistentDataContainer().get(displayIdKey, PersistentDataType.STRING);
                if (storedName != null && storedName.startsWith(NAME_PREFIX)) {
                    villager.getPersistentDataContainer().remove(displayIdKey);
                }
            }
        }
    }

    @Override
    public String name() {
        return "DECENT_HOLOGRAMS";
    }

    private String hologramName(UUID ownerId) {
        return NAME_PREFIX + ownerId.toString().replace("-", "");
    }

    private Location displayLocation(Villager villager) {
        return villager.getLocation().add(0, villager.getHeight() + heightOffset, 0);
    }

    private void moveHologram(TrackedHologram tracked, Location location) {
        Location last = tracked.lastLocation;
        if (last != null && last.getWorld() == location.getWorld() && last.distanceSquared(location) < 0.0001D) {
            return;
        }
        DHAPI.moveHologram(tracked.hologram, location);
        tracked.lastLocation = location;
    }

    private void removeHologram(String name) {
        if (name == null || !name.startsWith(NAME_PREFIX)) {
            return;
        }
        try {
            DHAPI.removeHologram(name);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Failed to remove DecentHolograms lifespan display " + name + ": " + ex.getMessage());
        }
    }

    private static final class TrackedHologram {
        private final String name;
        private final Hologram hologram;
        private Villager villager;
        private Location lastLocation;
        private List<String> lines = List.of();

        private TrackedHologram(String name, Villager villager, Hologram hologram) {
            this.name = name;
            this.villager = villager;
            this.hologram = hologram;
        }
    }
}
