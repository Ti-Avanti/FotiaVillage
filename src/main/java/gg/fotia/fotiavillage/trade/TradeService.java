package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import gg.fotia.fotiavillage.util.ExperienceUtil;
import gg.fotia.fotiavillage.util.TimeUtil;
import gg.fotia.fotiavillage.util.TradeRecipeUtil;
import io.papermc.paper.event.player.PlayerTradeEvent;
import org.bukkit.Material;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class TradeService implements Listener {
    private static final long TRADE_CLICK_FORGET_DELAY_TICKS = 5L;

    private final FotiaVillagePlugin plugin;
    private final PermissionGroupService groups;
    private final EconomyBalanceService economy;
    private final TradeLimitService limits;
    private final CooldownService cooldowns;
    private final CostScalingService scaling;
    private final TradeRecipeUtil tradeRecipes;
    private final Map<UUID, Villager> tradeGuiSessions = new HashMap<>();
    private final Map<UUID, List<TradeClickAllowance>> tradeClickAllowances = new HashMap<>();
    private final Set<UUID> pendingTradeDisplayCleanup = new HashSet<>();

    public TradeService(FotiaVillagePlugin plugin, PermissionGroupService groups, EconomyBalanceService economy, TradeLimitService limits, CooldownService cooldowns, CostScalingService scaling) {
        this.plugin = plugin;
        this.groups = groups;
        this.economy = economy;
        this.limits = limits;
        this.cooldowns = cooldowns;
        this.scaling = scaling;
        this.tradeRecipes = new TradeRecipeUtil(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMerchantOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!plugin.isWorldAllowed(player.getWorld())) {
            return;
        }
        if (!(event.getInventory() instanceof MerchantInventory inventory)) {
            return;
        }
        if (!(inventory.getMerchant() instanceof Villager villager)) {
            return;
        }
        if (!plugin.isWorldAllowed(villager.getWorld())) {
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
            tradeClickAllowances.remove(player.getUniqueId());
            stripTradeDisplayNextTick(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMerchantOutputClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!plugin.isWorldAllowed(player.getWorld())) {
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
        Merchant merchant = inventory.getMerchant();
        FotiaSettings.TradeControl trade = plugin.settings().tradeControl();
        if (!trade.enabled()) {
            rememberTradeClickAllowance(player, merchant, recipe, inventory, event);
            return;
        }
        TradeDecision decision = evaluate(player, recipe, profession(merchant));
        if (decision.allowed()) {
            rememberTradeClickAllowance(player, merchant, recipe, inventory, event);
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
        if (!plugin.isWorldAllowed(player.getWorld())) {
            return;
        }
        if (!(event.getView().getTopInventory() instanceof MerchantInventory inventory)) {
            return;
        }
        Merchant merchant = inventory.getMerchant();
        if (!shouldCommitFromInventoryClick(merchant)) {
            return;
        }
        if (event.isCancelled() && !isShopkeeperMerchant(merchant)) {
            return;
        }
        MerchantRecipe recipe = inventory.getSelectedRecipe();
        if (recipe == null || recipe.getResult().getType().isAir() || !isTradeResultAction(event, recipe)) {
            return;
        }
        if (!consumeTradeClickAllowance(player, merchant, recipe)) {
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
        if (!plugin.isWorldAllowed(player.getWorld()) || !plugin.isWorldAllowed(event.getVillager().getWorld())) {
            return;
        }
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
        if (!consumeTradeClickAllowance(player, event.getVillager(), recipe)) {
            return;
        }

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

    private boolean isShopkeeperMerchant(Merchant merchant) {
        return merchant instanceof AbstractVillager abstractVillager && plugin.compatibility().isShopkeeper(abstractVillager);
    }

    private void rememberTradeClickAllowance(Player player, Merchant merchant, MerchantRecipe recipe, MerchantInventory inventory, InventoryClickEvent event) {
        UUID playerId = player.getUniqueId();
        TradeClickAllowance allowance = new TradeClickAllowance(signature(merchant, recipe), allowedTradeCommits(inventory, recipe, event));
        tradeClickAllowances.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(allowance);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> forgetTradeClickAllowance(playerId, allowance), TRADE_CLICK_FORGET_DELAY_TICKS);
    }

    private void forgetTradeClickAllowance(UUID playerId, TradeClickAllowance allowance) {
        List<TradeClickAllowance> allowances = tradeClickAllowances.get(playerId);
        if (allowances == null) {
            return;
        }
        allowances.remove(allowance);
        if (allowances.isEmpty()) {
            tradeClickAllowances.remove(playerId);
        }
    }

    private boolean consumeTradeClickAllowance(Player player, Merchant merchant, MerchantRecipe recipe) {
        List<TradeClickAllowance> allowances = tradeClickAllowances.get(player.getUniqueId());
        if (allowances == null || allowances.isEmpty()) {
            return true;
        }
        TradeSignature signature = signature(merchant, recipe);
        boolean exhaustedMatch = false;
        for (TradeClickAllowance allowance : allowances) {
            if (!sameTradeSignature(allowance.signature(), signature)) {
                continue;
            }
            if (allowance.remaining() > 0) {
                allowance.consume();
                return true;
            }
            exhaustedMatch = true;
        }
        return !exhaustedMatch;
    }

    private String merchantKey(Merchant merchant) {
        if (merchant instanceof AbstractVillager abstractVillager) {
            return "entity:" + abstractVillager.getUniqueId();
        }
        return "merchant:" + System.identityHashCode(merchant);
    }

    private int allowedTradeCommits(MerchantInventory inventory, MerchantRecipe recipe, InventoryClickEvent event) {
        if (!event.isShiftClick() && event.getAction() != InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return 1;
        }
        return Math.max(1, possibleTradeCount(inventory, recipe));
    }

    private int possibleTradeCount(MerchantInventory inventory, MerchantRecipe recipe) {
        int remainingUses = recipe.getMaxUses() > 0 ? recipe.getMaxUses() - recipe.getUses() : Integer.MAX_VALUE;
        if (remainingUses <= 0) {
            return 0;
        }
        List<IngredientRequirement> requirements = ingredientRequirements(recipe);
        if (requirements.isEmpty()) {
            return 1;
        }
        int possible = remainingUses;
        for (IngredientRequirement requirement : requirements) {
            possible = Math.min(possible, availableIngredientAmount(inventory, requirement.item()) / requirement.amount());
        }
        return Math.max(0, possible);
    }

    private List<IngredientRequirement> ingredientRequirements(MerchantRecipe recipe) {
        List<IngredientRequirement> requirements = new ArrayList<>();
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.getType().isAir() || ingredient.getAmount() <= 0) {
                continue;
            }
            int existingIndex = -1;
            for (int index = 0; index < requirements.size(); index++) {
                if (sameItemKind(requirements.get(index).item(), ingredient)) {
                    existingIndex = index;
                    break;
                }
            }
            if (existingIndex >= 0) {
                IngredientRequirement existing = requirements.get(existingIndex);
                requirements.set(existingIndex, new IngredientRequirement(existing.item(), existing.amount() + ingredient.getAmount()));
            } else {
                requirements.add(new IngredientRequirement(ingredient.clone(), ingredient.getAmount()));
            }
        }
        return requirements;
    }

    private int availableIngredientAmount(MerchantInventory inventory, ItemStack ingredient) {
        return availableIngredientAmount(inventory.getItem(0), ingredient) + availableIngredientAmount(inventory.getItem(1), ingredient);
    }

    private int availableIngredientAmount(ItemStack item, ItemStack ingredient) {
        if (item == null || item.getType().isAir() || !sameItemKind(item, ingredient)) {
            return 0;
        }
        return item.getAmount();
    }

    private TradeSignature signature(Merchant merchant, MerchantRecipe recipe) {
        return new TradeSignature(merchantKey(merchant), recipe.getResult().clone(), ingredientKinds(recipe));
    }

    private List<ItemStack> ingredientKinds(MerchantRecipe recipe) {
        List<ItemStack> ingredients = new ArrayList<>();
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient != null && !ingredient.getType().isAir()) {
                ingredients.add(ingredient.clone());
            }
        }
        return ingredients;
    }

    private boolean sameTradeSignature(TradeSignature left, TradeSignature right) {
        return sameMerchantKey(left.merchantKey(), right.merchantKey())
            && sameItemKind(left.result(), right.result())
            && sameIngredientKinds(left.ingredients(), right.ingredients());
    }

    private boolean sameMerchantKey(String left, String right) {
        return left.equals(right) || left.startsWith("merchant:") || right.startsWith("merchant:");
    }

    private boolean sameIngredientKinds(List<ItemStack> left, List<ItemStack> right) {
        if (left.size() != right.size()) {
            return false;
        }
        List<ItemStack> unmatched = new ArrayList<>(right);
        for (ItemStack expected : left) {
            int matchedIndex = -1;
            for (int index = 0; index < unmatched.size(); index++) {
                if (sameItemKind(expected, unmatched.get(index))) {
                    matchedIndex = index;
                    break;
                }
            }
            if (matchedIndex < 0) {
                return false;
            }
            unmatched.remove(matchedIndex);
        }
        return true;
    }

    private boolean sameItemKind(ItemStack left, ItemStack right) {
        if (left == null || left.getType().isAir()) {
            return right == null || right.getType().isAir();
        }
        if (right == null || right.getType().isAir()) {
            return false;
        }
        return strippedSimilar(left, right);
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

        primeTradeState(player, trade);

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
        if (decision.messageKey() != null && !decision.guiOnly()) {
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
        if (!plugin.isWorldAllowed(villager.getWorld())) {
            return;
        }
        if (!villager.isValid() || villager.isDead() || plugin.compatibility().isShopkeeper(villager)) {
            return;
        }
        restoreTradeGui(player.getUniqueId());
        primeTradeState(player, plugin.settings().tradeControl());
        List<MerchantRecipe> originalRecipes = tradeRecipes.cleanCopyRecipes(villager.getRecipes());
        List<MerchantRecipe> decoratedRecipes = new ArrayList<>();
        String profession = profession(villager);
        for (MerchantRecipe recipe : originalRecipes) {
            decoratedRecipes.add(decorateRecipe(player, profession, recipe));
        }
        tradeGuiSessions.put(player.getUniqueId(), villager);
        villager.setRecipes(decoratedRecipes);
        player.updateInventory();
    }

    private MerchantRecipe decorateRecipe(Player player, String profession, MerchantRecipe recipe) {
        ItemStack result = tradeRecipes.stripTradeGuiInfo(recipe.getResult().clone());
        if (result.getType().isAir()) {
            return tradeRecipes.copyRecipe(recipe, result);
        }
        List<String> info = tradeInfoLore(player, profession, recipe);
        if (info.isEmpty()) {
            return tradeRecipes.copyRecipe(recipe, result);
        }
        ItemMeta meta = result.getItemMeta();
        if (meta == null) {
            return tradeRecipes.copyRecipe(recipe, result);
        }
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        int originalLoreSize = lore.size();
        lore.addAll(info);
        tradeRecipes.markResult(meta, originalLoreSize);
        meta.setLore(lore);
        result.setItemMeta(meta);
        return tradeRecipes.copyRecipe(recipe, result);
    }

    private List<String> tradeInfoLore(Player player, String profession, MerchantRecipe recipe) {
        FotiaSettings.TradeControl trade = plugin.settings().tradeControl();
        FotiaSettings.TradeGuiDisplay display = trade.guiDisplay();
        if (!display.enabled()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        ItemStack result = tradeRecipes.stripTradeGuiInfo(recipe.getResult().clone());
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
                EconomyBalanceService.ExtraEmeraldStatus status = economy.extraEmeraldStatus(player, recipe, extraEmeralds);
                lines.add(plugin.language().legacy("trade-gui.extra-emeralds", Map.of("amount", extraEmeralds)));
                if (status.missing() > 0) {
                    lines.add(plugin.language().legacy("trade-gui.extra-emeralds-missing", Map.of(
                        "available", status.available(),
                        "missing", status.missing()
                    )));
                } else {
                    lines.add(plugin.language().legacy("trade-gui.extra-emeralds-available", Map.of("available", status.available())));
                }
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

    private void primeTradeState(Player player, FotiaSettings.TradeControl trade) {
        plugin.database().primeTradeState(
            player.getUniqueId(),
            TimeUtil.resetKey(trade.limit().resetPeriod()),
            trade.limit().enabled(),
            trade.cooldown().enabled(),
            trade.costScaling().enabled()
        );
    }

    private List<MerchantRecipe> cleanCopyRecipes(List<MerchantRecipe> recipes) {
        return tradeRecipes.cleanCopyRecipes(recipes);
    }

    public void resetSessions() {
        Set<UUID> affectedPlayers = new HashSet<>(tradeGuiSessions.keySet());
        affectedPlayers.addAll(pendingTradeDisplayCleanup);
        for (UUID playerId : List.copyOf(tradeGuiSessions.keySet())) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.getOpenInventory().getTopInventory() instanceof MerchantInventory) {
                player.closeInventory();
            }
        }
        for (UUID playerId : List.copyOf(tradeGuiSessions.keySet())) {
            restoreTradeGui(playerId);
        }
        tradeClickAllowances.clear();
        pendingTradeDisplayCleanup.clear();
        for (UUID playerId : affectedPlayers) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                stripTradeDisplayNow(player);
            }
        }
    }

    private void restoreTradeGui(UUID playerId) {
        Villager merchant = tradeGuiSessions.remove(playerId);
        if (merchant != null && merchant.isValid() && !merchant.isDead()) {
            // 基于当前配方剥离 GUI 附加信息，保留本次交易产生的 uses/demand 变化。
            merchant.setRecipes(cleanCopyRecipes(merchant.getRecipes()));
        }
    }

    private boolean strippedSimilar(ItemStack left, ItemStack right) {
        if (left == null || right == null) {
            return left == right;
        }
        return tradeRecipes.stripTradeGuiInfo(left.clone()).isSimilar(tradeRecipes.stripTradeGuiInfo(right.clone()));
    }

    private void stripTradeDisplayNextTick(Player player) {
        if (!plugin.isEnabled()) {
            stripTradeDisplayNow(player);
            return;
        }
        UUID playerId = player.getUniqueId();
        if (!pendingTradeDisplayCleanup.add(playerId)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            pendingTradeDisplayCleanup.remove(playerId);
            if (!player.isOnline()) {
                return;
            }
            stripTradeDisplayNow(player);
        });
    }

    private void stripTradeDisplayNow(Player player) {
        boolean changed = false;
        ItemStack cursor = player.getItemOnCursor();
        if (tradeRecipes.hasTradeGuiInfo(cursor)) {
            player.setItemOnCursor(tradeRecipes.stripTradeGuiInfo(cursor));
            changed = true;
        }
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (tradeRecipes.hasTradeGuiInfo(item)) {
                inventory.setItem(slot, tradeRecipes.stripTradeGuiInfo(item));
                changed = true;
            }
        }
        if (changed) {
            player.updateInventory();
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

    private record TradeDecision(boolean allowed, String messageKey, Map<String, ?> replacements, int expCost, int extraEmeralds, boolean guiOnly) {
        static TradeDecision allow(int expCost, int extraEmeralds) {
            return new TradeDecision(true, null, Map.of(), expCost, extraEmeralds, false);
        }

        static TradeDecision block(String messageKey) {
            return block(messageKey, Map.of());
        }

        static TradeDecision block(String messageKey, Map<String, ?> replacements) {
            return new TradeDecision(false, messageKey, replacements, 0, 0, "trade.insufficient-emerald".equals(messageKey));
        }
    }

    private record TradeSignature(String merchantKey, ItemStack result, List<ItemStack> ingredients) {}

    private record IngredientRequirement(ItemStack item, int amount) {}

    private static final class TradeClickAllowance {
        private final TradeSignature signature;
        private int remaining;

        private TradeClickAllowance(TradeSignature signature, int remaining) {
            this.signature = signature;
            this.remaining = remaining;
        }

        private TradeSignature signature() {
            return signature;
        }

        private int remaining() {
            return remaining;
        }

        private void consume() {
            remaining--;
        }
    }
}
