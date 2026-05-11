package gg.fotia.fotiavillage.gui;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.stats.PlayerTradeStats;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;
import java.util.Map;

public final class TopGui extends BaseGui {
    private final List<PlayerTradeStats> leaderboard;

    public TopGui(FotiaVillagePlugin plugin, Player viewer, List<PlayerTradeStats> leaderboard) {
        super(plugin, viewer);
        this.leaderboard = leaderboard;
    }

    @Override
    public Component title() {
        return Component.text(plugin.language().plain("gui.top-title"));
    }

    @Override
    public int size() {
        return 54;
    }

    @Override
    public void draw() {
        border(Material.YELLOW_STAINED_GLASS_PANE);
        inventory.setItem(4, item(Material.GOLD_BLOCK, Component.text(plugin.language().plain("gui.top-title"))));
        if (leaderboard.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, plugin.language().component("gui.top-empty")));
        } else {
            int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
            for (int i = 0; i < Math.min(slots.length, leaderboard.size()); i++) {
                PlayerTradeStats stats = leaderboard.get(i);
                int rank = i + 1;
                inventory.setItem(slots[i], item(rankMaterial(rank), Component.text("#" + rank + " " + stats.playerName()), plugin.language().component("leaderboard.line", Map.of("rank", rank, "player", stats.playerName(), "trades", stats.totalTrades())), plugin.language().component("stats.total-exp", Map.of("exp", stats.totalExpSpent()))));
            }
        }
        inventory.setItem(49, item(Material.BARRIER, plugin.language().component("gui.close")));
    }

    @Override
    public void click(InventoryClickEvent event) {
        if (event.getSlot() == 49) {
            viewer.closeInventory();
        }
    }

    private Material rankMaterial(int rank) {
        return switch (rank) {
            case 1 -> Material.GOLD_BLOCK;
            case 2 -> Material.IRON_BLOCK;
            case 3 -> Material.COPPER_BLOCK;
            default -> Material.PLAYER_HEAD;
        };
    }
}
