package gg.fotia.fotiavillage.language;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageTemplatesTest {
    @Test
    void acceptsLegacyScalarAndOrderedListsWithBlankLines() {
        var config = new MemoryConfiguration();
        config.set("header", "旧标题");
        assertEquals(List.of("旧标题"), MessageTemplates.read(config, null, "header"));
        config.set("header", List.of("第一行", "", "第三行"));
        assertEquals(List.of("第一行", "", "第三行"), MessageTemplates.read(config, null, "header"));
    }

    @Test
    void onlyMissingKeysUseDefaults() {
        var config = new MemoryConfiguration();
        var defaults = new MemoryConfiguration();
        defaults.set("lore", List.of("默认说明"));
        assertEquals(List.of("默认说明"), MessageTemplates.read(config, defaults, "lore"));
        config.set("lore", List.of());
        assertEquals(List.of(), MessageTemplates.read(config, defaults, "lore"));
        config.set("lore", "");
        assertEquals(List.of(""), MessageTemplates.read(config, defaults, "lore"));
    }

    @Test
    void rejectsInvalidLineTypesInsteadOfSilentlyDroppingThem() {
        var config = new MemoryConfiguration();
        config.set("lore", List.of("有效文字", 7));
        assertThrows(IllegalArgumentException.class, () -> MessageTemplates.read(config, null, "lore"));
        config.set("lore", Map.of("unexpected", "section"));
        assertThrows(IllegalArgumentException.class, () -> MessageTemplates.read(config, null, "lore"));
    }
}
