package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.gui.BaltopHolder;
import com.enthusia.enthusiacurrency.service.CurrencyService;
import com.enthusia.enthusiacurrency.skin.SkinCache;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BaltopCommand implements CommandExecutor, TabCompleter {

    public static final int PLAYERS_PER_PAGE = 45;
    public static final int PREV_SLOT = 45;
    public static final int SELF_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    private final EnthusiaCurrencyPlugin plugin;

    public BaltopCommand(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public static List<Map.Entry<UUID, Long>> buildEntries(EnthusiaCurrencyPlugin plugin) {
        CurrencyService currencyService = plugin.getCurrencyService();
        Map<UUID, Long> totals = new HashMap<>(currencyService.getBankSnapshot());

        for (Player online : Bukkit.getOnlinePlayers()) {
            CurrencyService.BalanceView balanceView = currencyService.getBalanceView(online);
            totals.put(online.getUniqueId(), balanceView.total());
        }

        List<Map.Entry<UUID, Long>> entries = new ArrayList<>(totals.entrySet());
        entries.sort((left, right) -> {
            int amountCompare = Long.compare(right.getValue(), left.getValue());
            if (amountCompare != 0) {
                return amountCompare;
            }

            OfflinePlayer leftPlayer = Bukkit.getOfflinePlayer(left.getKey());
            OfflinePlayer rightPlayer = Bukkit.getOfflinePlayer(right.getKey());
            String leftName = leftPlayer.getName() == null ? "" : leftPlayer.getName();
            String rightName = rightPlayer.getName() == null ? "" : rightPlayer.getName();
            return leftName.compareToIgnoreCase(rightName);
        });
        return entries;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        int page = 1;
        if (args.length >= 1) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }
        if (page <= 0) {
            page = 1;
        }

        List<Map.Entry<UUID, Long>> entries = plugin.getBaltopTracker().getEntriesForDisplay();
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.getPrefix() + plugin.msgNoPrefix("baltop-no-data"));
            return true;
        }

        int perPageChat = plugin.getConfig().getInt("baltop.entries-per-page", 10);
        int pageCountChat = Math.max(1, (int) Math.ceil(entries.size() / (double) perPageChat));
        boolean guiEnabled = plugin.getConfig().getBoolean("baltop.gui.enabled", true);

        if (guiEnabled && sender instanceof Player player) {
            openGui(player, entries, page);
            return true;
        }

        page = Math.min(page, pageCountChat);
        int start = (page - 1) * perPageChat;
        int end = Math.min(start + perPageChat, entries.size());
        sendChat(sender, entries.subList(start, end), page);
        return true;
    }

    private void sendChat(CommandSender sender, List<Map.Entry<UUID, Long>> pageEntries, int page) {
        String header = plugin.msgNoPrefix("baltop-header").replace("%page%", String.valueOf(page));
        sender.sendMessage(plugin.getPrefix() + header);

        int perPageChat = plugin.getConfig().getInt("baltop.entries-per-page", 10);
        int startPos = (page - 1) * perPageChat + 1;
        String format = plugin.msgNoPrefix("baltop-entry");

        int offset = 0;
        for (Map.Entry<UUID, Long> entry : pageEntries) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(entry.getKey());
            String line = format
                    .replace("%pos%", String.valueOf(startPos + offset++))
                    .replace("%player%", offlinePlayer.getName() == null ? "Unknown" : offlinePlayer.getName())
                    .replace("%amount%", String.valueOf(entry.getValue()))
                    .replace("%symbol%", plugin.getCurrencySymbol());
            sender.sendMessage(plugin.getPrefix() + line);
        }
    }

    public void openGui(Player player, List<Map.Entry<UUID, Long>> entries, int page) {
        boolean bedrock = plugin.isBedrock(player);
        SkinCache skinCache = plugin.getSkinCache();
        int pageCount = Math.max(1, (int) Math.ceil(entries.size() / (double) PLAYERS_PER_PAGE));
        page = Math.min(page, pageCount);

        int start = (page - 1) * PLAYERS_PER_PAGE;
        int end = Math.min(start + PLAYERS_PER_PAGE, entries.size());
        List<Map.Entry<UUID, Long>> pageEntries = entries.subList(start, end);

        String titleRaw = plugin.getConfig().getString("baltop.gui.title", "&6Top Balances &7(Page %page%)")
                .replace("%page%", String.valueOf(page));
        String title = ChatColor.translateAlternateColorCodes('&', titleRaw);
        Inventory inventory = Bukkit.createInventory(new BaltopHolder(page), 54, title);

        int slot = 0;
        for (Map.Entry<UUID, Long> entry : pageEntries) {
            UUID uuid = entry.getKey();
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
            String displayName = ChatColor.YELLOW + (offlinePlayer.getName() == null ? "Unknown" : offlinePlayer.getName());

            ItemStack head;
            if (skinCache != null) {
                head = skinCache.createHead(uuid, displayName);
            } else {
                head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setOwningPlayer(offlinePlayer);
                meta.setDisplayName(displayName);
                head.setItemMeta(meta);
            }

            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setLore(Collections.singletonList(ChatColor.GRAY + "Balance: " + plugin.getCurrencySymbol() + entry.getValue()));
            head.setItemMeta(meta);
            inventory.setItem(slot++, head);
        }

        if (!bedrock) {
            ItemStack filler = makeItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " ");
            for (int index = PLAYERS_PER_PAGE; index < 54; index++) {
                inventory.setItem(index, filler);
            }
        }

        if (page > 1) {
            inventory.setItem(PREV_SLOT, makeItem(Material.ARROW, ChatColor.YELLOW + "Previous Page"));
        }

        CurrencyService.BalanceView selfBalance = plugin.getCurrencyService().getBalanceView(player);
        ItemStack selfHead;
        if (skinCache != null) {
            selfHead = skinCache.createHead(player.getUniqueId(), ChatColor.GOLD + "Your Balance");
        } else {
            selfHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) selfHead.getItemMeta();
            meta.setOwningPlayer(player);
            meta.setDisplayName(ChatColor.GOLD + "Your Balance");
            selfHead.setItemMeta(meta);
        }

        SkullMeta selfMeta = (SkullMeta) selfHead.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Total: " + plugin.getCurrencySymbol() + selfBalance.total());
        lore.add(ChatColor.DARK_GRAY + "Bank: " + plugin.getCurrencySymbol() + selfBalance.bank());
        lore.add(ChatColor.DARK_GRAY + "Items: " + plugin.getCurrencySymbol() + selfBalance.items());
        selfMeta.setLore(lore);
        selfHead.setItemMeta(selfMeta);
        inventory.setItem(SELF_SLOT, selfHead);

        if (page < pageCount) {
            inventory.setItem(NEXT_SLOT, makeItem(Material.ARROW, ChatColor.YELLOW + "Next Page"));
        }

        player.openInventory(inventory);
    }

    private ItemStack makeItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("1", "2", "3", "4", "5");
        }
        return List.of();
    }
}
