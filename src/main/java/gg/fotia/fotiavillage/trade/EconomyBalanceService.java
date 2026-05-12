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
