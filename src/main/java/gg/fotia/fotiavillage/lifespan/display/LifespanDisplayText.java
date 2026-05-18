package gg.fotia.fotiavillage.lifespan.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;

public record LifespanDisplayText(List<Component> components, List<String> hologramLines) {
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    public static LifespanDisplayText plain(List<Component> components) {
        return new LifespanDisplayText(components, components.stream().map(LEGACY_SECTION::serialize).toList());
    }

    public Component component() {
        return Component.join(JoinConfiguration.newlines(), components);
    }
}
