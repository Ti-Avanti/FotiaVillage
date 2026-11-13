package gg.fotia.fotiavillage.language;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/** Reads message lines without treating an explicit empty list as a missing value. */
public final class MessageTemplates {
    private MessageTemplates() {}

    public static List<String> read(ConfigurationSection configured, ConfigurationSection defaults, String key) {
        Object value = configured != null && configured.isSet(key) ? configured.get(key)
            : defaults == null ? null : defaults.get(key);
        if (value == null) {
            return List.of("<!i><red>" + key + "</red>");
        }
        if (value instanceof String text) {
            return List.of(text);
        }
        if (value instanceof List<?> lines && lines.stream().allMatch(line -> line instanceof String)) {
            return lines.stream().map(line -> (String) line).toList();
        }
        throw new IllegalArgumentException(key);
    }
}
