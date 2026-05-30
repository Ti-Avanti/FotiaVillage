package gg.fotia.fotiavillage.lifespan;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.config.FotiaSettings;
import gg.fotia.fotiavillage.lifespan.display.ArmorStandLifespanDisplayRenderer;
import gg.fotia.fotiavillage.lifespan.display.CustomNameplatesLifespanTagFormatter;
import gg.fotia.fotiavillage.lifespan.display.DecentHologramsLifespanDisplayRenderer;
import gg.fotia.fotiavillage.lifespan.display.LifespanDisplayRenderer;
import gg.fotia.fotiavillage.lifespan.display.LifespanDisplayText;
import gg.fotia.fotiavillage.lifespan.display.LifespanTagFormatter;
import gg.fotia.fotiavillage.lifespan.display.TextDisplayLifespanDisplayRenderer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LifespanService implements Listener {
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private final FotiaVillagePlugin plugin;
    private final NamespacedKey lifespanEndKey;
    private final NamespacedKey displayIdKey;
    private final NamespacedKey displayOwnerKey;
    private final ArrayList<BukkitTask> tasks = new ArrayList<>();
    private final Set<UUID> targetedVillagers = new HashSet<>();
    private final Set<UUID> visibleVillagers = new HashSet<>();
    private LifespanTagFormatter tagFormatter;
    private LifespanDisplayRenderer displayRenderer;

    public LifespanService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
        this.lifespanEndKey = new NamespacedKey(plugin, "lifespan_end");
        this.displayIdKey = new NamespacedKey(plugin, "lifespan_display");
        this.displayOwnerKey = new NamespacedKey(plugin, "lifespan_display_owner");
        this.tagFormatter = LifespanDisplayText::plain;
        this.displayRenderer = new ArmorStandLifespanDisplayRenderer(plugin, displayIdKey, displayOwnerKey, 0.65D);
    }

    public void start() {
        stop();
        displayRenderer = createDisplayRenderer();
        tagFormatter = createTagFormatter();
        if (!plugin.settings().lifespan().enabled()) {
            return;
        }
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::checkExpirations, 20L * 60L, 20L * 60L));
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickDisplayRenderer, 1L, 1L));
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateTargetedVillagers, 1L, plugin.settings().lifespan().displayVisibilityCheckIntervalTicks()));
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateDisplays, 20L * 5L, 20L * 5L));
        tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::cleanupOrphanDisplays, 20L * 60L * 5L, 20L * 60L * 5L));
        if (plugin.settings().lifespan().autoAddEnabled() && plugin.settings().lifespan().autoAddCheckOnStartup()) {
            tasks.add(plugin.getServer().getScheduler().runTaskLater(plugin, this::autoAddMissingLifespan, 20L * 5L));
        }
        int interval = plugin.settings().lifespan().autoAddCheckInterval();
        if (plugin.settings().lifespan().autoAddEnabled() && interval > 0) {
            tasks.add(plugin.getServer().getScheduler().runTaskTimer(plugin, this::autoAddMissingLifespan, 20L * interval, 20L * interval));
        }
        updateDisplays();
    }

    public void stop() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (displayRenderer != null) {
            displayRenderer.removeAll();
        }
        targetedVillagers.clear();
        visibleVillagers.clear();
    }

    public boolean setLifespan(Villager villager, int days) {
        if (!plugin.isWorldAllowed(villager.getWorld())) {
            cleanupDisplay(villager);
            return false;
        }
        if (isExcluded(villager)) {
            clearLifespanData(villager);
            return false;
        }
        long end = safeAdd(System.currentTimeMillis(), daysToMillis(days));
        villager.getPersistentDataContainer().set(lifespanEndKey, PersistentDataType.LONG, end);
        refreshDisplay(villager);
        return true;
    }

    public long addLifespan(Villager villager, int days) {
        if (!plugin.isWorldAllowed(villager.getWorld())) {
            cleanupDisplay(villager);
            return -1L;
        }
        if (isExcluded(villager)) {
            clearLifespanData(villager);
            return -1L;
        }
        long now = System.currentTimeMillis();
        Long currentEnd = villager.getPersistentDataContainer().get(lifespanEndKey, PersistentDataType.LONG);
        long baseEnd = currentEnd == null ? now : Math.max(now, currentEnd);
        long end = safeAdd(baseEnd, daysToMillis(days));
        villager.getPersistentDataContainer().set(lifespanEndKey, PersistentDataType.LONG, end);
        refreshDisplay(villager);
        return Math.max(0L, end - now);
    }

    public boolean hasLifespan(Villager villager) {
        if (!plugin.isWorldAllowed(villager.getWorld())) {
            return false;
        }
        if (isExcluded(villager)) {
            return false;
        }
        return villager.getPersistentDataContainer().has(lifespanEndKey, PersistentDataType.LONG);
    }

    public long remaining(Villager villager) {
        Long end = villager.getPersistentDataContainer().get(lifespanEndKey, PersistentDataType.LONG);
        return end == null ? -1L : Math.max(0L, end - System.currentTimeMillis());
    }

    public void cleanupDisplay(Villager villager) {
        visibleVillagers.remove(villager.getUniqueId());
        displayRenderer.cleanup(villager);
    }

    public LifespanScan scan() {
        int total = 0;
        int without = 0;
        for (var world : plugin.getServer().getWorlds()) {
            if (!plugin.isWorldAllowed(world)) {
                cleanupWorldDisplays(world);
                continue;
            }
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (isExcluded(villager)) {
                    clearLifespanData(villager);
                    continue;
                }
                total++;
                if (!hasLifespan(villager)) {
                    without++;
                }
            }
        }
        return new LifespanScan(total, total - without, without);
    }

    public int addMissingLifespan(int days) {
        int count = 0;
        for (var world : plugin.getServer().getWorlds()) {
            if (!plugin.isWorldAllowed(world)) {
                cleanupWorldDisplays(world);
                continue;
            }
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (isExcluded(villager)) {
                    clearLifespanData(villager);
                    continue;
                }
                if (!hasLifespan(villager)) {
                    setLifespan(villager, days);
                    count++;
                }
            }
        }
        return count;
    }

    public ArrayList<String> missingVillagerLines() {
        ArrayList<String> lines = new ArrayList<>();
        for (var world : plugin.getServer().getWorlds()) {
            if (!plugin.isWorldAllowed(world)) {
                cleanupWorldDisplays(world);
                continue;
            }
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (isExcluded(villager)) {
                    clearLifespanData(villager);
                    continue;
                }
                if (!hasLifespan(villager)) {
                    lines.add(villager.getUniqueId().toString().substring(0, 8) + "|" + world.getName() + "|" + villager.getLocation().getBlockX() + "|" + villager.getLocation().getBlockY() + "|" + villager.getLocation().getBlockZ());
                }
            }
        }
        return lines;
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Villager villager) {
                cleanupDisplay(villager);
            }
        }
    }

    private void checkExpirations() {
        if (!plugin.settings().lifespan().enabled()) {
            return;
        }
        boolean removedAny = false;
        for (var world : plugin.getServer().getWorlds()) {
            if (!plugin.isWorldAllowed(world)) {
                cleanupWorldDisplays(world);
                continue;
            }
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (isExcluded(villager)) {
                    clearLifespanData(villager);
                    continue;
                }
                if (hasLifespan(villager) && remaining(villager) <= 0) {
                    expireVillager(villager);
                    removedAny = true;
                }
            }
        }
        if (removedAny) {
            plugin.villagerTracker().initialize();
        }
    }

    private void updateDisplays() {
        if (!plugin.settings().lifespan().enabled()) {
            return;
        }
        for (var world : plugin.getServer().getWorlds()) {
            if (!plugin.isWorldAllowed(world)) {
                cleanupWorldDisplays(world);
                continue;
            }
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (isExcluded(villager)) {
                    clearLifespanData(villager);
                    continue;
                }
                if (hasLifespan(villager)) {
                    if (shouldShowDisplay(villager)) {
                        createOrUpdateDisplay(villager);
                    } else {
                        cleanupDisplay(villager);
                    }
                }
            }
        }
    }

    private void autoAddMissingLifespan() {
        if (!plugin.settings().lifespan().enabled() || !plugin.settings().lifespan().autoAddEnabled()) {
            return;
        }
        int added = addMissingLifespan(plugin.settings().lifespan().days());
        if (added > 0 || plugin.settings().debug()) {
            plugin.getLogger().info("[寿命系统] 自动补全村民寿命数量: " + added);
        }
    }

    private boolean isExcluded(Villager villager) {
        return plugin.compatibility().isExcludedFromLifespan(villager);
    }

    private void clearLifespanData(Villager villager) {
        cleanupDisplay(villager);
        villager.getPersistentDataContainer().remove(lifespanEndKey);
        villager.getPersistentDataContainer().remove(displayIdKey);
    }

    private void createOrUpdateDisplay(Villager villager) {
        if (!plugin.isWorldAllowed(villager.getWorld())) {
            cleanupDisplay(villager);
            return;
        }
        if (isExcluded(villager)) {
            clearLifespanData(villager);
            return;
        }
        LifespanDisplayText text = tagFormatter.format(plugin.language().components("lifespan.display-lines", formatValues(villager)));
        displayRenderer.createOrUpdate(villager, text);
        visibleVillagers.add(villager.getUniqueId());
    }

    private void refreshDisplay(Villager villager) {
        if (shouldShowDisplay(villager)) {
            createOrUpdateDisplay(villager);
        } else {
            cleanupDisplay(villager);
        }
    }

    private void expireVillager(Villager villager) {
        cleanupDisplay(villager);
        boolean zombified = plugin.settings().lifespan().zombifyOnExpire() && spawnZombieVillager(villager);
        notifyExpired(villager, zombified);
        villager.remove();
    }

    private boolean spawnZombieVillager(Villager villager) {
        try {
            ZombieVillager zombie = (ZombieVillager) villager.getWorld().spawnEntity(villager.getLocation(), EntityType.ZOMBIE_VILLAGER);
            copyZombieVillagerIdentity(villager, zombie);
            return true;
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Failed to spawn zombie villager for expired villager: " + ex.getMessage());
            return false;
        }
    }

    private void copyZombieVillagerIdentity(Villager villager, ZombieVillager zombie) {
        zombie.setVillagerProfession(villager.getProfession());
        zombie.setVillagerType(villager.getVillagerType());
        zombie.setBaby(!villager.isAdult());
        zombie.customName(villager.customName());
        zombie.setCustomNameVisible(villager.isCustomNameVisible());
        zombie.setPersistent(true);
        zombie.setRemoveWhenFarAway(false);
        for (String tag : villager.getScoreboardTags()) {
            zombie.addScoreboardTag(tag);
        }
    }

    private Map<String, ?> formatValues(Villager villager) {
        long seconds = remaining(villager) / 1000L;
        long minutes = seconds / 60L;
        long hours = minutes / 60L;
        long days = hours / 24L;
        return Map.ofEntries(
            Map.entry("time", plugin.language().formatDuration(Math.max(0L, remaining(villager)))),
            Map.entry("days", days),
            Map.entry("hours", hours % 24L),
            Map.entry("minutes", minutes % 60L),
            Map.entry("seconds", seconds % 60L),
            Map.entry("total_hours", hours),
            Map.entry("total_minutes", minutes),
            Map.entry("total_seconds", seconds),
            Map.entry("profession", villager.getProfession().name()),
            Map.entry("type", villager.getVillagerType().name()),
            Map.entry("uuid", villager.getUniqueId())
        );
    }

    private void notifyExpired(Villager villager, boolean zombified) {
        if (!plugin.settings().lifespan().notifyEnabled()) {
            return;
        }
        String key = zombified ? "lifespan.expired-zombified" : "lifespan.expired";
        int range = plugin.settings().lifespan().notifyRange();
        if (range <= 0) {
            plugin.getServer().getOnlinePlayers().forEach(player -> plugin.language().prefixed(player, key));
            return;
        }
        for (Player player : villager.getWorld().getNearbyPlayers(villager.getLocation(), range)) {
            plugin.language().prefixed(player, key);
        }
    }

    private void cleanupOrphanDisplays() {
        displayRenderer.cleanupOrphans();
    }

    private void tickDisplayRenderer() {
        displayRenderer.tick();
    }

    private void updateTargetedVillagers() {
        FotiaSettings.LifespanDisplayVisibilityMode mode = plugin.settings().lifespan().displayVisibilityMode();
        if (mode == FotiaSettings.LifespanDisplayVisibilityMode.ALWAYS) {
            targetedVillagers.clear();
            return;
        }
        Set<UUID> current = new HashSet<>();
        double range = plugin.settings().lifespan().displayLookAtRange();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!plugin.isWorldAllowed(player.getWorld())) {
                continue;
            }
            RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                range,
                0.3D,
                entity -> entity instanceof Villager villager && hasLifespan(villager) && player.hasLineOfSight(villager)
            );
            if (result != null && result.getHitEntity() instanceof Villager villager) {
                current.add(villager.getUniqueId());
            }
        }
        targetedVillagers.clear();
        targetedVillagers.addAll(current);
        updateDisplayVisibility();
    }

    private boolean shouldShowDisplay(Villager villager) {
        FotiaSettings.LifespanDisplayVisibilityMode mode = plugin.settings().lifespan().displayVisibilityMode();
        if (mode == FotiaSettings.LifespanDisplayVisibilityMode.ALWAYS) {
            return true;
        }
        if (mode == FotiaSettings.LifespanDisplayVisibilityMode.LOOK_AT_OR_LOW_LIFESPAN && isLowLifespan(villager)) {
            return true;
        }
        return targetedVillagers.contains(villager.getUniqueId());
    }

    private boolean isLowLifespan(Villager villager) {
        int threshold = plugin.settings().lifespan().displayLowLifespanAlwaysShowSeconds();
        return threshold > 0 && remaining(villager) <= threshold * 1000L;
    }

    private void updateDisplayVisibility() {
        for (var world : plugin.getServer().getWorlds()) {
            if (!plugin.isWorldAllowed(world)) {
                cleanupWorldDisplays(world);
                continue;
            }
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (isExcluded(villager)) {
                    clearLifespanData(villager);
                    continue;
                }
                if (!hasLifespan(villager)) {
                    continue;
                }
                boolean shouldShow = shouldShowDisplay(villager);
                boolean isVisible = visibleVillagers.contains(villager.getUniqueId());
                if (shouldShow && !isVisible) {
                    createOrUpdateDisplay(villager);
                } else if (!shouldShow && isVisible) {
                    cleanupDisplay(villager);
                }
            }
        }
    }

    private void cleanupWorldDisplays(org.bukkit.World world) {
        for (Villager villager : world.getEntitiesByClass(Villager.class)) {
            cleanupDisplay(villager);
        }
    }

    private LifespanDisplayRenderer createDisplayRenderer() {
        FotiaSettings.LifespanDisplayMode mode = plugin.settings().lifespan().displayMode();
        if (plugin.settings().lifespan().decentHologramsEnabled() || mode == FotiaSettings.LifespanDisplayMode.DECENT_HOLOGRAMS) {
            return createDecentHologramsDisplayRenderer();
        }
        if (mode == FotiaSettings.LifespanDisplayMode.ARMOR_STAND) {
            return new ArmorStandLifespanDisplayRenderer(plugin, displayIdKey, displayOwnerKey, displayHeightOffset());
        }
        return createTextDisplayOrFallback(mode == FotiaSettings.LifespanDisplayMode.TEXT_DISPLAY);
    }

    private LifespanDisplayRenderer createDecentHologramsDisplayRenderer() {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("DecentHolograms")) {
            plugin.getLogger().warning("villager-lifespan.display.mode is DECENT_HOLOGRAMS, but DecentHolograms is not enabled. Falling back to AUTO.");
            return createTextDisplayOrFallback(false);
        }
        try {
            return new DecentHologramsLifespanDisplayRenderer(plugin, displayIdKey, displayHeightOffset());
        } catch (LinkageError ex) {
            plugin.getLogger().warning("Failed to hook DecentHolograms API: " + ex.getMessage() + ". Falling back to AUTO.");
            return createTextDisplayOrFallback(false);
        }
    }

    private LifespanDisplayRenderer createTextDisplayOrFallback(boolean forcedTextDisplay) {
        if (supportsTextDisplay()) {
            return new TextDisplayLifespanDisplayRenderer(plugin, displayIdKey, displayOwnerKey, displayHeightOffset());
        }
        if (forcedTextDisplay) {
            plugin.getLogger().warning("villager-lifespan.display.mode is TEXT_DISPLAY, but this server does not support TextDisplay. Falling back to ARMOR_STAND.");
        }
        return new ArmorStandLifespanDisplayRenderer(plugin, displayIdKey, displayOwnerKey, displayHeightOffset());
    }

    private double displayHeightOffset() {
        return plugin.settings().lifespan().displayHeightOffset();
    }

    private LifespanTagFormatter createTagFormatter() {
        if (!plugin.settings().lifespan().customNameplatesEnabled()) {
            return LifespanDisplayText::plain;
        }
        if (!plugin.getServer().getPluginManager().isPluginEnabled("CustomNameplates")) {
            plugin.getLogger().warning("villager-lifespan.display.custom-nameplates.enabled is true, but CustomNameplates is not enabled. Falling back to plain lifespan tag text.");
            return LifespanDisplayText::plain;
        }
        try {
            return new CustomNameplatesLifespanTagFormatter(plugin);
        } catch (LinkageError ex) {
            plugin.getLogger().warning("Failed to hook CustomNameplates API: " + ex.getMessage() + ". Falling back to plain lifespan tag text.");
            return LifespanDisplayText::plain;
        }
    }

    private boolean supportsTextDisplay() {
        try {
            EntityType.valueOf("TEXT_DISPLAY");
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private long daysToMillis(int days) {
        return Math.max(1, days) * DAY_MILLIS;
    }

    private long safeAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    public record LifespanScan(int total, int withLifespan, int withoutLifespan) {}
}
