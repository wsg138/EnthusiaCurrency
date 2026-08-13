package com.enthusia.enthusiacurrency.moderation;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyAccountSnapshot;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRemovalPlan;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRemovalResult;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRestoreResult;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/** Flushes bank mutations and re-verifies the exact full account state on the primary thread. */
final class CurrencyDurabilityVerifier implements AutoCloseable {

    private final EnthusiaCurrencyPlugin plugin;
    private final BalanceStorage balances;
    private final MainThreadCurrencyVerifier verifier;

    CurrencyDurabilityVerifier(
            EnthusiaCurrencyPlugin plugin,
            BalanceStorage balances,
            CurrencyAccountCodec accounts
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.balances = Objects.requireNonNull(balances, "balances");
        this.verifier = new MainThreadCurrencyVerifier(plugin, accounts);
    }

    CompletionStage<CurrencyRemovalResult> flushRemoval(
            Player player,
            CurrencyRemovalPlan plan,
            CurrencyAccountSnapshot expected,
            CurrencyRemovalResult committed
    ) {
        return balances.flushAsync().handle((ignored, failure) -> failure)
                .thenCompose(failure -> finishRemovalFlush(
                        player,
                        plan,
                        expected,
                        committed,
                        failure
                ));
    }

    CompletionStage<CurrencyRestoreResult> flushRestore(
            Player player,
            CurrencyAccountSnapshot requested,
            CurrencyAccountSnapshot expected,
            CurrencyRestoreResult success
    ) {
        return balances.flushAsync().handle((ignored, failure) -> failure)
                .thenCompose(failure -> finishRestoreFlush(
                        player,
                        requested,
                        expected,
                        success,
                        failure
                ));
    }

    private CompletionStage<CurrencyRemovalResult> finishRemovalFlush(
            Player player,
            CurrencyRemovalPlan plan,
            CurrencyAccountSnapshot expected,
            CurrencyRemovalResult committed,
            Throwable failure
    ) {
        if (failure != null) {
            plugin.getLogger().severe(
                    "Failed to durably flush an ES-X02 currency debit: " + failure.getMessage()
            );
            return CompletableFuture.completedFuture(new CurrencyRemovalResult(
                    CurrencyRemovalResult.Status.QUARANTINE_REQUIRED,
                    plan.amount(),
                    plan.expectedFinalTotal(),
                    Optional.empty(),
                    "bank mutation is locally committed but durable flush failed"
            ));
        }
        return verifier.capture(player, plan.playerId()).handle(
                (durableState, verificationFailure) -> durableRemovalOutcome(
                        plan,
                        expected,
                        committed,
                        durableState,
                        verificationFailure
                )
        );
    }

    private CurrencyRemovalResult durableRemovalOutcome(
            CurrencyRemovalPlan plan,
            CurrencyAccountSnapshot expected,
            CurrencyRemovalResult committed,
            CurrencyAccountSnapshot durableState,
            Throwable verificationFailure
    ) {
        if (verificationFailure != null) {
            plugin.getLogger().severe(
                    "Failed exact post-flush ES-X02 debit verification: "
                            + verificationFailure.getMessage()
            );
            return new CurrencyRemovalResult(
                    CurrencyRemovalResult.Status.QUARANTINE_REQUIRED,
                    plan.amount(),
                    plan.expectedFinalTotal(),
                    Optional.empty(),
                    "durable debit could not be reverified on the primary thread"
            );
        }
        if (!durableState.equals(expected)) {
            return new CurrencyRemovalResult(
                    CurrencyRemovalResult.Status.QUARANTINE_REQUIRED,
                    plan.amount(),
                    durableState.authoritativeTotal(),
                    Optional.of(durableState),
                    "account state changed while the durable debit flush was completing"
            );
        }
        return committed;
    }

    private CompletionStage<CurrencyRestoreResult> finishRestoreFlush(
            Player player,
            CurrencyAccountSnapshot requested,
            CurrencyAccountSnapshot expected,
            CurrencyRestoreResult success,
            Throwable failure
    ) {
        if (failure != null) {
            plugin.getLogger().severe(
                    "Failed to durably flush an ES-X02 currency restore: " + failure.getMessage()
            );
            return CompletableFuture.completedFuture(new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                    Optional.empty(),
                    "restored bank state is local but durable flush failed"
            ));
        }
        return verifier.capture(player, requested.playerId()).handle(
                (durableState, verificationFailure) -> durableRestoreOutcome(
                        expected,
                        success,
                        durableState,
                        verificationFailure
                )
        );
    }

    private CurrencyRestoreResult durableRestoreOutcome(
            CurrencyAccountSnapshot expected,
            CurrencyRestoreResult success,
            CurrencyAccountSnapshot durableState,
            Throwable verificationFailure
    ) {
        if (verificationFailure != null) {
            plugin.getLogger().severe(
                    "Failed exact post-flush ES-X02 restore verification: "
                            + verificationFailure.getMessage()
            );
            return new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                    Optional.empty(),
                    "durable restore could not be reverified on the primary thread"
            );
        }
        if (!durableState.equals(expected)) {
            return new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                    Optional.of(durableState),
                    "account state changed while the durable restore flush was completing"
            );
        }
        return success;
    }

    @Override
    public void close() {
        verifier.close();
    }
}
