package com.enthusia.enthusiacurrency.moderation;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyAccountSnapshot;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyModerationApi;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRemovalPlan;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRemovalResult;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRestoreResult;
import com.enthusia.enthusiacurrency.api.moderation.CurrencySource;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Versioned destructive-currency provider used by EnthusiaStaff. */
public final class CurrencyModerationService implements CurrencyModerationApi, AutoCloseable {

    private final BalanceStorage balances;
    private final MovementLockRegistry locks;
    private final CurrencyAccountCodec accounts;
    private final CurrencyRemovalPlanner planner;
    private final CurrencyPhysicalAccountEditor physical;
    private final CurrencyDurabilityVerifier durability;

    public CurrencyModerationService(
            EnthusiaCurrencyPlugin plugin,
            BalanceStorage balances,
            CurrencyManager currencyManager,
            MovementLockRegistry locks
    ) {
        EnthusiaCurrencyPlugin checkedPlugin = Objects.requireNonNull(plugin, "plugin");
        this.balances = Objects.requireNonNull(balances, "balances");
        this.locks = Objects.requireNonNull(locks, "locks");
        CurrencyInventoryEditor inventories = new CurrencyInventoryEditor(currencyManager);
        this.accounts = new CurrencyAccountCodec(balances, inventories);
        this.planner = new CurrencyRemovalPlanner(inventories, accounts);
        this.physical = new CurrencyPhysicalAccountEditor(checkedPlugin, accounts);
        this.durability = new CurrencyDurabilityVerifier(checkedPlugin, balances, accounts);
    }

    @Override
    public int apiVersion() {
        return API_VERSION;
    }

    @Override
    public boolean acquireMovementLock(UUID playerId, UUID operationId, Duration leaseDuration) {
        return locks.acquire(playerId, operationId, leaseDuration);
    }

    @Override
    public boolean renewMovementLock(UUID playerId, UUID operationId, Duration leaseDuration) {
        return locks.renew(playerId, operationId, leaseDuration);
    }

    @Override
    public boolean releaseMovementLock(UUID playerId, UUID operationId) {
        return locks.release(playerId, operationId);
    }

    @Override
    public boolean isMovementLocked(UUID playerId) {
        return locks.isLocked(playerId);
    }

    @Override
    public CurrencyAccountSnapshot snapshot(Player player) {
        requirePrimaryThread();
        requireOnline(player);
        return accounts.capture(player);
    }

    @Override
    public CurrencyRemovalPlan planRemoval(
            UUID operationId,
            CurrencyAccountSnapshot before,
            long amount,
            List<CurrencySource> sourceOrder
    ) {
        requirePrimaryThread();
        return planner.plan(operationId, before, amount, sourceOrder);
    }

    @Override
    public CompletionStage<CurrencyRemovalResult> applyRemoval(Player player, CurrencyRemovalPlan plan) {
        requirePrimaryThread();
        Objects.requireNonNull(plan, "plan");
        Optional<CurrencyRemovalResult> requestFailure = validateRemovalRequest(player, plan);
        if (requestFailure.isPresent()) {
            return CompletableFuture.completedFuture(requestFailure.orElseThrow());
        }

        CurrencyAccountSnapshot current = accounts.capture(player);
        Optional<CurrencyRemovalResult> stateOutcome = evaluateRemovalState(current, plan);
        if (stateOutcome.isPresent()) {
            return CompletableFuture.completedFuture(stateOutcome.orElseThrow());
        }
        return applyFreshRemoval(player, plan, current);
    }

    private Optional<CurrencyRemovalResult> validateRemovalRequest(
            Player player,
            CurrencyRemovalPlan plan
    ) {
        if (!isOnlineMatch(player, plan.playerId())) {
            return Optional.of(removalResult(
                    CurrencyRemovalResult.Status.PLAYER_OFFLINE,
                    0L,
                    plan.before().authoritativeTotal(),
                    Optional.empty(),
                    "player is not online on this backend"
            ));
        }
        if (!locks.isOwnedBy(plan.playerId(), plan.operationId())) {
            return Optional.of(removalResult(
                    CurrencyRemovalResult.Status.LOCK_REQUIRED,
                    0L,
                    plan.before().authoritativeTotal(),
                    Optional.empty(),
                    "operation does not own the movement lease"
            ));
        }
        return Optional.empty();
    }

    private Optional<CurrencyRemovalResult> evaluateRemovalState(
            CurrencyAccountSnapshot current,
            CurrencyRemovalPlan plan
    ) {
        return switch (CurrencyModerationStateEvaluator.removal(
                current,
                plan,
                planner.validPlan(plan)
        )) {
            case INVALID_PLAN -> Optional.of(removalResult(
                    CurrencyRemovalResult.Status.INVALID_PLAN,
                    0L,
                    current.authoritativeTotal(),
                    Optional.of(current),
                    "plan does not match a fresh provider calculation"
            ));
            case COMMITTED -> Optional.of(removalResult(
                    CurrencyRemovalResult.Status.COMMITTED,
                    plan.amount(),
                    current.authoritativeTotal(),
                    Optional.of(current),
                    "operation was already committed"
            ));
            case STALE -> Optional.of(removalResult(
                    CurrencyRemovalResult.Status.STALE,
                    0L,
                    current.authoritativeTotal(),
                    Optional.of(current),
                    "account state changed after planning"
            ));
            case APPLY -> Optional.empty();
        };
    }

    private CompletionStage<CurrencyRemovalResult> applyFreshRemoval(
            Player player,
            CurrencyRemovalPlan plan,
            CurrencyAccountSnapshot current
    ) {
        CurrencyPhysicalAccountEditor.ReplacementAttempt replacement = physical.applyRemoval(
                player,
                plan
        );
        if (replacement == CurrencyPhysicalAccountEditor.ReplacementAttempt.INVALID_PAYLOAD) {
            return completedRemoval(
                    CurrencyRemovalResult.Status.INVALID_PLAN,
                    0L,
                    current.authoritativeTotal(),
                    Optional.of(current),
                    "replacement inventory payload is invalid"
            );
        }
        if (replacement == CurrencyPhysicalAccountEditor.ReplacementAttempt.MUTATION_FAILED) {
            return physical.compensateRemoval(
                    player,
                    current,
                    "physical mutation failed before bank commit"
            );
        }
        if (!replaceBankForRemoval(current, plan)) {
            return physical.compensateRemoval(
                    player,
                    current,
                    "bank revision changed during apply"
            );
        }
        return verifyAndFlushRemoval(player, plan, current);
    }

    private boolean replaceBankForRemoval(
            CurrencyAccountSnapshot current,
            CurrencyRemovalPlan plan
    ) {
        return balances.replaceIfCurrent(
                current.playerId(),
                current.bankBalance(),
                current.bankRevision(),
                plan.replacementBankBalance(),
                false
        );
    }

    private CompletionStage<CurrencyRemovalResult> verifyAndFlushRemoval(
            Player player,
            CurrencyRemovalPlan plan,
            CurrencyAccountSnapshot before
    ) {
        Optional<CurrencyAccountSnapshot> observed = physical.captureSafely(player);
        if (observed.isEmpty()) {
            return completedRemoval(
                    CurrencyRemovalResult.Status.QUARANTINE_REQUIRED,
                    plan.amount(),
                    plan.expectedFinalTotal(),
                    Optional.empty(),
                    "post-commit account verification failed"
            );
        }
        CurrencyAccountSnapshot after = observed.orElseThrow();
        if (!CurrencyModerationStateEvaluator.isCommitted(after, plan)) {
            return completedRemoval(
                    CurrencyRemovalResult.Status.QUARANTINE_REQUIRED,
                    plan.amount(),
                    after.authoritativeTotal(),
                    Optional.of(after),
                    "post-commit account state did not match the persisted exact plan"
            );
        }
        CurrencyRemovalResult committed = removalResult(
                CurrencyRemovalResult.Status.COMMITTED,
                plan.amount(),
                after.authoritativeTotal(),
                Optional.of(after),
                "exact removal committed"
        );
        if (plan.replacementBankBalance() == before.bankBalance()) {
            return CompletableFuture.completedFuture(committed);
        }
        return durability.flushRemoval(player, plan, after, committed);
    }

    @Override
    public CompletionStage<CurrencyRestoreResult> restore(
            Player player,
            UUID operationId,
            CurrencyAccountSnapshot requested,
            String expectedCurrentChecksum
    ) {
        requirePrimaryThread();
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(requested, "snapshot");
        Objects.requireNonNull(expectedCurrentChecksum, "expectedCurrentChecksum");
        Optional<CurrencyRestoreResult> requestFailure = validateRestoreRequest(
                player,
                operationId,
                requested
        );
        if (requestFailure.isPresent()) {
            return CompletableFuture.completedFuture(requestFailure.orElseThrow());
        }
        accounts.verifySnapshotChecksum(requested);
        return restoreCurrentState(player, requested, expectedCurrentChecksum);
    }

    private Optional<CurrencyRestoreResult> validateRestoreRequest(
            Player player,
            UUID operationId,
            CurrencyAccountSnapshot requested
    ) {
        if (!isOnlineMatch(player, requested.playerId())) {
            return Optional.of(restoreResult(
                    CurrencyRestoreResult.Status.PLAYER_OFFLINE,
                    Optional.empty(),
                    "player is not online on this backend"
            ));
        }
        if (!locks.isOwnedBy(requested.playerId(), operationId)) {
            return Optional.of(restoreResult(
                    CurrencyRestoreResult.Status.LOCK_REQUIRED,
                    Optional.empty(),
                    "operation does not own the movement lease"
            ));
        }
        return Optional.empty();
    }

    private CompletionStage<CurrencyRestoreResult> restoreCurrentState(
            Player player,
            CurrencyAccountSnapshot requested,
            String expectedCurrentChecksum
    ) {
        CurrencyAccountSnapshot current = accounts.capture(player);
        return switch (CurrencyModerationStateEvaluator.restore(
                current,
                requested,
                expectedCurrentChecksum,
                accounts.sameAssets(current, requested)
        )) {
            case RESTORED -> CompletableFuture.completedFuture(restoreResult(
                    CurrencyRestoreResult.Status.RESTORED,
                    Optional.of(current),
                    "requested assets were already restored"
            ));
            case STALE -> CompletableFuture.completedFuture(restoreResult(
                    CurrencyRestoreResult.Status.STALE,
                    Optional.of(current),
                    "account state changed after the removal result being restored"
            ));
            case APPLY -> applyFreshRestore(player, requested, current);
        };
    }

    private CompletionStage<CurrencyRestoreResult> applyFreshRestore(
            Player player,
            CurrencyAccountSnapshot requested,
            CurrencyAccountSnapshot current
    ) {
        CurrencyPhysicalAccountEditor.ReplacementAttempt replacement = physical.applySnapshot(
                player,
                requested
        );
        if (replacement == CurrencyPhysicalAccountEditor.ReplacementAttempt.INVALID_PAYLOAD) {
            return CompletableFuture.completedFuture(restoreResult(
                    CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                    Optional.of(current),
                    "stored restore snapshot cannot be decoded"
            ));
        }
        if (replacement == CurrencyPhysicalAccountEditor.ReplacementAttempt.MUTATION_FAILED) {
            return CompletableFuture.completedFuture(physical.compensateRestore(
                    player,
                    current,
                    "physical restore failed before bank commit"
            ));
        }
        if (!replaceBankForRestore(current, requested)) {
            return CompletableFuture.completedFuture(physical.compensateRestore(
                    player,
                    current,
                    "bank revision changed during restore"
            ));
        }
        return verifyAndFlushRestore(player, requested, current);
    }

    private boolean replaceBankForRestore(
            CurrencyAccountSnapshot current,
            CurrencyAccountSnapshot requested
    ) {
        return balances.replaceIfCurrent(
                current.playerId(),
                current.bankBalance(),
                current.bankRevision(),
                requested.bankBalance(),
                true
        );
    }

    private CompletionStage<CurrencyRestoreResult> verifyAndFlushRestore(
            Player player,
            CurrencyAccountSnapshot requested,
            CurrencyAccountSnapshot before
    ) {
        Optional<CurrencyAccountSnapshot> observed = physical.captureSafely(player);
        if (observed.isEmpty()) {
            return CompletableFuture.completedFuture(restoreResult(
                    CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                    Optional.empty(),
                    "restored account verification failed"
            ));
        }
        CurrencyAccountSnapshot restored = observed.orElseThrow();
        if (!isVerifiedRestore(restored, requested, before)) {
            return CompletableFuture.completedFuture(restoreResult(
                    CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                    Optional.of(restored),
                    "restored assets or monotonic bank revision could not be verified"
            ));
        }
        CurrencyRestoreResult success = restoreResult(
                CurrencyRestoreResult.Status.RESTORED,
                Optional.of(restored),
                "exact before assets restored"
        );
        return durability.flushRestore(player, requested, restored, success);
    }

    @Override
    public void close() {
        durability.close();
        locks.clear();
    }

    private boolean isVerifiedRestore(
            CurrencyAccountSnapshot restored,
            CurrencyAccountSnapshot requested,
            CurrencyAccountSnapshot before
    ) {
        return accounts.sameAssets(restored, requested)
                && restored.bankRevision() > before.bankRevision();
    }

    private static CompletionStage<CurrencyRemovalResult> completedRemoval(
            CurrencyRemovalResult.Status status,
            long amountRemoved,
            long finalTotal,
            Optional<CurrencyAccountSnapshot> state,
            String detail
    ) {
        return CompletableFuture.completedFuture(
                removalResult(status, amountRemoved, finalTotal, state, detail)
        );
    }

    private static CurrencyRemovalResult removalResult(
            CurrencyRemovalResult.Status status,
            long amountRemoved,
            long finalTotal,
            Optional<CurrencyAccountSnapshot> state,
            String detail
    ) {
        return new CurrencyRemovalResult(status, amountRemoved, finalTotal, state, detail);
    }

    private static CurrencyRestoreResult restoreResult(
            CurrencyRestoreResult.Status status,
            Optional<CurrencyAccountSnapshot> state,
            String detail
    ) {
        return new CurrencyRestoreResult(status, state, detail);
    }

    private static boolean isOnlineMatch(Player player, UUID playerId) {
        return player != null && player.isOnline() && player.getUniqueId().equals(playerId);
    }

    private static void requireOnline(Player player) {
        if (player == null || !player.isOnline()) {
            throw new IllegalArgumentException("player must be online");
        }
    }

    private static void requirePrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    "currency moderation API must be called on the primary server thread"
            );
        }
    }
}
