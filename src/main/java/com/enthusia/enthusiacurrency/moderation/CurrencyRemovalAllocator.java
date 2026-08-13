package com.enthusia.enthusiacurrency.moderation;

/** Exact-denomination allocator shared by planning and tests. */
final class CurrencyRemovalAllocator {

    private static final long ZERO_COUNT = 0L;
    private static final int ZERO_BLOCK_VALUE = 0;

    private CurrencyRemovalAllocator() {
    }

    static Allocation maximum(long items, long blocks, int blockValue, long limit) {
        if (items < ZERO_COUNT || blocks < ZERO_COUNT
                || blockValue < ZERO_BLOCK_VALUE || limit < ZERO_COUNT) {
            throw new IllegalArgumentException("currency counts and limit cannot be negative");
        }
        if (limit == ZERO_COUNT) {
            return new Allocation(ZERO_COUNT, ZERO_COUNT, ZERO_COUNT);
        }
        if (blockValue <= ZERO_BLOCK_VALUE) {
            long takenItems = Math.min(items, limit);
            return new Allocation(takenItems, ZERO_COUNT, takenItems);
        }
        long takenBlocks = Math.min(blocks, limit / blockValue);
        long blockAmount = Math.multiplyExact(takenBlocks, blockValue);
        long takenItems = Math.min(items, limit - blockAmount);
        return new Allocation(takenItems, takenBlocks, Math.addExact(blockAmount, takenItems));
    }

    record Allocation(long items, long blocks, long value) {
    }
}
