package org.pvp.villagerlimit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.pvp.villagerlimit.core.LanguageManager;

import java.util.Map;

public class VillagerTradeListener implements Listener {
    
    private final Villagerlimit plugin;
    private final TradeDataManager tradeDataManager;
    private final TradeCooldownManager cooldownManager;
    private final TradeLimitManager limitManager;
    private final TradeStatisticsManager statisticsManager;
    private final EconomyBalanceManager economyManager;
    
    public VillagerTradeListener(Villagerlimit plugin) {
        this.plugin = plugin;
        this.tradeDataManager = new TradeDataManager(plugin);
        this.cooldownManager = new TradeCooldownManager(plugin);
        this.limitManager = new TradeLimitManager(plugin);
        this.statisticsManager = new TradeStatisticsManager(plugin);
        this.economyManager = new EconomyBalanceManager(plugin);
    }
    
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory() instanceof MerchantInventory)) {
            return;
        }
        
        MerchantInventory merchantInventory = (MerchantInventory) event.getInventory();
        if (!(merchantInventory.getHolder() instanceof Villager)) {
            return;
        }
        
        if (event.getSlotType() != InventoryType.SlotType.RESULT) {
            return;
        }
        
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        
        // 获取交易配方
        MerchantRecipe recipe = merchantInventory.getSelectedRecipe();
        if (recipe == null) {
            return;
        }
        
        ItemStack result = recipe.getResult();
        if (result == null || result.getType() == Material.AIR) {
            return;
        }
        
        Player player = (Player) event.getWhoClicked();
        Villager villager = (Villager) merchantInventory.getHolder();
        VillagerLimitConfig config = plugin.getLimitConfig();
        LanguageManager lang = plugin.getModuleManager().getModule(LanguageManager.class);
        
        boolean debug = config.isDebugEnabled();
        
        if (debug) {
            plugin.getLogger().info("[交易调试] 玩家 " + player.getName() + " 尝试交易");
            plugin.getLogger().info("[交易调试] 禁用交易: " + config.isDisableTrading());
            plugin.getLogger().info("[交易调试] 经验消耗启用: " + config.isExpCostEnabled());
            plugin.getLogger().info("[交易调试] 事件已取消: " + event.isCancelled());
        }
        
        // 如果完全禁用交易
        if (config.isDisableTrading()) {
            event.setCancelled(true);
            if (lang != null) {
                player.sendMessage(lang.getMessage("trade.disabled"));
            } else {
                player.sendMessage("§c村民交易已被禁用！");
            }
            player.closeInventory();
            return;
        }
        
        String profession = villager.getProfession().name();
        
        // 检查交易冷却
        if (config.isCooldownEnabled() && cooldownManager.isOnCooldown(player, profession, result)) {
            event.setCancelled(true);
            long remaining = cooldownManager.getRemainingCooldown(player, profession, result);
            String message;
            if (lang != null) {
                message = lang.getMessage("trade.cooldown", 
                    Map.of("time", cooldownManager.formatTime(remaining)));
            } else {
                message = "§c该交易冷却中，剩余: §e" + cooldownManager.formatTime(remaining);
            }
            player.sendMessage(message);
            player.closeInventory();
            return;
        }
        
        // 检查交易次数限制
        if (config.isLimitEnabled() && !limitManager.canTrade(player, profession, result)) {
            event.setCancelled(true);
            if (lang != null) {
                player.sendMessage(lang.getMessage("trade.limit-reached"));
            } else {
                player.sendMessage("§c今日交易次数已达上限！");
            }
            player.closeInventory();
            return;
        }
        
        // 检查绿宝石消耗
        if (config.isEconomyBalanceEnabled() && !economyManager.hasEnoughEmeralds(player, result, merchantInventory)) {
            event.setCancelled(true);
            int required = economyManager.getRequiredEmeralds(result);
            String message;
            if (lang != null) {
                message = lang.getMessage("trade.insufficient-emerald", 
                    Map.of("required", required));
            } else {
                message = "§c交易需要额外 " + required + " 个绿宝石！";
            }
            player.sendMessage(message);
            player.closeInventory();
            return;
        }
        
        // 检查经验消耗
        final int requiredExp;
        if (config.isExpCostEnabled()) {
            requiredExp = calculateExpCost(player, villager, result);
            String costMode = config.getExpCostMode();
            
            if (debug) {
                plugin.getLogger().info("[交易调试] 需要经验: " + requiredExp);
                plugin.getLogger().info("[交易调试] 消耗模式: " + costMode);
                plugin.getLogger().info("[交易调试] 玩家等级: " + player.getLevel());
            }
            
            if ("POINTS".equalsIgnoreCase(costMode)) {
                // 经验值模式
                int currentExp = getTotalExperience(player);
                if (debug) {
                    plugin.getLogger().info("[交易调试] 玩家经验值: " + currentExp);
                }
                
                if (currentExp < requiredExp) {
                    event.setCancelled(true);
                    String message;
                    if (lang != null) {
                        message = lang.getMessage("trade.insufficient-points", 
                            Map.of("required", requiredExp, "current", currentExp));
                    } else {
                        message = "§c交易需要 " + requiredExp + " 点经验，你当前只有 " + currentExp + " 点！";
                    }
                    player.sendMessage(message);
                    player.closeInventory();
                    if (debug) {
                        plugin.getLogger().info("[交易调试] 经验不足，取消交易");
                    }
                    return;
                }
                
                // 检查扣除后是否会低于最低等级
                int minLevel = config.getMinLevel();
                if (minLevel > 0) {
                    int remainingExp = currentExp - requiredExp;
                    int minLevelExp = 0;
                    for (int i = 0; i < minLevel; i++) {
                        minLevelExp += getExperienceToLevelUp(i);
                    }
                    
                    if (remainingExp < minLevelExp) {
                        event.setCancelled(true);
                        if (lang != null) {
                            player.sendMessage(lang.getMessage("trade.min-level"));
                        } else {
                            player.sendMessage("§c交易会使你的等级低于最低保留等级！");
                        }
                        player.closeInventory();
                        if (debug) {
                            plugin.getLogger().info("[交易调试] 低于最低等级，取消交易");
                        }
                        return;
                    }
                }
            } else {
                // 等级模式（默认）
                int currentLevel = player.getLevel();
                if (debug) {
                    plugin.getLogger().info("[交易调试] 玩家等级: " + currentLevel);
                }
                
                if (currentLevel < requiredExp) {
                    event.setCancelled(true);
                    String message;
                    if (lang != null) {
                        message = lang.getMessage("trade.insufficient-exp", 
                            Map.of("required", requiredExp, "current", currentLevel));
                    } else {
                        message = "§c交易需要 " + requiredExp + " 级经验，你当前只有 " + currentLevel + " 级！";
                    }
                    player.sendMessage(message);
                    player.closeInventory();
                    if (debug) {
                        plugin.getLogger().info("[交易调试] 等级不足，取消交易");
                    }
                    return;
                }
                
                // 检查扣除后是否会低于最低等级
                int minLevel = config.getMinLevel();
                if (currentLevel - requiredExp < minLevel) {
                    event.setCancelled(true);
                    if (lang != null) {
                        player.sendMessage(lang.getMessage("trade.min-level"));
                    } else {
                        player.sendMessage("§c交易会使你的等级低于最低保留等级！");
                    }
                    player.closeInventory();
                    if (debug) {
                        plugin.getLogger().info("[交易调试] 低于最低等级，取消交易");
                    }
                    return;
                }
            }
        } else {
            requiredExp = 0;
        }
        
        final ItemStack finalResult = result;
        final boolean finalDebug = debug;
        
        // 所有检查通过，延迟执行扣除和记录操作
        // 使用延迟任务确保在交易完成后执行
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            // 获取语言管理器
            LanguageManager langManager = plugin.getModuleManager().getModule(LanguageManager.class);
            
            // 扣除经验
            if (config.isExpCostEnabled() && requiredExp > 0) {
                String costMode = config.getExpCostMode();
                if ("POINTS".equalsIgnoreCase(costMode)) {
                    int currentExp = getTotalExperience(player);
                    if (finalDebug) {
                        plugin.getLogger().info("[交易调试] 扣除经验值: " + requiredExp);
                    }
                    setTotalExperience(player, currentExp - requiredExp);
                    if (langManager != null) {
                        player.sendMessage(langManager.getMessage("trade.exp-consumed-points", 
                            Map.of("amount", requiredExp)));
                    } else {
                        player.sendMessage("§e消耗了 §c" + requiredExp + " §e点经验值");
                    }
                } else {
                    int currentLevel = player.getLevel();
                    if (finalDebug) {
                        plugin.getLogger().info("[交易调试] 扣除等级: " + requiredExp);
                    }
                    player.setLevel(currentLevel - requiredExp);
                    if (langManager != null) {
                        player.sendMessage(langManager.getMessage("trade.exp-consumed-level", 
                            Map.of("amount", requiredExp)));
                    } else {
                        player.sendMessage("§e消耗了 §c" + requiredExp + " §e级经验");
                    }
                }
            }
            
            // 消耗绿宝石
            economyManager.consumeEmeralds(player, finalResult);
            
            // 检查成本递增
            if (config.isCostScalingEnabled()) {
                double multiplier = tradeDataManager.getTradeMultiplier(player, finalResult);
                if (multiplier > 1.0) {
                    String message;
                    if (langManager != null) {
                        message = langManager.getMessage("trade.cost-scaling", 
                            Map.of("cost", String.format("%.1f", multiplier)));
                    } else {
                        message = "§e该交易成本已增加至 §c" + String.format("%.1f", multiplier) + "§e 倍！";
                    }
                    player.sendMessage(message);
                }
                
                // 记录交易
                tradeDataManager.recordTrade(player, finalResult);
            }
            
            // 设置冷却
            if (config.isCooldownEnabled()) {
                cooldownManager.setCooldown(player, profession, finalResult);
            }
            
            // 记录次数限制
            if (config.isLimitEnabled()) {
                limitManager.recordTrade(player, profession, finalResult);
            }
            
            // 记录统计
            if (config.isStatisticsEnabled()) {
                statisticsManager.recordTrade(player, finalResult, requiredExp);
            }
        });
    }
    
    private int calculateExpCost(Player player, Villager villager, ItemStack result) {
        VillagerLimitConfig config = plugin.getLimitConfig();
        boolean debug = config.isDebugEnabled();
        
        // 基础消耗
        int baseCost = config.getBaseExpCost();
        
        // 职业消耗
        String profession = villager.getProfession().name();
        int professionCost = config.getProfessionExpCost(profession);
        
        // 物品消耗
        String material = result.getType().name();
        int itemCost = config.getValuableItemExpCost(material);
        
        if (debug) {
            plugin.getLogger().info("[经验计算] 基础消耗: " + baseCost);
            plugin.getLogger().info("[经验计算] 职业: " + profession + ", 消耗: " + professionCost);
            plugin.getLogger().info("[经验计算] 物品: " + material + ", 消耗: " + itemCost);
        }
        
        // 取最大值
        int totalCost = Math.max(baseCost, professionCost);
        if (itemCost > 0) {
            totalCost = Math.max(totalCost, itemCost);
        }
        
        if (debug) {
            plugin.getLogger().info("[经验计算] 初始总消耗: " + totalCost);
        }
        
        // 应用成本递增
        if (config.isCostScalingEnabled()) {
            double multiplier = tradeDataManager.getTradeMultiplier(player, result);
            totalCost = (int) Math.ceil(totalCost * multiplier);
            if (debug) {
                plugin.getLogger().info("[经验计算] 递增倍率: " + multiplier + ", 最终消耗: " + totalCost);
            }
        }
        
        // 应用权限组倍率
        double permMultiplier = getPermissionMultiplier(player);
        totalCost = (int) Math.ceil(totalCost * permMultiplier);
        
        if (debug) {
            plugin.getLogger().info("[经验计算] 权限组倍率: " + permMultiplier + ", 最终消耗: " + totalCost);
        }
        
        return totalCost;
    }
    
    private double getPermissionMultiplier(Player player) {
        PermissionGroupManager.PermissionGroup group = plugin.getPermissionGroupManager().getPlayerGroup(player);
        return group.expCostMultiplier;
    }
    
    /**
     * 获取玩家总经验值
     */
    private int getTotalExperience(Player player) {
        int exp = 0;
        int level = player.getLevel();
        
        // 累加之前所有等级的经验
        for (int i = 0; i < level; i++) {
            exp += getExperienceToLevelUp(i);
        }
        
        // 加上当前等级的进度经验
        exp += Math.round(getExperienceToLevelUp(level) * player.getExp());
        
        return exp;
    }
    
    /**
     * 设置玩家总经验值
     */
    private void setTotalExperience(Player player, int exp) {
        player.setExp(0);
        player.setLevel(0);
        player.setTotalExperience(0);
        
        int level = 0;
        int expToLevel = getExperienceToLevelUp(level);
        
        while (exp >= expToLevel) {
            exp -= expToLevel;
            level++;
            expToLevel = getExperienceToLevelUp(level);
        }
        
        player.setLevel(level);
        player.setExp((float) exp / (float) expToLevel);
    }
    
    /**
     * 获取升到下一级所需经验
     */
    private int getExperienceToLevelUp(int level) {
        if (level >= 30) {
            return 112 + (level - 30) * 9;
        }
        if (level >= 15) {
            return 37 + (level - 15) * 5;
        }
        return 7 + level * 2;
    }
    
    public TradeDataManager getTradeDataManager() {
        return tradeDataManager;
    }
    
    public TradeStatisticsManager getStatisticsManager() {
        return statisticsManager;
    }
    
    public TradeLimitManager getLimitManager() {
        return limitManager;
    }
    
    public TradeCooldownManager getCooldownManager() {
        return cooldownManager;
    }
    
    public EconomyBalanceManager getEconomyManager() {
        return economyManager;
    }
}
