package com.enthusia.enthusiacurrency.api.moderation;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Exact before-state used for stale-state detection and recovery.
 *
 * <p>Serialized item arrays use Paper's versioned item-stack codec and should only be restored by
 * the same compatible server version.</p>
 */
public record CurrencyAccountSnapshot(
        UUID playerId,
        long bankBalance,
        long bankRevision,
        byte[] inventory,
        byte[] enderChest,
        long inventoryValue,
        long enderChestValue,
        long authoritativeTotal,
        String checksum
) {
    public CurrencyAccountSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        inventory = Objects.requireNonNull(inventory, "inventory").clone();
        enderChest = Objects.requireNonNull(enderChest, "enderChest").clone();
        if (bankBalance < 0L || bankRevision < 0L || inventoryValue < 0L
                || enderChestValue < 0L || authoritativeTotal < 0L) {
            throw new IllegalArgumentException("snapshot values cannot be negative");
        }
        long calculatedTotal = Math.addExact(
                bankBalance,
                Math.addExact(inventoryValue, enderChestValue)
        );
        if (authoritativeTotal != calculatedTotal) {
            throw new IllegalArgumentException("authoritativeTotal does not match its components");
        }
        if (!Objects.requireNonNull(checksum, "checksum").matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksum must be a lowercase SHA-256 value");
        }
    }

    @Override
    public byte[] inventory() {
        return inventory.clone();
    }

    @Override
    public byte[] enderChest() {
        return enderChest.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CurrencyAccountSnapshot that)) {
            return false;
        }
        return sameAmounts(that) && sameIdentityAndContents(that);
    }

    private boolean sameAmounts(CurrencyAccountSnapshot that) {
        return bankBalance == that.bankBalance
                && bankRevision == that.bankRevision
                && inventoryValue == that.inventoryValue
                && enderChestValue == that.enderChestValue
                && authoritativeTotal == that.authoritativeTotal;
    }

    private boolean sameIdentityAndContents(CurrencyAccountSnapshot that) {
        return playerId.equals(that.playerId)
                && Arrays.equals(inventory, that.inventory)
                && Arrays.equals(enderChest, that.enderChest)
                && checksum.equals(that.checksum);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                playerId,
                bankBalance,
                bankRevision,
                inventoryValue,
                enderChestValue,
                authoritativeTotal,
                checksum
        );
        result = 31 * result + Arrays.hashCode(inventory);
        return 31 * result + Arrays.hashCode(enderChest);
    }
}
