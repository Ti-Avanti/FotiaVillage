package gg.fotia.fotiavillage;

import gg.fotia.fotiavillage.command.FotiaCommand;
import gg.fotia.fotiavillage.config.ConfigService;
import gg.fotia.fotiavillage.config.FotiaSettings;
import gg.fotia.fotiavillage.database.DatabaseService;
import gg.fotia.fotiavillage.gui.GuiService;
import gg.fotia.fotiavillage.language.LanguageService;
import gg.fotia.fotiavillage.lifespan.LifespanDeathListener;
import gg.fotia.fotiavillage.lifespan.LifespanService;
import gg.fotia.fotiavillage.placeholder.FotiaPlaceholderExpansion;
import gg.fotia.fotiavillage.stats.StatsService;
import gg.fotia.fotiavillage.trade.CooldownService;
import gg.fotia.fotiavillage.trade.CostScalingService;
import gg.fotia.fotiavillage.trade.EconomyBalanceService;
import gg.fotia.fotiavillage.trade.PermissionGroupService;
import gg.fotia.fotiavillage.trade.TradeLimitService;
import gg.fotia.fotiavillage.trade.TradeService;
import gg.fotia.fotiavillage.update.UpdateCheckService;
import gg.fotia.fotiavillage.villager.VillagerControlListener;
import gg.fotia.fotiavillage.villager.VillagerTracker;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class FotiaVillagePlugin extends JavaPlugin {
    private ConfigService configService;
    private LanguageService languageService;
    private DatabaseService databaseService;
    private PerformanceService performanceService;
    private VillagerTracker villagerTracker;
    private LifespanService lifespanService;
    private PermissionGroupService permissionGroupService;
    private EconomyBalanceService economyBalanceService;
    private TradeLimitService tradeLimitService;
    private CooldownService cooldownService;
    private CostScalingService costScalingService;
    private StatsService statsService;
    private GuiService guiService;
    private UpdateCheckService updateCheckService;
    private FotiaPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        configService = new ConfigService(this);
        configService.load();
        languageService = new LanguageService(this);
        languageService.load();
        databaseService = new DatabaseService(this);
        databaseService.open();
        performanceService = new PerformanceService(this);
        villagerTracker = new VillagerTracker(this);
        lifespanService = new LifespanService(this);
        permissionGroupService = new PermissionGroupService(this);
        economyBalanceService = new EconomyBalanceService(this);
        tradeLimitService = new TradeLimitService(this, permissionGroupService);
        cooldownService = new CooldownService(this, permissionGroupService);
        costScalingService = new CostScalingService(this);
        statsService = new StatsService(this);
        guiService = new GuiService(this);
        updateCheckService = new UpdateCheckService(this);

        villagerTracker.initialize();
        registerEvents();
        registerCommand();
        syncPlaceholder();
        lifespanService.start();
        performanceService.start();
        updateCheckService.checkAsync();
        getLogger().info("FotiaVillage 已启用。语言: " + settings().language() + ", 数据库: " + (database().isConnected() ? "已连接" : "未连接"));
    }

    @Override
    public void onDisable() {
        if (lifespanService != null) lifespanService.stop();
        if (performanceService != null) performanceService.stop();
        if (placeholderExpansion != null) placeholderExpansion.unregister();
        if (databaseService != null) databaseService.close();
        getLogger().info("FotiaVillage 已关闭。");
    }

    public void reloadRuntime() {
        configService.load();
        languageService.load();
        villagerTracker.initialize();
        lifespanService.start();
        performanceService.start();
        syncPlaceholder();
        updateCheckService.checkAsync();
    }

    public FotiaSettings settings() {
        return configService.settings();
    }

    public LanguageService language() {
        return languageService;
    }

    public DatabaseService database() {
        return databaseService;
    }

    public PerformanceService performance() {
        return performanceService;
    }

    public VillagerTracker villagerTracker() {
        return villagerTracker;
    }

    public LifespanService lifespan() {
        return lifespanService;
    }

    public PermissionGroupService permissionGroups() {
        return permissionGroupService;
    }

    public TradeLimitService tradeLimits() {
        return tradeLimitService;
    }

    public StatsService stats() {
        return statsService;
    }

    public GuiService gui() {
        return guiService;
    }

    private void registerEvents() {
        getServer().getPluginManager().registerEvents(villagerTracker, this);
        getServer().getPluginManager().registerEvents(new VillagerControlListener(this), this);
        getServer().getPluginManager().registerEvents(lifespanService, this);
        getServer().getPluginManager().registerEvents(new LifespanDeathListener(this), this);
        getServer().getPluginManager().registerEvents(new TradeService(this, permissionGroupService, economyBalanceService, tradeLimitService, cooldownService, costScalingService), this);
        getServer().getPluginManager().registerEvents(guiService, this);
    }

    private void registerCommand() {
        PluginCommand command = getCommand("fv");
        if (command == null) {
            throw new IllegalStateException("Command /fv is missing from plugin.yml");
        }
        FotiaCommand executor = new FotiaCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void syncPlaceholder() {
        if (!settings().placeholder().enabled()) {
            if (placeholderExpansion != null) {
                placeholderExpansion.unregister();
                placeholderExpansion = null;
                getLogger().info("PlaceholderAPI 扩展已注销。");
            }
            return;
        }
        if (placeholderExpansion != null || getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        placeholderExpansion = new FotiaPlaceholderExpansion(this);
        placeholderExpansion.register();
        getLogger().info("PlaceholderAPI 扩展已注册。");
    }
}
