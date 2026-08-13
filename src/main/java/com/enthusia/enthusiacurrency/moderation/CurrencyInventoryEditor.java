package com.enthusia.enthusiacurrency.moderation;

import com.enthusia.enthusiacurrency.util.CurrencyManager;
import java.util.Objects;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/** Counts and removes configured physical currency without touching non-currency items. */
final class CurrencyInventoryEditor {

    private final CurrencyManager manager;

    CurrencyInventoryEditor(CurrencyManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    long value(ItemStack[] contents) {
        Count count = count(contents);
        return Math.addExact(count.items(), Math.multiplyExact(count.blocks(), manager.getBlockValue()));
    }

    @SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
    long removeUpTo(ItemStack[] contents, long limit) {
        Count available = count(contents);
        CurrencyRemovalAllocator.Allocation allocation = CurrencyRemovalAllocator.maximum(
                available.items(),
                available.blocks(),
                manager.getBlockValue(),
                limit
        );
        if (allocation.value() == 0L) {
            return 0L;
        }
        Remaining remaining = new Remaining(allocation.items(), allocation.blocks());
        remove(contents, remaining);
        if (remaining.items != 0L || remaining.blocks != 0L) {
            throw new IllegalStateException("planned physical-currency removal could not be reproduced");
        }
        return allocation.value();
    }

    private Count count(ItemStack[] contents) {
        long items = 0L;
        long blocks = 0L;
        for (ItemStack stack : contents) {
            if (isEmpty(stack)) {
                continue;
            }
            if (manager.isCurrencyItem(stack)) {
                items = Math.addExact(items, stack.getAmount());
                continue;
            }
            if (manager.isCurrencyBlock(stack)) {
                blocks = Math.addExact(blocks, stack.getAmount());
                continue;
            }
            Count nested = nestedCount(stack);
            items = Math.addExact(items, nested.items());
            blocks = Math.addExact(blocks, nested.blocks());
        }
        return new Count(items, blocks);
    }

    private Count nestedCount(ItemStack stack) {
        if (!(stack.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) {
            return Count.EMPTY;
        }
        return count(box.getInventory().getContents());
    }

    private void remove(ItemStack[] contents, Remaining remaining) {
        for (int index = 0; index < contents.length; index++) {
            if (remaining.done()) {
                return;
            }
            ItemStack stack = contents[index];
            if (isEmpty(stack)) {
                continue;
            }
            if (removeCurrencyItem(contents, index, stack, remaining)) {
                continue;
            }
            if (removeCurrencyBlock(contents, index, stack, remaining)) {
                continue;
            }
            removeNested(stack, remaining);
        }
    }

    private boolean removeCurrencyItem(
            ItemStack[] contents,
            int index,
            ItemStack stack,
            Remaining remaining
    ) {
        if (!manager.isCurrencyItem(stack) || remaining.items <= 0L) {
            return false;
        }
        int taken = (int) Math.min(stack.getAmount(), remaining.items);
        remaining.items -= taken;
        decrease(contents, index, stack, taken);
        return true;
    }

    private boolean removeCurrencyBlock(
            ItemStack[] contents,
            int index,
            ItemStack stack,
            Remaining remaining
    ) {
        if (!manager.isCurrencyBlock(stack) || remaining.blocks <= 0L) {
            return false;
        }
        int taken = (int) Math.min(stack.getAmount(), remaining.blocks);
        remaining.blocks -= taken;
        decrease(contents, index, stack, taken);
        return true;
    }

    private void removeNested(ItemStack stack, Remaining remaining) {
        if (!(stack.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox box)) {
            return;
        }
        Inventory nested = box.getInventory();
        ItemStack[] nestedContents = nested.getContents();
        remove(nestedContents, remaining);
        nested.setContents(nestedContents);
        meta.setBlockState(box);
        stack.setItemMeta(meta);
    }

    private static void decrease(ItemStack[] contents, int index, ItemStack stack, int amount) {
        int replacement = stack.getAmount() - amount;
        if (replacement == 0) {
            contents[index] = null; // NOPMD - Bukkit represents an empty inventory-array slot with null.
        } else {
            stack.setAmount(replacement);
        }
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.getType() == Material.AIR;
    }

    private record Count(long items, long blocks) {
        private static final Count EMPTY = new Count(0L, 0L);
    }

    private static final class Remaining {
        private long items;
        private long blocks;

        private Remaining(long items, long blocks) {
            this.items = items;
            this.blocks = blocks;
        }

        private boolean done() {
            return items == 0L && blocks == 0L;
        }
    }
}
