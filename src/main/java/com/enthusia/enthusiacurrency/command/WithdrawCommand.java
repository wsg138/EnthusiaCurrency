package com.enthusia.enthusiacurrency.command;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import com.enthusia.enthusiacurrency.event.CurrencyBalanceZeroEvent;
import com.enthusia.enthusiacurrency.event.CurrencyWithdrawEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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

        BalanceStorage storage = plugin.getBalanceStorage();
        long bank = storage.getBalance(player.getUniqueId());
        if (bank < amount) {
            String msg = plugin.msgNoPrefix("not-enough-funds")
                    .replace("%have%", String.valueOf(bank))
                    .replace("%symbol%", plugin.getCurrencySymbol())
                    .replace("%currency%", plugin.getCurrencyName(bank));
            player.sendMessage(plugin.getPrefix() + msg);
            return true;
        }

        CurrencyManager cm = plugin.getCurrencyManager();

        int blockValue = cm.getBlockValue();
        Material blockMat = cm.getBlockMaterial();
        Material itemMat = cm.getMaterial();

        int blocks = 0;
        int items = amount;

        boolean canUseBlocks = blockMat != null && blockValue > 0;
        boolean divisibleByBlock = (blockValue > 0 && amount % blockValue == 0);

        if (canUseBlocks && (divisibleByBlock || amount > 128)) {
            blocks = amount / blockValue;
            items = amount % blockValue;
        } else {
            blocks = 0;
            items = amount;
        }

        int blockMaxStack = (blockMat != null ? blockMat.getMaxStackSize() : 64);
        int itemMaxStack = itemMat.getMaxStackSize();

        int stacksNeeded = 0;
        if (blocks > 0) {
            stacksNeeded += (blocks + blockMaxStack - 1) / blockMaxStack;
        }
        if (items > 0) {
            stacksNeeded += (items + itemMaxStack - 1) / itemMaxStack;
        }

        int freeSlots = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.getType() == Material.AIR) {
                freeSlots++;
            }
        }

        if (stacksNeeded > freeSlots) {
            player.sendMessage(plugin.getPrefix() + plugin.msgNoPrefix("inventory-full"));
            return true;
        }

        storage.withdraw(player.getUniqueId(), (long) amount);
        storage.saveSingle(player.getUniqueId());

        long newBalance = storage.getBalance(player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new CurrencyWithdrawEvent(
                player.getUniqueId(), amount, newBalance));
        if (newBalance <= 0) {
            Bukkit.getPluginManager().callEvent(new CurrencyBalanceZeroEvent(player.getUniqueId()));
        }

        if (canUseBlocks && blocks > 0) {
            int remainingBlocks = blocks;
            while (remainingBlocks > 0) {
                int stackSize = Math.min(remainingBlocks, blockMaxStack);
                ItemStack stack = new ItemStack(blockMat, stackSize);
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
                for (ItemStack dropped : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), dropped);
                }
                remainingBlocks -= stackSize;
            }
        }

        if (items > 0) {
            int remainingItems = items;
            while (remainingItems > 0) {
                int stackSize = Math.min(remainingItems, itemMaxStack);
                ItemStack stack = cm.createCurrencyItem(stackSize);
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
                for (ItemStack dropped : overflow.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), dropped);
                }
                remainingItems -= stackSize;
            }
        }

        plugin.getBaltopTracker().refreshTop3();

        String msg = plugin.msgNoPrefix("withdraw-success")
                .replace("%amount%", String.valueOf(amount))
                .replace("%symbol%", plugin.getCurrencySymbol())
                .replace("%currency%", plugin.getCurrencyName(amount));
        player.sendMessage(plugin.getPrefix() + msg);

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
