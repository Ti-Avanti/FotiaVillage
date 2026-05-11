package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import org.bukkit.entity.Player;

public final class CostScalingService {
    private final FotiaVillagePlugin plugin;

    public CostScalingService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public double currentMultiplier(Player player, String itemType) {
        FotiaSettings.CostScaling config = plugin.settings().tradeControl().costScaling();
        if (!config.enabled()) {
            return 1.0;
        }
        ScalingRecord record = effective(plugin.database().getScaling(player.getUniqueId(), itemType), config);
        return Math.max(1.0, Math.min(record.multiplier(), config.maxMultiplier()));
    }

    public void record(Player player, String itemType) {
        FotiaSettings.CostScaling config = plugin.settings().tradeControl().costScaling();
        if (!config.enabled()) {
            return;
        }
        ScalingRecord current = effective(plugin.database().getScaling(player.getUniqueId(), itemType), config);
        int newCount = current.tradeCount() + 1;
        double next = switch (config.scalingType()) {
            case MULTIPLIER -> current.multiplier() * config.multiplier();
            case ADDITIVE -> current.multiplier() + config.additiveAmount();
            case STEPPED -> newCount <= 5 ? 1.0 : newCount <= 10 ? 2.0 : newCount <= 20 ? 5.0 : config.maxMultiplier();
        };
        plugin.database().saveScaling(player.getUniqueId(), itemType, new ScalingRecord(Math.max(1.0, Math.min(next, config.maxMultiplier())), newCount, System.currentTimeMillis()));
    }

    private ScalingRecord effective(ScalingRecord record, FotiaSettings.CostScaling config) {
        if (record.lastTradeTime() <= 0) {
            return ScalingRecord.empty();
        }
        long hours = (System.currentTimeMillis() - record.lastTradeTime()) / (1000L * 60L * 60L);
        if (config.resetHours() > 0 && hours >= config.resetHours()) {
            return ScalingRecord.empty();
        }
        if (config.decayRate() <= 0 || hours <= 0) {
            return record;
        }
        double decay = Math.max(0.0, 1.0 - (config.decayRate() * hours));
        return new ScalingRecord(Math.max(1.0, 1.0 + ((record.multiplier() - 1.0) * decay)), record.tradeCount(), record.lastTradeTime());
    }
}
