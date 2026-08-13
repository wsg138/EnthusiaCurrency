package com.enthusia.enthusiacurrency.moderation;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Process-local, expiring ownership lock used while a destructive operation is in flight. */
public final class MovementLockRegistry {

    private final ConcurrentHashMap<UUID, Lease> leases = new ConcurrentHashMap<>();
    private final LongSupplier nowMillis;

    public MovementLockRegistry() {
        this(System::currentTimeMillis);
    }

    MovementLockRegistry(LongSupplier nowMillis) {
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
    }

    public boolean acquire(UUID playerId, UUID operationId, Duration duration) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        long expiresAt = expiry(duration);
        long now = nowMillis.getAsLong();
        AtomicBoolean acquired = new AtomicBoolean();
        leases.compute(playerId, (ignored, existing) -> {
            if (existing == null || existing.expired(now) || existing.operationId().equals(operationId)) {
                acquired.set(true);
                return new Lease(operationId, expiresAt);
            }
            return existing;
        });
        return acquired.get();
    }

    public boolean renew(UUID playerId, UUID operationId, Duration duration) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        long expiresAt = expiry(duration);
        long now = nowMillis.getAsLong();
        AtomicBoolean renewed = new AtomicBoolean();
        leases.computeIfPresent(playerId, (ignored, existing) -> {
            if (existing.expired(now)) {
                return null;
            }
            if (!existing.operationId().equals(operationId)) {
                return existing;
            }
            renewed.set(true);
            return new Lease(operationId, expiresAt);
        });
        return renewed.get();
    }

    public boolean release(UUID playerId, UUID operationId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        AtomicBoolean released = new AtomicBoolean();
        leases.computeIfPresent(playerId, (ignored, existing) -> {
            if (existing.operationId().equals(operationId)) {
                released.set(true);
                return null;
            }
            return existing;
        });
        return released.get();
    }

    public boolean isLocked(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        long now = nowMillis.getAsLong();
        AtomicBoolean locked = new AtomicBoolean();
        leases.computeIfPresent(playerId, (ignored, existing) -> {
            if (existing.expired(now)) {
                return null;
            }
            locked.set(true);
            return existing;
        });
        return locked.get();
    }

    public boolean isOwnedBy(UUID playerId, UUID operationId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(operationId, "operationId");
        long now = nowMillis.getAsLong();
        AtomicBoolean owned = new AtomicBoolean();
        leases.computeIfPresent(playerId, (ignored, existing) -> {
            if (existing.expired(now)) {
                return null;
            }
            owned.set(existing.operationId().equals(operationId));
            return existing;
        });
        return owned.get();
    }

    public void clear() {
        leases.clear();
    }

    private long expiry(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        long millis = duration.toMillis();
        if (millis <= 0L) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        try {
            return Math.addExact(nowMillis.getAsLong(), millis);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private record Lease(UUID operationId, long expiresAtMillis) {
        private boolean expired(long now) {
            return now >= expiresAtMillis;
        }
    }
}
