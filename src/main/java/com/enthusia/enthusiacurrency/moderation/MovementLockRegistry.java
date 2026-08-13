package com.enthusia.enthusiacurrency.moderation;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** Process-local, expiring ownership lock used while a destructive operation is in flight. */
public final class MovementLockRegistry {

    private static final String PLAYER_ID_ARGUMENT = "playerId";
    private static final String OPERATION_ID_ARGUMENT = "operationId";

    private final ConcurrentHashMap<UUID, Lease> leases = new ConcurrentHashMap<>();
    private final LongSupplier nowMillis;

    public MovementLockRegistry() {
        this(System::currentTimeMillis);
    }

    MovementLockRegistry(LongSupplier nowMillis) {
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
    }

    public boolean acquire(UUID playerId, UUID operationId, Duration duration) {
        Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
        Objects.requireNonNull(operationId, OPERATION_ID_ARGUMENT);
        long durationMillis = leaseMillis(duration);
        AtomicBoolean acquired = new AtomicBoolean();
        leases.compute(playerId, (ignored, existing) -> {
            long now = nowMillis.getAsLong();
            if (existing == null || existing.expired(now) || existing.operationId().equals(operationId)) {
                acquired.set(true);
                return new Lease(operationId, expiry(now, durationMillis));
            }
            return existing;
        });
        return acquired.get();
    }

    public boolean renew(UUID playerId, UUID operationId, Duration duration) {
        Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
        Objects.requireNonNull(operationId, OPERATION_ID_ARGUMENT);
        long durationMillis = leaseMillis(duration);
        AtomicBoolean renewed = new AtomicBoolean();
        leases.computeIfPresent(playerId, (ignored, existing) -> {
            long now = nowMillis.getAsLong();
            if (existing.expired(now)) {
                return null;
            }
            if (!existing.operationId().equals(operationId)) {
                return existing;
            }
            renewed.set(true);
            return new Lease(operationId, expiry(now, durationMillis));
        });
        return renewed.get();
    }

    public boolean release(UUID playerId, UUID operationId) {
        Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
        Objects.requireNonNull(operationId, OPERATION_ID_ARGUMENT);
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
        Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
        AtomicBoolean locked = new AtomicBoolean();
        leases.computeIfPresent(playerId, (ignored, existing) -> {
            if (existing.expired(nowMillis.getAsLong())) {
                return null;
            }
            locked.set(true);
            return existing;
        });
        return locked.get();
    }

    public boolean isOwnedBy(UUID playerId, UUID operationId) {
        Objects.requireNonNull(playerId, PLAYER_ID_ARGUMENT);
        Objects.requireNonNull(operationId, OPERATION_ID_ARGUMENT);
        AtomicBoolean owned = new AtomicBoolean();
        leases.computeIfPresent(playerId, (ignored, existing) -> {
            if (existing.expired(nowMillis.getAsLong())) {
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

    private static long leaseMillis(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("lease duration must be positive");
        }
        try {
            long millis = duration.toMillis();
            return millis == 0L ? 1L : millis;
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long expiry(long now, long durationMillis) {
        try {
            return Math.addExact(now, durationMillis);
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
