package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.service.CurrencyAmountParser;
import com.enthusia.enthusiacurrency.service.CurrencyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;

public class PayCommand implements CommandExecutor, TabCompleter {

    private final EnthusiaCurrencyPlugin plugin;

    public PayCommand(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.sendMsg(sender, "player-only");
            return true;
        }

        if (args.length != 2) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        if ((target.getName() == null || !target.hasPlayedBefore()) && !target.isOnline()) {
            plugin.sendMsg(player, "player-not-found");
            return true;
        }

        boolean allowDecimals = plugin.getConfig().getBoolean("economy.allow-decimals", false);
        OptionalLong parsedAmount = CurrencyAmountParser.parseUserAmount(args[1], allowDecimals);
        if (parsedAmount.isEmpty()) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        CurrencyService.PayResult result = plugin.getCurrencyService().pay(player, target, parsedAmount.getAsLong());
        if (!result.success()) {
            if ("self".equals(result.failureReason())) {
                plugin.sendMsg(player, "self-pay");
                return true;
            }
            if ("overflow".equals(result.failureReason())) {
                plugin.sendMsg(player, "invalid-amount");
                return true;
            }

            CurrencyService.BalanceView senderView = plugin.getCurrencyService().getBalanceView(player);
            String message = plugin.msgNoPrefix("not-enough-funds")
                    .replace("%have%", String.valueOf(senderView.total()))
                    .replace("%symbol%", plugin.getCurrencySymbol())
                    .replace("%currency%", plugin.getCurrencyName(senderView.total()));
            player.sendMessage(plugin.getPrefix() + message);
            return true;
        }

        String senderMessage = plugin.msgNoPrefix("pay-success-sender")
                .replace("%target%", target.getName() == null ? "Unknown" : target.getName())
                .replace("%amount%", String.valueOf(result.amount()))
                .replace("%symbol%", plugin.getCurrencySymbol())
                .replace("%currency%", plugin.getCurrencyName(result.amount()));
        player.sendMessage(plugin.getPrefix() + senderMessage);

        if (target.isOnline() && target.getPlayer() != null) {
            String targetMessage = plugin.msgNoPrefix("pay-success-target")
                    .replace("%sender%", player.getName())
                    .replace("%amount%", String.valueOf(result.amount()))
                    .replace("%symbol%", plugin.getCurrencySymbol())
                    .replace("%currency%", plugin.getCurrencyName(result.amount()));
            target.getPlayer().sendMessage(plugin.getPrefix() + targetMessage);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || args.length != 1) {
            return Collections.emptyList();
        }

        String prefix = args[0].toLowerCase();
        List<String> matches = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase().startsWith(prefix)) {
                matches.add(online.getName());
            }
        }
        return matches;
    }
}
