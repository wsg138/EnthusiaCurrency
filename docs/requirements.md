# Performance requirements

## CUR-PERF-001 - Stale balance read coalescing

WHEN repeated balance reads encounter a stale item snapshot THE SYSTEM SHALL queue one refresh for that player and preserve its deadline until it executes. Reads SHALL retain the cached value without inventory or financial mutation. Real inventory-change events SHALL retain their existing trailing debounce behavior; exact transaction validation SHALL remain unchanged.

## CUR-PERF-002 - Recovery and lifecycle

WHEN the queued refresh completes THE SYSTEM SHALL publish the refreshed count and permit a later stale period to queue again. Plugin stop SHALL cancel pending work. Separate players SHALL retain independent pending refreshes. Snapshot cache misses and offline reads SHALL retain existing behavior.
