package gg.fotia.fotiavillage.util;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 交易 GUI 附加说明的物品标记工具：负责标记、识别、还原交易结果物品，并复制配方。
 */
public final class TradeRecipeUtil {
    private final NamespacedKey markerKey;
    private final NamespacedKey loreSizeKey;

    public TradeRecipeUtil(Plugin plugin) {
        this.markerKey = new NamespacedKey(plugin, "trade_gui_display");
        this.loreSizeKey = new NamespacedKey(plugin, "trade_gui_lore_size");
    }

    public void markResult(ItemMeta meta, int originalLoreSize) {
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(markerKey, PersistentDataType.BYTE, (byte) 1);
        container.set(loreSizeKey, PersistentDataType.INTEGER, originalLoreSize);
    }

    public boolean hasTradeGuiInfo(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    public ItemStack stripTradeGuiInfo(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return item;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (!container.has(markerKey, PersistentDataType.BYTE)) {
            return item;
        }
        Integer originalLoreSize = container.get(loreSizeKey, PersistentDataType.INTEGER);
        List<String> lore = meta.getLore();
        if (originalLoreSize == null || originalLoreSize <= 0 || lore == null) {
            meta.setLore(null);
        } else if (originalLoreSize < lore.size()) {
            meta.setLore(new ArrayList<>(lore.subList(0, originalLoreSize)));
        }
        container.remove(markerKey);
        container.remove(loreSizeKey);
        item.setItemMeta(meta);
        return item;
    }

    public MerchantRecipe copyRecipe(MerchantRecipe recipe, ItemStack result) {
        MerchantRecipe copy = new MerchantRecipe(result, recipe.getUses(), recipe.getMaxUses(), recipe.hasExperienceReward(), recipe.getVillagerExperience(), recipe.getPriceMultiplier(), recipe.getDemand(), recipe.getSpecialPrice());
        copy.setIngredients(recipe.getIngredients().stream().map(ItemStack::clone).toList());
        return copy;
    }

    public List<MerchantRecipe> cleanCopyRecipes(List<MerchantRecipe> recipes) {
        List<MerchantRecipe> copies = new ArrayList<>();
        for (MerchantRecipe recipe : recipes) {
            copies.add(copyRecipe(recipe, stripTradeGuiInfo(recipe.getResult().clone())));
        }
        return copies;
    }
}
