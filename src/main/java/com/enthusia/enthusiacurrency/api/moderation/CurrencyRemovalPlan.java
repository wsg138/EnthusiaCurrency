package com.enthusia.enthusiacurrency.api.moderation;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable exact debit plan prepared from a {@link CurrencyAccountSnapshot}. */
public record CurrencyRemovalPlan(
        UUID operationId,
        UUID playerId,
        long amount,
        CurrencyAccountSnapshot before,
        long replacementBankBalance,
        byte[] replacementInventory,
        byte[] replacementEnderChest,
        long expectedFinalTotal,
        String replacementChecksum,
        List<CurrencySource> sourceOrder
) {
    public CurrencyRemovalPlan {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(before, "before");
        if (!playerId.equals(before.playerId())) {
            throw new IllegalArgumentException("plan player does not match snapshot player");
        }
        if (amount <= 0L || amount > before.authoritativeTotal()) {
            throw new IllegalArgumentException(
                    "amount must be positive and no greater than the before total"
            );
        }
        if (replacementBankBalance < 0L || expectedFinalTotal < 0L) {
            throw new IllegalArgumentException("replacement values cannot be negative");
        }
        if (expectedFinalTotal != before.authoritativeTotal() - amount) {
            throw new IllegalArgumentException("expectedFinalTotal does not match the debit");
        }
        replacementInventory = Objects.requireNonNull(
                replacementInventory,
                "replacementInventory"
        ).clone();
        replacementEnderChest = Objects.requireNonNull(
                replacementEnderChest,
                "replacementEnderChest"
        ).clone();
        if (!Objects.requireNonNull(
                replacementChecksum,
                "replacementChecksum"
        ).matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("replacementChecksum must be a lowercase SHA-256 value");
        }
        sourceOrder = List.copyOf(Objects.requireNonNull(sourceOrder, "sourceOrder"));
        if (sourceOrder.size() != CurrencySource.values().length
                || !EnumSet.copyOf(sourceOrder).equals(EnumSet.allOf(CurrencySource.class))) {
            throw new IllegalArgumentException(
                    "sourceOrder must contain each currency source exactly once"
            );
        }
    }

    @Override
    public byte[] replacementInventory() {
        return replacementInventory.clone();
    }

    @Override
    public byte[] replacementEnderChest() {
        return replacementEnderChest.clone();
    }
}
