package org.pvp.villagerlimit;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTransformEvent;
import org.pvp.villagerlimit.core.LanguageManager;

import java.util.Map;

public class ZombieVillagerCureListener implements Listener {
    
    private final Villagerlimit plugin;
    
    public ZombieVillagerCureListener(Villagerlimit plugin) {
        this.plugin = plugin;
    }
    
    @EventHandler
    public void onEntityTransform(EntityTransformEvent event) {
        if (event.getEntity().getType() != EntityType.ZOMBIE_VILLAGER || 
            event.getTransformedEntity().getType() != EntityType.VILLAGER) {
            return;
        }
        
        VillagerLimitConfig config = plugin.getLimitConfig();
        
        // 如果不允许治愈，直接取消
        if (!config.isAllowCure()) {
            event.setCancelled(true);
            return;
        }
        
        // 如果启用了数量限制，检查区域内村民数量
        if (config.isVillagerLimitEnabled()) {
            Location location = event.getEntity().getLocation();
            int nearbyVillagers = countNearbyVillagers(location, config.getChunkRadius());
            
            if (nearbyVillagers >= config.getMaxVillagers()) {
                event.setCancelled(true);
                
                // 通知附近玩家
                Player nearestPlayer = location.getWorld().getNearbyPlayers(location, 10).stream()
                    .findFirst()
                    .orElse(null);
                
                if (nearestPlayer != null) {
                    LanguageManager lang = plugin.getModuleManager().getModule(LanguageManager.class);
                    if (lang != null) {
                        nearestPlayer.sendMessage(lang.getMessage("villager.limit-reached"));
                    } else {
                        nearestPlayer.sendMessage("§c该区域村民数量已达上限！");
                    }
                }
                return;
            }
        }
        
        // 设置村民寿命
        boolean debug = config.isDebugEnabled();
        if (debug) {
            plugin.getLogger().info("[寿命调试] 寿命系统启用: " + config.isLifespanEnabled());
        }
        if (config.isLifespanEnabled()) {
            if (debug) {
                plugin.getLogger().info("[寿命调试] 准备设置村民寿命");
            }
            // 增加延迟时间到10tick（0.5秒），确保村民完全生成
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (event.getTransformedEntity() instanceof Villager villager) {
                    // 验证村民是否存在且有效
                    if (!villager.isValid() || villager.isDead()) {
                        if (debug) {
                            plugin.getLogger().warning("[寿命警告] 村民无效或已死亡，无法设置寿命");
                        }
                        return;
                    }
                    
                    VillagerLimitConfig cfg = plugin.getLimitConfig();
                    int lifespanDays = cfg.getVillagerLifespanDays();
                    if (debug) {
                        plugin.getLogger().info("[寿命调试] 设置村民寿命: " + lifespanDays + " 天");
                        plugin.getLogger().info("[寿命调试] 村民UUID: " + villager.getUniqueId());
                        plugin.getLogger().info("[寿命调试] 村民位置: " + villager.getLocation());
                    }
                    plugin.getLifespanManager().setVillagerLifespan(villager, lifespanDays);
                    
                    // 通知附近玩家
                    if (cfg.isLifespanNotifyEnabled()) {
                        LanguageManager lang = plugin.getModuleManager().getModule(LanguageManager.class);
                        String message;
                        if (lang != null) {
                            message = lang.getMessage("lifespan.set", 
                                Map.of("days", lifespanDays));
                        } else {
                            message = "§a村民已获得 " + lifespanDays + " 天寿命！";
                        }
                        
                        int range = cfg.getLifespanNotifyRange();
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
                } else {
                    if (debug) {
                        plugin.getLogger().warning("[寿命警告] 转换后的实体不是村民类型");
                    }
                }
            }, 10L); // 从1L增加到10L
        }
    }
    
    private int countNearbyVillagers(Location location, int chunkRadius) {
        int count = 0;
        Chunk centerChunk = location.getChunk();
        int centerX = centerChunk.getX();
        int centerZ = centerChunk.getZ();
        
        for (int x = centerX - chunkRadius; x <= centerX + chunkRadius; x++) {
            for (int z = centerZ - chunkRadius; z <= centerZ + chunkRadius; z++) {
                Chunk chunk = location.getWorld().getChunkAt(x, z);
                for (org.bukkit.entity.Entity entity : chunk.getEntities()) {
                    if (entity instanceof Villager) {
                        count++;
                    }
                }
            }
        }
        
        return count;
    }
}