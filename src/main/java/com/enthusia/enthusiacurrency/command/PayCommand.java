package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import com.enthusia.enthusiacurrency.util.CurrencyUtils;
import com.enthusia.enthusiacurrency.util.CurrencyUtils.CurrencyBreakdown;
import com.enthusia.enthusiacurrency.event.CurrencyBalanceZeroEvent;
import com.enthusia.enthusiacurrency.event.CurrencyPayEvent;
import com.enthusia.enthusiacurrency.event.CurrencyPaySelfAttemptEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

        String targetName = args[0];
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

        if ((target.getName() == null || !target.hasPlayedBefore()) && !target.isOnline()) {
            plugin.sendMsg(player, "player-not-found");
            return true;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            Bukkit.getPluginManager().callEvent(new CurrencyPaySelfAttemptEvent(player.getUniqueId()));
            plugin.sendMsg(player, "self-pay");
            return true;
        }

        boolean allowDecimals = plugin.getConfig().getBoolean("economy.allow-decimals", false);

        double amountDouble;
        try {
            amountDouble = Double.parseDouble(args[1]);
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

        BalanceStorage storage = plugin.getBalanceStorage();
        CurrencyManager manager = plugin.getCurrencyManager();

        long bankBalance = storage.getBalance(player.getUniqueId());
        CurrencyBreakdown breakdown = CurrencyUtils.getCurrencyBreakdown(manager, player);
        int itemValue = breakdown.totalValue();
        long total = bankBalance + itemValue;

        if (amount > total) {
            String msg = plugin.msgNoPrefix("not-enough-funds")
                    .replace("%have%", String.valueOf(total))
                    .replace("%symbol%", plugin.getCurrencySymbol())
                    .replace("%currency%", plugin.getCurrencyName(total));
            player.sendMessage(plugin.getPrefix() + msg);
            return true;
        }

        int remaining = amount;
        int totalDeducted = 0;

        while (remaining > 0) {
            long currentBank = storage.getBalance(player.getUniqueId());
            if (currentBank > 0) {
                int use = (int) Math.min(currentBank, remaining);
                if (use > 0 && storage.withdraw(player.getUniqueId(), (long) use)) {
                    remaining -= use;
                    totalDeducted += use;
                    if (remaining <= 0) break;
                }
            }

            CurrencyBreakdown cb = CurrencyUtils.getCurrencyBreakdown(manager, player);

            if (cb.items() > 0 && remaining > 0) {
                int useItems = Math.min(cb.items(), remaining);
                CurrencyUtils.removeAllFromPlayer(manager, player, useItems, 0);
                remaining -= useItems;
                totalDeducted += useItems;
                continue;
            }

            if (cb.blocks() > 0 && remaining > 0) {
                CurrencyUtils.removeAllFromPlayer(manager, player, 0, 1);
                int blockValue = manager.getBlockValue();
                if (blockValue > 0) {
                    storage.deposit(player.getUniqueId(), (long) blockValue);
                    totalDeducted -= blockValue;
                }
                continue;
            }

            break;
        }

        if (remaining > 0) {
            // Rollback: refund everything taken from sender
            if (totalDeducted > 0) {
                storage.deposit(player.getUniqueId(), (long) totalDeducted);
            }
            plugin.getLogger().warning("PayCommand: partial deduction rollback for " + player.getName());
            long currentBal = storage.getBalance(player.getUniqueId());
            String msg = plugin.msgNoPrefix("not-enough-funds")
                    .replace("%have%", String.valueOf(currentBal))
                    .replace("%symbol%", plugin.getCurrencySymbol())
                    .replace("%currency%", plugin.getCurrencyName(currentBal));
            player.sendMessage(plugin.getPrefix() + msg);
            return true;
        }

        storage.deposit(target.getUniqueId(), (long) amount);
        storage.saveSingle(player.getUniqueId());
        storage.saveSingle(target.getUniqueId());

        Bukkit.getPluginManager().callEvent(new CurrencyPayEvent(
                player.getUniqueId(), target.getUniqueId(), amount));
        long senderBalance = storage.getBalance(player.getUniqueId());
        if (senderBalance <= 0) {
            Bukkit.getPluginManager().callEvent(new CurrencyBalanceZeroEvent(player.getUniqueId()));
        }

        String senderMsg = plugin.msgNoPrefix("pay-success-sender")
                .replace("%target%", target.getName() == null ? "Unknown" : target.getName())
                .replace("%amount%", String.valueOf(amount))
                .replace("%symbol%", plugin.getCurrencySymbol())
                .replace("%currency%", plugin.getCurrencyName(amount));
        player.sendMessage(plugin.getPrefix() + senderMsg);

        if (target.isOnline() && target.getPlayer() != null) {
            String targetMsg = plugin.msgNoPrefix("pay-success-target")
                    .replace("%sender%", player.getName())
                    .replace("%amount%", String.valueOf(amount))
                    .replace("%symbol%", plugin.getCurrencySymbol())
                    .replace("%currency%", plugin.getCurrencyName(amount));
            target.getPlayer().sendMessage(plugin.getPrefix() + targetMsg);
        }

        plugin.getBaltopTracker().refreshTop3();

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            List<String> matches = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                String name = online.getName();
                if (name.toLowerCase().startsWith(prefix)) {
                    matches.add(name);
                }
            }
            return matches;
        }

        return Collections.emptyList();
    }
}
