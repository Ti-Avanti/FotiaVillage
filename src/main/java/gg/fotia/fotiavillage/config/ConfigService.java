package gg.fotia.fotiavillage.config;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ConfigService {
    private final FotiaVillagePlugin plugin;
    private FotiaSettings settings;

    public ConfigService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        ensureDefaults();
        plugin.reloadConfig();
        settings = read(plugin.getConfig());
    }

    public FotiaSettings settings() {
        return settings;
    }

    private void ensureDefaults() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        if (!new File(plugin.getDataFolder(), "config.yml").exists()) {
            plugin.saveResource("config.yml", false);
        }
        File langFolder = new File(plugin.getDataFolder(), "languages");
        if (!langFolder.exists()) {
            langFolder.mkdirs();
        }
        saveLanguage("zh_CN.yml");
        saveLanguage("en_US.yml");
    }

    private void saveLanguage(String name) {
        File file = new File(plugin.getDataFolder(), "languages/" + name);
        if (!file.exists()) {
            plugin.saveResource("languages/" + name, false);
        }
    }

    private FotiaSettings read(FileConfiguration config) {
        return new FotiaSettings(
            config.getString("language", "zh_CN"),
            config.getBoolean("debug", false),
            new FotiaSettings.Gui(config.getBoolean("gui.enabled", true)),
            new FotiaSettings.Placeholder(config.getBoolean("placeholder.enabled", true)),
            new FotiaSettings.UpdateChecker(config.getBoolean("update-checker.enabled", true)),
            new FotiaSettings.Performance(
                config.getBoolean("performance.auto-report", true),
                atLeast(config.getInt("performance.report-interval", 300), 0),
                atLeast(config.getDouble("performance.low-tps-warning", 18.0), 0.0),
                atLeast(config.getInt("performance.cleanup-expired-interval", 600), 0)
            ),
            new FotiaSettings.Compatibility(
                config.getBoolean("compatibility.shopkeepers.exclude-lifespan", true),
                config.getBoolean("compatibility.citizens.exclude-lifespan", true),
                config.getBoolean("compatibility.generic-npc-metadata.exclude-lifespan", true),
                nonBlank(config.getString("compatibility.generic-npc-metadata.key", "NPC"), "NPC")
            ),
            new FotiaSettings.VillagerLimit(
                config.getBoolean("villager-limit.enabled", true),
                atLeast(config.getInt("villager-limit.chunk-radius", 3), 0),
                atLeast(config.getInt("villager-limit.max-villagers", 5), 0)
            ),
            new FotiaSettings.SpawnControl(
                config.getBoolean("spawn-control.enabled", true),
                config.getBoolean("spawn-control.block-natural-spawn", true),
                config.getBoolean("spawn-control.allow-cure", true),
                config.getBoolean("spawn-control.allow-spawn-egg", true),
                config.getBoolean("spawn-control.allow-breeding", true)
            ),
            new FotiaSettings.Lifespan(
                config.getBoolean("villager-lifespan.enabled", true),
                atLeast(config.getInt("villager-lifespan.days", 7), 1),
                config.getBoolean("villager-lifespan.notify-enabled", true),
                atLeast(config.getInt("villager-lifespan.notify-range", 16), 0),
                config.getBoolean("villager-lifespan.auto-add-lifespan.enabled", true),
                atLeast(config.getInt("villager-lifespan.auto-add-lifespan.check-interval", 300), 0),
                config.getBoolean("villager-lifespan.auto-add-lifespan.check-on-startup", true)
            ),
            readTradeControl(config),
            new FotiaSettings.Commands(config.getBoolean("commands.kill-enabled", true))
        );
    }

    private FotiaSettings.TradeControl readTradeControl(FileConfiguration config) {
        return new FotiaSettings.TradeControl(
            config.getBoolean("trade-control.enabled", true),
            config.getBoolean("trade-control.disable-trading", false),
            new FotiaSettings.ExpCost(
                config.getBoolean("trade-control.exp-cost.enabled", true),
                enumValue(FotiaSettings.CostMode.class, config.getString("trade-control.exp-cost.cost-mode", "LEVEL"), FotiaSettings.CostMode.LEVEL),
                atLeast(config.getInt("trade-control.exp-cost.base-cost", 5), 0),
                readIntMap(config.getConfigurationSection("trade-control.exp-cost.per-profession")),
                readIntMap(config.getConfigurationSection("trade-control.exp-cost.valuable-items")),
                atLeast(config.getInt("trade-control.exp-cost.min-level", 0), 0)
            ),
            new FotiaSettings.CostScaling(
                config.getBoolean("trade-control.cost-scaling.enabled", true),
                enumValue(FotiaSettings.ScalingType.class, config.getString("trade-control.cost-scaling.scaling-type", "MULTIPLIER"), FotiaSettings.ScalingType.MULTIPLIER),
                atLeast(config.getDouble("trade-control.cost-scaling.multiplier", 1.5), 1.0),
                atLeast(config.getDouble("trade-control.cost-scaling.max-multiplier", 10.0), 1.0),
                atLeast(config.getInt("trade-control.cost-scaling.additive-amount", 1), 0),
                atLeast(config.getInt("trade-control.cost-scaling.reset-hours", 24), 0),
                atLeast(config.getDouble("trade-control.cost-scaling.decay-rate", 0.0), 0.0)
            ),
            new FotiaSettings.Cooldown(
                config.getBoolean("trade-control.cooldown.enabled", true),
                atLeast(config.getInt("trade-control.cooldown.default-cooldown", 300), 0),
                readIntMap(config.getConfigurationSection("trade-control.cooldown.per-profession")),
                readIntMap(config.getConfigurationSection("trade-control.cooldown.per-item"))
            ),
            new FotiaSettings.Limit(
                config.getBoolean("trade-control.limit.enabled", true),
                enumValue(FotiaSettings.ResetPeriod.class, config.getString("trade-control.limit.reset-period", "DAILY"), FotiaSettings.ResetPeriod.DAILY),
                atLeast(config.getInt("trade-control.limit.global-limit", 50), 0),
                readIntMap(config.getConfigurationSection("trade-control.limit.per-profession")),
                readIntMap(config.getConfigurationSection("trade-control.limit.per-item"))
            ),
            config.getBoolean("trade-control.permission-groups.enabled", true),
            readPermissionGroups(config.getConfigurationSection("trade-control.permission-groups")),
            new FotiaSettings.EconomyBalance(
                config.getBoolean("trade-control.economy-balance.enabled", true),
                atLeast(config.getDouble("trade-control.economy-balance.emerald-cost-multiplier", 1.5), 0.0),
                config.getBoolean("trade-control.economy-balance.require-extra-emeralds", true),
                readIntMap(config.getConfigurationSection("trade-control.economy-balance.valuable-items-emerald-cost"))
            ),
            new FotiaSettings.Statistics(
                config.getBoolean("trade-control.statistics.enabled", true),
                config.getBoolean("trade-control.statistics.detailed-logging", true),
                atLeast(config.getInt("trade-control.statistics.leaderboard-size", 10), 1)
            )
        );
    }

    private Map<String, Integer> readIntMap(ConfigurationSection section) {
        Map<String, Integer> result = new HashMap<>();
        if (section == null) {
            return Map.of();
        }
        for (String key : section.getKeys(false)) {
            result.put(key.toUpperCase(Locale.ROOT), section.getInt(key));
        }
        return Map.copyOf(result);
    }

    private List<FotiaSettings.PermissionGroup> readPermissionGroups(ConfigurationSection section) {
        List<FotiaSettings.PermissionGroup> groups = new ArrayList<>();
        if (section != null) {
            for (String name : section.getKeys(false)) {
                ConfigurationSection group = section.getConfigurationSection(name);
                if (group == null) {
                    continue;
                }
                groups.add(new FotiaSettings.PermissionGroup(
                    name,
                    group.getString("permission", ""),
                    group.getInt("priority", 0),
                    atLeast(group.getDouble("exp-cost-multiplier", 1.0), 0.0),
                    atLeast(group.getDouble("cooldown-multiplier", 1.0), 0.0),
                    atLeast(group.getInt("daily-limit-bonus", 0), 0)
                ));
            }
        }
        if (groups.isEmpty()) {
            groups.add(new FotiaSettings.PermissionGroup("default", "", 0, 1.0, 1.0, 0));
        }
        groups.sort(Comparator.comparingInt(FotiaSettings.PermissionGroup::priority).reversed());
        return List.copyOf(groups);
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String raw, E fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid value '" + raw + "' for " + type.getSimpleName() + ", using " + fallback);
            return fallback;
        }
    }

    private int atLeast(int value, int minimum) {
        return Math.max(value, minimum);
    }

    private double atLeast(double value, double minimum) {
        return Math.max(value, minimum);
    }

    private String nonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
