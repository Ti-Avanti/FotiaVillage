package gg.fotia.fotiavillage.compat;

import org.bukkit.entity.AbstractVillager;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;

public final class MerchantCompat {
    private MerchantCompat() {
    }

    public static Merchant resolve(MerchantInventory inventory) {
        Merchant merchant = inventory.getMerchant();
        if (merchant instanceof AbstractVillager) {
            return merchant;
        }
        // 早期 Paper 返回 CraftMerchant 包装器，实际村民仍可通过公共 holder API 取得。
        return inventory.getHolder() instanceof AbstractVillager villager ? villager : merchant;
    }
}
