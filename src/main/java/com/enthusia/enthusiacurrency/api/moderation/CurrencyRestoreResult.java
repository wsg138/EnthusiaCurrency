package com.enthusia.enthusiacurrency.api.moderation;

import java.util.Objects;
import java.util.Optional;

/** Restore outcome and, when observable, the actual state after restoration or compensation. */
public record CurrencyRestoreResult(
        Status status,
        Optional<CurrencyAccountSnapshot> accountState,
        String detail
) {
    public CurrencyRestoreResult {
        Objects.requireNonNull(status, "status");
        accountState = Objects.requireNonNull(accountState, "accountState");
        detail = detail == null ? "" : detail;
    }

    public CurrencyRestoreResult(Status status, String detail) {
        this(status, Optional.empty(), detail);
    }

    public boolean success() {
        return status == Status.RESTORED;
    }

    public enum Status {
        RESTORED,
        STALE,
        LOCK_REQUIRED,
        PLAYER_OFFLINE,
        FAILED_ROLLED_BACK,
        QUARANTINE_REQUIRED
    }
}
