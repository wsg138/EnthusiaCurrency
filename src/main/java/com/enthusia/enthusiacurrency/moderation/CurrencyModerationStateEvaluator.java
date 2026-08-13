package com.enthusia.enthusiacurrency.moderation;

import com.enthusia.enthusiacurrency.api.moderation.CurrencyAccountSnapshot;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRemovalPlan;

/** Applies fail-closed ordering to destructive currency state transitions. */
final class CurrencyModerationStateEvaluator {

    private CurrencyModerationStateEvaluator() {
    }

    static RemovalState removal(
            CurrencyAccountSnapshot current,
            CurrencyRemovalPlan plan,
            boolean validPlan
    ) {
        if (!validPlan) {
            return RemovalState.INVALID_PLAN;
        }
        if (isCommitted(current, plan)) {
            return RemovalState.COMMITTED;
        }
        if (!current.checksum().equals(plan.before().checksum())) {
            return RemovalState.STALE;
        }
        return RemovalState.APPLY;
    }

    static RestoreState restore(
            CurrencyAccountSnapshot current,
            CurrencyAccountSnapshot requested,
            String expectedCurrentChecksum,
            boolean sameAssets
    ) {
        if (sameAssets && current.bankRevision() > requested.bankRevision()) {
            return RestoreState.RESTORED;
        }
        if (!current.checksum().equals(expectedCurrentChecksum)) {
            return RestoreState.STALE;
        }
        return RestoreState.APPLY;
    }

    static boolean isCommitted(
            CurrencyAccountSnapshot current,
            CurrencyRemovalPlan plan
    ) {
        return current.checksum().equals(plan.replacementChecksum())
                && current.authoritativeTotal() == plan.expectedFinalTotal();
    }

    enum RemovalState {
        INVALID_PLAN,
        COMMITTED,
        STALE,
        APPLY
    }

    enum RestoreState {
        RESTORED,
        STALE,
        APPLY
    }
}
