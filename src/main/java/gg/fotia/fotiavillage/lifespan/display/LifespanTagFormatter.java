package gg.fotia.fotiavillage.lifespan.display;

import net.kyori.adventure.text.Component;

import java.util.List;

@FunctionalInterface
public interface LifespanTagFormatter {
    LifespanDisplayText format(List<Component> text);
}
