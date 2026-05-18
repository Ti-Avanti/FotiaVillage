package gg.fotia.fotiavillage.lifespan.display;

import org.bukkit.entity.Villager;

public interface LifespanDisplayRenderer {
    void createOrUpdate(Villager villager, LifespanDisplayText text);

    default void tick() {
    }

    void cleanup(Villager villager);

    void cleanupOrphans();

    void removeAll();

    String name();
}
