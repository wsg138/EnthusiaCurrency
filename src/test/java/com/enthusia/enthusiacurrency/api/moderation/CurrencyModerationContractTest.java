package com.enthusia.enthusiacurrency.api.moderation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CurrencyModerationContractTest {
    private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String CHECKSUM_A = "a".repeat(64);
    private static final String CHECKSUM_B = "b".repeat(64);

    @Test
    void accountSnapshotDefensivelyCopiesItemBytesAndUsesContentEquality() {
        byte[] inventory = {1, 2};
        byte[] ender = {3, 4};
        CurrencyAccountSnapshot snapshot = snapshot(PLAYER, 100L, 20L, 30L, inventory, ender, CHECKSUM_A);
        CurrencyAccountSnapshot equivalent = snapshot(PLAYER, 100L, 20L, 30L,
                new byte[]{1, 2}, new byte[]{3, 4}, CHECKSUM_A);

        inventory[0] = 9;
        ender[0] = 9;
        byte[] exposedInventory = snapshot.inventory();
        exposedInventory[0] = 8;

        assertArrayEquals(new byte[]{1, 2}, snapshot.inventory());
        assertArrayEquals(new byte[]{3, 4}, snapshot.enderChest());
        assertEquals(equivalent, snapshot);
        assertEquals(equivalent.hashCode(), snapshot.hashCode());
        assertNotEquals(snapshot(PLAYER, 101L, 20L, 30L, new byte[]{1, 2}, new byte[]{3, 4}, CHECKSUM_A), snapshot);
    }

    @Test
    void accountSnapshotRejectsNegativeMismatchedOverflowingAndMalformedState() {
        assertThrows(IllegalArgumentException.class, () -> new CurrencyAccountSnapshot(
                PLAYER, -1L, 0L, new byte[0], new byte[0], 0L, 0L, 0L, CHECKSUM_A));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyAccountSnapshot(
                PLAYER, 1L, 0L, new byte[0], new byte[0], 1L, 1L, 99L, CHECKSUM_A));
        assertThrows(ArithmeticException.class, () -> new CurrencyAccountSnapshot(
                PLAYER, Long.MAX_VALUE, 0L, new byte[0], new byte[0], 1L, 0L, Long.MAX_VALUE, CHECKSUM_A));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyAccountSnapshot(
                PLAYER, 0L, 0L, new byte[0], new byte[0], 0L, 0L, 0L, "A".repeat(64)));
    }

    @Test
    void removalPlanRequiresMatchingPlayerExactDebitAndEverySourceExactlyOnce() {
        CurrencyAccountSnapshot before = snapshot(PLAYER, 100L, 20L, 30L,
                new byte[]{1}, new byte[]{2}, CHECKSUM_A);
        byte[] replacementInventory = {7};
        byte[] replacementEnder = {8};
        CurrencyRemovalPlan plan = new CurrencyRemovalPlan(
                UUID.randomUUID(),
                PLAYER,
                60L,
                before,
                40L,
                replacementInventory,
                replacementEnder,
                90L,
                CHECKSUM_B,
                List.of(CurrencySource.BANK, CurrencySource.INVENTORY, CurrencySource.ENDER_CHEST)
        );

        replacementInventory[0] = 0;
        replacementEnder[0] = 0;
        assertArrayEquals(new byte[]{7}, plan.replacementInventory());
        assertArrayEquals(new byte[]{8}, plan.replacementEnderChest());
        assertEquals(90L, plan.expectedFinalTotal());

        assertThrows(IllegalArgumentException.class, () -> new CurrencyRemovalPlan(
                UUID.randomUUID(), OTHER, 1L, before, 99L, new byte[0], new byte[0],
                149L, CHECKSUM_B, allSources()));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyRemovalPlan(
                UUID.randomUUID(), PLAYER, 0L, before, 100L, new byte[0], new byte[0],
                150L, CHECKSUM_B, allSources()));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyRemovalPlan(
                UUID.randomUUID(), PLAYER, 151L, before, 0L, new byte[0], new byte[0],
                0L, CHECKSUM_B, allSources()));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyRemovalPlan(
                UUID.randomUUID(), PLAYER, 60L, before, 40L, new byte[0], new byte[0],
                91L, CHECKSUM_B, allSources()));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyRemovalPlan(
                UUID.randomUUID(), PLAYER, 60L, before, 40L, new byte[0], new byte[0],
                90L, CHECKSUM_B,
                List.of(CurrencySource.BANK, CurrencySource.BANK, CurrencySource.ENDER_CHEST)));
    }

    @Test
    void removalResultValidatesObservableFinalStateAndOnlyCommittedIsSuccess() {
        CurrencyAccountSnapshot finalState = snapshot(PLAYER, 40L, 20L, 30L,
                new byte[]{1}, new byte[]{2}, CHECKSUM_B);
        CurrencyRemovalResult committed = new CurrencyRemovalResult(
                CurrencyRemovalResult.Status.COMMITTED, 60L, 90L, Optional.of(finalState), null);

        assertTrue(committed.success());
        assertEquals("", committed.detail());
        for (CurrencyRemovalResult.Status status : CurrencyRemovalResult.Status.values()) {
            CurrencyRemovalResult result = new CurrencyRemovalResult(status, 0L, 90L, Optional.of(finalState), "detail");
            assertEquals(status == CurrencyRemovalResult.Status.COMMITTED, result.success(), status.name());
        }
        assertThrows(IllegalArgumentException.class, () -> new CurrencyRemovalResult(
                CurrencyRemovalResult.Status.STALE, 0L, 91L, Optional.of(finalState), "bad"));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyRemovalResult(
                CurrencyRemovalResult.Status.STALE, -1L, 90L, Optional.of(finalState), "bad"));
    }

    @Test
    void restoreResultOnlyTreatsRestoredAsSuccessAndNormalizesNullDetail() {
        CurrencyRestoreResult restored = new CurrencyRestoreResult(CurrencyRestoreResult.Status.RESTORED, (String) null);
        assertTrue(restored.success());
        assertEquals("", restored.detail());

        for (CurrencyRestoreResult.Status status : CurrencyRestoreResult.Status.values()) {
            CurrencyRestoreResult result = new CurrencyRestoreResult(status, "detail");
            assertEquals(status == CurrencyRestoreResult.Status.RESTORED, result.success(), status.name());
        }
        assertFalse(new CurrencyRestoreResult(CurrencyRestoreResult.Status.STALE, "stale").success());
    }

    private static CurrencyAccountSnapshot snapshot(
            UUID player,
            long bank,
            long inventoryValue,
            long enderValue,
            byte[] inventory,
            byte[] ender,
            String checksum
    ) {
        return new CurrencyAccountSnapshot(
                player,
                bank,
                1L,
                inventory,
                ender,
                inventoryValue,
                enderValue,
                Math.addExact(bank, Math.addExact(inventoryValue, enderValue)),
                checksum
        );
    }

    private static List<CurrencySource> allSources() {
        return List.of(CurrencySource.BANK, CurrencySource.INVENTORY, CurrencySource.ENDER_CHEST);
    }
}
