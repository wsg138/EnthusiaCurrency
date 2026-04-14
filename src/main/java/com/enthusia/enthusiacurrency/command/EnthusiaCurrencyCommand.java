package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class EnthusiaCurrencyCommand implements CommandExecutor {

    private final EnthusiaCurrencyPlugin plugin;

    public EnthusiaCurrencyCommand(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("currency.admin")) {
            plugin.sendMsg(sender, "no-permission");
            return true;
        }

        plugin.getBalanceStorage().save();
        plugin.reloadAndSyncConfig();
        plugin.getCurrencyManager().reload();
        plugin.sendMsg(sender, "reloaded");
        return true;
    }
}
