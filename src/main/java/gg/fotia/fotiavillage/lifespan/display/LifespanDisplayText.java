package gg.fotia.fotiavillage.lifespan.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public record LifespanDisplayText(Component component, String hologramLine) {
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    public static LifespanDisplayText plain(Component component) {
        return new LifespanDisplayText(component, LEGACY_SECTION.serialize(component));
    }
}
