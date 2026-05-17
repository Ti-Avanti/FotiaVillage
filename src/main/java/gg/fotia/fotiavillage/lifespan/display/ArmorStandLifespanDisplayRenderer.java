package gg.fotia.fotiavillage.lifespan.display;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;

public final class ArmorStandLifespanDisplayRenderer extends AbstractEntityLifespanDisplayRenderer {
    private final double heightOffset;

    public ArmorStandLifespanDisplayRenderer(FotiaVillagePlugin plugin, NamespacedKey displayIdKey, NamespacedKey displayOwnerKey, double heightOffset) {
        super(plugin, displayIdKey, displayOwnerKey);
        this.heightOffset = heightOffset;
    }

    @Override
    public String name() {
        return "ARMOR_STAND";
    }

    @Override
    protected boolean isDisplayEntity(Entity entity) {
        return entity instanceof ArmorStand;
    }

    @Override
    protected Entity createDisplay(Villager villager) {
        ArmorStand display = (ArmorStand) villager.getWorld().spawnEntity(villager.getLocation().add(0, villager.getHeight() + heightOffset, 0), EntityType.ARMOR_STAND);
        display.setInvisible(true);
        display.setMarker(true);
        display.setGravity(false);
        display.setSmall(true);
        display.setCustomNameVisible(true);
        villager.addPassenger(display);
        return display;
    }

    @Override
    protected void updateDisplay(Villager villager, Entity display, LifespanDisplayText text) {
        display.customName(text.component());
    }
}
