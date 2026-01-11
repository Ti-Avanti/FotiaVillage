package org.pvp.villagerlimit;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

public class VillagerSpawnListener implements Listener {
    
    private final Villagerlimit plugin;
    
    public VillagerSpawnListener(Villagerlimit plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        // 性能优化：首先检查实体类型
        if (event.getEntityType() != EntityType.VILLAGER) {
            return;
        }
        
        VillagerLimitConfig config = plugin.getLimitConfig();
        
        // 如果是刷怪蛋生成，交给 VillagerSpawnEggListener 处理
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
            return;
        }
        
        // 如果是治愈生成，允许通过（由 ZombieVillagerCureListener 处理）
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CURED) {
            if (config.isDebugEnabled()) {
                plugin.getLogger().info("[生成调试] 检测到治愈村民生成，允许通过");
            }
            return;
        }
        
        // 处理繁殖生成
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BREEDING) {
            handleBreedingVillager(event, config);
            return;
        }
        
        // 如果配置为禁止自然生成，则取消所有其他自然生成
        if (config.isBlockNaturalSpawn()) {
            if (config.isDebugEnabled()) {
                plugin.getLogger().info("[生成调试] 阻止自然生成，原因: " + event.getSpawnReason());
            }
            event.setCancelled(true);
        }
    }
    
    /**
     * 处理繁殖产生的村民
     * 
     * @param event 生物生成事件
     * @param config 配置对象
     */
    private void handleBreedingVillager(CreatureSpawnEvent event, VillagerLimitConfig config) {
        try {
            // 检查配置：是否允许繁殖
            if (!config.isBreedingAllowed()) {
                event.setCancelled(true);
                if (config.isDebugEnabled()) {
                    plugin.getLogger().info("[繁殖调试] 繁殖被配置禁止，取消生成");
                }
                return;
            }
            
            // 如果允许繁殖，检查是否需要设置寿命
            if (!config.isLifespanEnabled()) {
                // 寿命系统未启用，允许繁殖但不设置寿命
                if (config.isDebugEnabled()) {
                    plugin.getLogger().info("[繁殖调试] 寿命系统未启用，允许繁殖但不设置寿命");
                }
                return; // 允许村民生成
            }
            
            // 获取村民实体（使用传统的 instanceof 和类型转换）
            if (!(event.getEntity() instanceof Villager)) {
                return;
            }
            Villager villager = (Villager) event.getEntity();
            
            // 调试日志
            if (config.isDebugEnabled()) {
                plugin.getLogger().info("[繁殖调试] 检测到繁殖村民: " + villager.getUniqueId());
            }
            
            // 设置寿命（复用现有管理器）
            VillagerLifespanManager lifespanManager = plugin.getLifespanManager();
            if (lifespanManager != null) {
                int days = config.getLifespanDays();
                lifespanManager.setVillagerLifespan(villager, days);
                
                if (config.isDebugEnabled()) {
                    plugin.getLogger().info("[繁殖调试] 已为繁殖村民设置寿命: " + days + " 天");
                }
            } else {
                plugin.getLogger().warning("[繁殖警告] 寿命管理器未初始化");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("[繁殖错误] 处理繁殖村民时发生异常:");
            e.printStackTrace();
        }
    }
}
