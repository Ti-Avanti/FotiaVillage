package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.util.TradeRecipeUtil;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 一次点击可完成的交易数量和短期去重状态。 */
final class TradeClickTracker {
    private static final long TRADE_CLICK_FORGET_DELAY_TICKS = 5L;
    private final FotiaVillagePlugin plugin;
    private final TradeRecipeUtil tradeRecipes;
    private final Map<UUID, List<TradeClickAllowance>> tradeClickAllowances = new HashMap<>();

    TradeClickTracker(FotiaVillagePlugin plugin, TradeRecipeUtil tradeRecipes) {
        this.plugin = plugin;
        this.tradeRecipes = tradeRecipes;
    }

    void forgetPlayer(UUID uuid) { tradeClickAllowances.remove(uuid); }
    void clear() { tradeClickAllowances.clear(); }

    private boolean strippedSimilar(ItemStack left, ItemStack right) {
        return tradeRecipes.stripTradeGuiInfo(left.clone()).isSimilar(tradeRecipes.stripTradeGuiInfo(right.clone()));
    }

    void remember(Player player, Merchant merchant, MerchantRecipe recipe, MerchantInventory inventory, InventoryClickEvent event) {
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

    boolean consume(Player player, Merchant merchant, MerchantRecipe recipe) {
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
        List<ItemStack> ingredients = new ArrayList<>(recipe.getIngredients());
        if (!ingredients.isEmpty()) {
            try {
                ItemStack adjusted = recipe.getAdjustedIngredient1();
                if (adjusted != null && !adjusted.getType().isAir()) ingredients.set(0, adjusted);
            } catch (NoSuchMethodError ignored) {
                // 旧版 API 未提供实际价格时，额度耗尽仍取消交易，不能跳过校验。
            }
        }
        for (ItemStack ingredient : ingredients) {
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
