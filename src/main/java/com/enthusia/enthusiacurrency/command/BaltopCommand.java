package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.gui.BaltopHolder;
import com.enthusia.enthusiacurrency.skin.SkinCache;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import com.enthusia.enthusiacurrency.util.CurrencyUtils;
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

import java.util.*;
import java.util.stream.Collectors;

public class BaltopCommand implements CommandExecutor, TabCompleter {

    private final EnthusiaCurrencyPlugin plugin;

    public static final int PLAYERS_PER_PAGE = 45;
    public static final int PREV_SLOT = 45;
    public static final int SELF_SLOT = 49;
    public static final int NEXT_SLOT = 53;

    public BaltopCommand(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public static List<Map.Entry<UUID, Double>> buildEntries(EnthusiaCurrencyPlugin plugin) {
        BalanceStorage storage = plugin.getBalanceStorage();
        CurrencyManager currency = plugin.getCurrencyManager();

        Map<UUID, Double> baseBalances = storage.getAllBalancesSnapshot();

        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID uuid = online.getUniqueId();
            double bank = baseBalances.getOrDefault(uuid, storage.getBalance(uuid));
            int items = CurrencyUtils.countCurrencyInPlayer(currency, online);
            baseBalances.put(uuid, bank + items);
        }

        return baseBalances.entrySet().stream()
                .sorted((a, b) -> {
                    int cmp = Double.compare(b.getValue(), a.getValue());
                    if (cmp != 0) return cmp;
                    OfflinePlayer pa = Bukkit.getOfflinePlayer(a.getKey());
                    OfflinePlayer pb = Bukkit.getOfflinePlayer(b.getKey());
                    String na = pa.getName() == null ? "" : pa.getName();
                    String nb = pb.getName() == null ? "" : pb.getName();
                    return na.compareToIgnoreCase(nb);
                })
                .collect(Collectors.toList());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        int page = 1;
        if (args.length >= 1) {
            try {
                page = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }
        if (page <= 0) page = 1;

        List<Map.Entry<UUID, Double>> entries = buildEntries(plugin);

        if (entries.isEmpty()) {
            sender.sendMessage(plugin.getPrefix() + plugin.msgNoPrefix("baltop-no-data"));
            return true;
        }

        int perPageChat = plugin.getConfig().getInt("baltop.entries-per-page", 10);
        int pageCountChat = (int) Math.ceil(entries.size() / (double) perPageChat);
        if (pageCountChat <= 0) pageCountChat = 1;

        boolean guiEnabled = plugin.getConfig().getBoolean("baltop.gui.enabled", true);

        if (guiEnabled && sender instanceof Player player) {
            openGui(player, entries, page);
        } else {
            if (page > pageCountChat) page = pageCountChat;
            int start = (page - 1) * perPageChat;
            int end = Math.min(start + perPageChat, entries.size());
            List<Map.Entry<UUID, Double>> pageEntries = entries.subList(start, end);

            sendChat(sender, pageEntries, page);
        }

        return true;
    }

    private void sendChat(CommandSender sender, List<Map.Entry<UUID, Double>> pageEntries, int page) {
        String header = plugin.msgNoPrefix("baltop-header")
                .replace("%page%", String.valueOf(page));
        sender.sendMessage(plugin.getPrefix() + header);

        int perPageChat = plugin.getConfig().getInt("baltop.entries-per-page", 10);
        int startPos = (page - 1) * perPageChat + 1;

        String format = plugin.msgNoPrefix("baltop-entry");

        int index = 0;
        for (Map.Entry<UUID, Double> entry : pageEntries) {
            int pos = startPos + index++;
            OfflinePlayer op = Bukkit.getOfflinePlayer(entry.getKey());
            String name = op.getName() == null ? "Unknown" : op.getName();
            String line = format
                    .replace("%pos%", String.valueOf(pos))
                    .replace("%player%", name)
                    .replace("%amount%", String.format("%.0f", entry.getValue()))
                    .replace("%symbol%", plugin.getCurrencySymbol());
            sender.sendMessage(plugin.getPrefix() + line);
        }
    }

    public void openGui(Player player, List<Map.Entry<UUID, Double>> entries, int page) {
        boolean bedrock = plugin.isBedrock(player);
        SkinCache skinCache = plugin.getSkinCache();

        int perPageGui = PLAYERS_PER_PAGE;
        int invSize = 54;

        int pageCount = (int) Math.ceil(entries.size() / (double) perPageGui);
        if (pageCount <= 0) pageCount = 1;
        if (page > pageCount) page = pageCount;

        int start = (page - 1) * perPageGui;
        int end = Math.min(start + perPageGui, entries.size());
        List<Map.Entry<UUID, Double>> pageEntries = entries.subList(start, end);

        String titleRaw = plugin.getConfig().getString("baltop.gui.title", "&6Top Balances &7(Page %page%)")
                .replace("%page%", String.valueOf(page));
        String title = ChatColor.translateAlternateColorCodes('&', titleRaw);

        Inventory inv = Bukkit.createInventory(new BaltopHolder(page), invSize, title);

        int headSlots = perPageGui;
        int slot = 0;
        for (Map.Entry<UUID, Double> entry : pageEntries) {
            if (slot >= headSlots) break;

            UUID uuid = entry.getKey();
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            String name = op.getName() == null ? "Unknown" : op.getName();
            String displayName = ChatColor.YELLOW + name;

            ItemStack head;
            if (skinCache != null) {
                head = skinCache.createHead(uuid, displayName);
            } else {
                head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) head.getItemMeta();
                meta.setOwningPlayer(op);
                meta.setDisplayName(displayName);
                head.setItemMeta(meta);
            }

            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setLore(Collections.singletonList(
                    ChatColor.GRAY + "Balance: " + plugin.getCurrencySymbol() + String.format("%.0f", entry.getValue())
            ));
            head.setItemMeta(meta);

            inv.setItem(slot, head);
            slot++;
        }

        if (!bedrock) {
            ItemStack filler = makeItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " ");
            for (int i = headSlots; i < invSize; i++) {
                inv.setItem(i, filler);
            }
        }

        if (page > 1) {
            ItemStack prev = makeItem(Material.ARROW, ChatColor.YELLOW + "Previous Page");
            inv.setItem(PREV_SLOT, prev);
        }

        ItemStack self;
        if (skinCache != null) {
            self = skinCache.createHead(player.getUniqueId(), ChatColor.GOLD + "Your Balance");
        } else {
            self = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) self.getItemMeta();
            sm.setOwningPlayer(player);
            sm.setDisplayName(ChatColor.GOLD + "Your Balance");
            self.setItemMeta(sm);
        }

        SkullMeta sm = (SkullMeta) self.getItemMeta();
        double bank = plugin.getBalanceStorage().getBalance(player.getUniqueId());
        int items = CurrencyUtils.countCurrencyInPlayer(plugin.getCurrencyManager(), player);
        double total = bank + items;
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Total: " + plugin.getCurrencySymbol() + String.format("%.0f", total));
        lore.add(ChatColor.DARK_GRAY + "Bank: " + plugin.getCurrencySymbol() + String.format("%.0f", bank));
        lore.add(ChatColor.DARK_GRAY + "Items: " + plugin.getCurrencySymbol() + items);
        sm.setLore(lore);
        self.setItemMeta(sm);
        inv.setItem(SELF_SLOT, self);

        if (page < pageCount) {
            ItemStack next = makeItem(Material.ARROW, ChatColor.YELLOW + "Next Page");
            inv.setItem(NEXT_SLOT, next);
        }

        player.openInventory(inv);
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
