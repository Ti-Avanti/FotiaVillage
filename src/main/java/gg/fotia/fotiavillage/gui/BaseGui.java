package gg.fotia.fotiavillage.gui;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public abstract class BaseGui {
    protected final FotiaVillagePlugin plugin;
    protected final Player viewer;
    protected Inventory inventory;

    protected BaseGui(FotiaVillagePlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
    }

    public abstract Component title();
    public abstract int size();
    public abstract void draw();
    public abstract void click(InventoryClickEvent event);

    public Inventory create() {
        inventory = Bukkit.createInventory(null, size(), title());
        draw();
        return inventory;
    }

    protected ItemStack item(Material material, Component name, Component... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(name));
            if (lore.length > 0) {
                meta.lore(List.of(lore).stream().map(this::noItalic).toList());
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private Component noItalic(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    protected void border(Material material) {
        ItemStack pane = item(material, Component.empty());
        for (int i = 0; i < 9; i++) {
            inventory.setItem(i, pane);
            inventory.setItem(size() - 9 + i, pane);
        }
        for (int i = 9; i < size() - 9; i += 9) {
            inventory.setItem(i, pane);
            inventory.setItem(i + 8, pane);
        }
    }
}
