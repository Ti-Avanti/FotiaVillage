package org.pvp.villagerlimit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.pvp.villagerlimit.core.LanguageManager;
import org.pvp.villagerlimit.display.ArmorStandAdapter;
import org.pvp.villagerlimit.display.DisplayAdapter;
import org.pvp.villagerlimit.display.TextDisplayAdapter;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 村民寿命管理器
 * 管理治愈获得的村民的寿命
 */
public class VillagerLifespanManager {
    
    private final Villagerlimit plugin;
    private final NamespacedKey lifespanKey;
    private final NamespacedKey displayKey;
    private final Map<UUID, Entity> displayCache;
    private final DisplayAdapter displayAdapter;
    
    public VillagerLifespanManager(Villagerlimit plugin) {
        this.plugin = plugin;
        this.lifespanKey = new NamespacedKey(plugin, "lifespan_end");
        this.displayKey = new NamespacedKey(plugin, "display_entity");
        this.displayCache = new ConcurrentHashMap<>();
        
        // 根据MC版本选择适配器
        this.displayAdapter = detectDisplayAdapter();
        
        plugin.getLogger().info("使用显示适配器: " + displayAdapter.getClass().getSimpleName());
        
        startLifespanCheckTask();
        startDisplayUpdateTask();
        startAutoAddLifespanTask();
        startOrphanDisplayCleanupTask();
    }
    
    /**
     * 检测并选择合适的显示适配器
     */
    private DisplayAdapter detectDisplayAdapter() {
        try {
            // 尝试加载TextDisplay类
            Class.forName("org.bukkit.entity.TextDisplay");
            return new TextDisplayAdapter();
        } catch (ClassNotFoundException e) {
            // TextDisplay不存在，使用ArmorStand
            return new ArmorStandAdapter();
        }
    }
    
    /**
     * 设置村民寿命
     */
    public void setVillagerLifespan(Villager villager, int days) {
        long endTime = System.currentTimeMillis() + (days * 24L * 60 * 60 * 1000);
        villager.getPersistentDataContainer().set(lifespanKey, PersistentDataType.LONG, endTime);
        
        boolean debug = plugin.getLimitConfig().isDebugEnabled();
        if (debug) {
            plugin.getLogger().info("[寿命调试] 村民UUID: " + villager.getUniqueId());
            plugin.getLogger().info("[寿命调试] 寿命结束时间: " + endTime);
        }
        
        // 创建显示实体
        createLifespanDisplay(villager);
    }
    
    /**
     * 获取村民剩余寿命（毫秒）
     */
    public long getRemainingLifespan(Villager villager) {
        Long endTime = villager.getPersistentDataContainer().get(lifespanKey, PersistentDataType.LONG);
        if (endTime == null) {
            return -1;
        }
        
        long remaining = endTime - System.currentTimeMillis();
        return remaining > 0 ? remaining : 0;
    }
    
    /**
     * 检查村民是否有寿命限制
     */
    public boolean hasLifespan(Villager villager) {
        return villager.getPersistentDataContainer().has(lifespanKey, PersistentDataType.LONG);
    }
    
    /**
     * 创建寿命显示实体
     */
    private void createLifespanDisplay(Villager villager) {
        // 移除旧的显示实体
        removeLifespanDisplay(villager);
        
        // 创建新的显示实体
        String text = formatRemainingTime(getRemainingLifespan(villager));
        boolean debug = plugin.getLimitConfig().isDebugEnabled();
        if (debug) {
            plugin.getLogger().info("[寿命调试] 创建显示实体，文本: " + text);
        }
        
        Entity display = displayAdapter.createDisplay(villager, text);
        if (debug) {
            plugin.getLogger().info("[寿命调试] 显示实体UUID: " + display.getUniqueId());
        }
        
        // 保存显示实体UUID
        villager.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, display.getUniqueId().toString());
        displayCache.put(villager.getUniqueId(), display);
    }
    
    /**
     * 更新显示文本
     */
    private void updateDisplayText(Entity display, Villager villager) {
        long remaining = getRemainingLifespan(villager);
        if (remaining <= 0) {
            return;
        }
        
        String text = formatRemainingTime(remaining);
        displayAdapter.updateDisplay(display, text);
    }
    
    /**
     * 格式化剩余时间
     */
    private String formatRemainingTime(long milliseconds) {
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        
        VillagerLimitConfig config = plugin.getLimitConfig();
        LanguageManager lang = plugin.getModuleManager().getModule(LanguageManager.class);
        
        if (lang != null) {
            if (days > 0) {
                hours = hours % 24;
                return lang.getMessage("lifespan.display-days", 
                    Map.of("days", days, "hours", hours));
            } else if (hours > 0) {
                return lang.getMessage("lifespan.display-hours", 
                    Map.of("hours", hours));
            } else if (minutes > 0) {
                return lang.getMessage("lifespan.display-minutes", 
                    Map.of("minutes", minutes));
            } else {
                return lang.getMessage("lifespan.display-seconds", 
                    Map.of("seconds", seconds));
            }
        } else {
            // 后备方案
            if (days > 0) {
                hours = hours % 24;
                return "§e剩余 " + days + "天 " + hours + "小时";
            } else if (hours > 0) {
                return "§e剩余 " + hours + "小时";
            } else if (minutes > 0) {
                return "§e剩余 " + minutes + " 分钟";
            } else {
                return "§c剩余 " + seconds + " 秒";
            }
        }
    }
    
    /**
     * 移除寿命显示实体
     */
    private void removeLifespanDisplay(Villager villager) {
        // 从缓存移除
        Entity cached = displayCache.remove(villager.getUniqueId());
        if (cached != null) {
            displayAdapter.removeDisplay(cached);
        }
        
        // 从持久化数据获取
        String displayUUID = villager.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
        if (displayUUID != null) {
            try {
                UUID uuid = UUID.fromString(displayUUID);
                var entity = Bukkit.getEntity(uuid);
                if (entity != null) {
                    displayAdapter.removeDisplay(entity);
                }
            } catch (Exception ignored) {
            }
            villager.getPersistentDataContainer().remove(displayKey);
        }
    }
    
    /**
     * 启动寿命检查任务
     */
    private void startLifespanCheckTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (var world : plugin.getServer().getWorlds()) {
                for (var entity : world.getEntities()) {
                    if (entity instanceof Villager villager) {
                        if (hasLifespan(villager)) {
                            long remaining = getRemainingLifespan(villager);
                            
                            if (remaining <= 0) {
                                // 寿命到期，移除村民
                                removeLifespanDisplay(villager);
                                villager.remove();
                                
                                VillagerLimitConfig config = plugin.getLimitConfig();
                                if (config.isLifespanNotifyEnabled()) {
                                    // 通知玩家
                                    LanguageManager lang = plugin.getModuleManager().getModule(LanguageManager.class);
                                    String message;
                                    if (lang != null) {
                                        message = lang.getMessage("lifespan.expired");
                                    } else {
                                        message = "§c一个村民的寿命已到期并消失了！";
                                    }
                                    int range = config.getLifespanNotifyRange();
                                    
                                    if (range <= 0) {
                                        // 全服通知
                                        plugin.getServer().getOnlinePlayers()
                                            .forEach(player -> player.sendMessage(message));
                                    } else {
                                        // 范围通知
                                        villager.getWorld().getNearbyPlayers(villager.getLocation(), range)
                                            .forEach(player -> player.sendMessage(message));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }, 20L * 60, 20L * 60); // 每分钟检查一次
    }
    
    /**
     * 启动显示更新任务
     */
    private void startDisplayUpdateTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (var world : plugin.getServer().getWorlds()) {
                for (var entity : world.getEntities()) {
                    if (entity instanceof Villager villager) {
                        if (hasLifespan(villager)) {
                            updateVillagerDisplay(villager);
                        }
                    }
                }
            }
        }, 20L * 10, 20L * 10); // 每10秒更新一次
    }
    
    /**
     * 更新村民显示
     */
    private void updateVillagerDisplay(Villager villager) {
        Entity display = displayCache.get(villager.getUniqueId());
        
        if (display == null || !displayAdapter.isValid(display)) {
            // 重新创建显示实体
            String displayUUID = villager.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING);
            if (displayUUID != null) {
                try {
                    UUID uuid = UUID.fromString(displayUUID);
                    var entity = Bukkit.getEntity(uuid);
                    if (entity != null && displayAdapter.isValid(entity)) {
                        display = entity;
                        displayCache.put(villager.getUniqueId(), display);
                    } else {
                        createLifespanDisplay(villager);
                        return;
                    }
                } catch (Exception e) {
                    createLifespanDisplay(villager);
                    return;
                }
            } else {
                createLifespanDisplay(villager);
                return;
            }
        }
        
        // 只更新文本，位置由乘客系统自动处理
        updateDisplayText(display, villager);
    }
    
    /**
     * 清理村民显示（公开方法，供死亡监听器调用）
     */
    public void cleanupVillagerDisplay(Villager villager) {
        boolean debug = plugin.getLimitConfig().isDebugEnabled();
        if (debug) {
            plugin.getLogger().info("[寿命调试] 村民死亡，清理显示实体");
        }
        removeLifespanDisplay(villager);
    }
    
    /**
     * 清理数据
     */
    public void cleanup() {
        for (Entity display : displayCache.values()) {
            displayAdapter.removeDisplay(display);
        }
        displayCache.clear();
    }
    
    /**
     * 启动自动添加寿命任务
     */
    private void startAutoAddLifespanTask() {
        VillagerLimitConfig config = plugin.getLimitConfig();
        
        // 检查是否启用自动添加寿命
        if (!config.isAutoAddLifespanEnabled()) {
            plugin.getLogger().info("[寿命系统] 自动添加寿命功能未启用");
            return;
        }
        
        // 启动时检查
        if (config.isAutoAddCheckOnStartup()) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                plugin.getLogger().info("[寿命系统] 执行启动时自动检查...");
                autoAddLifespanToVillagers();
            }, 20L * 5); // 延迟5秒执行，等待世界完全加载
        }
        
        // 定时任务
        int interval = config.getAutoAddCheckInterval();
        if (interval > 0) {
            plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                plugin.getLogger().info("[寿命系统] 执行定时自动检查...");
                autoAddLifespanToVillagers();
            }, 20L * interval, 20L * interval);
            
            plugin.getLogger().info("[寿命系统] 自动添加寿命任务已启动，检查间隔: " + interval + " 秒");
        } else {
            plugin.getLogger().info("[寿命系统] 自动添加寿命任务仅在启动时执行");
        }
    }
    
    /**
     * 自动为所有无寿命的村民添加寿命
     */
    private void autoAddLifespanToVillagers() {
        VillagerLimitConfig config = plugin.getLimitConfig();
        
        // 检查寿命系统是否启用
        if (!config.isLifespanEnabled()) {
            plugin.getLogger().info("[寿命系统] 寿命系统未启用，跳过自动添加");
            return;
        }
        
        int totalVillagers = 0;
        int addedCount = 0;
        int days = config.getLifespanDays();
        
        try {
            // 扫描所有世界的所有村民
            for (var world : plugin.getServer().getWorlds()) {
                for (var entity : world.getEntities()) {
                    if (entity instanceof Villager villager) {
                        // 验证村民是否有效
                        if (!villager.isValid() || villager.isDead()) {
                            continue;
                        }
                        
                        totalVillagers++;
                        
                        // 检查是否已有寿命
                        if (!hasLifespan(villager)) {
                            // 添加寿命
                            setVillagerLifespan(villager, days);
                            addedCount++;
                            
                            if (config.isDebugEnabled()) {
                                plugin.getLogger().info("[寿命调试] 为村民添加寿命: " + villager.getUniqueId() + 
                                    " 位置: " + villager.getLocation());
                            }
                        }
                    }
                }
            }
            
            // 记录结果
            plugin.getLogger().info("[寿命系统] 自动检查完成 - 总村民数: " + totalVillagers + 
                ", 添加寿命: " + addedCount + ", 已有寿命: " + (totalVillagers - addedCount));
            
        } catch (Exception e) {
            plugin.getLogger().severe("[寿命系统] 自动添加寿命时发生错误:");
            e.printStackTrace();
        }
    }
    
    /**
     * 启动孤儿显示实体清理任务
     */
    private void startOrphanDisplayCleanupTask() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int cleanedCount = cleanupOrphanDisplays();
            if (cleanedCount > 0) {
                plugin.getLogger().info("[寿命系统] 清理了 " + cleanedCount + " 个孤儿显示实体");
            }
        }, 20L * 60 * 5, 20L * 60 * 5); // 每5分钟清理一次
        
        plugin.getLogger().info("[寿命系统] 孤儿显示实体清理任务已启动，清理间隔: 5 分钟");
    }
    
    /**
     * 清理孤儿显示实体（没有对应村民的显示实体）
     * @return 清理的数量
     */
    private int cleanupOrphanDisplays() {
        int cleanedCount = 0;
        boolean debug = plugin.getLimitConfig().isDebugEnabled();
        
        try {
            // 收集所有有效的村民UUID
            var validVillagerUUIDs = new java.util.HashSet<UUID>();
            for (var world : plugin.getServer().getWorlds()) {
                for (var entity : world.getEntities()) {
                    if (entity instanceof Villager villager && villager.isValid() && !villager.isDead()) {
                        validVillagerUUIDs.add(villager.getUniqueId());
                    }
                }
            }
            
            // 清理缓存中的孤儿显示实体
            var iterator = displayCache.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                UUID villagerUUID = entry.getKey();
                Entity display = entry.getValue();
                
                // 检查村民是否还存在
                if (!validVillagerUUIDs.contains(villagerUUID)) {
                    if (debug) {
                        plugin.getLogger().info("[寿命调试] 清理孤儿显示实体: " + display.getUniqueId() + 
                            " (村民UUID: " + villagerUUID + ")");
                    }
                    displayAdapter.removeDisplay(display);
                    iterator.remove();
                    cleanedCount++;
                }
            }
            
            // 扫描世界中的所有显示实体，清理没有对应村民的
            for (var world : plugin.getServer().getWorlds()) {
                for (var entity : world.getEntities()) {
                    // 检查是否是显示实体（ArmorStand 或 TextDisplay）
                    if (displayAdapter.isValid(entity)) {
                        // 检查是否有乘客（村民）
                        var passengers = entity.getPassengers();
                        if (passengers.isEmpty()) {
                            // 没有乘客，可能是孤儿显示实体
                            if (debug) {
                                plugin.getLogger().info("[寿命调试] 发现无乘客的显示实体: " + entity.getUniqueId());
                            }
                            displayAdapter.removeDisplay(entity);
                            cleanedCount++;
                        } else {
                            // 检查乘客是否是有效的村民
                            boolean hasValidVillager = false;
                            for (var passenger : passengers) {
                                if (passenger instanceof Villager villager && 
                                    villager.isValid() && !villager.isDead()) {
                                    hasValidVillager = true;
                                    break;
                                }
                            }
                            
                            if (!hasValidVillager) {
                                if (debug) {
                                    plugin.getLogger().info("[寿命调试] 发现无有效村民乘客的显示实体: " + entity.getUniqueId());
                                }
                                displayAdapter.removeDisplay(entity);
                                cleanedCount++;
                            }
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            plugin.getLogger().severe("[寿命系统] 清理孤儿显示实体时发生错误:");
            e.printStackTrace();
        }
        
        return cleanedCount;
    }
}
