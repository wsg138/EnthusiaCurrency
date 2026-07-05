package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.gui.BaltopHolder;
import com.enthusia.enthusiacurrency.service.CurrencyService;
import com.enthusia.enthusiacurrency.skin.SkinCache;
import com.enthusia.enthusiacurrency.storage.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BaltopCommand implements CommandExecutor, TabCompleter {

    public static final int PLAYERS_PER_PAGE = 45;
    public static final int PREV_SLOT = 45;
    public static final int SELF_SLOT = 49;
    public static final int NEXT_SLOT = 53;
    private static final int FIRST_PAGE = 1;
    private static final int GUI_SIZE = 54;
    private static final int PAGE_ARG_COUNT = 1;
    private static final String UNKNOWN_PLAYER = "Unknown";

    private final EnthusiaCurrencyPlugin plugin;

    public BaltopCommand(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public static List<Map.Entry<UUID, Long>> buildEntries(EnthusiaCurrencyPlugin plugin) {
        CurrencyService currencyService = plugin.getCurrencyService();
        Map<UUID, Long> totals = new ConcurrentHashMap<>(currencyService.getBankSnapshot());

        for (Player online : Bukkit.getOnlinePlayers()) {
            CurrencyService.BalanceView balanceView = currencyService.getCachedBalanceView(online);
            totals.put(online.getUniqueId(), balanceView.total());
        }

        Map<UUID, PlayerProfile> profiles = plugin.getPlayerProfileStorage().getAllProfilesSnapshot();
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>(totals.entrySet());
        entries.sort((left, right) -> {
            int amountCompare = Long.compare(right.getValue(), left.getValue());
            if (amountCompare != 0) {
                return amountCompare;
            }

            String leftName = profileName(profiles, left.getKey());
            String rightName = profileName(profiles, right.getKey());
            return leftName.compareToIgnoreCase(rightName);
        });
        return entries;
    }

    private static String profileName(Map<UUID, PlayerProfile> profiles, UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        if (profile != null && profile.username() != null) {
            return profile.username();
        }
        return "";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        int page = parsePage(args);

        List<Map.Entry<UUID, Long>> entries = plugin.getBaltopTracker().getEntriesForDisplay();
        if (entries.isEmpty()) {
            sender.sendMessage(plugin.getPrefix() + plugin.msgNoPrefix("baltop-no-data"));
            return true;
        }

        int perPageChat = plugin.getConfig().getInt("baltop.entries-per-page", 10);
        int pageCountChat = Math.max(FIRST_PAGE, (int) Math.ceil(entries.size() / (double) perPageChat));
        boolean guiEnabled = plugin.getConfig().getBoolean("baltop.gui.enabled", true);

        if (guiEnabled && sender instanceof Player player) {
            openGui(player, entries, page);
            return true;
        }

        int chatPage = Math.min(page, pageCountChat);
        int start = (chatPage - FIRST_PAGE) * perPageChat;
        int end = Math.min(start + perPageChat, entries.size());
        sendChat(sender, entries.subList(start, end), chatPage);
        return true;
    }

    private int parsePage(String[] args) {
        if (args.length < PAGE_ARG_COUNT) {
            return FIRST_PAGE;
        }
        try {
            return Math.max(FIRST_PAGE, Integer.parseInt(args[0]));
        } catch (NumberFormatException ignored) {
            return FIRST_PAGE;
        }
    }

    private void sendChat(CommandSender sender, List<Map.Entry<UUID, Long>> pageEntries, int page) {
        String header = plugin.msgNoPrefix("baltop-header").replace("%page%", String.valueOf(page));
        sender.sendMessage(plugin.getPrefix() + header);

        int perPageChat = plugin.getConfig().getInt("baltop.entries-per-page", 10);
        int startPos = (page - FIRST_PAGE) * perPageChat + FIRST_PAGE;
        String format = plugin.msgNoPrefix("baltop-entry");

        int offset = 0;
        for (Map.Entry<UUID, Long> entry : pageEntries) {
            PlayerProfile profile = plugin.getPlayerProfileStorage().getProfile(entry.getKey());
            String playerName = profile == null || profile.username() == null ? UNKNOWN_PLAYER : profile.username();
            String line = format
                    .replace("%pos%", String.valueOf(startPos + offset++))
                    .replace("%player%", playerName)
                    .replace("%amount%", String.valueOf(entry.getValue()))
                    .replace("%symbol%", plugin.getCurrencySymbol());
            sender.sendMessage(plugin.getPrefix() + line);
        }
    }

    public void openGui(Player player, List<Map.Entry<UUID, Long>> entries, int page) {
        boolean bedrock = plugin.isBedrock(player);
        SkinCache skinCache = plugin.getSkinCache();
        int pageCount = Math.max(FIRST_PAGE, (int) Math.ceil(entries.size() / (double) PLAYERS_PER_PAGE));
        int displayPage = Math.min(page, pageCount);

        int start = (displayPage - FIRST_PAGE) * PLAYERS_PER_PAGE;
        int end = Math.min(start + PLAYERS_PER_PAGE, entries.size());
        List<Map.Entry<UUID, Long>> pageEntries = entries.subList(start, end);

        String titleRaw = plugin.getConfig().getString("baltop.gui.title", "&6Top Balances &7(Page %page%)")
                .replace("%page%", String.valueOf(displayPage));
        String title = ChatColor.translateAlternateColorCodes('&', titleRaw);
        Inventory inventory = Bukkit.createInventory(new BaltopHolder(displayPage), GUI_SIZE, title);

        addLeaderboardHeads(inventory, pageEntries, skinCache);
        addFiller(inventory, bedrock);

        if (displayPage > FIRST_PAGE) {
            inventory.setItem(PREV_SLOT, makeItem(Material.ARROW, ChatColor.YELLOW + "Previous Page"));
        }

        inventory.setItem(SELF_SLOT, selfHead(player, skinCache));

        if (displayPage < pageCount) {
            inventory.setItem(NEXT_SLOT, makeItem(Material.ARROW, ChatColor.YELLOW + "Next Page"));
        }

        player.openInventory(inventory);
    }

    private void addLeaderboardHeads(Inventory inventory, List<Map.Entry<UUID, Long>> pageEntries, SkinCache skinCache) {
        int slot = 0;
        for (Map.Entry<UUID, Long> entry : pageEntries) {
            inventory.setItem(slot++, leaderboardHead(entry, skinCache));
        }
    }

    private ItemStack leaderboardHead(Map.Entry<UUID, Long> entry, SkinCache skinCache) {
        UUID uuid = entry.getKey();
        PlayerProfile profile = plugin.getPlayerProfileStorage().getProfile(uuid);
        String playerName = profile == null || profile.username() == null ? UNKNOWN_PLAYER : profile.username();
        String displayName = ChatColor.YELLOW + playerName;
        ItemStack head = playerHead(uuid, displayName, skinCache);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setLore(Collections.singletonList(ChatColor.GRAY + "Balance: " + plugin.getCurrencySymbol() + entry.getValue()));
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack playerHead(UUID uuid, String displayName, SkinCache skinCache) {
        if (skinCache != null) {
            return skinCache.createHead(uuid, displayName);
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setDisplayName(displayName);
        head.setItemMeta(meta);
        return head;
    }

    private void addFiller(Inventory inventory, boolean bedrock) {
        if (bedrock) {
            return;
        }
        ItemStack filler = makeItem(Material.GRAY_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " ");
        for (int index = PLAYERS_PER_PAGE; index < GUI_SIZE; index++) {
            inventory.setItem(index, filler);
        }
    }

    private ItemStack selfHead(Player player, SkinCache skinCache) {
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

        CurrencyService.BalanceView selfBalance = plugin.getCurrencyService().getCachedBalanceView(player);
        SkullMeta selfMeta = (SkullMeta) selfHead.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Total: " + plugin.getCurrencySymbol() + selfBalance.total());
        lore.add(ChatColor.DARK_GRAY + "Bank: " + plugin.getCurrencySymbol() + selfBalance.bank());
        lore.add(ChatColor.DARK_GRAY + "Items: " + plugin.getCurrencySymbol() + selfBalance.items());
        selfMeta.setLore(lore);
        selfHead.setItemMeta(selfMeta);
        return selfHead;
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
        if (args.length == PAGE_ARG_COUNT) {
            return Arrays.asList("1", "2", "3", "4", "5");
        }
        return List.of();
    }
}
