package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import com.enthusia.enthusiacurrency.util.CurrencyUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public class BalanceCommand implements CommandExecutor {

    private final EnthusiaCurrencyPlugin plugin;

    public BalanceCommand(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.sendMsg(sender, "player-only");
                return true;
            }
            sendBalance(player, player);
            return true;
        }

        if (!sender.hasPermission("currency.balance.others")) {
            plugin.sendMsg(sender, "no-permission");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) {
            plugin.sendMsg(sender, "player-not-found");
            return true;
        }

        sendBalance(sender, target);
        return true;
    }

    private void sendBalance(CommandSender viewer, OfflinePlayer target) {
        BalanceStorage storage = plugin.getBalanceStorage();
        CurrencyManager currencyManager = plugin.getCurrencyManager();

        double bank = storage.getBalance(target.getUniqueId());
        double total = bank;
        int items = 0;

        if (target.isOnline()) {
            Player p = target.getPlayer();
            items = CurrencyUtils.countCurrencyInPlayer(currencyManager, p);
            total += items;
        }

        String msgKey = (viewer instanceof Player && viewer.getName().equalsIgnoreCase(target.getName()))
                ? "balance-self"
                : "balance-other";

        String raw = plugin.msgNoPrefix(msgKey)
                .replace("%total%", String.format("%.0f", total))
                .replace("%bank%", String.format("%.0f", bank))
                .replace("%items%", String.valueOf(items))
                .replace("%target%", target.getName() == null ? "Unknown" : target.getName())
                .replace("%symbol%", plugin.getCurrencySymbol())
                .replace("%currency_plural%", plugin.getCurrencyPlural());

        viewer.sendMessage(plugin.getPrefix() + raw);
    }
}
