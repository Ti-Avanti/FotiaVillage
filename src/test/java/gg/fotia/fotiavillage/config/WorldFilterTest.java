package gg.fotia.fotiavillage.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldFilterTest {
    @Test
    void preservesLegacyNamesAndAlsoAcceptsWorldKeys() {
        var byName = new FotiaSettings.WorldFilter(true, Set.of("world"), Set.of());
        var byKey = new FotiaSettings.WorldFilter(true, Set.of("minecraft:overworld"), Set.of());
        assertTrue(byName.isAllowed("WORLD", "minecraft:overworld"));
        assertTrue(byKey.isAllowed("world", "MINECRAFT:OVERWORLD"));
        assertFalse(byKey.isAllowed("world_nether", "minecraft:the_nether"));
    }

    @Test
    void blacklistWinsAcrossBothIdentifiers() {
        var byName = new FotiaSettings.WorldFilter(true, Set.of("minecraft:overworld"), Set.of("world"));
        var byKey = new FotiaSettings.WorldFilter(true, Set.of("world"), Set.of("minecraft:overworld"));
        assertFalse(byName.isAllowed("world", "minecraft:overworld"));
        assertFalse(byKey.isAllowed("world", "minecraft:overworld"));
    }

    @Test
    void disabledAndEmptyFiltersKeepExistingBehavior() {
        var disabled = new FotiaSettings.WorldFilter(false, Set.of("other"), Set.of("world"));
        var unrestricted = new FotiaSettings.WorldFilter(true, Set.of(), Set.of());
        assertTrue(disabled.isAllowed("world", "minecraft:overworld"));
        assertTrue(unrestricted.isAllowed("custom", "custom:dimension"));
    }
}
