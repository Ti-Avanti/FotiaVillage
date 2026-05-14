package gg.fotia.fotiavillage.lifespan;

import gg.fotia.fotiavillage.FotiaVillagePlugin;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.Optional;

public final class LifespanItemListener implements Listener {
    private final FotiaVillagePlugin plugin;

    public LifespanItemListener(FotiaVillagePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onUseLifespanItem(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof Villager villager)) {
            return;
        }

        Player player = event.getPlayer();
        if (plugin.lifespanItems().requireSneaking() && !player.isSneaking()) {
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<LifespanItemService.LifespanItem> match = plugin.lifespanItems().findMatchingItem(hand);
        if (match.isEmpty()) {
            return;
        }

        event.setCancelled(true);
        LifespanItemService.LifespanItem item = match.get();
        if (!plugin.settings().lifespan().enabled()) {
            plugin.language().prefixed(player, "lifespan.disabled");
            return;
        }
        if (!item.hasPermission(player)) {
            plugin.language().prefixed(player, "lifespan.item-no-permission");
            return;
        }
        if (!item.matchesTarget(villager)) {
            plugin.language().prefixed(player, "lifespan.item-wrong-target");
            return;
        }

        long remaining = plugin.lifespan().addLifespan(villager, item.days());
        if (remaining < 0L) {
            plugin.language().prefixed(player, "lifespan.item-excluded");
            return;
        }

        consumeItem(player, hand, item);
        plugin.language().prefixed(player, "lifespan.item-success", Map.of(
            "item", item.id(),
            "days", item.days(),
            "time", plugin.language().formatDuration(remaining)
        ));
    }

    private void consumeItem(Player player, ItemStack hand, LifespanItemService.LifespanItem item) {
        if (!item.consume()) {
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE && !item.consumeInCreative()) {
            return;
        }
        int newAmount = hand.getAmount() - item.consumeAmount();
        if (newAmount <= 0) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        hand.setAmount(newAmount);
        player.getInventory().setItemInMainHand(hand);
    }
}
