package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import gg.fotia.fotiavillage.util.ExperienceUtil;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Material;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMerchantOutputClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory() instanceof MerchantInventory inventory)) {
            return;
        }
        MerchantRecipe recipe = inventory.getSelectedRecipe();
        if (recipe == null || recipe.getResult().getType().isAir()) {
            return;
        }
        boolean outputClick = event.getRawSlot() == 2;
        boolean collectToCursor = event.getAction() == InventoryAction.COLLECT_TO_CURSOR
            && event.getCursor() != null
            && event.getCursor().isSimilar(recipe.getResult());
        if (!outputClick && !collectToCursor) {
            return;
        }
        FotiaSettings.TradeControl trade = plugin.settings().tradeControl();
        if (!trade.enabled()) {
            return;
        }
        TradeDecision decision = evaluate(player, recipe, profession(inventory.getMerchant()));
        if (decision.allowed()) {
            return;
        }
        ItemStack cursor = event.getCursor() == null ? new ItemStack(Material.AIR) : event.getCursor().clone();
        event.setCancelled(true);
        event.setResult(Event.Result.DENY);
        sendDecisionMessage(player, decision);
        resyncCancelledTrade(player, recipe, cursor, true);
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

        TradeDecision decision = evaluate(player, recipe, profession);
        if (!decision.allowed()) {
            event.setCancelled(true);
            sendDecisionMessage(player, decision);
            resyncCancelledTrade(player, recipe, null, false);
            return;
        }

        if (!economy.reserveExtraEmeralds(player, recipe, decision.extraEmeralds())) {
            event.setCancelled(true);
            plugin.language().prefixed(player, "trade.insufficient-emerald", Map.of("required", decision.extraEmeralds()));
            resyncCancelledTrade(player, recipe, null, false);
            return;
        }

        double currentMultiplier = scaling.currentMultiplier(player, itemType);
        try {
            plugin.database().runInTransaction(() -> {
                cooldowns.record(player, profession, itemType);
                limits.record(player, profession, itemType);
                plugin.stats().record(player, itemType, decision.expCost());
                scaling.record(player, itemType);
            });
        } catch (RuntimeException ex) {
            economy.releaseReservedExtraEmeralds(player, decision.extraEmeralds());
            event.setCancelled(true);
            plugin.language().prefixed(player, "trade.database-error");
            resyncCancelledTrade(player, recipe, null, false);
            plugin.getLogger().log(Level.SEVERE, "Failed to persist trade data for " + player.getName(), ex);
            return;
        }

        payExperience(player, decision.expCost(), trade.expCost());
        if (decision.extraEmeralds() > 0) {
            plugin.getServer().getScheduler().runTask(plugin, () -> economy.consumeReservedExtraEmeralds(player, decision.extraEmeralds()));
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

    private TradeDecision evaluate(Player player, MerchantRecipe recipe, String profession) {
        ItemStack result = recipe.getResult();
        String itemType = result.getType().name();
        FotiaSettings.TradeControl trade = plugin.settings().tradeControl();

        if (trade.disableTrading()) {
            return TradeDecision.block("trade.disabled");
        }

        long remainingCooldown = cooldowns.remaining(player, profession, itemType);
        if (remainingCooldown > 0) {
            return TradeDecision.block("trade.cooldown", Map.of("time", plugin.language().formatDuration(remainingCooldown)));
        }

        if (!limits.canTrade(player, profession, itemType)) {
            return TradeDecision.block("trade.limit-reached");
        }

        int extraEmeralds = economy.requiredExtraEmeralds(result);
        if (!economy.hasEnoughExtraEmeralds(player, recipe, extraEmeralds)) {
            return TradeDecision.block("trade.insufficient-emerald", Map.of("required", extraEmeralds));
        }

        int expCost = calculateExpCost(player, profession, itemType);
        if (trade.expCost().enabled() && expCost > 0) {
            TradeDecision experienceDecision = canPayExperience(player, expCost, trade.expCost());
            if (!experienceDecision.allowed()) {
                return experienceDecision;
            }
        }

        return TradeDecision.allow(expCost, extraEmeralds);
    }

    private void sendDecisionMessage(Player player, TradeDecision decision) {
        if (decision.messageKey() != null) {
            plugin.language().prefixed(player, decision.messageKey(), decision.replacements());
        }
    }

    private void resyncCancelledTrade(Player player, MerchantRecipe recipe, ItemStack cursorSnapshot, boolean restoreCursor) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (restoreCursor) {
                player.setItemOnCursor(cursorSnapshot == null ? new ItemStack(Material.AIR) : cursorSnapshot);
            } else {
                ItemStack cursor = player.getItemOnCursor();
                if (cursor != null && cursor.getType() != Material.AIR && cursor.isSimilar(recipe.getResult())) {
                    player.setItemOnCursor(new ItemStack(Material.AIR));
                }
            }
            if (player.getOpenInventory().getTopInventory() instanceof MerchantInventory inventory) {
                inventory.setItem(2, null);
            }
            player.updateInventory();
            player.closeInventory();
        });
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

    private TradeDecision canPayExperience(Player player, int amount, FotiaSettings.ExpCost config) {
        if (config.costMode() == FotiaSettings.CostMode.POINTS) {
            int current = ExperienceUtil.getTotalExperience(player);
            if (current < amount) {
                return TradeDecision.block("trade.insufficient-points", Map.of("required", amount, "current", current));
            }
            if (current - amount < ExperienceUtil.getExperienceForLevel(config.minLevel())) {
                return TradeDecision.block("trade.min-level");
            }
            return TradeDecision.allow(amount, 0);
        }
        int currentLevel = player.getLevel();
        if (currentLevel < amount) {
            return TradeDecision.block("trade.insufficient-exp", Map.of("required", amount, "current", currentLevel));
        }
        if (currentLevel - amount < config.minLevel()) {
            return TradeDecision.block("trade.min-level");
        }
        return TradeDecision.allow(amount, 0);
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

    private String profession(Merchant merchant) {
        if (merchant instanceof AbstractVillager abstractVillager) {
            return profession(abstractVillager);
        }
        return "CUSTOM";
    }

    private record TradeDecision(boolean allowed, String messageKey, Map<String, ?> replacements, int expCost, int extraEmeralds) {
        static TradeDecision allow(int expCost, int extraEmeralds) {
            return new TradeDecision(true, null, Map.of(), expCost, extraEmeralds);
        }

        static TradeDecision block(String messageKey) {
            return block(messageKey, Map.of());
        }

        static TradeDecision block(String messageKey, Map<String, ?> replacements) {
            return new TradeDecision(false, messageKey, replacements, 0, 0);
        }
    }
}
