package com.enthusia.enthusiacurrency.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceStorageTest {

    private BalanceStorage storage;

    @AfterEach
    void closeStorage() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    void rejectsInvalidWithdrawalsAndNeverCreatesNegativeBalances() {
        UUID player = UUID.randomUUID();
        storage = new BalanceStorage(new RecordingRepository(), 100L);

        assertThat(storage.withdraw(player, 0L)).isFalse();
        assertThat(storage.withdraw(player, -1L)).isFalse();
        assertThat(storage.withdraw(player, 101L)).isFalse();
        assertThat(storage.getBalance(player)).isEqualTo(100L);
        assertThat(storage.withdraw(player, 100L)).isTrue();
        assertThat(storage.getBalance(player)).isZero();
        assertThat(storage.withdraw(player, 1L)).isFalse();
    }

    @Test
    void saturatesLargeDepositsWithoutOverflowing() {
        UUID player = UUID.randomUUID();
        storage = new BalanceStorage(new RecordingRepository(), 0L);

        assertThat(storage.deposit(player, Long.MAX_VALUE - 2L)).isEqualTo(Long.MAX_VALUE - 2L);
        assertThat(storage.deposit(player, 10L)).isEqualTo(Long.MAX_VALUE);
        assertThat(storage.deposit(player, -5L)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void concurrentWithdrawalsCannotOverspendAndFlushPersistsAcceptedWrites() throws Exception {
        UUID player = UUID.randomUUID();
        RecordingRepository repository = new RecordingRepository();
        storage = new BalanceStorage(repository, 100L);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> withdrawWhenReleased(player, ready, start));
            var second = workers.submit(() -> withdrawWhenReleased(player, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS) ^ second.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(storage.getBalance(player)).isZero();
            storage.flushAsync().get(5, TimeUnit.SECONDS);
            assertThat(repository.savedBalances()).containsEntry(player, 0L);
        } finally {
            workers.shutdownNow();
        }
    }

    private boolean withdrawWhenReleased(UUID player, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("workers were not released");
            }
            return storage.withdraw(player, 100L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingRepository implements BalanceRepository {
        private final Map<UUID, Long> savedBalances = new ConcurrentHashMap<>();

        @Override public void initialize() { }
        @Override public Map<UUID, Long> loadAllBalances() { return Map.copyOf(savedBalances); }
        @Override public void saveBalances(Map<UUID, Long> balances) { savedBalances.putAll(balances); }
        @Override public void close() { }

        Map<UUID, Long> savedBalances() { return Map.copyOf(savedBalances); }
    }
}
