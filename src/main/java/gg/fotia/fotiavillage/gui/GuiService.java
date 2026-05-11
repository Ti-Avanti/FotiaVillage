package gg.fotia.fotiavillage.gui;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class GuiService implements Listener {
    private final FotiaVillagePlugin plugin;
    private final Map<UUID, BaseGui> open = new HashMap<>();

    public GuiService(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player, BaseGui gui) {
        if (!plugin.settings().gui().enabled()) {
            return;
        }
        player.openInventory(gui.create());
        open.put(player.getUniqueId(), gui);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        BaseGui gui = open.get(player.getUniqueId());
        if (gui == null) {
            return;
        }
        event.setCancelled(true);
        gui.click(event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        open.remove(event.getPlayer().getUniqueId());
    }
}
