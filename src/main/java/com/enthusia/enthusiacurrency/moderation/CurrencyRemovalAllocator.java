package com.enthusia.enthusiacurrency.moderation;

/** Exact-denomination allocator shared by planning and tests. */
final class CurrencyRemovalAllocator {

    private CurrencyRemovalAllocator() {
    }

    static Allocation maximum(long items, long blocks, int blockValue, long limit) {
        if (items < 0L || blocks < 0L || blockValue < 0 || limit < 0L) {
            throw new IllegalArgumentException("currency counts and limit cannot be negative");
        }
        if (limit == 0L) {
            return new Allocation(0L, 0L, 0L);
        }
        if (blockValue <= 0) {
            long takenItems = Math.min(items, limit);
            return new Allocation(takenItems, 0L, takenItems);
        }
        long takenBlocks = Math.min(blocks, limit / blockValue);
        long blockAmount = Math.multiplyExact(takenBlocks, blockValue);
        long takenItems = Math.min(items, limit - blockAmount);
        return new Allocation(takenItems, takenBlocks, Math.addExact(blockAmount, takenItems));
    }

    record Allocation(long items, long blocks, long value) {
    }
}
