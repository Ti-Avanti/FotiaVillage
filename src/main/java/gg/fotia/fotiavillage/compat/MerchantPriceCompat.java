package gg.fotia.fotiavillage.compat;

import org.bukkit.inventory.MerchantRecipe;

/** 最初的 Paper 1.18 尚未公开需求值和特殊价格 API。 */
public final class MerchantPriceCompat {
    private static boolean extendedPrices = true;

    private MerchantPriceCompat() {
    }

    public static boolean hasExtendedPrices() {
        return extendedPrices;
    }

    public static int demand(MerchantRecipe recipe) {
        if (!extendedPrices) {
            return 0;
        }
        try {
            return recipe.getDemand();
        } catch (NoSuchMethodError ignored) {
            extendedPrices = false;
            return 0;
        }
    }

    public static int specialPrice(MerchantRecipe recipe) {
        if (!extendedPrices) {
            return 0;
        }
        try {
            return recipe.getSpecialPrice();
        } catch (NoSuchMethodError ignored) {
            extendedPrices = false;
            return 0;
        }
    }

    public static void apply(MerchantRecipe recipe, int demand, int specialPrice) {
        if (!extendedPrices) {
            return;
        }
        try {
            recipe.setDemand(demand);
            recipe.setSpecialPrice(specialPrice);
        } catch (NoSuchMethodError ignored) {
            extendedPrices = false;
        }
    }
}
