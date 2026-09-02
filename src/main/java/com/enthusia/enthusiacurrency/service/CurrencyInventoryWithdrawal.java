package com.enthusia.enthusiacurrency.service;

import com.enthusia.enthusiacurrency.util.CurrencyManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/** Plans, capacity-checks, and delivers physical currency withdrawals. */
final class CurrencyInventoryWithdrawal {
    record WithdrawalStacks(boolean canUseBlocks, long blocks, long items) {
    }

    private static final long SMALL_WITHDRAWAL_ITEM_PREFERENCE = 128L;

    private final CurrencyManager currencyManager;

    CurrencyInventoryWithdrawal(CurrencyManager currencyManager) {
        this.currencyManager = currencyManager;
    }

    WithdrawalStacks plan(long amount) {
        return plan(amount, currencyManager.hasBlockForm(), currencyManager.getBlockValue());
    }

    static WithdrawalStacks plan(long amount, boolean canUseBlocks, int blockValue) {
        if (!canUseBlocks || (amount % blockValue != 0 && amount <= SMALL_WITHDRAWAL_ITEM_PREFERENCE)) {
            return new WithdrawalStacks(canUseBlocks, 0L, amount);
        }
        return new WithdrawalStacks(true, amount / blockValue, amount % blockValue);
    }

    boolean canFit(Inventory inventory, WithdrawalStacks stacks) {
        ItemStack[] simulated = cloneInventoryContents(inventory.getStorageContents());
        if (!canFitBlocks(simulated, stacks)) {
            return false;
        }
        return canFitItems(simulated, stacks.items());
    }

    void deliver(Player player, WithdrawalStacks stacks) {
        if (stacks.canUseBlocks() && stacks.blocks() > 0) {
            addBlockStacks(player, stacks.blocks());
        }
        if (stacks.items() > 0) {
            addCurrencyItemStacks(player, stacks.items());
        }
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops") // Each simulated stack must be a distinct Bukkit object.
    private boolean canFitBlocks(ItemStack[] simulated, WithdrawalStacks stacks) {
        if (!stacks.canUseBlocks() || stacks.blocks() <= 0) {
            return true;
        }
        long remainingBlocks = stacks.blocks();
        int maxStack = currencyManager.getBlockMaterial().getMaxStackSize();
        while (remainingBlocks > 0) {
            int stackSize = (int) Math.min(remainingBlocks, maxStack);
            if (!simulateAddStack(simulated, new ItemStack(currencyManager.getBlockMaterial(), stackSize))) {
                return false;
            }
            remainingBlocks -= stackSize;
        }
        return true;
    }

    private boolean canFitItems(ItemStack[] simulated, long items) {
        long remainingItems = items;
        int maxStack = currencyManager.getMaterial().getMaxStackSize();
        while (remainingItems > 0) {
            int stackSize = (int) Math.min(remainingItems, maxStack);
            if (!simulateAddStack(simulated, currencyManager.createCurrencyItem(stackSize))) {
                return false;
            }
            remainingItems -= stackSize;
        }
        return true;
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops") // Bukkit inventory insertion requires distinct stacks.
    private void addBlockStacks(Player player, long blocks) {
        long remainingBlocks = blocks;
        int maxStack = currencyManager.getBlockMaterial().getMaxStackSize();
        while (remainingBlocks > 0) {
            int stackSize = (int) Math.min(remainingBlocks, maxStack);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem( // NOPMD - Bukkit-owned local result.
                    new ItemStack(currencyManager.getBlockMaterial(), stackSize));
            dropOverflow(player, overflow);
            remainingBlocks -= stackSize;
        }
    }

    private void addCurrencyItemStacks(Player player, long items) {
        long remainingItems = items;
        int maxStack = currencyManager.getMaterial().getMaxStackSize();
        while (remainingItems > 0) {
            int stackSize = (int) Math.min(remainingItems, maxStack);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem( // NOPMD - Bukkit-owned local result.
                    currencyManager.createCurrencyItem(stackSize));
            dropOverflow(player, overflow);
            remainingItems -= stackSize;
        }
    }

    private void dropOverflow(Player player, Map<Integer, ItemStack> overflow) {
        for (ItemStack itemStack : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), itemStack);
        }
    }

    private static ItemStack[] cloneInventoryContents(ItemStack[] contents) {
        ItemStack[] cloned = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            ItemStack itemStack = contents[index];
            cloned[index] = itemStack == null ? null : itemStack.clone();
        }
        return cloned;
    }

    static boolean simulateAddStack(ItemStack[] contents, ItemStack incoming) {
        int remaining = mergeIntoExistingStacks(contents, incoming);
        return placeInEmptySlots(contents, incoming, remaining) == 0;
    }

    private static int mergeIntoExistingStacks(ItemStack[] contents, ItemStack incoming) {
        int remaining = incoming.getAmount();
        for (ItemStack existing : contents) {
            if (remaining <= 0) {
                break;
            }
            if (existing == null || existing.getType() == Material.AIR || !existing.isSimilar(incoming)) {
                continue;
            }
            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining);
            existing.setAmount(existing.getAmount() + moved);
            remaining -= moved;
        }
        return remaining;
    }

    private static int placeInEmptySlots(ItemStack[] contents, ItemStack incoming, int remaining) {
        int remainingItems = remaining;
        for (int index = 0; index < contents.length && remainingItems > 0; index++) {
            ItemStack existing = contents[index];
            if (existing != null && existing.getType() != Material.AIR) {
                continue;
            }
            int moved = Math.min(incoming.getMaxStackSize(), remainingItems);
            ItemStack placed = incoming.clone();
            placed.setAmount(moved);
            contents[index] = placed;
            remainingItems -= moved;
        }
        return remainingItems;
    }
}
