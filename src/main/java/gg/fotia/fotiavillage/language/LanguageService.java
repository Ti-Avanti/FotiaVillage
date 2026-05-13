package gg.fotia.fotiavillage.language;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class LanguageService {
    private final FotiaVillagePlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
    private FileConfiguration language;
    private FileConfiguration defaults;
    private String currentLanguage;

    public LanguageService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        currentLanguage = plugin.settings().language();
        File langFile = new File(plugin.getDataFolder(), "languages/" + currentLanguage + ".yml");
        if (!langFile.exists()) {
            currentLanguage = "zh_CN";
            langFile = new File(plugin.getDataFolder(), "languages/zh_CN.yml");
        }
        language = YamlConfiguration.loadConfiguration(langFile);
        defaults = loadDefaults(currentLanguage);
    }

    public Component component(String key) {
        return component(key, Map.of());
    }

    public Component component(String key, Map<String, ?> replacements) {
        String template = template(key);
        boolean miniMessageTemplate = template.contains("<");
        String raw = applyReplacements(template, replacements, miniMessageTemplate);
        if (miniMessageTemplate) {
            return miniMessage.deserialize(raw);
        }
        return legacy.deserialize(raw);
    }

    public String plain(String key) {
        return raw(key, Map.of());
    }

    public String plain(String key, Map<String, ?> replacements) {
        return raw(key, replacements);
    }

    public String formatDuration(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) {
            return plain("time.duration-days-hours", Map.of("days", days, "hours", hours % 24));
        }
        if (hours > 0) {
            return plain("time.duration-hours-minutes", Map.of("hours", hours, "minutes", minutes % 60));
        }
        if (minutes > 0) {
            return plain("time.duration-minutes-seconds", Map.of("minutes", minutes, "seconds", seconds % 60));
        }
        return plain("time.duration-seconds", Map.of("seconds", seconds));
    }

    public String formatAgo(long timestampMillis) {
        if (timestampMillis <= 0) {
            return plain("gui.never");
        }
        long seconds = Math.max(1, (System.currentTimeMillis() - timestampMillis) / 1000);
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) {
            return plain("time.ago-days", Map.of("days", days));
        }
        if (hours > 0) {
            return plain("time.ago-hours", Map.of("hours", hours));
        }
        if (minutes > 0) {
            return plain("time.ago-minutes", Map.of("minutes", minutes));
        }
        return plain("time.ago-seconds", Map.of("seconds", seconds));
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(component(key));
    }

    public void send(CommandSender sender, String key, Map<String, ?> replacements) {
        sender.sendMessage(component(key, replacements));
    }

    public void prefixed(CommandSender sender, String key) {
        sender.sendMessage(component("prefix").append(component(key)));
    }

    public void prefixed(CommandSender sender, String key, Map<String, ?> replacements) {
        sender.sendMessage(component("prefix").append(component(key, replacements)));
    }

    private String raw(String key, Map<String, ?> replacements) {
        return applyReplacements(template(key), replacements, false);
    }

    private String template(String key) {
        String value = language.getString(key);
        if (value == null && defaults != null) {
            value = defaults.getString(key);
        }
        return value == null ? "<!i><red>" + key + "</red>" : value;
    }

    private String applyReplacements(String value, Map<String, ?> replacements, boolean escapeMiniMessageTags) {
        for (Map.Entry<String, ?> entry : replacements.entrySet()) {
            String replacement = String.valueOf(entry.getValue());
            if (escapeMiniMessageTags) {
                replacement = miniMessage.escapeTags(replacement);
            }
            value = value.replace("{" + entry.getKey() + "}", replacement);
        }
        return value;
    }

    private FileConfiguration loadDefaults(String lang) {
        InputStream stream = plugin.getResource("languages/" + lang + ".yml");
        if (stream == null) {
            stream = plugin.getResource("languages/zh_CN.yml");
        }
        if (stream == null) {
            return null;
        }
        try (InputStream input = stream; InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException ex) {
            plugin.getLogger().warning("Failed to load bundled language defaults: " + ex.getMessage());
            return null;
        }
    }
}
