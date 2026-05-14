package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import gg.fotia.fotiavillage.util.ExperienceUtil;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class TradeService implements Listener {
    private final FotiaVillagePlugin plugin;
    private final PermissionGroupService groups;
    private final EconomyBalanceService economy;
    private final TradeLimitService limits;
    private final CooldownService cooldowns;
    private final CostScalingService scaling;
    private final NamespacedKey tradeGuiMarkerKey;
    private final NamespacedKey tradeGuiLoreSizeKey;
    private final Map<UUID, TradeGuiSession> tradeGuiSessions = new HashMap<>();

    public TradeService(FotiaVillagePlugin plugin, PermissionGroupService groups, EconomyBalanceService economy, TradeLimitService limits, CooldownService cooldowns, CostScalingService scaling) {
        this.plugin = plugin;
        this.groups = groups;
        this.economy = economy;
        this.limits = limits;
        this.cooldowns = cooldowns;
        this.scaling = scaling;
        this.tradeGuiMarkerKey = new NamespacedKey(plugin, "trade_gui_display");
        this.tradeGuiLoreSizeKey = new NamespacedKey(plugin, "trade_gui_lore_size");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMerchantOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory() instanceof MerchantInventory inventory)) {
            return;
        }
        if (!(inventory.getMerchant() instanceof Villager villager)) {
            return;
        }
        if (!plugin.settings().tradeControl().guiDisplay().enabled() || plugin.compatibility().isShopkeeper(villager)) {
            return;
        }
        decorateOpenMerchant(player, villager);
    }

    @EventHandler
    public void onMerchantClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            restoreTradeGui(player.getUniqueId());
            stripTradeDisplayNextTick(player);
        }
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
        if (!isTradeResultAction(event, recipe)) {
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryMerchantTrade(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory() instanceof MerchantInventory inventory)) {
            return;
        }
        Merchant merchant = inventory.getMerchant();
        if (!shouldCommitFromInventoryClick(merchant)) {
            return;
        }
        MerchantRecipe recipe = inventory.getSelectedRecipe();
        if (recipe == null || recipe.getResult().getType().isAir() || !isTradeResultAction(event, recipe)) {
            return;
        }

        String itemType = recipe.getResult().getType().name();
        FotiaSettings.TradeControl trade = plugin.settings().tradeControl();
        ItemStack cursor = event.getCursor() == null ? new ItemStack(Material.AIR) : event.getCursor().clone();

        if (!trade.enabled()) {
            if (recordStatsOnly(player, itemType, () -> {
                event.setCancelled(true);
                event.setResult(Event.Result.DENY);
                resyncCancelledTrade(player, recipe, cursor, true);
            })) {
                stripTradeDisplayNextTick(player);
            }
            return;
        }

        String profession = profession(merchant);
        TradeDecision decision = evaluate(player, recipe, profession);
        if (!decision.allowed()) {
            if (event.isCancelled()) {
                return;
            }
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
            sendDecisionMessage(player, decision);
            resyncCancelledTrade(player, recipe, cursor, true);
            return;
        }

        if (commitTrade(player, recipe, profession, decision, () -> {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
            resyncCancelledTrade(player, recipe, cursor, true);
        })) {
            stripTradeDisplayNextTick(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerTrade(PlayerTradeEvent event) {
        Player player = event.getPlayer();
        MerchantRecipe recipe = event.getTrade();
        ItemStack result = recipe.getResult();
        if (result.getType().isAir()) {
            return;
        }
        if (plugin.compatibility().isShopkeeper(event.getVillager())) {
            return;
        }
        FotiaSettings.TradeControl trade = plugin.settings().tradeControl();
        String itemType = result.getType().name();
        String profession = profession(event.getVillager());

        if (!trade.enabled()) {
            if (recordStatsOnly(player, itemType, () -> event.setCancelled(true))) {
                stripTradeDisplayNextTick(player);
            }
            return;
        }

        TradeDecision decision = evaluate(player, recipe, profession);
        if (!decision.allowed()) {
            event.setCancelled(true);
            sendDecisionMessage(player, decision);
            resyncCancelledTrade(player, recipe, null, false);
            return;
        }

        if (commitTrade(player, recipe, profession, decision, () -> {
            event.setCancelled(true);
            resyncCancelledTrade(player, recipe, null, false);
        })) {
            stripTradeDisplayNextTick(player);
        }
    }

    private boolean isTradeResultAction(InventoryClickEvent event, MerchantRecipe recipe) {
        ItemStack cursor = event.getCursor();
        boolean cursorCanAccept = cursor == null || cursor.getType() == Material.AIR || cursor.isSimilar(recipe.getResult());
        if (cursor != null && cursor.getType() != Material.AIR) {
            cursorCanAccept = cursorCanAccept || strippedSimilar(cursor, recipe.getResult());
        }
        boolean outputClick = event.getRawSlot() == 2 && cursorCanAccept;
        boolean collectToCursor = event.getAction() == InventoryAction.COLLECT_TO_CURSOR
            && cursor != null
            && strippedSimilar(cursor, recipe.getResult());
        return outputClick || collectToCursor;
    }

    private boolean shouldCommitFromInventoryClick(Merchant merchant) {
        if (merchant instanceof AbstractVillager abstractVillager) {
            return plugin.compatibility().isShopkeeper(abstractVillager);
        }
        return true;
    }

    private boolean recordStatsOnly(Player player, String itemType, Runnable rollback) {
        try {
            plugin.database().runInTransaction(() -> plugin.stats().record(player, itemType, 0));
            return true;
        } catch (RuntimeException ex) {
            rollback.run();
            plugin.language().prefixed(player, "trade.database-error");
            plugin.getLogger().log(Level.SEVERE, "Failed to persist trade stats for " + player.getName(), ex);
            return false;
        }
    }

    private boolean commitTrade(Player player, MerchantRecipe recipe, String profession, TradeDecision decision, Runnable rollback) {
        if (!economy.reserveExtraEmeralds(player, recipe, decision.extraEmeralds())) {
            rollback.run();
            plugin.language().prefixed(player, "trade.insufficient-emerald", Map.of("required", decision.extraEmeralds()));
            return false;
        }

        String itemType = recipe.getResult().getType().name();
        FotiaSettings.TradeControl trade = plugin.settings().tradeControl();
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
            rollback.run();
            plugin.language().prefixed(player, "trade.database-error");
            plugin.getLogger().log(Level.SEVERE, "Failed to persist trade data for " + player.getName(), ex);
            return false;
        }

        payExperience(player, decision.expCost(), trade.expCost());
        if (decision.extraEmeralds() > 0) {
            plugin.getServer().getScheduler().runTask(plugin, () -> economy.consumeReservedExtraEmeralds(player, decision.extraEmeralds()));
        }
        if (currentMultiplier > 1.0) {
            plugin.language().prefixed(player, "trade.scaling", Map.of("multiplier", String.format(Locale.ROOT, "%.1f", currentMultiplier)));
        }
        return true;
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

    private void decorateOpenMerchant(Player player, Villager villager) {
        if (!villager.isValid() || villager.isDead() || plugin.compatibility().isShopkeeper(villager)) {
            return;
        }
        restoreTradeGui(player.getUniqueId());
        List<MerchantRecipe> originalRecipes = cleanCopyRecipes(villager.getRecipes());
        List<MerchantRecipe> decoratedRecipes = new ArrayList<>();
        String profession = profession(villager);
        for (MerchantRecipe recipe : originalRecipes) {
            decoratedRecipes.add(decorateRecipe(player, profession, recipe));
        }
        tradeGuiSessions.put(player.getUniqueId(), new TradeGuiSession(villager, originalRecipes));
        villager.setRecipes(decoratedRecipes);
        player.updateInventory();
    }

    private MerchantRecipe decorateRecipe(Player player, String profession, MerchantRecipe recipe) {
        ItemStack result = stripTradeGuiInfo(recipe.getResult().clone());
        if (result.getType().isAir()) {
            return copyRecipe(recipe, result);
        }
        List<String> info = tradeInfoLore(player, profession, recipe);
        if (info.isEmpty()) {
            return copyRecipe(recipe, result);
        }
        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            return copyRecipe(recipe, result);
        }
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        int originalLoreSize = lore.size();
        lore.addAll(info);
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(tradeGuiMarkerKey, PersistentDataType.BYTE, (byte) 1);
        container.set(tradeGuiLoreSizeKey, PersistentDataType.INTEGER, originalLoreSize);
        meta.setLore(lore);
        result.setItemMeta(meta);
        return copyRecipe(recipe, result);
    }

    private List<String> tradeInfoLore(Player player, String profession, MerchantRecipe recipe) {
        FotiaSettings.TradeControl trade = plugin.settings().tradeControl();
        FotiaSettings.TradeGuiDisplay display = trade.guiDisplay();
        if (!display.enabled()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        ItemStack result = stripTradeGuiInfo(recipe.getResult().clone());
        String itemType = result.getType().name();
        if (!trade.enabled()) {
            lines.add(plugin.language().legacy("trade-gui.disabled", Map.of()));
            return lines;
        }
        if (trade.disableTrading()) {
            lines.add(plugin.language().legacy("trade-gui.trading-disabled", Map.of()));
            return lines;
        }

        if (display.showExpCost() && trade.expCost().enabled()) {
            int expCost = calculateExpCost(player, profession, itemType);
            String key = trade.expCost().costMode() == FotiaSettings.CostMode.POINTS ? "trade-gui.exp-cost-points" : "trade-gui.exp-cost-levels";
            lines.add(plugin.language().legacy(key, Map.of("amount", expCost)));
        }
        if (display.showExpMultiplier() && (trade.costScaling().enabled() || trade.permissionGroupsEnabled())) {
            double scalingMultiplier = scaling.currentMultiplier(player, itemType);
            double groupMultiplier = groups.group(player).expCostMultiplier();
            double totalMultiplier = scalingMultiplier * groupMultiplier;
            lines.add(plugin.language().legacy("trade-gui.exp-multiplier", Map.of(
                "multiplier", formatMultiplier(totalMultiplier),
                "scaling", formatMultiplier(scalingMultiplier),
                "group", formatMultiplier(groupMultiplier)
            )));
        }
        if (display.showCooldown() && trade.cooldown().enabled()) {
            long remaining = cooldowns.remaining(player, profession, itemType);
            int configuredSeconds = cooldowns.cooldownSeconds(player, profession, itemType);
            if (remaining > 0L) {
                lines.add(plugin.language().legacy("trade-gui.cooldown-remaining", Map.of("time", plugin.language().formatDuration(remaining))));
            } else if (configuredSeconds > 0) {
                lines.add(plugin.language().legacy("trade-gui.cooldown-next", Map.of("time", plugin.language().formatDuration(configuredSeconds * 1000L))));
            }
        }
        if (display.showLimits() && trade.limit().enabled()) {
            addLimitLine(lines, "trade-gui.limit-global", limits.globalStatus(player));
            addLimitLine(lines, "trade-gui.limit-profession", limits.professionStatus(player, profession));
            addLimitLine(lines, "trade-gui.limit-item", limits.itemStatus(player, itemType));
        }
        if (display.showExtraEmeralds() && trade.economyBalance().enabled()) {
            int extraEmeralds = economy.requiredExtraEmeralds(result);
            if (extraEmeralds > 0) {
                lines.add(plugin.language().legacy("trade-gui.extra-emeralds", Map.of("amount", extraEmeralds)));
            }
        }
        if (lines.isEmpty()) {
            return List.of();
        }
        lines.add(0, plugin.language().legacy("trade-gui.header", Map.of()));
        return lines;
    }

    private void addLimitLine(List<String> lines, String key, TradeLimitService.LimitStatus status) {
        if (status.enabled()) {
            lines.add(plugin.language().legacy(key, Map.of("used", status.used(), "limit", status.limit())));
        }
    }

    private String formatMultiplier(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private List<MerchantRecipe> cleanCopyRecipes(List<MerchantRecipe> recipes) {
        List<MerchantRecipe> copies = new ArrayList<>();
        for (MerchantRecipe recipe : recipes) {
            copies.add(copyRecipe(recipe, stripTradeGuiInfo(recipe.getResult().clone())));
        }
        return copies;
    }

    private MerchantRecipe copyRecipe(MerchantRecipe recipe, ItemStack result) {
        MerchantRecipe copy = new MerchantRecipe(result, recipe.getUses(), recipe.getMaxUses(), recipe.hasExperienceReward(), recipe.getVillagerExperience(), recipe.getPriceMultiplier(), recipe.getDemand(), recipe.getSpecialPrice());
        copy.setIngredients(recipe.getIngredients().stream().map(ItemStack::clone).toList());
        return copy;
    }

    private void restoreTradeGui(UUID playerId) {
        TradeGuiSession session = tradeGuiSessions.remove(playerId);
        if (session != null && session.merchant().isValid() && !session.merchant().isDead()) {
            session.merchant().setRecipes(cleanCopyRecipes(session.originalRecipes()));
        }
    }

    private boolean strippedSimilar(ItemStack left, ItemStack right) {
        if (left == null || right == null) {
            return left == right;
        }
        return stripTradeGuiInfo(left.clone()).isSimilar(stripTradeGuiInfo(right.clone()));
    }

    private void stripTradeDisplayNextTick(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            player.setItemOnCursor(stripTradeGuiInfo(player.getItemOnCursor()));
            PlayerInventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item != null && item.getType() != Material.AIR) {
                    inventory.setItem(slot, stripTradeGuiInfo(item));
                }
            }
            player.updateInventory();
        });
    }

    private ItemStack stripTradeGuiInfo(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (!container.has(tradeGuiMarkerKey, PersistentDataType.BYTE)) {
            return item;
        }
        Integer originalLoreSize = container.get(tradeGuiLoreSizeKey, PersistentDataType.INTEGER);
        List<String> lore = meta.getLore();
        if (originalLoreSize == null || originalLoreSize <= 0 || lore == null) {
            meta.setLore(null);
        } else if (originalLoreSize < lore.size()) {
            meta.setLore(new ArrayList<>(lore.subList(0, originalLoreSize)));
        }
        container.remove(tradeGuiMarkerKey);
        container.remove(tradeGuiLoreSizeKey);
        item.setItemMeta(meta);
        return item;
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

    private record TradeGuiSession(Villager merchant, List<MerchantRecipe> originalRecipes) {}
}
