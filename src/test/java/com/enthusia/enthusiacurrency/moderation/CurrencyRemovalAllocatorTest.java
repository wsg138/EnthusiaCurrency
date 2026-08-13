package com.enthusia.enthusiacurrency.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CurrencyRemovalAllocatorTest {

    @Test
    void neverOverRemovesWhenOnlyBlocksExist() {
        CurrencyRemovalAllocator.Allocation allocation = CurrencyRemovalAllocator.maximum(0L, 4L, 9, 5L);
        assertEquals(0L, allocation.value());
        assertEquals(0L, allocation.blocks());
    }

    @Test
    void combinesBlocksAndUnitItemsForAnExactMaximum() {
        CurrencyRemovalAllocator.Allocation allocation = CurrencyRemovalAllocator.maximum(5L, 2L, 9, 20L);
        assertEquals(20L, allocation.value());
        assertEquals(2L, allocation.blocks());
        assertEquals(2L, allocation.items());
    }

    @Test
    void unitOnlyCurrencyRemainsExact() {
        CurrencyRemovalAllocator.Allocation allocation = CurrencyRemovalAllocator.maximum(12L, 99L, 0, 7L);
        assertEquals(7L, allocation.value());
        assertEquals(7L, allocation.items());
        assertEquals(0L, allocation.blocks());
    }
}
