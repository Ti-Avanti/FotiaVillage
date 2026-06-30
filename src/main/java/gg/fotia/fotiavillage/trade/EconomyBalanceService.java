package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EconomyBalanceService {
    private final FotiaVillagePlugin plugin;
    private final Map<UUID, Integer> reservedExtraEmeralds = new HashMap<>();

    public EconomyBalanceService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public int requiredExtraEmeralds(ItemStack result) {
        if (!plugin.settings().tradeControl().enabled()) {
            return 0;
        }
        FotiaSettings.EconomyBalance balance = plugin.settings().tradeControl().economyBalance();
        if (!balance.enabled() || !balance.requireExtraEmeralds()) {
            return 0;
        }
        int base = balance.valuableItemEmeraldCost().getOrDefault(result.getType().name(), 0);
        return base <= 0 ? 0 : (int) Math.ceil(base * balance.emeraldCostMultiplier());
    }

    public boolean hasEnoughExtraEmeralds(Player player, MerchantRecipe recipe, int extraEmeralds) {
        if (extraEmeralds <= 0) {
            return true;
        }
        return extraEmeraldStatus(player, recipe, extraEmeralds).missing() <= 0;
    }

    public boolean reserveExtraEmeralds(Player player, MerchantRecipe recipe, int extraEmeralds) {
        if (extraEmeralds <= 0) {
            return true;
        }
        if (!hasEnoughExtraEmeralds(player, recipe, extraEmeralds)) {
            return false;
        }
        reservedExtraEmeralds.merge(player.getUniqueId(), extraEmeralds, Integer::sum);
        return true;
    }

    public ExtraEmeraldStatus extraEmeraldStatus(Player player, MerchantRecipe recipe, int extraEmeralds) {
        int available = Math.max(0, availableExtraEmeralds(player, recipe) - reservedExtraEmeralds.getOrDefault(player.getUniqueId(), 0));
        int missing = Math.max(0, extraEmeralds - available);
        return new ExtraEmeraldStatus(extraEmeralds, available, missing);
    }

    public void consumeReservedExtraEmeralds(Player player, int amount) {
        try {
            consumeExtraEmeralds(player, amount);
        } finally {
            releaseReservedExtraEmeralds(player, amount);
        }
    }

    public void releaseReservedExtraEmeralds(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        reservedExtraEmeralds.computeIfPresent(player.getUniqueId(), (uuid, reserved) -> reserved > amount ? reserved - amount : null);
    }

    public void consumeExtraEmeralds(Player player, int amount) {
        int remaining = consumeMerchantInputEmeralds(player, amount);
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getStorageContents().length; slot++) {
            if (remaining <= 0) {
                return;
            }
            remaining = consumeEmeralds(inventory, slot, remaining);
        }
        ItemStack offHand = inventory.getItemInOffHand();
        if (remaining > 0 && offHand.getType() == Material.EMERALD) {
            if (offHand.getAmount() <= remaining) {
                inventory.setItemInOffHand(new ItemStack(Material.AIR));
            } else {
                offHand.setAmount(offHand.getAmount() - remaining);
                inventory.setItemInOffHand(offHand);
            }
        }
    }

    private int consumeMerchantInputEmeralds(Player player, int amount) {
        if (!(player.getOpenInventory().getTopInventory() instanceof MerchantInventory inventory)) {
            return amount;
        }
        int remaining = consumeEmeralds(inventory, 0, amount);
        return consumeEmeralds(inventory, 1, remaining);
    }

    private int consumeEmeralds(Inventory inventory, int slot, int amount) {
        ItemStack item = inventory.getItem(slot);
        if (amount <= 0 || item == null || item.getType() != Material.EMERALD) {
            return amount;
        }
        int stackAmount = item.getAmount();
        if (stackAmount <= amount) {
            inventory.setItem(slot, null);
            return amount - stackAmount;
        }
        item.setAmount(stackAmount - amount);
        inventory.setItem(slot, item);
        return 0;
    }

    private int availableExtraEmeralds(Player player, MerchantRecipe recipe) {
        int vanillaEmeralds = vanillaEmeralds(player, recipe);
        return countEmeralds(player) + countMerchantInputEmeralds(player) - vanillaEmeralds;
    }

    private int vanillaEmeralds(Player player, MerchantRecipe recipe) {
        List<ItemStack> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) {
            return 0;
        }
        int count = countEmeralds(adjustedFirstIngredient(player, recipe, ingredients.get(0)));
        for (int i = 1; i < ingredients.size(); i++) {
            count += countEmeralds(ingredients.get(i));
        }
        return count;
    }

    private ItemStack adjustedFirstIngredient(Player player, MerchantRecipe recipe, ItemStack fallback) {
        if (player.getOpenInventory().getTopInventory() instanceof MerchantInventory inventory) {
            MerchantRecipe selected = inventory.getSelectedRecipe();
            if (selected == recipe) {
                ItemStack adjusted = adjustedFirstIngredient(recipe);
                if (adjusted != null && !adjusted.getType().isAir()) {
                    return adjusted;
                }
            }
        }
        ItemStack adjusted = adjustedFirstIngredient(recipe);
        return adjusted != null && !adjusted.getType().isAir() ? adjusted : fallback;
    }

    private ItemStack adjustedFirstIngredient(MerchantRecipe recipe) {
        try {
            return recipe.getAdjustedIngredient1();
        } catch (NoSuchMethodError ignored) {
            // Paper 1.18 fallback: use the recipe ingredient amount.
            return null;
        }
    }

    private int countEmeralds(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            count += countEmeralds(item);
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        count += countEmeralds(offHand);
        return count;
    }

    private int countMerchantInputEmeralds(Player player) {
        if (!(player.getOpenInventory().getTopInventory() instanceof MerchantInventory inventory)) {
            return 0;
        }
        return countEmeralds(inventory.getItem(0)) + countEmeralds(inventory.getItem(1));
    }

    private int countEmeralds(ItemStack item) {
        if (item == null || item.getType() != Material.EMERALD) {
            return 0;
        }
        return item.getAmount();
    }

    public record ExtraEmeraldStatus(int required, int available, int missing) {}
}
