package com.enthusia.enthusiacurrency.service;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrencyInventoryWithdrawalTest {
    private static final class RegistryFreeItemStack extends ItemStack implements Cloneable {
        private final Material material;
        private int amount;

        private RegistryFreeItemStack(Material material, int amount) {
            this.material = material;
            this.amount = amount;
        }

        @Override
        public Material getType() {
            return material;
        }

        @Override
        public int getAmount() {
            return amount;
        }

        @Override
        public void setAmount(int amount) {
            this.amount = amount;
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }

        @Override
        public boolean isSimilar(ItemStack other) {
            return other != null && material == other.getType();
        }

        @Override
        public RegistryFreeItemStack clone() {
            return new RegistryFreeItemStack(material, amount);
        }
    }

    @Test
    void plansSmallNonDivisibleWithdrawalsAsItems() {
        CurrencyInventoryWithdrawal.WithdrawalStacks stacks =
                CurrencyInventoryWithdrawal.plan(127L, true, 9);

        assertEquals(0L, stacks.blocks());
        assertEquals(127L, stacks.items());
    }

    @Test
    void plansLargeAndDivisibleWithdrawalsWithBlocks() {
        assertEquals(
                new CurrencyInventoryWithdrawal.WithdrawalStacks(true, 14L, 0L),
                CurrencyInventoryWithdrawal.plan(126L, true, 9));
        assertEquals(
                new CurrencyInventoryWithdrawal.WithdrawalStacks(true, 14L, 3L),
                CurrencyInventoryWithdrawal.plan(129L, true, 9));
        assertEquals(
                new CurrencyInventoryWithdrawal.WithdrawalStacks(false, 0L, 129L),
                CurrencyInventoryWithdrawal.plan(129L, false, 0));
    }

    @Test
    void simulationCombinesCompatibleStacksBeforeUsingEmptySlots() {
        ItemStack[] contents = {new RegistryFreeItemStack(Material.RAW_GOLD, 60), null};

        assertTrue(CurrencyInventoryWithdrawal.simulateAddStack(
                contents, new RegistryFreeItemStack(Material.RAW_GOLD, 10)));
        assertEquals(64, contents[0].getAmount());
        assertEquals(6, contents[1].getAmount());
    }

    @Test
    void simulationRejectsStacksThatDoNotFit() {
        ItemStack[] contents = {new RegistryFreeItemStack(Material.RAW_GOLD, 64)};

        assertFalse(CurrencyInventoryWithdrawal.simulateAddStack(
                contents, new RegistryFreeItemStack(Material.RAW_GOLD, 1)));
        assertEquals(64, contents[0].getAmount());
    }
}
