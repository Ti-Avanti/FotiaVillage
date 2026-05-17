package gg.fotia.fotiavillage.lifespan.display;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

abstract class AbstractEntityLifespanDisplayRenderer implements LifespanDisplayRenderer {
    protected final FotiaVillagePlugin plugin;
    protected final NamespacedKey displayIdKey;
    protected final NamespacedKey displayOwnerKey;
    private final Map<UUID, Entity> displays = new ConcurrentHashMap<>();

    AbstractEntityLifespanDisplayRenderer(FotiaVillagePlugin plugin, NamespacedKey displayIdKey, NamespacedKey displayOwnerKey) {
        this.plugin = plugin;
        this.displayIdKey = displayIdKey;
        this.displayOwnerKey = displayOwnerKey;
    }

    @Override
    public final void createOrUpdate(Villager villager, LifespanDisplayText text) {
        Entity display = displays.get(villager.getUniqueId());
        if (display == null || !display.isValid() || !isDisplayEntity(display)) {
            cleanup(villager);
            display = createDisplay(villager);
            display.setPersistent(false);
            display.setSilent(true);
            display.setInvulnerable(true);
            display.getPersistentDataContainer().set(displayOwnerKey, PersistentDataType.STRING, villager.getUniqueId().toString());
            displays.put(villager.getUniqueId(), display);
            villager.getPersistentDataContainer().set(displayIdKey, PersistentDataType.STRING, display.getUniqueId().toString());
        }
        updateDisplay(villager, display, text);
    }

    @Override
    public final void cleanup(Villager villager) {
        Entity display = displays.remove(villager.getUniqueId());
        if (display != null && display.isValid()) {
            display.remove();
        }
        String id = villager.getPersistentDataContainer().get(displayIdKey, PersistentDataType.STRING);
        if (id != null) {
            removeEntity(id);
            villager.getPersistentDataContainer().remove(displayIdKey);
        }
    }

    @Override
    public final void cleanupOrphans() {
        for (var world : plugin.getServer().getWorlds()) {
            for (Entity display : world.getEntities()) {
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

    @Override
    public final void removeAll() {
        displays.values().forEach(Entity::remove);
        displays.clear();
        for (var world : plugin.getServer().getWorlds()) {
            for (Entity display : world.getEntities()) {
                if (display.getPersistentDataContainer().has(displayOwnerKey, PersistentDataType.STRING)) {
                    display.remove();
                }
            }
        }
    }

    protected abstract boolean isDisplayEntity(Entity entity);

    protected abstract Entity createDisplay(Villager villager);

    protected abstract void updateDisplay(Villager villager, Entity display, LifespanDisplayText text);

    private void removeEntity(String id) {
        try {
            Entity entity = plugin.getServer().getEntity(UUID.fromString(id));
            if (entity != null) {
                entity.remove();
            }
        } catch (IllegalArgumentException ignored) {
        }
    }
}
