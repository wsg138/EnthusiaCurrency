package com.enthusia.enthusiacurrency.moderation;

import com.enthusia.enthusiacurrency.api.moderation.CurrencyAccountSnapshot;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Captures and verifies exact currency account state. */
final class CurrencyAccountCodec {

    private final BalanceStorage balances;
    private final CurrencyInventoryEditor inventories;

    CurrencyAccountCodec(BalanceStorage balances, CurrencyInventoryEditor inventories) {
        this.balances = Objects.requireNonNull(balances, "balances");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
    }

    CurrencyAccountSnapshot capture(Player player) {
        BalanceStorage.BalanceSnapshot bank = balances.getBalanceSnapshot(player.getUniqueId());
        byte[] inventory = ItemStack.serializeItemsAsBytes(player.getInventory().getContents());
        byte[] enderChest = ItemStack.serializeItemsAsBytes(player.getEnderChest().getContents());
        long inventoryValue = inventories.value(decode(inventory));
        long enderValue = inventories.value(decode(enderChest));
        long total = total(bank.amount(), inventoryValue, enderValue);
        String stateChecksum = checksum(
                player.getUniqueId(),
                bank.amount(),
                bank.revision(),
                inventory,
                enderChest,
                inventoryValue,
                enderValue
        );
        return new CurrencyAccountSnapshot(
                player.getUniqueId(),
                bank.amount(),
                bank.revision(),
                inventory,
                enderChest,
                inventoryValue,
                enderValue,
                total,
                stateChecksum
        );
    }

    void verifySnapshotChecksum(CurrencyAccountSnapshot snapshot) {
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

    boolean sameAssets(CurrencyAccountSnapshot first, CurrencyAccountSnapshot second) {
        return first.playerId().equals(second.playerId())
                && first.bankBalance() == second.bankBalance()
                && first.inventoryValue() == second.inventoryValue()
                && first.enderChestValue() == second.enderChestValue()
                && first.authoritativeTotal() == second.authoritativeTotal()
                && Arrays.equals(first.inventory(), second.inventory())
                && Arrays.equals(first.enderChest(), second.enderChest());
    }

    ItemStack[] decode(byte[] bytes) {
        return ItemStack.deserializeItemsFromBytes(bytes);
    }

    long total(long bank, long inventory, long enderChest) {
        return Math.addExact(bank, Math.addExact(inventory, enderChest));
    }

    String checksum(
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
}
