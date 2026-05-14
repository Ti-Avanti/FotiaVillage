package gg.fotia.fotiavillage.config;

import java.util.List;
import java.util.Map;

public record FotiaSettings(
    String language,
    boolean debug,
    Gui gui,
    Placeholder placeholder,
    UpdateChecker updateChecker,
    Performance performance,
    Compatibility compatibility,
    VillagerLimit villagerLimit,
    SpawnControl spawnControl,
    Lifespan lifespan,
    TradeControl tradeControl,
    Commands commands
) {
    public record Gui(boolean enabled) {}
    public record Placeholder(boolean enabled) {}
    public record UpdateChecker(boolean enabled) {}
    public record Performance(boolean autoReport, int reportInterval, double lowTpsWarning, int cleanupExpiredInterval) {}
    public record Compatibility(boolean excludeShopkeepersFromLifespan, boolean excludeCitizensFromLifespan, boolean excludeGenericNpcMetadataFromLifespan, String genericNpcMetadataKey) {}
    public record VillagerLimit(boolean enabled, int chunkRadius, int maxVillagers) {}
    public record SpawnControl(boolean enabled, boolean blockNaturalSpawn, boolean allowCure, boolean allowSpawnEgg, boolean allowBreeding) {}
    public record Lifespan(boolean enabled, int days, boolean notifyEnabled, int notifyRange, double commandTargetRange, boolean zombifyOnExpire, boolean autoAddEnabled, int autoAddCheckInterval, boolean autoAddCheckOnStartup) {}
    public record TradeControl(boolean enabled, boolean disableTrading, ExpCost expCost, CostScaling costScaling, Cooldown cooldown, Limit limit, boolean permissionGroupsEnabled, List<PermissionGroup> permissionGroups, EconomyBalance economyBalance, Statistics statistics, TradeGuiDisplay guiDisplay) {}
    public record ExpCost(boolean enabled, CostMode costMode, int baseCost, Map<String, Integer> perProfession, Map<String, Integer> valuableItems, int minLevel) {}
    public enum CostMode { LEVEL, POINTS }
    public record CostScaling(boolean enabled, ScalingType scalingType, double multiplier, double maxMultiplier, int additiveAmount, int resetHours, double decayRate) {}
    public enum ScalingType { MULTIPLIER, ADDITIVE, STEPPED }
    public record Cooldown(boolean enabled, int defaultCooldown, Map<String, Integer> perProfession, Map<String, Integer> perItem) {}
    public record Limit(boolean enabled, ResetPeriod resetPeriod, int globalLimit, Map<String, Integer> perProfession, Map<String, Integer> perItem) {}
    public enum ResetPeriod { DAILY, WEEKLY, MONTHLY }
    public record PermissionGroup(String name, String permission, int priority, double expCostMultiplier, double cooldownMultiplier, int dailyLimitBonus) {}
    public record EconomyBalance(boolean enabled, double emeraldCostMultiplier, boolean requireExtraEmeralds, Map<String, Integer> valuableItemEmeraldCost) {}
    public record Statistics(boolean enabled, boolean detailedLogging, int leaderboardSize) {}
    public record TradeGuiDisplay(boolean enabled, boolean showExpCost, boolean showExpMultiplier, boolean showCooldown, boolean showLimits, boolean showExtraEmeralds) {}
    public record Commands(boolean killEnabled) {}
}
