package com.enthusia.enthusiacurrency.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ItemBalanceSnapshotTest {
    private static final UUID PLAYER = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void emptySnapshotStartsDirtyAndNotScanningWithZeroBalances() {
        ItemBalanceSnapshot snapshot = ItemBalanceSnapshot.empty(PLAYER, "PlayerOne");

        assertEquals(PLAYER, snapshot.uuid());
        assertEquals("PlayerOne", snapshot.lastKnownName());
        assertEquals(0L, snapshot.inventoryCurrency());
        assertEquals(0L, snapshot.enderChestCurrency());
        assertEquals(0L, snapshot.shulkerCurrency());
        assertEquals(0L, snapshot.totalItemCurrency());
        assertEquals(0L, snapshot.lastScannedAtMillis());
        assertTrue(snapshot.dirty());
        assertFalse(snapshot.scanInProgress());
    }

    @Test
    void markDirtyAndMarkScanningPreserveAllObservedBalancesAndIdentity() {
        ItemBalanceSnapshot clean = new ItemBalanceSnapshot(
                PLAYER, "PlayerOne", 10L, 20L, 30L, 60L, 12345L, false, false);

        ItemBalanceSnapshot dirty = clean.markDirty();
        ItemBalanceSnapshot scanning = clean.markScanning();

        assertEquals(PLAYER, dirty.uuid());
        assertEquals("PlayerOne", dirty.lastKnownName());
        assertEquals(10L, dirty.inventoryCurrency());
        assertEquals(20L, dirty.enderChestCurrency());
        assertEquals(30L, dirty.shulkerCurrency());
        assertEquals(60L, dirty.totalItemCurrency());
        assertEquals(12345L, dirty.lastScannedAtMillis());
        assertTrue(dirty.dirty());
        assertFalse(dirty.scanInProgress());

        assertEquals(60L, scanning.totalItemCurrency());
        assertFalse(scanning.dirty());
        assertTrue(scanning.scanInProgress());
    }
}
