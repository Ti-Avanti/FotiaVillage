package gg.fotia.fotiavillage.gui;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import gg.fotia.fotiavillage.stats.PlayerTradeStats;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TopGui extends BaseGui {
    private static final List<Integer> CONTENT_SLOTS = contentSlots();
    private final List<PlayerTradeStats> leaderboard;
    private final int page;

    public TopGui(FotiaVillagePlugin plugin, Player viewer, List<PlayerTradeStats> leaderboard) {
        this(plugin, viewer, leaderboard, 0);
    }

    private TopGui(FotiaVillagePlugin plugin, Player viewer, List<PlayerTradeStats> leaderboard, int page) {
        super(plugin, viewer);
        this.leaderboard = leaderboard;
        this.page = Math.max(0, page);
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
        inventory.setItem(4, item(Material.GOLD_BLOCK, Component.text(plugin.language().plain("gui.top-title")), plugin.language().component("gui.page", Map.of("page", page + 1, "pages", pages()))));
        if (leaderboard.isEmpty()) {
            inventory.setItem(22, item(Material.BARRIER, plugin.language().component("gui.top-empty")));
        } else {
            int start = page * CONTENT_SLOTS.size();
            int end = Math.min(start + CONTENT_SLOTS.size(), leaderboard.size());
            for (int index = start; index < end; index++) {
                PlayerTradeStats stats = leaderboard.get(index);
                int rank = index + 1;
                int slot = CONTENT_SLOTS.get(index - start);
                inventory.setItem(slot, item(rankMaterial(rank), Component.text("#" + rank + " " + stats.playerName()), plugin.language().component("leaderboard.line", Map.of("rank", rank, "player", stats.playerName(), "trades", stats.totalTrades())), plugin.language().component("stats.total-exp", Map.of("exp", stats.totalExpSpent()))));
            }
        }
        if (page > 0) {
            inventory.setItem(45, item(Material.ARROW, plugin.language().component("gui.previous-page")));
        }
        if ((page + 1) * CONTENT_SLOTS.size() < leaderboard.size()) {
            inventory.setItem(53, item(Material.ARROW, plugin.language().component("gui.next-page")));
        }
        inventory.setItem(49, item(Material.BARRIER, plugin.language().component("gui.close")));
    }

    @Override
    public void click(InventoryClickEvent event) {
        if (event.getSlot() == 49) {
            viewer.closeInventory();
            return;
        }
        if (event.getSlot() == 45 && page > 0) {
            plugin.gui().open(viewer, new TopGui(plugin, viewer, leaderboard, page - 1));
            return;
        }
        if (event.getSlot() == 53 && (page + 1) * CONTENT_SLOTS.size() < leaderboard.size()) {
            plugin.gui().open(viewer, new TopGui(plugin, viewer, leaderboard, page + 1));
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

    private int pages() {
        return Math.max(1, (int) Math.ceil(leaderboard.size() / (double) CONTENT_SLOTS.size()));
    }

    private static List<Integer> contentSlots() {
        ArrayList<Integer> slots = new ArrayList<>();
        for (int row = 1; row <= 4; row++) {
            for (int column = 1; column <= 7; column++) {
                slots.add(row * 9 + column);
            }
        }
        return List.copyOf(slots);
    }
}
