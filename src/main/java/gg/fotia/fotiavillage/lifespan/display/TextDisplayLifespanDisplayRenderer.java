package gg.fotia.fotiavillage.lifespan.display;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class TextDisplayLifespanDisplayRenderer extends AbstractEntityLifespanDisplayRenderer {
    private final double heightOffset;

    public TextDisplayLifespanDisplayRenderer(FotiaVillagePlugin plugin, NamespacedKey displayIdKey, NamespacedKey displayOwnerKey, double heightOffset) {
        super(plugin, displayIdKey, displayOwnerKey);
        this.heightOffset = heightOffset;
    }

    @Override
    public String name() {
        return "TEXT_DISPLAY";
    }

    @Override
    protected boolean isDisplayEntity(Entity entity) {
        return entity instanceof TextDisplay;
    }

    @Override
    protected Entity createDisplay(Villager villager) {
        TextDisplay display = (TextDisplay) villager.getWorld().spawnEntity(villager.getLocation(), EntityType.TEXT_DISPLAY);
        display.setBillboard(Display.Billboard.CENTER);
        display.setDefaultBackground(false);
        display.setSeeThrough(false);
        display.setShadowed(true);
        display.setLineWidth(200);
        display.setTextOpacity((byte) 255);
        attachToVillager(villager, display);
        updateTranslation(villager, display);
        return display;
    }

    @Override
    protected void updateDisplay(Villager villager, Entity display, LifespanDisplayText text) {
        TextDisplay textDisplay = (TextDisplay) display;
        attachToVillager(villager, textDisplay);
        updateTranslation(villager, textDisplay);
        textDisplay.text(text.component());
    }

    private void attachToVillager(Villager villager, TextDisplay display) {
        if (display.getVehicle() == villager) {
            return;
        }
        if (display.isInsideVehicle()) {
            display.leaveVehicle();
        }
        display.teleport(villager.getLocation());
        villager.addPassenger(display);
    }

    private void updateTranslation(Villager villager, TextDisplay display) {
        display.setTransformation(new Transformation(
            new Vector3f(0.0F, (float) (villager.getHeight() + heightOffset), 0.0F),
            new Quaternionf(),
            new Vector3f(1.0F, 1.0F, 1.0F),
            new Quaternionf()
        ));
    }
}
