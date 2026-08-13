package com.enthusia.enthusiacurrency.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.enthusia.enthusiacurrency.api.moderation.CurrencyAccountSnapshot;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRemovalPlan;
import com.enthusia.enthusiacurrency.api.moderation.CurrencySource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrencyModerationStateEvaluatorTest {

    private static final String BEFORE_CHECKSUM = "a".repeat(64);
    private static final String REPLACEMENT_CHECKSUM = "b".repeat(64);
    private static final String RESTORED_CHECKSUM = "c".repeat(64);

    @Test
    void invalidPlanCannotMasqueradeAsCommittedReplay() {
        UUID playerId = UUID.randomUUID();
        CurrencyAccountSnapshot before = snapshot(playerId, 10L, 5L, BEFORE_CHECKSUM);
        CurrencyRemovalPlan supplied = plan(before, REPLACEMENT_CHECKSUM);
        CurrencyAccountSnapshot current = snapshot(playerId, 9L, 6L, REPLACEMENT_CHECKSUM);

        assertEquals(
                CurrencyModerationStateEvaluator.RemovalState.INVALID_PLAN,
                CurrencyModerationStateEvaluator.removal(current, supplied, false)
        );
    }

    @Test
    void validCommittedPlanRemainsIdempotent() {
        UUID playerId = UUID.randomUUID();
        CurrencyAccountSnapshot before = snapshot(playerId, 10L, 5L, BEFORE_CHECKSUM);
        CurrencyRemovalPlan supplied = plan(before, REPLACEMENT_CHECKSUM);
        CurrencyAccountSnapshot current = snapshot(playerId, 9L, 6L, REPLACEMENT_CHECKSUM);

        assertEquals(
                CurrencyModerationStateEvaluator.RemovalState.COMMITTED,
                CurrencyModerationStateEvaluator.removal(current, supplied, true)
        );
    }

    @Test
    void unadvancedBeforeStateIsNotAcceptedAsRestored() {
        UUID playerId = UUID.randomUUID();
        CurrencyAccountSnapshot requested = snapshot(playerId, 10L, 5L, BEFORE_CHECKSUM);
        CurrencyAccountSnapshot current = snapshot(playerId, 10L, 5L, BEFORE_CHECKSUM);

        assertEquals(
                CurrencyModerationStateEvaluator.RestoreState.STALE,
                CurrencyModerationStateEvaluator.restore(
                        current,
                        requested,
                        REPLACEMENT_CHECKSUM,
                        true
                )
        );
    }

    @Test
    void advancedMatchingAssetsAreAcceptedAsIdempotentRestore() {
        UUID playerId = UUID.randomUUID();
        CurrencyAccountSnapshot requested = snapshot(playerId, 10L, 5L, BEFORE_CHECKSUM);
        CurrencyAccountSnapshot current = snapshot(playerId, 10L, 6L, RESTORED_CHECKSUM);

        assertEquals(
                CurrencyModerationStateEvaluator.RestoreState.RESTORED,
                CurrencyModerationStateEvaluator.restore(
                        current,
                        requested,
                        REPLACEMENT_CHECKSUM,
                        true
                )
        );
    }

    private static CurrencyRemovalPlan plan(
            CurrencyAccountSnapshot before,
            String replacementChecksum
    ) {
        return new CurrencyRemovalPlan(
                UUID.randomUUID(),
                before.playerId(),
                1L,
                before,
                9L,
                new byte[]{3},
                new byte[]{4},
                9L,
                replacementChecksum,
                List.of(
                        CurrencySource.BANK,
                        CurrencySource.INVENTORY,
                        CurrencySource.ENDER_CHEST
                )
        );
    }

    private static CurrencyAccountSnapshot snapshot(
            UUID playerId,
            long bankBalance,
            long bankRevision,
            String checksum
    ) {
        return new CurrencyAccountSnapshot(
                playerId,
                bankBalance,
                bankRevision,
                new byte[]{1},
                new byte[]{2},
                0L,
                0L,
                bankBalance,
                checksum
        );
    }
}
