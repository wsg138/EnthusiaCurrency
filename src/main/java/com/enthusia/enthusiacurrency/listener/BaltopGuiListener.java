package com.enthusia.enthusiacurrency.listener;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.command.BaltopCommand;
import com.enthusia.enthusiacurrency.gui.BaltopHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BaltopGuiListener implements Listener {

    private final EnthusiaCurrencyPlugin plugin;

    public BaltopGuiListener(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        InventoryView view = event.getView();
        InventoryHolder holder = view.getTopInventory().getHolder();

        if (!(holder instanceof BaltopHolder baltopHolder)) {
            return;
        }

        event.setCancelled(true);

        int rawSlot = event.getRawSlot();
        int topSize = view.getTopInventory().getSize();

        if (rawSlot < 0 || rawSlot >= topSize) {
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        int slot = rawSlot;

        boolean isPrevArrow = (slot == BaltopCommand.PREV_SLOT && clicked.getType() == Material.ARROW);
        boolean isNextArrow = (slot == BaltopCommand.NEXT_SLOT && clicked.getType() == Material.ARROW);

        if (!isPrevArrow && !isNextArrow) {
            return;
        }

        int currentPage = baltopHolder.getPage();
        int delta = isPrevArrow ? -1 : 1;
        int newPage = currentPage + delta;
        if (newPage < 1) newPage = 1;

        List<Map.Entry<UUID, Double>> entries = BaltopCommand.buildEntries(plugin);
        if (entries.isEmpty()) {
            player.closeInventory();
            return;
        }

        int perPageGui = BaltopCommand.PLAYERS_PER_PAGE;
        int pageCount = (int) Math.ceil(entries.size() / (double) perPageGui);
        if (pageCount <= 0) pageCount = 1;
        if (newPage > pageCount) newPage = pageCount;

        BaltopCommand cmd = new BaltopCommand(plugin);
        cmd.openGui(player, entries, newPage);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        InventoryView view = event.getView();
        InventoryHolder holder = view.getTopInventory().getHolder();

        if (!(holder instanceof BaltopHolder)) {
            return;
        }

        event.setCancelled(true);
    }
}
