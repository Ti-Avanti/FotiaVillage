package gg.fotia.fotiavillage;

import gg.fotia.fotiavillage.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.text.DecimalFormat;

public final class PerformanceService {
    private final FotiaVillagePlugin plugin;
    private final long startedAt = System.currentTimeMillis();
    private BukkitTask reportTask;
    private BukkitTask cleanupTask;

    public PerformanceService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        var settings = plugin.settings().performance();
        if (settings.autoReport() && settings.reportInterval() > 0) {
            reportTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::reportIfNeeded, 20L * settings.reportInterval(), 20L * settings.reportInterval());
        }
        if (settings.cleanupExpiredInterval() > 0) {
            cleanupTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::cleanupExpiredData, 20L * settings.cleanupExpiredInterval(), 20L * settings.cleanupExpiredInterval());
        }
    }

    public void stop() {
        if (reportTask != null) reportTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();
        reportTask = null;
        cleanupTask = null;
    }

    public String tps() {
        return new DecimalFormat("0.00").format(Math.min(20.0, Bukkit.getTPS()[0]));
    }

    public long usedMemoryMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024L / 1024L;
    }

    public long maxMemoryMb() {
        return Runtime.getRuntime().maxMemory() / 1024L / 1024L;
    }

    public String uptime() {
        return plugin.language().formatDuration(System.currentTimeMillis() - startedAt);
    }

    public void cleanupExpiredData() {
        plugin.database().cleanupExpired(System.currentTimeMillis(), TimeUtil.resetKey(plugin.settings().tradeControl().limit().resetPeriod()));
    }

    private void reportIfNeeded() {
        double currentTps = Bukkit.getTPS()[0];
        if (currentTps < plugin.settings().performance().lowTpsWarning()) {
            plugin.getLogger().warning("服务器 TPS 较低: " + new DecimalFormat("0.00").format(currentTps));
        }
        double memoryPercent = maxMemoryMb() <= 0 ? 0.0 : usedMemoryMb() * 100.0 / maxMemoryMb();
        if (memoryPercent > 90.0) {
            plugin.getLogger().warning("内存使用较高: " + new DecimalFormat("0.00").format(memoryPercent) + "%");
        }
    }
}
