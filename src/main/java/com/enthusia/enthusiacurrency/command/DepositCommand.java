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

public class DepositCommand implements CommandExecutor, TabCompleter {

    private final EnthusiaCurrencyPlugin plugin;

    public DepositCommand(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.sendMsg(sender, "player-only");
            return true;
        }

        CurrencyService currencyService = plugin.getCurrencyService();

        if (args.length == 0 || args[0].equalsIgnoreCase("all")) {
            CurrencyService.DepositResult result = currencyService.depositAll(player);
            if (!result.success()) {
                String message = plugin.msgNoPrefix("not-enough-items")
                        .replace("%currency_plural%", plugin.getCurrencyPlural());
                player.sendMessage(plugin.getPrefix() + message);
                return true;
            }

            String message = plugin.msgNoPrefix("deposit-all-success")
                    .replace("%amount%", String.valueOf(result.depositedAmount()))
                    .replace("%symbol%", plugin.getCurrencySymbol())
                    .replace("%currency_plural%", plugin.getCurrencyPlural())
                    .replace("%currency%", plugin.getCurrencyName(result.depositedAmount()));
            player.sendMessage(plugin.getPrefix() + message);
            return true;
        }

        boolean allowDecimals = plugin.getConfig().getBoolean("economy.allow-decimals", false);
        OptionalLong parsedAmount = CurrencyAmountParser.parseUserAmount(args[0], allowDecimals);
        if (parsedAmount.isEmpty()) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        CurrencyService.DepositResult result = currencyService.deposit(player, parsedAmount.getAsLong());
        if (!result.success()) {
            String message = plugin.msgNoPrefix("not-enough-items")
                    .replace("%currency_plural%", plugin.getCurrencyPlural());
            player.sendMessage(plugin.getPrefix() + message);
            return true;
        }

        String message = plugin.msgNoPrefix("deposit-success")
                .replace("%amount%", String.valueOf(result.depositedAmount()))
                .replace("%symbol%", plugin.getCurrencySymbol())
                .replace("%currency%", plugin.getCurrencyName(result.depositedAmount()));
        player.sendMessage(plugin.getPrefix() + message);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && "all".startsWith(args[0].toLowerCase())) {
            return Collections.singletonList("all");
        }
        return Collections.emptyList();
    }
}
