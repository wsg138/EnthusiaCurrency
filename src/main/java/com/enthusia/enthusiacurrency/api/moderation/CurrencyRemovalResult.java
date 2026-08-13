package com.enthusia.enthusiacurrency.api.moderation;

import java.util.Objects;
import java.util.Optional;

/** Debit outcome and, when observable, the actual state after commit or compensation. */
public record CurrencyRemovalResult(
        Status status,
        long amountRemoved,
        long finalTotal,
        Optional<CurrencyAccountSnapshot> accountState,
        String detail
) {
    public CurrencyRemovalResult {
        Objects.requireNonNull(status, "status");
        if (amountRemoved < 0L || finalTotal < 0L) {
            throw new IllegalArgumentException("result values cannot be negative");
        }
        accountState = Objects.requireNonNull(accountState, "accountState");
        if (accountState.isPresent()
                && accountState.orElseThrow().authoritativeTotal() != finalTotal) {
            throw new IllegalArgumentException("finalTotal does not match accountState");
        }
        detail = detail == null ? "" : detail;
    }

    public CurrencyRemovalResult(Status status, long amountRemoved, long finalTotal, String detail) {
        this(status, amountRemoved, finalTotal, Optional.empty(), detail);
    }

    public boolean success() {
        return status == Status.COMMITTED;
    }

    public enum Status {
        COMMITTED,
        STALE,
        INVALID_PLAN,
        LOCK_REQUIRED,
        PLAYER_OFFLINE,
        FAILED_ROLLED_BACK,
        QUARANTINE_REQUIRED
    }
}
