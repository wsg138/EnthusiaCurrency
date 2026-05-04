package com.enthusia.enthusiacurrency.service;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.analytics.CurrencyAnalyticsAction;
import com.enthusia.enthusiacurrency.event.CurrencyBalanceZeroEvent;
import com.enthusia.enthusiacurrency.event.CurrencyDepositEvent;
import com.enthusia.enthusiacurrency.event.CurrencyPayEvent;
import com.enthusia.enthusiacurrency.event.CurrencyPaySelfAttemptEvent;
import com.enthusia.enthusiacurrency.event.CurrencyWithdrawEvent;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import com.enthusia.enthusiacurrency.util.CurrencyUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;

public class CurrencyService {

    private record CachedItemBalance(long amount, long expiresAtNanos) {
    }

    private static final long ITEM_BALANCE_CACHE_TTL_NANOS = 2_000_000_000L;

    public record BalanceView(long bank, long items, long total) {
    }

    public record DepositResult(boolean success, long depositedAmount, long newBalance) {
    }

    public record WithdrawResult(boolean success, long withdrawnAmount, long newBalance, String failureReason) {
    }

    public record PayResult(boolean success, long amount, long senderBalance, String failureReason) {
    }

    public record VaultWithdrawResult(boolean success, long amountRemoved, long newBalance, String failureReason) {
    }

    private final EnthusiaCurrencyPlugin plugin;
    private final BalanceStorage balanceStorage;
    private final CurrencyManager currencyManager;
    private final Map<UUID, CachedItemBalance> itemBalanceCache = new ConcurrentHashMap<>();

    public CurrencyService(EnthusiaCurrencyPlugin plugin, BalanceStorage balanceStorage, CurrencyManager currencyManager) {
        this.plugin = plugin;
        this.balanceStorage = balanceStorage;
        this.currencyManager = currencyManager;
    }

    public long getBankBalance(UUID playerId) {
        return balanceStorage.getBalance(playerId);
    }

    public long getBankBalance(OfflinePlayer player) {
        return getBankBalance(player.getUniqueId());
    }

    public BalanceView getBalanceView(OfflinePlayer player) {
        long bank = balanceStorage.getBalance(player.getUniqueId());
        long items = 0L;
        if (player.isOnline() && player.getPlayer() != null) {
            items = getCachedItemBalance(player.getPlayer());
        }
        return new BalanceView(bank, items, bank + items);
    }

    public Map<UUID, Long> getBankSnapshot() {
        return balanceStorage.getAllBalancesSnapshot();
    }

    public DepositResult depositAll(Player player) {
        plugin.getPlayerProfileStorage().recordOnlinePlayer(player);
        CurrencyUtils.CurrencyBreakdown breakdown = CurrencyUtils.getCurrencyBreakdown(currencyManager, player);
        long totalValue = breakdown.totalValue();
        if (totalValue <= 0) {
            recordAnalytics(CurrencyAnalyticsAction.DEPOSIT, false, player, null, 0L, getBankBalance(player.getUniqueId()), "no-items");
            return new DepositResult(false, 0L, getBankBalance(player.getUniqueId()));
        }
        if (balanceStorage.wouldOverflow(player.getUniqueId(), totalValue)) {
            recordAnalytics(CurrencyAnalyticsAction.DEPOSIT, false, player, null, totalValue, getBankBalance(player.getUniqueId()), "overflow");
            return new DepositResult(false, 0L, getBankBalance(player.getUniqueId()));
        }

        CurrencyUtils.removeAllFromPlayer(
                currencyManager,
                player,
                Math.toIntExact(breakdown.items()),
                Math.toIntExact(breakdown.blocks())
        );
        long newBalance = balanceStorage.deposit(player.getUniqueId(), totalValue);
        invalidateItemBalance(player.getUniqueId());
        fireDepositEvent(player.getUniqueId(), totalValue, newBalance);
        recordAnalytics(CurrencyAnalyticsAction.DEPOSIT, true, player, null, totalValue, newBalance, null);
        markLeaderboardDirty();
        return new DepositResult(true, totalValue, newBalance);
    }

    public DepositResult deposit(Player player, long amount) {
        plugin.getPlayerProfileStorage().recordOnlinePlayer(player);
        CurrencyUtils.CurrencyBreakdown breakdown = CurrencyUtils.getCurrencyBreakdown(currencyManager, player);
        if (amount <= 0 || amount > breakdown.totalValue()) {
            recordAnalytics(CurrencyAnalyticsAction.DEPOSIT, false, player, null, Math.max(0L, amount), getBankBalance(player.getUniqueId()), "invalid");
            return new DepositResult(false, 0L, getBankBalance(player.getUniqueId()));
        }
        if (balanceStorage.wouldOverflow(player.getUniqueId(), amount)) {
            recordAnalytics(CurrencyAnalyticsAction.DEPOSIT, false, player, null, amount, getBankBalance(player.getUniqueId()), "overflow");
            return new DepositResult(false, 0L, getBankBalance(player.getUniqueId()));
        }

        long itemsToRemove;
        long blocksToRemove;

        if (amount <= breakdown.items()) {
            itemsToRemove = amount;
            blocksToRemove = 0L;
        } else {
            int blockValue = currencyManager.getBlockValue();
            if (blockValue <= 0) {
                recordAnalytics(CurrencyAnalyticsAction.DEPOSIT, false, player, null, amount, getBankBalance(player.getUniqueId()), "invalid-block-value");
                return new DepositResult(false, 0L, getBankBalance(player.getUniqueId()));
            }

            long needFromBlocks = amount - breakdown.items();
            if (needFromBlocks % blockValue != 0) {
                recordAnalytics(CurrencyAnalyticsAction.DEPOSIT, false, player, null, amount, getBankBalance(player.getUniqueId()), "invalid-denomination");
                return new DepositResult(false, 0L, getBankBalance(player.getUniqueId()));
            }

            long requiredBlocks = needFromBlocks / blockValue;
            if (requiredBlocks > breakdown.blocks()) {
                recordAnalytics(CurrencyAnalyticsAction.DEPOSIT, false, player, null, amount, getBankBalance(player.getUniqueId()), "not-enough-items");
                return new DepositResult(false, 0L, getBankBalance(player.getUniqueId()));
            }

            itemsToRemove = breakdown.items();
            blocksToRemove = requiredBlocks;
        }

        CurrencyUtils.removeAllFromPlayer(
                currencyManager,
                player,
                Math.toIntExact(itemsToRemove),
                Math.toIntExact(blocksToRemove)
        );
        long newBalance = balanceStorage.deposit(player.getUniqueId(), amount);
        invalidateItemBalance(player.getUniqueId());
        fireDepositEvent(player.getUniqueId(), amount, newBalance);
        recordAnalytics(CurrencyAnalyticsAction.DEPOSIT, true, player, null, amount, newBalance, null);
        markLeaderboardDirty();
        return new DepositResult(true, amount, newBalance);
    }

    public WithdrawResult withdrawToInventory(Player player, long amount) {
        plugin.getPlayerProfileStorage().recordOnlinePlayer(player);
        if (amount <= 0) {
            recordAnalytics(CurrencyAnalyticsAction.WITHDRAW_FAILED, false, player, null, Math.max(0L, amount), getBankBalance(player.getUniqueId()), "invalid");
            return new WithdrawResult(false, 0L, getBankBalance(player.getUniqueId()), "invalid");
        }

        long bankBalance = getBankBalance(player.getUniqueId());
        if (bankBalance < amount) {
            recordAnalytics(CurrencyAnalyticsAction.WITHDRAW_FAILED, false, player, null, amount, bankBalance, "insufficient");
            return new WithdrawResult(false, 0L, bankBalance, "insufficient");
        }

        int blockValue = currencyManager.getBlockValue();
        boolean canUseBlocks = currencyManager.getBlockMaterial() != null && blockValue > 0;

        long itemBalanceBefore = CurrencyUtils.countCurrencyInPlayer(currencyManager, player);
        long blocks = 0L;
        long items = amount;

        if (canUseBlocks && amount > 128) {
            blocks = amount / blockValue;
            items = amount % blockValue;
        }

        if (!canFitWithdrawal(player.getInventory(), canUseBlocks, blocks, items)) {
            recordAnalytics(CurrencyAnalyticsAction.WITHDRAW_FAILED, false, player, null, amount, bankBalance, "inventory-full");
            return new WithdrawResult(false, 0L, bankBalance, "inventory-full");
        }

        if (!balanceStorage.withdraw(player.getUniqueId(), amount)) {
            recordAnalytics(CurrencyAnalyticsAction.WITHDRAW_FAILED, false, player, null, amount, getBankBalance(player.getUniqueId()), "insufficient");
            return new WithdrawResult(false, 0L, getBankBalance(player.getUniqueId()), "insufficient");
        }

        if (canUseBlocks && blocks > 0) {
            long remainingBlocks = blocks;
            int blockMaxStack = currencyManager.getBlockMaterial().getMaxStackSize();
            while (remainingBlocks > 0) {
                int stackSize = (int) Math.min(remainingBlocks, blockMaxStack);
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(currencyManager.getBlockMaterial(), stackSize));
                dropOverflow(player, overflow);
                remainingBlocks -= stackSize;
            }
        }

        if (items > 0) {
            long remainingItems = items;
            int itemMaxStack = currencyManager.getMaterial().getMaxStackSize();
            while (remainingItems > 0) {
                int stackSize = (int) Math.min(remainingItems, itemMaxStack);
                Map<Integer, ItemStack> overflow = player.getInventory().addItem(currencyManager.createCurrencyItem(stackSize));
                dropOverflow(player, overflow);
                remainingItems -= stackSize;
            }
        }

        long itemBalanceAfter = CurrencyUtils.countCurrencyInPlayer(currencyManager, player);
        long physicalDelta = itemBalanceAfter - itemBalanceBefore;
        if (physicalDelta > amount) {
            long excess = physicalDelta - amount;
            int removed = CurrencyUtils.removeCurrencyFromPlayer(currencyManager, player, Math.toIntExact(excess));
            if (removed > excess) {
                balanceStorage.deposit(player.getUniqueId(), removed - excess);
            }
            invalidateItemBalance(player.getUniqueId());
        }

        invalidateItemBalance(player.getUniqueId());
        long newBalance = getBankBalance(player.getUniqueId());
        fireWithdrawEvent(player.getUniqueId(), amount, newBalance);
        recordAnalytics(CurrencyAnalyticsAction.WITHDRAW, true, player, null, amount, newBalance, null);
        markLeaderboardDirty();
        return new WithdrawResult(true, amount, newBalance, null);
    }

    public PayResult pay(Player sender, OfflinePlayer target, long amount) {
        plugin.getPlayerProfileStorage().recordOnlinePlayer(sender);
        plugin.getPlayerProfileStorage().recordKnownPlayer(target);
        if (target.getUniqueId().equals(sender.getUniqueId())) {
            Bukkit.getPluginManager().callEvent(new CurrencyPaySelfAttemptEvent(sender.getUniqueId()));
            recordAnalytics(CurrencyAnalyticsAction.PAY_FAILED, false, sender, target, amount, getBankBalance(sender.getUniqueId()), "self");
            return new PayResult(false, 0L, getBankBalance(sender.getUniqueId()), "self");
        }

        BalanceView senderView = getBalanceView(sender);
        if (amount <= 0 || senderView.total() < amount) {
            recordAnalytics(CurrencyAnalyticsAction.PAY_FAILED, false, sender, target, Math.max(0L, amount), senderView.bank(), "insufficient");
            return new PayResult(false, 0L, senderView.bank(), "insufficient");
        }
        if (balanceStorage.wouldOverflow(target.getUniqueId(), amount)) {
            recordAnalytics(CurrencyAnalyticsAction.PAY_FAILED, false, sender, target, amount, getBankBalance(sender.getUniqueId()), "overflow");
            return new PayResult(false, 0L, getBankBalance(sender.getUniqueId()), "overflow");
        }

        long remaining = amount;
        long totalRefund = 0L;

        long bankAvailable = getBankBalance(sender.getUniqueId());
        long fromBank = Math.min(bankAvailable, remaining);
        if (fromBank > 0) {
            if (!balanceStorage.withdraw(sender.getUniqueId(), fromBank)) {
                recordAnalytics(CurrencyAnalyticsAction.PAY_FAILED, false, sender, target, amount, getBankBalance(sender.getUniqueId()), "insufficient");
                return new PayResult(false, 0L, getBankBalance(sender.getUniqueId()), "insufficient");
            }
            remaining -= fromBank;
            totalRefund += fromBank;
        }

        if (remaining > 0) {
            int removed = CurrencyUtils.removeCurrencyFromPlayer(currencyManager, sender, Math.toIntExact(remaining));
            if (removed < remaining) {
                if (totalRefund > 0) {
                    balanceStorage.deposit(sender.getUniqueId(), totalRefund);
                }
                invalidateItemBalance(sender.getUniqueId());
                recordAnalytics(CurrencyAnalyticsAction.PAY_FAILED, false, sender, target, amount, getBankBalance(sender.getUniqueId()), "insufficient");
                return new PayResult(false, 0L, getBankBalance(sender.getUniqueId()), "insufficient");
            }

            if (removed > remaining) {
                balanceStorage.deposit(sender.getUniqueId(), removed - remaining);
            }
            invalidateItemBalance(sender.getUniqueId());
        }

        balanceStorage.deposit(target.getUniqueId(), amount);
        Bukkit.getPluginManager().callEvent(new CurrencyPayEvent(sender.getUniqueId(), target.getUniqueId(), amount));

        long senderBalance = getBankBalance(sender.getUniqueId());
        if (senderBalance <= 0) {
            Bukkit.getPluginManager().callEvent(new CurrencyBalanceZeroEvent(sender.getUniqueId()));
        }

        recordAnalytics(CurrencyAnalyticsAction.PAY, true, sender, target, amount, senderBalance, null);
        markLeaderboardDirty();
        return new PayResult(true, amount, senderBalance, null);
    }

    public long depositBank(UUID playerId, long amount) {
        if (amount <= 0) {
            return getBankBalance(playerId);
        }
        if (balanceStorage.wouldOverflow(playerId, amount)) {
            throw new IllegalArgumentException("Deposit would overflow balance for " + playerId);
        }
        long newBalance = balanceStorage.deposit(playerId, amount);
        fireDepositEvent(playerId, amount, newBalance);
        recordAnalytics(CurrencyAnalyticsAction.DEPOSIT, true, playerId, null, null, null, amount, newBalance, null);
        markLeaderboardDirty();
        return newBalance;
    }

    public VaultWithdrawResult withdrawTotal(OfflinePlayer player, long amount) {
        plugin.getPlayerProfileStorage().recordKnownPlayer(player);
        if (amount <= 0) {
            recordAnalytics(CurrencyAnalyticsAction.WITHDRAW_FAILED, false, player, null, Math.max(0L, amount), getBankBalance(player), "invalid");
            return new VaultWithdrawResult(false, 0L, getBankBalance(player), "invalid");
        }

        Player onlinePlayer = player.getPlayer();
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            BalanceView balanceView = getBalanceView(player);
            if (balanceView.total() < amount) {
                recordAnalytics(CurrencyAnalyticsAction.WITHDRAW_FAILED, false, player, null, amount, balanceView.total(), "insufficient");
                return new VaultWithdrawResult(false, 0L, balanceView.total(), "insufficient");
            }

            long bankTaken = Math.min(balanceView.bank(), amount);
            if (bankTaken > 0 && !balanceStorage.withdraw(player.getUniqueId(), bankTaken)) {
                recordAnalytics(CurrencyAnalyticsAction.WITHDRAW_FAILED, false, player, null, amount, getBankBalance(player), "insufficient");
                return new VaultWithdrawResult(false, 0L, getBankBalance(player), "insufficient");
            }

            long remaining = amount - bankTaken;
            if (remaining > 0) {
                int removed = CurrencyUtils.removeCurrencyFromPlayer(currencyManager, onlinePlayer, Math.toIntExact(remaining));
                if (removed < remaining) {
                    if (bankTaken > 0) {
                        balanceStorage.deposit(player.getUniqueId(), bankTaken);
                    }
                    invalidateItemBalance(player.getUniqueId());
                    recordAnalytics(CurrencyAnalyticsAction.WITHDRAW_FAILED, false, player, null, amount, getBalanceView(player).total(), "insufficient");
                    return new VaultWithdrawResult(false, bankTaken, getBalanceView(player).total(), "insufficient");
                }
                if (removed > remaining) {
                    balanceStorage.deposit(player.getUniqueId(), removed - remaining);
                }
                invalidateItemBalance(player.getUniqueId());
            }

            long newBalance = getBankBalance(player);
            fireWithdrawEvent(player.getUniqueId(), amount, newBalance);
            recordAnalytics(CurrencyAnalyticsAction.WITHDRAW, true, player, null, amount, getBalanceView(player).total(), null);
            return new VaultWithdrawResult(true, amount, getBalanceView(player).total(), null);
        }

        if (!balanceStorage.withdraw(player.getUniqueId(), amount)) {
            recordAnalytics(CurrencyAnalyticsAction.WITHDRAW_FAILED, false, player, null, amount, getBankBalance(player), "insufficient");
            return new VaultWithdrawResult(false, 0L, getBankBalance(player), "insufficient");
        }

        long newBalance = getBankBalance(player);
        fireWithdrawEvent(player.getUniqueId(), amount, newBalance);
        recordAnalytics(CurrencyAnalyticsAction.WITHDRAW, true, player, null, amount, newBalance, null);
        return new VaultWithdrawResult(true, amount, newBalance, null);
    }

    public void markLeaderboardDirty() {
        if (plugin.getBaltopTracker() != null) {
            plugin.getBaltopTracker().refreshTop3();
        }
        if (plugin.getLeaderboardExportService() != null) {
            plugin.getLeaderboardExportService().markDirty();
        }
    }

    private void fireDepositEvent(UUID playerId, long amount, long newBalance) {
        Bukkit.getPluginManager().callEvent(new CurrencyDepositEvent(playerId, amount, newBalance));
    }

    private void fireWithdrawEvent(UUID playerId, long amount, long newBalance) {
        Bukkit.getPluginManager().callEvent(new CurrencyWithdrawEvent(playerId, amount, newBalance));
        if (newBalance <= 0) {
            Bukkit.getPluginManager().callEvent(new CurrencyBalanceZeroEvent(playerId));
        }
    }

    private void dropOverflow(Player player, Map<Integer, ItemStack> overflow) {
        for (ItemStack itemStack : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), itemStack);
        }
    }

    private void recordAnalytics(
            CurrencyAnalyticsAction action,
            boolean success,
            OfflinePlayer actor,
            OfflinePlayer target,
            long amount,
            long balanceAfter,
            String reason
    ) {
        if (actor == null) {
            return;
        }
        recordAnalytics(
                action,
                success,
                actor.getUniqueId(),
                actor.getName(),
                target == null ? null : target.getUniqueId(),
                target == null ? null : target.getName(),
                amount,
                balanceAfter,
                reason
        );
    }

    private void recordAnalytics(
            CurrencyAnalyticsAction action,
            boolean success,
            UUID actorUuid,
            String actorName,
            UUID targetUuid,
            String targetName,
            long amount,
            long balanceAfter,
            String reason
    ) {
        if (plugin.getCurrencyAnalyticsStorage() == null) {
            return;
        }
        plugin.getCurrencyAnalyticsStorage().record(
                action,
                success,
                actorUuid,
                actorName,
                targetUuid,
                targetName,
                amount,
                balanceAfter,
                reason
        );
    }

    private boolean canFitWithdrawal(Inventory inventory, boolean canUseBlocks, long blocks, long items) {
        ItemStack[] simulated = cloneInventoryContents(inventory.getStorageContents());

        if (canUseBlocks && blocks > 0) {
            long remainingBlocks = blocks;
            while (remainingBlocks > 0) {
                int stackSize = (int) Math.min(remainingBlocks, currencyManager.getBlockMaterial().getMaxStackSize());
                if (!simulateAddStack(simulated, new ItemStack(currencyManager.getBlockMaterial(), stackSize))) {
                    return false;
                }
                remainingBlocks -= stackSize;
            }
        }

        if (items > 0) {
            long remainingItems = items;
            while (remainingItems > 0) {
                int stackSize = (int) Math.min(remainingItems, currencyManager.getMaterial().getMaxStackSize());
                if (!simulateAddStack(simulated, currencyManager.createCurrencyItem(stackSize))) {
                    return false;
                }
                remainingItems -= stackSize;
            }
        }

        return true;
    }

    private ItemStack[] cloneInventoryContents(ItemStack[] contents) {
        ItemStack[] cloned = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            ItemStack itemStack = contents[index];
            cloned[index] = itemStack == null ? null : itemStack.clone();
        }
        return cloned;
    }

    private boolean simulateAddStack(ItemStack[] contents, ItemStack incoming) {
        int remaining = incoming.getAmount();

        for (int index = 0; index < contents.length && remaining > 0; index++) {
            ItemStack existing = contents[index];
            if (existing == null || existing.getType() == Material.AIR) {
                continue;
            }
            if (!existing.isSimilar(incoming)) {
                continue;
            }

            int maxStack = existing.getMaxStackSize();
            int space = maxStack - existing.getAmount();
            if (space <= 0) {
                continue;
            }

            int moved = Math.min(space, remaining);
            existing.setAmount(existing.getAmount() + moved);
            remaining -= moved;
        }

        for (int index = 0; index < contents.length && remaining > 0; index++) {
            ItemStack existing = contents[index];
            if (existing != null && existing.getType() != Material.AIR) {
                continue;
            }

            int moved = Math.min(incoming.getMaxStackSize(), remaining);
            ItemStack placed = incoming.clone();
            placed.setAmount(moved);
            contents[index] = placed;
            remaining -= moved;
        }

        return remaining == 0;
    }

    private long getCachedItemBalance(Player player) {
        long now = System.nanoTime();
        CachedItemBalance cached = itemBalanceCache.get(player.getUniqueId());
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.amount();
        }

        long amount = CurrencyUtils.countCurrencyInPlayer(currencyManager, player);
        itemBalanceCache.put(player.getUniqueId(), new CachedItemBalance(amount, now + ITEM_BALANCE_CACHE_TTL_NANOS));
        return amount;
    }

    private void invalidateItemBalance(UUID playerId) {
        itemBalanceCache.remove(playerId);
    }
}
