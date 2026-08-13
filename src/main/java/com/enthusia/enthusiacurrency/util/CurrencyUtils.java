package com.enthusia.enthusiacurrency.util;

import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

@SuppressWarnings("PMD.NPathComplexity")
public final class CurrencyUtils {

    private CurrencyUtils() {
    }

    public static int removeCurrencyFromPlayer(CurrencyManager currencyManager, Player player, int toRemove) {
        if (toRemove <= 0) return 0;

        CurrencyBreakdown breakdown = getCurrencyBreakdown(currencyManager, player);
        if (breakdown.totalValue() < toRemove) {
            return 0;
        }

        int blockValue = currencyManager.getBlockValue();
        int itemsToRemove;
        int blocksToRemove;

        if (blockValue <= 0 || !currencyManager.hasBlockForm()) {
            if (breakdown.items() < toRemove) {
                return 0;
            }
            itemsToRemove = toRemove;
            blocksToRemove = 0;
        } else {
            int itemsAvailable = Math.toIntExact(breakdown.items());
            int blocksAvailable = Math.toIntExact(breakdown.blocks());

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

    public record CurrencyBreakdown(long items, long blocks, long totalValue) {
        public static CurrencyBreakdown of(long items, long blocks, int blockValue) {
            return new CurrencyBreakdown(items, blocks, items + blocks * Math.max(blockValue, 0));
        }
    }

    public record CurrencyInventorySnapshot(
            long inventoryCurrency,
            long enderChestCurrency,
            long shulkerCurrency,
            long totalCurrency,
            long shulkersScanned
    ) {
    }

    public static long countCurrencyInPlayer(CurrencyManager manager, Player player) {
        return getCurrencyBreakdown(manager, player).totalValue();
    }

    public static CurrencyBreakdown getCurrencyBreakdown(CurrencyManager manager, Player player) {
        CountResult invCounts = countInInventory(manager, player.getInventory());
        CountResult ecCounts = countInInventory(manager, player.getEnderChest());
        long items = invCounts.items() + invCounts.shulkerItems() + ecCounts.items() + ecCounts.shulkerItems();
        long blocks = invCounts.blocks() + invCounts.shulkerBlocks() + ecCounts.blocks() + ecCounts.shulkerBlocks();
        return CurrencyBreakdown.of(items, blocks, manager.getBlockValue());
    }

    public static CurrencyInventorySnapshot countCurrencyLocations(CurrencyManager manager, Player player) {
        CountResult invCounts = countInInventory(manager, player.getInventory());
        CountResult ecCounts = countInInventory(manager, player.getEnderChest());

        long inventoryCurrency = invCounts.directValue(manager.getBlockValue());
        long enderChestCurrency = ecCounts.directValue(manager.getBlockValue());
        long shulkerCurrency = invCounts.shulkerValue(manager.getBlockValue()) + ecCounts.shulkerValue(manager.getBlockValue());
        return new CurrencyInventorySnapshot(
                inventoryCurrency,
                enderChestCurrency,
                shulkerCurrency,
                inventoryCurrency + enderChestCurrency + shulkerCurrency,
                invCounts.shulkersScanned() + ecCounts.shulkersScanned()
        );
    }

    private record CountResult(long items, long blocks, long shulkerItems, long shulkerBlocks, long shulkersScanned) {

        long directValue(int blockValue) {
            return items + blocks * Math.max(blockValue, 0);
        }

        long shulkerValue(int blockValue) {
            return shulkerItems + shulkerBlocks * Math.max(blockValue, 0);
        }
    }

    private static CountResult countInInventory(CurrencyManager manager, Inventory inv) {
        long items = 0;
        long blocks = 0;
        long shulkerItems = 0;
        long shulkerBlocks = 0;
        long shulkersScanned = 0;

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
                    CountResult inner = countInInventory(manager, box.getInventory());
                    shulkerItems += inner.items() + inner.shulkerItems();
                    shulkerBlocks += inner.blocks() + inner.shulkerBlocks();
                    shulkersScanned += 1L + inner.shulkersScanned();
                }
            }
        }

        return new CountResult(items, blocks, shulkerItems, shulkerBlocks, shulkersScanned);
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

        int remainingItems = itemsToRemove;
        int remainingBlocks = blocksToRemove;
        for (int i = 0; i < inv.getSize(); i++) {
            if (remainingItems <= 0 && remainingBlocks <= 0) break;

            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.getType() == Material.AIR) continue;

            if (manager.isCurrencyItem(stack) && remainingItems > 0) {
                int take = Math.min(stack.getAmount(), remainingItems);
                remainingItems -= take;
                int newAmount = stack.getAmount() - take;
                if (newAmount <= 0) {
                    inv.setItem(i, null);
                } else {
                    stack.setAmount(newAmount);
                }
                continue;
            }

            if (manager.isCurrencyBlock(stack) && remainingBlocks > 0) {
                int take = Math.min(stack.getAmount(), remainingBlocks);
                remainingBlocks -= take;
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
                    int[] innerRem = removeFromInventory(manager, innerInv, remainingItems, remainingBlocks);
                    remainingItems = innerRem[0];
                    remainingBlocks = innerRem[1];
                    box.getInventory().setContents(innerInv.getContents());
                    meta.setBlockState(box);
                    stack.setItemMeta(meta);
                    if (remainingItems <= 0 && remainingBlocks <= 0) break;
                }
            }
        }

        return new int[]{remainingItems, remainingBlocks};
    }
}
