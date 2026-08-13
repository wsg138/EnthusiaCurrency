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
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Versioned destructive-currency provider used by EnthusiaStaff. */
public final class CurrencyModerationService implements CurrencyModerationApi, AutoCloseable {

    private final EnthusiaCurrencyPlugin plugin;
    private final BalanceStorage balances;
    private final MovementLockRegistry locks;
    private final CurrencyInventoryEditor inventories;

    public CurrencyModerationService(
            EnthusiaCurrencyPlugin plugin,
            BalanceStorage balances,
            CurrencyManager currencyManager,
            MovementLockRegistry locks
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.balances = Objects.requireNonNull(balances, "balances");
        this.locks = Objects.requireNonNull(locks, "locks");
        this.inventories = new CurrencyInventoryEditor(currencyManager);
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
        return capture(player);
    }

    @Override
    public CurrencyRemovalPlan planRemoval(
            UUID operationId,
            CurrencyAccountSnapshot before,
            long amount,
            List<CurrencySource> sourceOrder
    ) {
        requirePrimaryThread();
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(before, "snapshot");
        List<CurrencySource> order = validateSourceOrder(sourceOrder);
        if (amount <= 0L || amount > before.authoritativeTotal()) {
            throw new IllegalArgumentException("amount must be positive and no greater than the snapshot total");
        }
        verifySnapshotChecksum(before);

        ItemStack[] inventory = decode(before.inventory());
        ItemStack[] enderChest = decode(before.enderChest());
        long beforeInventoryValue = inventories.value(inventory);
        long beforeEnderValue = inventories.value(enderChest);
        if (beforeInventoryValue != before.inventoryValue()
                || beforeEnderValue != before.enderChestValue()) {
            throw new IllegalArgumentException("snapshot physical-currency totals do not match serialized contents");
        }

        long remaining = amount;
        long bankBalance = before.bankBalance();
        for (CurrencySource source : order) {
            if (remaining == 0L) {
                break;
            }
            switch (source) {
                case BANK -> {
                    long taken = Math.min(bankBalance, remaining);
                    bankBalance -= taken;
                    remaining -= taken;
                }
                case INVENTORY -> remaining -= inventories.removeUpTo(inventory, remaining);
                case ENDER_CHEST -> remaining -= inventories.removeUpTo(enderChest, remaining);
            }
        }
        if (remaining != 0L) {
            throw new IllegalArgumentException(
                    "exact removal cannot be represented by the available currency denominations"
            );
        }

        byte[] replacementInventory = ItemStack.serializeItemsAsBytes(inventory);
        byte[] replacementEnder = ItemStack.serializeItemsAsBytes(enderChest);
        long inventoryValue = inventories.value(inventory);
        long enderValue = inventories.value(enderChest);
        long expectedTotal = total(bankBalance, inventoryValue, enderValue);
        if (expectedTotal != before.authoritativeTotal() - amount) {
            throw new IllegalStateException("planned debit did not preserve exact total arithmetic");
        }
        long replacementRevision = before.bankRevision();
        if (bankBalance != before.bankBalance()) {
            replacementRevision = Math.addExact(replacementRevision, 1L);
        }
        String replacementChecksum = checksum(
                before.playerId(),
                bankBalance,
                replacementRevision,
                replacementInventory,
                replacementEnder,
                inventoryValue,
                enderValue
        );
        return new CurrencyRemovalPlan(
                operationId,
                before.playerId(),
                amount,
                before,
                bankBalance,
                replacementInventory,
                replacementEnder,
                expectedTotal,
                replacementChecksum,
                order
        );
    }

    @Override
    public CompletionStage<CurrencyRemovalResult> applyRemoval(Player player, CurrencyRemovalPlan plan) {
        requirePrimaryThread();
        Objects.requireNonNull(plan, "plan");
        if (!isOnlineMatch(player, plan.playerId())) {
            return completedRemoval(CurrencyRemovalResult.Status.PLAYER_OFFLINE, 0L, plan.before().authoritativeTotal(), Optional.empty(), "player is not online on this backend");
        }
        if (!locks.isOwnedBy(plan.playerId(), plan.operationId())) {
            return completedRemoval(CurrencyRemovalResult.Status.LOCK_REQUIRED, 0L, plan.before().authoritativeTotal(), Optional.empty(), "operation does not own the movement lease");
        }

        CurrencyAccountSnapshot current = capture(player);
        if (current.checksum().equals(plan.replacementChecksum())
                && current.authoritativeTotal() == plan.expectedFinalTotal()) {
            return completedRemoval(CurrencyRemovalResult.Status.COMMITTED, plan.amount(), current.authoritativeTotal(), Optional.of(current), "operation was already committed");
        }
        if (!current.checksum().equals(plan.before().checksum())) {
            return completedRemoval(CurrencyRemovalResult.Status.STALE, 0L, current.authoritativeTotal(), Optional.of(current), "account state changed after planning");
        }
        if (!validPlan(plan)) {
            return completedRemoval(CurrencyRemovalResult.Status.INVALID_PLAN, 0L, current.authoritativeTotal(), Optional.of(current), "plan does not match a fresh provider calculation");
        }

        ItemStack[] replacementInventory;
        ItemStack[] replacementEnder;
        try {
            replacementInventory = decode(plan.replacementInventory());
            replacementEnder = decode(plan.replacementEnderChest());
        } catch (RuntimeException exception) {
            return completedRemoval(CurrencyRemovalResult.Status.INVALID_PLAN, 0L, current.authoritativeTotal(), Optional.of(current), "replacement inventory payload is invalid");
        }

        try {
            player.getInventory().setContents(replacementInventory);
            player.getEnderChest().setContents(replacementEnder);
        } catch (RuntimeException exception) {
            return compensateRemoval(
                    player,
                    current,
                    "physical mutation failed before bank commit"
            );
        }

        boolean bankChanged = plan.replacementBankBalance() != current.bankBalance();
        if (!balances.replaceIfCurrent(
                current.playerId(),
                current.bankBalance(),
                current.bankRevision(),
                plan.replacementBankBalance(),
                false
        )) {
            return compensateRemoval(
                    player,
                    current,
                    "bank revision changed during apply"
            );
        }

        CurrencyAccountSnapshot after;
        try {
            after = capture(player);
        } catch (RuntimeException exception) {
            return completedRemoval(CurrencyRemovalResult.Status.QUARANTINE_REQUIRED, plan.amount(), plan.expectedFinalTotal(), Optional.empty(), "post-commit account verification failed");
        }
        if (!after.checksum().equals(plan.replacementChecksum())
                || after.authoritativeTotal() != plan.expectedFinalTotal()) {
            return completedRemoval(CurrencyRemovalResult.Status.QUARANTINE_REQUIRED, plan.amount(), after.authoritativeTotal(), Optional.of(after), "post-commit account state did not match the persisted exact plan");
        }

        CurrencyRemovalResult committed = new CurrencyRemovalResult(
                CurrencyRemovalResult.Status.COMMITTED,
                plan.amount(),
                after.authoritativeTotal(),
                Optional.of(after),
                "exact removal committed"
        );
        if (!bankChanged) {
            return CompletableFuture.completedFuture(committed);
        }
        return balances.flushAsync().handle((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().severe(
                        "Failed to durably flush an ES-X02 currency debit: " + failure.getMessage()
                );
                return new CurrencyRemovalResult(
                        CurrencyRemovalResult.Status.QUARANTINE_REQUIRED,
                        plan.amount(),
                        plan.expectedFinalTotal(),
                        Optional.empty(),
                        "bank mutation is locally committed but durable flush failed"
                );
            }
            BalanceStorage.BalanceSnapshot durableBank = balances.getBalanceSnapshot(plan.playerId());
            if (durableBank.amount() != after.bankBalance()
                    || durableBank.revision() != after.bankRevision()) {
                return new CurrencyRemovalResult(
                        CurrencyRemovalResult.Status.QUARANTINE_REQUIRED,
                        plan.amount(),
                        plan.expectedFinalTotal(),
                        Optional.empty(),
                        "bank state changed while the durable debit flush was completing"
                );
            }
            return committed;
        });
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
        if (!isOnlineMatch(player, requested.playerId())) {
            return CompletableFuture.completedFuture(new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.PLAYER_OFFLINE,
                    "player is not online on this backend"
            ));
        }
        if (!locks.isOwnedBy(requested.playerId(), operationId)) {
            return CompletableFuture.completedFuture(new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.LOCK_REQUIRED,
                    "operation does not own the movement lease"
            ));
        }
        verifySnapshotChecksum(requested);

        CurrencyAccountSnapshot current = capture(player);
        if (sameAssets(current, requested)) {
            return CompletableFuture.completedFuture(new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.RESTORED,
                    Optional.of(current),
                    "requested assets were already restored"
            ));
        }
        if (!current.checksum().equals(expectedCurrentChecksum)) {
            return CompletableFuture.completedFuture(new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.STALE,
                    Optional.of(current),
                    "account state changed after the removal result being restored"
            ));
        }

        ItemStack[] requestedInventory;
        ItemStack[] requestedEnder;
        try {
            requestedInventory = decode(requested.inventory());
            requestedEnder = decode(requested.enderChest());
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                    Optional.of(current),
                    "stored restore snapshot cannot be decoded"
            ));
        }

        try {
            player.getInventory().setContents(requestedInventory);
            player.getEnderChest().setContents(requestedEnder);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(compensateRestore(
                    player,
                    current,
                    "physical restore failed before bank commit"
            ));
        }

        if (!balances.replaceIfCurrent(
                current.playerId(),
                current.bankBalance(),
                current.bankRevision(),
                requested.bankBalance(),
                true
        )) {
            return CompletableFuture.completedFuture(compensateRestore(
                    player,
                    current,
                    "bank revision changed during restore"
            ));
        }

        CurrencyAccountSnapshot restored = capture(player);
        if (!sameAssets(restored, requested) || restored.bankRevision() <= current.bankRevision()) {
            return CompletableFuture.completedFuture(new CurrencyRestoreResult(
                    CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                    Optional.of(restored),
                    "restored assets or monotonic bank revision could not be verified"
            ));
        }
        CurrencyRestoreResult success = new CurrencyRestoreResult(
                CurrencyRestoreResult.Status.RESTORED,
                Optional.of(restored),
                "exact before assets restored"
        );
        return balances.flushAsync().handle((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().severe(
                        "Failed to durably flush an ES-X02 currency restore: " + failure.getMessage()
                );
                return new CurrencyRestoreResult(
                        CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                        Optional.empty(),
                        "restored bank state is local but durable flush failed"
                );
            }
            BalanceStorage.BalanceSnapshot durableBank = balances.getBalanceSnapshot(requested.playerId());
            if (durableBank.amount() != restored.bankBalance()
                    || durableBank.revision() != restored.bankRevision()) {
                return new CurrencyRestoreResult(
                        CurrencyRestoreResult.Status.QUARANTINE_REQUIRED,
                        Optional.empty(),
                        "bank state changed while the durable restore flush was completing"
                );
            }
            return success;
        });
    }

    @Override
    public void close() {
        locks.clear();
    }

    private CurrencyAccountSnapshot capture(Player player) {
        BalanceStorage.BalanceSnapshot bank = balances.getBalanceSnapshot(player.getUniqueId());
        byte[] inventory = ItemStack.serializeItemsAsBytes(player.getInventory().getContents());
        byte[] ender = ItemStack.serializeItemsAsBytes(player.getEnderChest().getContents());
        ItemStack[] inventoryItems = decode(inventory);
        ItemStack[] enderItems = decode(ender);
        long inventoryValue = inventories.value(inventoryItems);
        long enderValue = inventories.value(enderItems);
        long total = total(bank.amount(), inventoryValue, enderValue);
        String checksum = checksum(
                player.getUniqueId(),
                bank.amount(),
                bank.revision(),
                inventory,
                ender,
                inventoryValue,
                enderValue
        );
        return new CurrencyAccountSnapshot(
                player.getUniqueId(),
                bank.amount(),
                bank.revision(),
                inventory,
                ender,
                inventoryValue,
                enderValue,
                total,
                checksum
        );
    }

    private boolean validPlan(CurrencyRemovalPlan supplied) {
        try {
            CurrencyRemovalPlan calculated = planRemoval(
                    supplied.operationId(),
                    supplied.before(),
                    supplied.amount(),
                    supplied.sourceOrder()
            );
            return calculated.playerId().equals(supplied.playerId())
                    && calculated.replacementBankBalance() == supplied.replacementBankBalance()
                    && calculated.expectedFinalTotal() == supplied.expectedFinalTotal()
                    && calculated.replacementChecksum().equals(supplied.replacementChecksum())
                    && calculated.sourceOrder().equals(supplied.sourceOrder())
                    && Arrays.equals(calculated.replacementInventory(), supplied.replacementInventory())
                    && Arrays.equals(calculated.replacementEnderChest(), supplied.replacementEnderChest());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }

    private void verifySnapshotChecksum(CurrencyAccountSnapshot snapshot) {
        String expected = checksum(
                snapshot.playerId(),
                snapshot.bankBalance(),
                snapshot.bankRevision(),
                snapshot.inventory(),
                snapshot.enderChest(),
                snapshot.inventoryValue(),
                snapshot.enderChestValue()
        );
        if (!expected.equals(snapshot.checksum())) {
            throw new IllegalArgumentException("snapshot checksum does not match its exact account state");
        }
    }

    private static List<CurrencySource> validateSourceOrder(List<CurrencySource> sourceOrder) {
        List<CurrencySource> order = List.copyOf(Objects.requireNonNull(sourceOrder, "sourceOrder"));
        if (order.size() != CurrencySource.values().length
                || !EnumSet.copyOf(order).equals(EnumSet.allOf(CurrencySource.class))) {
            throw new IllegalArgumentException("sourceOrder must contain each currency source exactly once");
        }
        return order;
    }

    private static boolean sameAssets(CurrencyAccountSnapshot first, CurrencyAccountSnapshot second) {
        return first.playerId().equals(second.playerId())
                && first.bankBalance() == second.bankBalance()
                && first.inventoryValue() == second.inventoryValue()
                && first.enderChestValue() == second.enderChestValue()
                && first.authoritativeTotal() == second.authoritativeTotal()
                && Arrays.equals(first.inventory(), second.inventory())
                && Arrays.equals(first.enderChest(), second.enderChest());
    }

    private static ItemStack[] decode(byte[] bytes) {
        return ItemStack.deserializeItemsFromBytes(bytes);
    }

    private static long total(long bank, long inventory, long ender) {
        return Math.addExact(bank, Math.addExact(inventory, ender));
    }

    private static String checksum(
            UUID playerId,
            long bankBalance,
            long bankRevision,
            byte[] inventory,
            byte[] enderChest,
            long inventoryValue,
            long enderValue
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateLong(digest, playerId.getMostSignificantBits());
            updateLong(digest, playerId.getLeastSignificantBits());
            updateLong(digest, bankBalance);
            updateLong(digest, bankRevision);
            updateBytes(digest, inventory);
            updateBytes(digest, enderChest);
            updateLong(digest, inventoryValue);
            updateLong(digest, enderValue);
            updateLong(digest, total(bankBalance, inventoryValue, enderValue));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static void updateBytes(MessageDigest digest, byte[] value) {
        updateLong(digest, value.length);
        digest.update(value);
    }

    private CompletionStage<CurrencyRemovalResult> compensateRemoval(
            Player player,
            CurrencyAccountSnapshot before,
            String failureDetail
    ) {
        Optional<CurrencyAccountSnapshot> observed = restorePhysicalAndObserve(player, before);
        if (observed.isPresent() && sameAssets(observed.orElseThrow(), before)) {
            CurrencyAccountSnapshot rolledBack = observed.orElseThrow();
            return completedRemoval(
                    CurrencyRemovalResult.Status.FAILED_ROLLED_BACK,
                    0L,
                    rolledBack.authoritativeTotal(),
                    observed,
                    failureDetail + "; exact physical state was restored"
            );
        }
        long finalTotal = observed.map(CurrencyAccountSnapshot::authoritativeTotal)
                .orElse(before.authoritativeTotal());
        return completedRemoval(
                CurrencyRemovalResult.Status.QUARANTINE_REQUIRED,
                0L,
                finalTotal,
                observed,
                failureDetail + "; exact rollback could not be verified"
        );
    }

    private CurrencyRestoreResult compensateRestore(
            Player player,
            CurrencyAccountSnapshot before,
            String failureDetail
    ) {
        Optional<CurrencyAccountSnapshot> observed = restorePhysicalAndObserve(player, before);
        if (observed.isPresent() && sameAssets(observed.orElseThrow(), before)) {
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
        try {
            player.getInventory().setContents(decode(snapshot.inventory()));
            player.getEnderChest().setContents(decode(snapshot.enderChest()));
        } catch (RuntimeException exception) {
            plugin.getLogger().severe(
                    "ES-X02 physical compensation failed: " + exception.getMessage()
            );
        }
        try {
            return Optional.of(capture(player));
        } catch (RuntimeException exception) {
            plugin.getLogger().severe(
                    "ES-X02 could not observe state after compensation: " + exception.getMessage()
            );
            return Optional.empty();
        }
    }

    private static CompletionStage<CurrencyRemovalResult> completedRemoval(
            CurrencyRemovalResult.Status status,
            long amountRemoved,
            long finalTotal,
            Optional<CurrencyAccountSnapshot> state,
            String detail
    ) {
        return CompletableFuture.completedFuture(new CurrencyRemovalResult(
                status,
                amountRemoved,
                finalTotal,
                state,
                detail
        ));
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
            throw new IllegalStateException("currency moderation API must be called on the primary server thread");
        }
    }
}
