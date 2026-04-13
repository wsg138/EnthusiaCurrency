package com.enthusia.enthusiacurrency.util;

import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

public final class CurrencyUtils {

    private CurrencyUtils() {
    }

    public static int removeCurrencyFromPlayer(CurrencyManager currencyManager, Player player, int toRemove) {
        if (toRemove <= 0) return 0;

        CurrencyBreakdown breakdown = getCurrencyBreakdown(currencyManager, player);
        if (breakdown.totalValue < toRemove) {
            return 0;
        }

        int blockValue = currencyManager.getBlockValue();
        int itemsToRemove;
        int blocksToRemove;

        if (blockValue <= 0 || !currencyManager.hasBlockForm()) {
            if (breakdown.items < toRemove) {
                return 0;
            }
            itemsToRemove = toRemove;
            blocksToRemove = 0;
        } else {
            int itemsAvailable = breakdown.items;
            int blocksAvailable = breakdown.blocks;

            int minBlocksForExact = Math.max(0, ceilDiv(toRemove - itemsAvailable, blockValue));
            int maxBlocksForExact = Math.min(blocksAvailable, toRemove / blockValue);

            if (minBlocksForExact <= maxBlocksForExact) {
                blocksToRemove = maxBlocksForExact;
                itemsToRemove = toRemove - (blocksToRemove * blockValue);
            } else {
                itemsToRemove = itemsAvailable;
                int remainingValue = toRemove - itemsToRemove;
                blocksToRemove = ceilDiv(Math.max(0, remainingValue), blockValue);
                if (blocksToRemove > blocksAvailable) {
                    return 0;
                }
            }
        }

        removeAllFromPlayer(currencyManager, player, itemsToRemove, blocksToRemove);
        return itemsToRemove + (blocksToRemove * blockValue);
    }

    public static final class CurrencyBreakdown {
        public final int items;
        public final int blocks;
        public final int totalValue;

        public CurrencyBreakdown(int items, int blocks, int blockValue) {
            this.items = items;
            this.blocks = blocks;
            this.totalValue = items + blocks * Math.max(blockValue, 0);
        }
    }

    public static int countCurrencyInPlayer(CurrencyManager manager, Player player) {
        return getCurrencyBreakdown(manager, player).totalValue;
    }

    public static CurrencyBreakdown getCurrencyBreakdown(CurrencyManager manager, Player player) {
        int items = 0;
        int blocks = 0;

        int[] invCounts = countInInventory(manager, player.getInventory());
        items += invCounts[0];
        blocks += invCounts[1];

        int[] ecCounts = countInInventory(manager, player.getEnderChest());
        items += ecCounts[0];
        blocks += ecCounts[1];

        return new CurrencyBreakdown(items, blocks, manager.getBlockValue());
    }

    private static int[] countInInventory(CurrencyManager manager, Inventory inv) {
        int items = 0;
        int blocks = 0;

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType() == Material.AIR) continue;

            if (manager.isCurrencyItem(stack)) {
                items += stack.getAmount();
                continue;
            }

            if (manager.isCurrencyBlock(stack)) {
                blocks += stack.getAmount();
                continue;
            }

            if (stack.getItemMeta() instanceof BlockStateMeta meta) {
                if (meta.getBlockState() instanceof ShulkerBox box) {
                    int[] inner = countInInventory(manager, box.getInventory());
                    items += inner[0];
                    blocks += inner[1];
                }
            }
        }

        return new int[]{items, blocks};
    }

    public static void removeCurrencyFromPlayerByCounts(CurrencyManager manager,
                                                        Player player,
                                                        int itemsToRemove,
                                                        int blocksToRemove) {
        if (itemsToRemove <= 0 && blocksToRemove <= 0) return;
        removeAllFromPlayer(manager, player, itemsToRemove, blocksToRemove);
    }

    public static void removeAllFromPlayer(CurrencyManager manager,
                                           Player player,
                                           int itemsToRemove,
                                           int blocksToRemove) {
        int[] remaining = removeFromInventory(manager, player.getInventory(), itemsToRemove, blocksToRemove);
        remaining = removeFromInventory(manager, player.getEnderChest(), remaining[0], remaining[1]);
    }

    private static int ceilDiv(int value, int divisor) {
        if (value <= 0) return 0;
        return (value + divisor - 1) / divisor;
    }

    private static int[] removeFromInventory(CurrencyManager manager,
                                             Inventory inv,
                                             int itemsToRemove,
                                             int blocksToRemove) {
        if (itemsToRemove <= 0 && blocksToRemove <= 0) {
            return new int[]{0, 0};
        }

        for (int i = 0; i < inv.getSize(); i++) {
            if (itemsToRemove <= 0 && blocksToRemove <= 0) break;

            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType() == Material.AIR) continue;

            if (manager.isCurrencyItem(stack) && itemsToRemove > 0) {
                int take = Math.min(stack.getAmount(), itemsToRemove);
                itemsToRemove -= take;
                int newAmount = stack.getAmount() - take;
                if (newAmount <= 0) {
                    inv.setItem(i, null);
                } else {
                    stack.setAmount(newAmount);
                }
                continue;
            }

            if (manager.isCurrencyBlock(stack) && blocksToRemove > 0) {
                int take = Math.min(stack.getAmount(), blocksToRemove);
                blocksToRemove -= take;
                int newAmount = stack.getAmount() - take;
                if (newAmount <= 0) {
                    inv.setItem(i, null);
                } else {
                    stack.setAmount(newAmount);
                }
                continue;
            }

            if (stack.getItemMeta() instanceof BlockStateMeta meta) {
                if (meta.getBlockState() instanceof ShulkerBox box) {
                    Inventory innerInv = box.getInventory();
                    int[] innerRem = removeFromInventory(manager, innerInv, itemsToRemove, blocksToRemove);
                    itemsToRemove = innerRem[0];
                    blocksToRemove = innerRem[1];
                    box.getInventory().setContents(innerInv.getContents());
                    meta.setBlockState(box);
                    stack.setItemMeta(meta);
                    if (itemsToRemove <= 0 && blocksToRemove <= 0) break;
                }
            }
        }

        return new int[]{itemsToRemove, blocksToRemove};
    }
}
