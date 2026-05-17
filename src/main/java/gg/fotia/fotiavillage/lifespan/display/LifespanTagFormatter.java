package gg.fotia.fotiavillage.lifespan.display;

import net.kyori.adventure.text.Component;

@FunctionalInterface
public interface LifespanTagFormatter {
    LifespanDisplayText format(Component text);
}
