package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.service.CurrencyAmountParser;
import com.enthusia.enthusiacurrency.service.CurrencyService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;

public class WithdrawCommand implements CommandExecutor, TabCompleter {

    private final EnthusiaCurrencyPlugin plugin;

    public WithdrawCommand(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.sendMsg(sender, "player-only");
            return true;
        }

        if (args.length != 1) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        boolean allowDecimals = plugin.getConfig().getBoolean("economy.allow-decimals", false);
        OptionalLong parsedAmount = CurrencyAmountParser.parseUserAmount(args[0], allowDecimals);
        if (parsedAmount.isEmpty()) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        CurrencyService.WithdrawResult result = plugin.getCurrencyService().withdrawToInventory(player, parsedAmount.getAsLong());
        if (!result.success()) {
            if ("inventory-full".equals(result.failureReason())) {
                player.sendMessage(plugin.getPrefix() + plugin.msgNoPrefix("inventory-full"));
                return true;
            }

            String message = plugin.msgNoPrefix("not-enough-funds")
                    .replace("%have%", String.valueOf(result.newBalance()))
                    .replace("%symbol%", plugin.getCurrencySymbol())
                    .replace("%currency%", plugin.getCurrencyName(result.newBalance()));
            player.sendMessage(plugin.getPrefix() + message);
            return true;
        }

        String message = plugin.msgNoPrefix("withdraw-success")
                .replace("%amount%", String.valueOf(result.withdrawnAmount()))
                .replace("%symbol%", plugin.getCurrencySymbol())
                .replace("%currency%", plugin.getCurrencyName(result.withdrawnAmount()));
        player.sendMessage(plugin.getPrefix() + message);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
