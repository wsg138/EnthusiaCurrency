package com.enthusia.enthusiacurrency.moderation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MovementLockRegistryTest {

    @Test
    void onlyOwnerCanRenewOrReleaseAndExpiredLeaseCanBeReclaimed() {
        AtomicLong now = new AtomicLong(1_000L);
        MovementLockRegistry registry = new MovementLockRegistry(now::get);
        UUID player = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        assertTrue(registry.acquire(player, first, Duration.ofSeconds(2)));
        assertFalse(registry.acquire(player, second, Duration.ofSeconds(2)));
        assertFalse(registry.renew(player, second, Duration.ofSeconds(2)));
        assertFalse(registry.release(player, second));
        assertTrue(registry.isOwnedBy(player, first));

        now.set(3_001L);
        assertFalse(registry.isLocked(player));
        assertTrue(registry.acquire(player, second, Duration.ofSeconds(2)));
        assertTrue(registry.renew(player, second, Duration.ofSeconds(3)));
        assertTrue(registry.release(player, second));
        assertFalse(registry.isLocked(player));
    }
}
