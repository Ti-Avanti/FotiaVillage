package org.pvp.villagerlimit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;

public class EconomyBalanceManager {
    
    private final Villagerlimit plugin;
    
    public EconomyBalanceManager(Villagerlimit plugin) {
        this.plugin = plugin;
    }
    
    public boolean hasEnoughEmeralds(Player player, ItemStack item, MerchantInventory merchantInventory) {
        VillagerLimitConfig config = plugin.getLimitConfig();
        
        if (!config.isEconomyBalanceEnabled() || !config.isRequireExtraEmeralds()) {
            return true;
        }
        
        int requiredEmeralds = getRequiredEmeralds(item);
        if (requiredEmeralds <= 0) {
            return true;
        }
        
        int playerEmeralds = countEmeralds(player, merchantInventory);
        
        boolean debug = config.isDebugEnabled();
        if (debug) {
            plugin.getLogger().info("[绿宝石调试] 物品: " + item.getType().name());
            plugin.getLogger().info("[绿宝石调试] 需要绿宝石: " + requiredEmeralds);
            plugin.getLogger().info("[绿宝石调试] 玩家绿宝石: " + playerEmeralds);
            plugin.getLogger().info("[绿宝石调试] 检查结果: " + (playerEmeralds >= requiredEmeralds));
        }
        
        return playerEmeralds >= requiredEmeralds;
    }
    
    public void consumeEmeralds(Player player, ItemStack item) {
        VillagerLimitConfig config = plugin.getLimitConfig();
        
        if (!config.isEconomyBalanceEnabled() || !config.isRequireExtraEmeralds()) {
            return;
        }
        
        int requiredEmeralds = getRequiredEmeralds(item);
        if (requiredEmeralds <= 0) {
            return;
        }
        
        removeEmeralds(player, requiredEmeralds);
    }
    
    public int getRequiredEmeralds(ItemStack item) {
        VillagerLimitConfig config = plugin.getLimitConfig();
        
        int baseEmeralds = config.getValuableItemEmeraldCost(item.getType().name());
        
        boolean debug = config.isDebugEnabled();
        if (debug) {
            plugin.getLogger().info("[绿宝石调试] 获取物品绿宝石消耗");
            plugin.getLogger().info("[绿宝石调试] 物品类型: " + item.getType().name());
            plugin.getLogger().info("[绿宝石调试] 基础消耗: " + baseEmeralds);
        }
        
        if (baseEmeralds <= 0) {
            if (debug) {
                plugin.getLogger().info("[绿宝石调试] 物品未配置绿宝石消耗，返回0");
            }
            return 0;
        }
        
        double multiplier = config.getEmeraldCostMultiplier();
        int finalCost = (int) Math.ceil(baseEmeralds * multiplier);
        
        if (debug) {
            plugin.getLogger().info("[绿宝石调试] 倍率: " + multiplier);
            plugin.getLogger().info("[绿宝石调试] 最终消耗: " + finalCost);
        }
        
        return finalCost;
    }
    
    private int countEmeralds(Player player, MerchantInventory merchantInventory) {
        int count = 0;
        
        // 1. 统计主背包和快捷栏
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == Material.EMERALD) {
                count += item.getAmount();
            }
        }
        
        // 2. 统计副手
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && offHand.getType() == Material.EMERALD) {
            count += offHand.getAmount();
        }
        
        // 3. 统计交易界面的输入栏（slot 0 和 slot 1）
        if (merchantInventory != null) {
            ItemStack slot0 = merchantInventory.getItem(0);
            if (slot0 != null && slot0.getType() == Material.EMERALD) {
                count += slot0.getAmount();
            }
            
            ItemStack slot1 = merchantInventory.getItem(1);
            if (slot1 != null && slot1.getType() == Material.EMERALD) {
                count += slot1.getAmount();
            }
        }
        
        boolean debug = plugin.getLimitConfig().isDebugEnabled();
        if (debug) {
            plugin.getLogger().info("[绿宝石调试] 统计玩家绿宝石总数: " + count);
        }
        
        return count;
    }
    
    private void removeEmeralds(Player player, int amount) {
        int remaining = amount;
        
        // 先从主背包移除
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == Material.EMERALD) {
                int itemAmount = item.getAmount();
                if (itemAmount <= remaining) {
                    remaining -= itemAmount;
                    item.setAmount(0);
                } else {
                    item.setAmount(itemAmount - remaining);
                    remaining = 0;
                }
                
                if (remaining == 0) {
                    break;
                }
            }
        }
        
        // 如果还不够，从副手移除
        if (remaining > 0) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (offHand != null && offHand.getType() == Material.EMERALD) {
                int itemAmount = offHand.getAmount();
                if (itemAmount <= remaining) {
                    remaining -= itemAmount;
                    offHand.setAmount(0);
                } else {
                    offHand.setAmount(itemAmount - remaining);
                    remaining = 0;
                }
            }
        }
        
        boolean debug = plugin.getLimitConfig().isDebugEnabled();
        if (debug) {
            plugin.getLogger().info("[绿宝石调试] 移除了 " + (amount - remaining) + " 个绿宝石");
        }
    }
}
