package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import com.enthusia.enthusiacurrency.util.CurrencyUtils;
import com.enthusia.enthusiacurrency.util.CurrencyUtils.CurrencyBreakdown;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

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

        CurrencyManager currencyManager = plugin.getCurrencyManager();
        BalanceStorage storage = plugin.getBalanceStorage();
        boolean allowDecimals = plugin.getConfig().getBoolean("economy.allow-decimals", false);

        CurrencyBreakdown breakdown = CurrencyUtils.getCurrencyBreakdown(currencyManager, player);
        int items = breakdown.items;
        int blocks = breakdown.blocks;
        int blockValue = currencyManager.getBlockValue();
        int totalValue = breakdown.totalValue;

        if (args.length == 0 || args[0].equalsIgnoreCase("all")) {
            if (totalValue <= 0) {
                String msg = plugin.msgNoPrefix("not-enough-items")
                        .replace("%currency_plural%", plugin.getCurrencyPlural());
                player.sendMessage(plugin.getPrefix() + msg);
                return true;
            }

            storage.deposit(player.getUniqueId(), totalValue);
            CurrencyUtils.removeAllFromPlayer(currencyManager, player, items, blocks);

            plugin.getBaltopTracker().refreshTop3();

            String msg = plugin.msgNoPrefix("deposit-all-success")
                    .replace("%amount%", String.valueOf(totalValue))
                    .replace("%symbol%", plugin.getCurrencySymbol())
                    .replace("%currency_plural%", plugin.getCurrencyPlural())
                    .replace("%currency%", plugin.getCurrencyName(totalValue));
            player.sendMessage(plugin.getPrefix() + msg);
            return true;
        }

        double amountDouble;
        try {
            amountDouble = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        if (amountDouble <= 0) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        if (!allowDecimals && amountDouble != Math.floor(amountDouble)) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        int amount = (int) Math.floor(amountDouble);

        if (amount > totalValue) {
            String msg = plugin.msgNoPrefix("not-enough-items")
                    .replace("%currency_plural%", plugin.getCurrencyPlural());
            player.sendMessage(plugin.getPrefix() + msg);
            return true;
        }

        int itemsToRemove;
        int blocksToRemove;

        if (amount <= items) {
            itemsToRemove = amount;
            blocksToRemove = 0;
        } else {
            if (blockValue <= 0) {
                String msg = plugin.msgNoPrefix("not-enough-items")
                        .replace("%currency_plural%", plugin.getCurrencyPlural());
                player.sendMessage(plugin.getPrefix() + msg);
                return true;
            }

            int needFromBlocks = amount - items;
            if (needFromBlocks % blockValue != 0) {
                String msg = plugin.msgNoPrefix("not-enough-items")
                        .replace("%currency_plural%", plugin.getCurrencyPlural());
                player.sendMessage(plugin.getPrefix() + msg);
                return true;
            }

            int requiredBlocks = needFromBlocks / blockValue;
            if (requiredBlocks > blocks) {
                String msg = plugin.msgNoPrefix("not-enough-items")
                        .replace("%currency_plural%", plugin.getCurrencyPlural());
                player.sendMessage(plugin.getPrefix() + msg);
                return true;
            }

            itemsToRemove = items;
            blocksToRemove = requiredBlocks;
        }

        storage.deposit(player.getUniqueId(), amount);
        CurrencyUtils.removeAllFromPlayer(currencyManager, player, itemsToRemove, blocksToRemove);

        plugin.getBaltopTracker().refreshTop3();

        String msg = plugin.msgNoPrefix("deposit-success")
                .replace("%amount%", String.valueOf(amount))
                .replace("%symbol%", plugin.getCurrencySymbol())
                .replace("%currency%", plugin.getCurrencyName(amount));
        player.sendMessage(plugin.getPrefix() + msg);

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
