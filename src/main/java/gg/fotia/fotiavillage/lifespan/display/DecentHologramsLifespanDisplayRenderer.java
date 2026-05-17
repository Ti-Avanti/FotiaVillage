package gg.fotia.fotiavillage.lifespan.display;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DecentHologramsLifespanDisplayRenderer implements LifespanDisplayRenderer {
    private static final String NAME_PREFIX = "fotiavillage_lifespan_";

    private final FotiaVillagePlugin plugin;
    private final NamespacedKey displayIdKey;
    private final double heightOffset;
    private final Map<UUID, String> hologramNames = new ConcurrentHashMap<>();

    public DecentHologramsLifespanDisplayRenderer(FotiaVillagePlugin plugin, NamespacedKey displayIdKey, double heightOffset) {
        this.plugin = plugin;
        this.displayIdKey = displayIdKey;
        this.heightOffset = heightOffset;
    }

    @Override
    public void createOrUpdate(Villager villager, LifespanDisplayText text) {
        UUID ownerId = villager.getUniqueId();
        String name = hologramNames.computeIfAbsent(ownerId, this::hologramName);
        villager.getPersistentDataContainer().set(displayIdKey, PersistentDataType.STRING, name);

        Location location = displayLocation(villager);
        List<String> lines = List.of(text.hologramLine());
        try {
            Hologram hologram = DHAPI.getHologram(name);
            if (hologram == null) {
                DHAPI.createHologram(name, location, false, lines);
                return;
            }
            DHAPI.moveHologram(hologram, location);
            DHAPI.setHologramLines(hologram, lines);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Failed to update DecentHolograms lifespan display " + name + ": " + ex.getMessage());
        }
    }

    @Override
    public void cleanup(Villager villager) {
        String name = hologramNames.remove(villager.getUniqueId());
        String storedName = villager.getPersistentDataContainer().get(displayIdKey, PersistentDataType.STRING);
        if (storedName != null && storedName.startsWith(NAME_PREFIX)) {
            name = storedName;
        }
        removeHologram(name);
        villager.getPersistentDataContainer().remove(displayIdKey);
    }

    @Override
    public void cleanupOrphans() {
        for (Map.Entry<UUID, String> entry : new ArrayList<>(hologramNames.entrySet())) {
            Entity entity = plugin.getServer().getEntity(entry.getKey());
            if (!(entity instanceof Villager villager) || !villager.isValid() || villager.isDead()) {
                removeHologram(entry.getValue());
                hologramNames.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    @Override
    public void removeAll() {
        for (String name : new ArrayList<>(hologramNames.values())) {
            removeHologram(name);
        }
        hologramNames.clear();
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
}
