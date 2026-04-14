package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.service.CurrencyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            plugin.sendMsg(sender, "player-not-found");
            return true;
        }

        sendBalance(sender, target);
        return true;
    }

    private void sendBalance(CommandSender viewer, OfflinePlayer target) {
        CurrencyService.BalanceView balanceView = plugin.getCurrencyService().getBalanceView(target);

        String messageKey = (viewer instanceof Player player && player.getUniqueId().equals(target.getUniqueId()))
                ? "balance-self"
                : "balance-other";

        String message = plugin.msgNoPrefix(messageKey)
                .replace("%total%", String.valueOf(balanceView.total()))
                .replace("%bank%", String.valueOf(balanceView.bank()))
                .replace("%items%", String.valueOf(balanceView.items()))
                .replace("%target%", target.getName() == null ? "Unknown" : target.getName())
                .replace("%symbol%", plugin.getCurrencySymbol())
                .replace("%currency_plural%", plugin.getCurrencyPlural());

        viewer.sendMessage(plugin.getPrefix() + message);
    }
}
