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
import java.util.Locale;
import java.util.OptionalLong;

public class PayCommand implements CommandExecutor, TabCompleter {

    private static final int PAY_ARGUMENTS = 2;
    private static final int TARGET_ARGUMENT = 0;
    private static final int AMOUNT_ARGUMENT = 1;
    private static final int TAB_TARGET_ARGUMENTS = 1;
    private static final String SELF_REASON = "self";
    private static final String OVERFLOW_REASON = "overflow";
    private static final String UNKNOWN_PLAYER = "Unknown";

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

        if (args.length != PAY_ARGUMENTS) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[TARGET_ARGUMENT]);
        if (unknownTarget(target)) {
            plugin.sendMsg(player, "player-not-found");
            return true;
        }

        OptionalLong parsedAmount = parseAmount(args[AMOUNT_ARGUMENT]);
        if (parsedAmount.isEmpty()) {
            plugin.sendMsg(player, "invalid-amount");
            return true;
        }

        CurrencyService.PayResult result = plugin.getCurrencyService().pay(player, target, parsedAmount.getAsLong());
        if (!result.success()) {
            sendFailure(player, result);
            return true;
        }

        sendSuccess(player, target, result);
        return true;
    }

    private boolean unknownTarget(OfflinePlayer target) {
        return (target.getName() == null || !target.hasPlayedBefore()) && !target.isOnline();
    }

    private OptionalLong parseAmount(String rawAmount) {
        boolean allowDecimals = plugin.getConfig().getBoolean("economy.allow-decimals", false);
        return CurrencyAmountParser.parseUserAmount(rawAmount, allowDecimals);
    }

    private void sendFailure(Player player, CurrencyService.PayResult result) {
        if (SELF_REASON.equals(result.failureReason())) {
            plugin.sendMsg(player, "self-pay");
            return;
        }
        if (OVERFLOW_REASON.equals(result.failureReason())) {
            plugin.sendMsg(player, "invalid-amount");
            return;
        }

        CurrencyService.BalanceView senderView = plugin.getCurrencyService().getBalanceView(player);
        String message = plugin.msgNoPrefix("not-enough-funds")
                .replace("%have%", String.valueOf(senderView.total()))
                .replace("%symbol%", plugin.getCurrencySymbol())
                .replace("%currency%", plugin.getCurrencyName(senderView.total()));
        player.sendMessage(plugin.getPrefix() + message);
    }

    private void sendSuccess(Player player, OfflinePlayer target, CurrencyService.PayResult result) {
        String senderMessage = plugin.msgNoPrefix("pay-success-sender")
                .replace("%target%", target.getName() == null ? UNKNOWN_PLAYER : target.getName())
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
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || args.length != TAB_TARGET_ARGUMENTS) {
            return Collections.emptyList();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(online.getName());
            }
        }
        return matches;
    }
}
