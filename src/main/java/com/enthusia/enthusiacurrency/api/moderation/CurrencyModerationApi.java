package com.enthusia.enthusiacurrency.api.moderation;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/**
 * Stable moderation service registered with Bukkit's {@code ServicesManager}.
 *
 * <p>Snapshot, planning, apply, and restore methods must be called on the primary server thread.
 * The returned stage completes only after the bank mutation has been flushed or safely rolled
 * back. A successful restore reproduces the requested assets with a new monotonic bank revision;
 * callers must retain the account state returned by {@link CurrencyRestoreResult}. Completion
 * callbacks are not guaranteed to run on the primary thread.</p>
 */
public interface CurrencyModerationApi {

    int API_VERSION = 1;

    int apiVersion();

    boolean acquireMovementLock(UUID playerId, UUID operationId, Duration leaseDuration);

    boolean renewMovementLock(UUID playerId, UUID operationId, Duration leaseDuration);

    boolean releaseMovementLock(UUID playerId, UUID operationId);

    boolean isMovementLocked(UUID playerId);

    CurrencyAccountSnapshot snapshot(Player player);

    CurrencyRemovalPlan planRemoval(
            UUID operationId,
            CurrencyAccountSnapshot snapshot,
            long amount,
            List<CurrencySource> sourceOrder
    );

    CompletionStage<CurrencyRemovalResult> applyRemoval(Player player, CurrencyRemovalPlan plan);

    CompletionStage<CurrencyRestoreResult> restore(
            Player player,
            UUID operationId,
            CurrencyAccountSnapshot snapshot,
            String expectedCurrentChecksum
    );
}
