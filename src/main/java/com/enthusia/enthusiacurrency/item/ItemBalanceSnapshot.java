package com.enthusia.enthusiacurrency.item;

import java.util.UUID;

public record ItemBalanceSnapshot(
        UUID uuid,
        String lastKnownName,
        long inventoryCurrency,
        long enderChestCurrency,
        long shulkerCurrency,
        long totalItemCurrency,
        long lastScannedAtMillis,
        boolean dirty,
        boolean scanInProgress
) {

    public static ItemBalanceSnapshot empty(UUID uuid, String lastKnownName) {
        return new ItemBalanceSnapshot(uuid, lastKnownName, 0L, 0L, 0L, 0L, 0L, true, false);
    }

    public ItemBalanceSnapshot markDirty() {
        return new ItemBalanceSnapshot(uuid, lastKnownName, inventoryCurrency, enderChestCurrency, shulkerCurrency,
                totalItemCurrency, lastScannedAtMillis, true, scanInProgress);
    }

    public ItemBalanceSnapshot markScanning() {
        return new ItemBalanceSnapshot(uuid, lastKnownName, inventoryCurrency, enderChestCurrency, shulkerCurrency,
                totalItemCurrency, lastScannedAtMillis, dirty, true);
    }
}
