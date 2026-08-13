package com.enthusia.enthusiacurrency.moderation;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyAccountSnapshot;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRemovalPlan;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRemovalResult;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRestoreResult;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Applies and compensates exact inventory and Ender Chest snapshots on the primary thread. */
final class CurrencyPhysicalAccountEditor {

    enum ReplacementAttempt {
        APPLIED,
        INVALID_PAYLOAD,
        MUTATION_FAILED
    }

    private final EnthusiaCurrencyPlugin plugin;
    private final CurrencyAccountCodec accounts;

    CurrencyPhysicalAccountEditor(
            EnthusiaCurrencyPlugin plugin,
            CurrencyAccountCodec accounts
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    ReplacementAttempt applyRemoval(Player player, CurrencyRemovalPlan plan) {
        Optional<PhysicalContents> replacement = decode(
                plan.replacementInventory(),
                plan.replacementEnderChest()
        );
        return applyDecoded(player, replacement);
    }

    ReplacementAttempt applySnapshot(Player player, CurrencyAccountSnapshot snapshot) {
        Optional<PhysicalContents> replacement = decode(snapshot.inventory(), snapshot.enderChest());
        return applyDecoded(player, replacement);
    }

    Optional<CurrencyAccountSnapshot> captureSafely(Player player) {
        try {
            return Optional.of(accounts.capture(player));
        } catch (RuntimeException exception) {
            plugin.getLogger().severe(
                    "ES-X02 could not observe exact currency state: " + exception.getMessage()
            );
            return Optional.empty();
        }
    }

    CompletionStage<CurrencyRemovalResult> compensateRemoval(
            Player player,
            CurrencyAccountSnapshot before,
            String failureDetail
    ) {
        Optional<CurrencyAccountSnapshot> observed = restorePhysicalAndObserve(player, before);
        if (observed.isPresent() && accounts.sameAssets(observed.orElseThrow(), before)) {
            CurrencyAccountSnapshot rolledBack = observed.orElseThrow();
            return CompletableFuture.completedFuture(new CurrencyRemovalResult(
                    CurrencyRemovalResult.Status.FAILED_ROLLED_BACK,
                    0L,
                    rolledBack.authoritativeTotal(),
                    observed,
                    failureDetail + "; exact physical state was restored"
            ));
        }
        long finalTotal = observed.map(CurrencyAccountSnapshot::authoritativeTotal)
                .orElse(before.authoritativeTotal());
        return CompletableFuture.completedFuture(new CurrencyRemovalResult(
                CurrencyRemovalResult.Status.QUARANTINE_REQUIRED,
                0L,
                finalTotal,
                observed,
                failureDetail + "; exact rollback could not be verified"
        ));
    }

    CurrencyRestoreResult compensateRestore(
            Player player,
            CurrencyAccountSnapshot before,
            String failureDetail
    ) {
        Optional<CurrencyAccountSnapshot> observed = restorePhysicalAndObserve(player, before);
        if (observed.isPresent() && accounts.sameAssets(observed.orElseThrow(), before)) {
            return new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.FAILED_ROLLED_BACK,
                    observed,
                    failureDetail + "; exact physical state was restored"
            );
        }
        return new CurrencyRestoreResult(
                CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                observed,
                failureDetail + "; exact rollback could not be verified"
        );
    }

    private Optional<CurrencyAccountSnapshot> restorePhysicalAndObserve(
            Player player,
            CurrencyAccountSnapshot snapshot
    ) {
        ReplacementAttempt replacement = applySnapshot(player, snapshot);
        if (replacement != ReplacementAttempt.APPLIED) {
            plugin.getLogger().severe(
                    "ES-X02 physical compensation failed: " + replacement
            );
        }
        return captureSafely(player);
    }

    private ReplacementAttempt applyDecoded(
            Player player,
            Optional<PhysicalContents> replacement
    ) {
        if (replacement.isEmpty()) {
            return ReplacementAttempt.INVALID_PAYLOAD;
        }
        PhysicalContents contents = replacement.orElseThrow();
        try {
            player.getInventory().setContents(contents.inventory());
            player.getEnderChest().setContents(contents.enderChest());
            return ReplacementAttempt.APPLIED;
        } catch (RuntimeException exception) {
            return ReplacementAttempt.MUTATION_FAILED;
        }
    }

    private Optional<PhysicalContents> decode(byte[] inventory, byte[] enderChest) {
        try {
            return Optional.of(new PhysicalContents(
                    accounts.decode(inventory),
                    accounts.decode(enderChest)
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private record PhysicalContents(ItemStack[] inventory, ItemStack[] enderChest) {
    }
}
