package gg.fotia.fotiavillage.trade;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

public final class EconomyBalanceService {
    private final FotiaVillagePlugin plugin;

    public EconomyBalanceService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public int requiredExtraEmeralds(ItemStack result) {
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
        int vanillaEmeralds = recipe.getIngredients().stream()
            .filter(item -> item != null && item.getType() == Material.EMERALD)
            .mapToInt(ItemStack::getAmount)
            .sum();
        return countEmeralds(player) + countMerchantInputEmeralds(player) >= vanillaEmeralds + extraEmeralds;
    }

    public void consumeExtraEmeralds(Player player, int amount) {
        int remaining = consumeMerchantInputEmeralds(player, amount);
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (remaining <= 0) {
                return;
            }
            if (item == null || item.getType() != Material.EMERALD) {
                continue;
            }
            int stackAmount = item.getAmount();
            if (stackAmount <= remaining) {
                remaining -= stackAmount;
                item.setAmount(0);
            } else {
                item.setAmount(stackAmount - remaining);
                return;
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (remaining > 0 && offHand.getType() == Material.EMERALD) {
            offHand.setAmount(Math.max(0, offHand.getAmount() - remaining));
        }
    }

    private int consumeMerchantInputEmeralds(Player player, int amount) {
        if (!(player.getOpenInventory().getTopInventory() instanceof MerchantInventory inventory)) {
            return amount;
        }
        int remaining = consumeEmeralds(inventory.getItem(0), amount);
        return consumeEmeralds(inventory.getItem(1), remaining);
    }

    private int consumeEmeralds(ItemStack item, int amount) {
        if (amount <= 0 || item == null || item.getType() != Material.EMERALD) {
            return amount;
        }
        int stackAmount = item.getAmount();
        if (stackAmount <= amount) {
            item.setAmount(0);
            return amount - stackAmount;
        }
        item.setAmount(stackAmount - amount);
        return 0;
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
}
