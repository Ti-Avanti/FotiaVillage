package gg.fotia.fotiavillage.lifespan.display;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;

public record LifespanDisplayText(List<Component> components, List<String> hologramLines) {
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    public static LifespanDisplayText plain(List<Component> components) {
        return new LifespanDisplayText(components, components.stream().map(LEGACY_SECTION::serialize).toList());
    }

    public Component component() {
        TextComponent.Builder result = Component.text();
        for (int i = 0; i < components.size(); i++) {
            if (i > 0) {
                result.append(Component.newline());
            }
            result.append(components.get(i));
        }
        return result.build();
    }
}
