package gg.fotia.fotiavillage.gui;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.stats.PlayerTradeStats;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

public final class StatsGui extends BaseGui {
    private final PlayerTradeStats stats;

    public StatsGui(FotiaVillagePlugin plugin, Player viewer, PlayerTradeStats stats) {
        super(plugin, viewer);
        this.stats = stats;
    }

    @Override
    public Component title() {
        return Component.text(plugin.language().plain("gui.stats-title", Map.of("player", stats.playerName())));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void draw() {
        border(Material.GRAY_STAINED_GLASS_PANE);
        inventory.setItem(13, item(Material.PLAYER_HEAD, Component.text(stats.playerName()), plugin.language().component("stats.total-trades", Map.of("trades", stats.totalTrades())), plugin.language().component("stats.total-exp", Map.of("exp", stats.totalExpSpent()))));
        inventory.setItem(20, item(Material.EMERALD, plugin.language().component("gui.total-trades"), Component.text(String.valueOf(stats.totalTrades()))));
        inventory.setItem(22, item(Material.EXPERIENCE_BOTTLE, plugin.language().component("gui.total-exp"), Component.text(String.valueOf(stats.totalExpSpent()))));
        inventory.setItem(24, item(Material.CLOCK, plugin.language().component("gui.last-trade"), Component.text(formatLastTrade())));

        ArrayList<Map.Entry<String, Integer>> items = new ArrayList<>(stats.itemCounts().entrySet());
        items.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
        int[] slots = {28, 29, 30, 31, 32, 33, 34};
        for (int i = 0; i < Math.min(slots.length, items.size()); i++) {
            Map.Entry<String, Integer> entry = items.get(i);
            Material material = Material.matchMaterial(entry.getKey());
            inventory.setItem(slots[i], item(material == null ? Material.PAPER : material, Component.text(entry.getKey()), plugin.language().component("stats.item-line", Map.of("item", entry.getKey(), "count", entry.getValue()))));
        }
        inventory.setItem(49, item(Material.BARRIER, plugin.language().component("gui.close")));
    }

    @Override
    public void click(InventoryClickEvent event) {
        if (event.getSlot() == 49) {
            viewer.closeInventory();
        }
    }

    private String formatLastTrade() {
        return plugin.language().formatAgo(stats.lastTradeTime());
    }
}
