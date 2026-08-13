package com.enthusia.enthusiacurrency.moderation;

import com.enthusia.enthusiacurrency.api.moderation.CurrencyAccountSnapshot;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyRemovalPlan;
import com.enthusia.enthusiacurrency.api.moderation.CurrencySource;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/** Builds and revalidates exact source-ordered removal plans. */
final class CurrencyRemovalPlanner {

    private static final long NO_REMAINING_AMOUNT = 0L;

    private final CurrencyInventoryEditor inventories;
    private final CurrencyAccountCodec accounts;

    CurrencyRemovalPlanner(CurrencyInventoryEditor inventories, CurrencyAccountCodec accounts) {
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
    }

    CurrencyRemovalPlan plan(
            UUID operationId,
            CurrencyAccountSnapshot before,
            long amount,
            List<CurrencySource> sourceOrder
    ) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(before, "snapshot");
        List<CurrencySource> order = validateSourceOrder(sourceOrder);
        validateAmount(amount, before);
        accounts.verifySnapshotChecksum(before);

        PhysicalState physical = decodeAndVerify(before);
        MutablePlanState state = new MutablePlanState(before.bankBalance(), amount);
        applySources(order, physical, state);
        requireFullyAllocated(state);
        return createPlan(operationId, before, amount, order, physical, state.bankBalance);
    }

    boolean validPlan(CurrencyRemovalPlan supplied) {
        try {
            CurrencyRemovalPlan calculated = plan(
                    supplied.operationId(),
                    supplied.before(),
                    supplied.amount(),
                    supplied.sourceOrder()
            );
            return sameReplacement(calculated, supplied);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return false;
        }
    }

    private static void validateAmount(long amount, CurrencyAccountSnapshot before) {
        if (amount <= NO_REMAINING_AMOUNT || amount > before.authoritativeTotal()) {
            throw new IllegalArgumentException(
                    "amount must be positive and no greater than the snapshot total"
            );
        }
    }

    private PhysicalState decodeAndVerify(CurrencyAccountSnapshot before) {
        ItemStack[] inventory = accounts.decode(before.inventory());
        ItemStack[] enderChest = accounts.decode(before.enderChest());
        long inventoryValue = inventories.value(inventory);
        long enderValue = inventories.value(enderChest);
        if (inventoryValue != before.inventoryValue() || enderValue != before.enderChestValue()) {
            throw new IllegalArgumentException(
                    "snapshot physical-currency totals do not match serialized contents"
            );
        }
        return new PhysicalState(inventory, enderChest);
    }

    private void applySources(
            List<CurrencySource> order,
            PhysicalState physical,
            MutablePlanState state
    ) {
        for (CurrencySource source : order) {
            if (state.remaining == NO_REMAINING_AMOUNT) {
                return;
            }
            applySource(source, physical, state);
        }
    }

    private void applySource(
            CurrencySource source,
            PhysicalState physical,
            MutablePlanState state
    ) {
        switch (source) {
            case BANK -> removeFromBank(state);
            case INVENTORY -> state.remaining -= inventories.removeUpTo(
                    physical.inventory(),
                    state.remaining
            );
            case ENDER_CHEST -> state.remaining -= inventories.removeUpTo(
                    physical.enderChest(),
                    state.remaining
            );
            default -> throw new IllegalStateException("unsupported currency source: " + source);
        }
    }

    private static void removeFromBank(MutablePlanState state) {
        long taken = Math.min(state.bankBalance, state.remaining);
        state.bankBalance -= taken;
        state.remaining -= taken;
    }

    private static void requireFullyAllocated(MutablePlanState state) {
        if (state.remaining != NO_REMAINING_AMOUNT) {
            throw new IllegalArgumentException(
                    "exact removal cannot be represented by the available currency denominations"
            );
        }
    }

    private CurrencyRemovalPlan createPlan(
            UUID operationId,
            CurrencyAccountSnapshot before,
            long amount,
            List<CurrencySource> order,
            PhysicalState physical,
            long bankBalance
    ) {
        byte[] replacementInventory = ItemStack.serializeItemsAsBytes(physical.inventory());
        byte[] replacementEnder = ItemStack.serializeItemsAsBytes(physical.enderChest());
        long inventoryValue = inventories.value(physical.inventory());
        long enderValue = inventories.value(physical.enderChest());
        long expectedTotal = accounts.total(bankBalance, inventoryValue, enderValue);
        requireExactTotal(before, amount, expectedTotal);
        long replacementRevision = replacementRevision(before, bankBalance);
        String replacementChecksum = accounts.checksum(
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

    private static void requireExactTotal(
            CurrencyAccountSnapshot before,
            long amount,
            long expectedTotal
    ) {
        if (expectedTotal != before.authoritativeTotal() - amount) {
            throw new IllegalStateException("planned debit did not preserve exact total arithmetic");
        }
    }

    private static long replacementRevision(CurrencyAccountSnapshot before, long bankBalance) {
        if (bankBalance == before.bankBalance()) {
            return before.bankRevision();
        }
        return Math.addExact(before.bankRevision(), 1L);
    }

    private static boolean sameReplacement(
            CurrencyRemovalPlan calculated,
            CurrencyRemovalPlan supplied
    ) {
        return calculated.playerId().equals(supplied.playerId())
                && calculated.replacementBankBalance() == supplied.replacementBankBalance()
                && calculated.expectedFinalTotal() == supplied.expectedFinalTotal()
                && calculated.replacementChecksum().equals(supplied.replacementChecksum())
                && calculated.sourceOrder().equals(supplied.sourceOrder())
                && Arrays.equals(calculated.replacementInventory(), supplied.replacementInventory())
                && Arrays.equals(calculated.replacementEnderChest(), supplied.replacementEnderChest());
    }

    private static List<CurrencySource> validateSourceOrder(List<CurrencySource> sourceOrder) {
        List<CurrencySource> order = List.copyOf(Objects.requireNonNull(sourceOrder, "sourceOrder"));
        if (order.size() != CurrencySource.values().length
                || !EnumSet.copyOf(order).equals(EnumSet.allOf(CurrencySource.class))) {
            throw new IllegalArgumentException(
                    "sourceOrder must contain each currency source exactly once"
            );
        }
        return order;
    }

    private record PhysicalState(ItemStack[] inventory, ItemStack[] enderChest) {
    }

    private static final class MutablePlanState {
        private long bankBalance;
        private long remaining;

        private MutablePlanState(long bankBalance, long remaining) {
            this.bankBalance = bankBalance;
            this.remaining = remaining;
        }
    }
}
