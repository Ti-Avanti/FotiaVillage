package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import gg.fotia.fotiavillage.util.ExperienceUtil;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public final class TradeService implements Listener {
    private final FotiaVillagePlugin plugin;
    private final PermissionGroupService groups;
    private final EconomyBalanceService economy;
    private final TradeLimitService limits;
    private final CooldownService cooldowns;
    private final CostScalingService scaling;

    public TradeService(FotiaVillagePlugin plugin, PermissionGroupService groups, EconomyBalanceService economy, TradeLimitService limits, CooldownService cooldowns, CostScalingService scaling) {
        this.plugin = plugin;
        this.groups = groups;
        this.economy = economy;
        this.limits = limits;
        this.cooldowns = cooldowns;
        this.scaling = scaling;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTrade(PlayerTradeEvent event) {
        Player player = event.getPlayer();
        MerchantRecipe recipe = event.getTrade();
        ItemStack result = recipe.getResult();
        if (result.getType().isAir()) {
            return;
        }
        FotiaSettings.TradeControl trade = plugin.settings().tradeControl();
        String itemType = result.getType().name();
        String profession = profession(event.getVillager());

        if (!trade.enabled()) {
            recordStatsOnly(event, player, itemType);
            return;
        }

        if (trade.disableTrading()) {
            cancel(event, player, "trade.disabled");
            return;
        }

        long remainingCooldown = cooldowns.remaining(player, profession, itemType);
        if (remainingCooldown > 0) {
            event.setCancelled(true);
            plugin.language().prefixed(player, "trade.cooldown", Map.of("time", plugin.language().formatDuration(remainingCooldown)));
            return;
        }

        if (!limits.canTrade(player, profession, itemType)) {
            cancel(event, player, "trade.limit-reached");
            return;
        }

        int extraEmeralds = economy.requiredExtraEmeralds(result);
        if (!economy.hasEnoughExtraEmeralds(player, recipe, extraEmeralds)) {
            event.setCancelled(true);
            plugin.language().prefixed(player, "trade.insufficient-emerald", Map.of("required", extraEmeralds));
            return;
        }

        int expCost = calculateExpCost(player, profession, itemType);
        if (trade.expCost().enabled() && expCost > 0 && !canPayExperience(player, expCost, trade.expCost())) {
            event.setCancelled(true);
            return;
        }

        if (!economy.reserveExtraEmeralds(player, recipe, extraEmeralds)) {
            event.setCancelled(true);
            plugin.language().prefixed(player, "trade.insufficient-emerald", Map.of("required", extraEmeralds));
            return;
        }

        double currentMultiplier = scaling.currentMultiplier(player, itemType);
        try {
            plugin.database().runInTransaction(() -> {
                cooldowns.record(player, profession, itemType);
                limits.record(player, profession, itemType);
                plugin.stats().record(player, itemType, expCost);
                scaling.record(player, itemType);
            });
        } catch (RuntimeException ex) {
            economy.releaseReservedExtraEmeralds(player, extraEmeralds);
            event.setCancelled(true);
            plugin.language().prefixed(player, "trade.database-error");
            plugin.getLogger().log(Level.SEVERE, "Failed to persist trade data for " + player.getName(), ex);
            return;
        }

        payExperience(player, expCost, trade.expCost());
        if (extraEmeralds > 0) {
            plugin.getServer().getScheduler().runTask(plugin, () -> economy.consumeReservedExtraEmeralds(player, extraEmeralds));
        }
        if (currentMultiplier > 1.0) {
            plugin.language().prefixed(player, "trade.scaling", Map.of("multiplier", String.format(Locale.ROOT, "%.1f", currentMultiplier)));
        }
    }

    private void recordStatsOnly(PlayerTradeEvent event, Player player, String itemType) {
        try {
            plugin.database().runInTransaction(() -> plugin.stats().record(player, itemType, 0));
        } catch (RuntimeException ex) {
            event.setCancelled(true);
            plugin.language().prefixed(player, "trade.database-error");
            plugin.getLogger().log(Level.SEVERE, "Failed to persist trade stats for " + player.getName(), ex);
        }
    }

    private void cancel(PlayerTradeEvent event, Player player, String messageKey) {
        event.setCancelled(true);
        plugin.language().prefixed(player, messageKey);
    }

    private int calculateExpCost(Player player, String profession, String itemType) {
        FotiaSettings.ExpCost config = plugin.settings().tradeControl().expCost();
        if (!config.enabled()) {
            return 0;
        }
        int professionCost = config.perProfession().getOrDefault(profession, config.baseCost());
        int itemExtraCost = config.valuableItems().getOrDefault(itemType, 0);
        int total = Math.max(0, professionCost) + Math.max(0, itemExtraCost);
        total = (int) Math.ceil(total * scaling.currentMultiplier(player, itemType));
        total = (int) Math.ceil(total * groups.group(player).expCostMultiplier());
        return Math.max(0, total);
    }

    private boolean canPayExperience(Player player, int amount, FotiaSettings.ExpCost config) {
        if (config.costMode() == FotiaSettings.CostMode.POINTS) {
            int current = ExperienceUtil.getTotalExperience(player);
            if (current < amount) {
                plugin.language().prefixed(player, "trade.insufficient-points", Map.of("required", amount, "current", current));
                return false;
            }
            if (current - amount < ExperienceUtil.getExperienceForLevel(config.minLevel())) {
                plugin.language().prefixed(player, "trade.min-level");
                return false;
            }
            return true;
        }
        int currentLevel = player.getLevel();
        if (currentLevel < amount) {
            plugin.language().prefixed(player, "trade.insufficient-exp", Map.of("required", amount, "current", currentLevel));
            return false;
        }
        if (currentLevel - amount < config.minLevel()) {
            plugin.language().prefixed(player, "trade.min-level");
            return false;
        }
        return true;
    }

    private void payExperience(Player player, int amount, FotiaSettings.ExpCost config) {
        if (!config.enabled() || amount <= 0) {
            return;
        }
        if (config.costMode() == FotiaSettings.CostMode.POINTS) {
            ExperienceUtil.setTotalExperience(player, ExperienceUtil.getTotalExperience(player) - amount);
            plugin.language().prefixed(player, "trade.exp-consumed-points", Map.of("amount", amount));
        } else {
            player.setLevel(Math.max(0, player.getLevel() - amount));
            plugin.language().prefixed(player, "trade.exp-consumed-level", Map.of("amount", amount));
        }
    }

    private String profession(AbstractVillager abstractVillager) {
        if (abstractVillager instanceof Villager villager) {
            return villager.getProfession().getKey().getKey().toUpperCase(Locale.ROOT);
        }
        return "WANDERING_TRADER";
    }
}
