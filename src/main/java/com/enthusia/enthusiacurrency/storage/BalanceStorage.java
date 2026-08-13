package com.enthusia.enthusiacurrency.storage;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import org.bukkit.Bukkit;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("PMD.DoNotUseThreads")
public class BalanceStorage {

    public record BalanceSnapshot(long amount, long revision) {
    }

    private record CachedBalance(long amount, long version) {
    }

    private final EnthusiaCurrencyPlugin plugin;
    private final Map<UUID, CachedBalance> balances = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyKeys = ConcurrentHashMap.newKeySet();
    private final Object flushLock = new Object();
    private final List<CompletableFuture<Void>> pendingFlushFutures = new ArrayList<>();
    private final ExecutorService writerExecutor;

    private BalanceRepository repository;
    private int flushTaskId = -1;
    private volatile long startingBalance;
    private volatile long flushIntervalTicks;
    private volatile int flushThreshold;
    private volatile boolean warnedAboutDecimals;
    private volatile boolean closed;
    private volatile boolean flushQueued;

    public BalanceStorage(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
        this.writerExecutor = Executors.newSingleThreadExecutor(new BalanceWriterThreadFactory());
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    public void load() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());

            this.repository = new SqliteBalanceRepository(plugin.getDataFolder().toPath().resolve("balances.db"));
            this.repository.initialize();

            Map<UUID, BalanceRepository.StoredBalance> loadedBalances = repository.loadAllBalances();
            if (loadedBalances.isEmpty()) {
                File yamlFile = new File(plugin.getDataFolder(), "balances.yml");
                Map<UUID, Long> migratedBalances = LegacyYamlBalanceMigration.loadBalances(yamlFile, plugin.getLogger());
                if (!migratedBalances.isEmpty()) {
                    Map<UUID, BalanceRepository.StoredBalance> migrated = new ConcurrentHashMap<>();
                    for (Map.Entry<UUID, Long> entry : migratedBalances.entrySet()) {
                        migrated.put(
                                entry.getKey(),
                                new BalanceRepository.StoredBalance(Math.max(0L, entry.getValue()), 0L)
                        );
                    }
                    repository.saveBalances(migrated);
                    LegacyYamlBalanceMigration.markMigrated(yamlFile);
                    loadedBalances = migrated;
                    plugin.getLogger().info("Migrated " + migratedBalances.size() + " balance(s) from balances.yml to SQLite.");
                }
            }

            balances.clear();
            for (Map.Entry<UUID, BalanceRepository.StoredBalance> entry : loadedBalances.entrySet()) {
                BalanceRepository.StoredBalance stored = entry.getValue();
                balances.put(
                        entry.getKey(),
                        new CachedBalance(Math.max(0L, stored.amount()), stored.revision())
                );
            }

            reloadSettings();
            startFlushTask();
            plugin.getLogger().info("Loaded " + balances.size() + " balance(s) into memory.");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize balance storage", ex);
        }
    }

    public void reloadSettings() {
        this.startingBalance = loadStartingBalance();
        long intervalSeconds = Math.max(5L, plugin.getConfig().getLong("storage.flush-interval-seconds", 30L));
        this.flushIntervalTicks = intervalSeconds * 20L;
        this.flushThreshold = Math.max(25, plugin.getConfig().getInt("storage.flush-threshold", 200));
        if (plugin.getConfig().getBoolean("economy.allow-decimals", false) && !warnedAboutDecimals) {
            plugin.getLogger().warning("economy.allow-decimals is enabled, but balances are stored as whole numbers. Decimal input will be rounded down.");
            warnedAboutDecimals = true;
        }
        restartFlushTask();
    }

    public long getBalance(UUID uuid) {
        return balances.computeIfAbsent(uuid, ignored -> new CachedBalance(startingBalance, 0L)).amount();
    }

    public BalanceSnapshot getBalanceSnapshot(UUID uuid) {
        CachedBalance current = balances.computeIfAbsent(
                uuid,
                ignored -> new CachedBalance(startingBalance, 0L)
        );
        return new BalanceSnapshot(current.amount(), current.version());
    }

    public long ensureAccount(UUID uuid) {
        CachedBalance existing = balances.putIfAbsent(uuid, new CachedBalance(startingBalance, 1L));
        if (existing == null) {
            markDirty(uuid);
            return startingBalance;
        }
        return existing.amount();
    }

    public boolean wouldOverflow(UUID uuid, long amount) {
        if (amount <= 0) {
            return false;
        }
        long current = getBalance(uuid);
        return Long.MAX_VALUE - current < amount;
    }

    public long setBalance(UUID uuid, long amount) {
        CachedBalance updated = balances.compute(uuid, (ignored, current) -> {
            long clampedAmount = Math.max(0L, amount);
            return new CachedBalance(clampedAmount, nextVersion(current));
        });
        markDirty(uuid);
        return updated.amount();
    }

    public boolean replaceIfCurrent(
            UUID uuid,
            long expectedAmount,
            long expectedRevision,
            long replacementAmount,
            boolean forceRevisionBump
    ) {
        validateCasValues(expectedAmount, expectedRevision, replacementAmount);
        CasOutcome outcome = new CasOutcome();
        balances.compute(
                uuid,
                (ignored, current) -> replaceCachedBalance(
                        current,
                        expectedAmount,
                        expectedRevision,
                        replacementAmount,
                        forceRevisionBump,
                        outcome
                )
        );
        if (outcome.changed) {
            markDirty(uuid);
        }
        return outcome.success;
    }

    private static void validateCasValues(
            long expectedAmount,
            long expectedRevision,
            long replacementAmount
    ) {
        if (expectedAmount < 0L || expectedRevision < 0L || replacementAmount < 0L) {
            throw new IllegalArgumentException("balance CAS values cannot be negative");
        }
    }

    private CachedBalance replaceCachedBalance(
            CachedBalance current,
            long expectedAmount,
            long expectedRevision,
            long replacementAmount,
            boolean forceRevisionBump,
            CasOutcome outcome
    ) {
        CachedBalance base = current == null
                ? new CachedBalance(startingBalance, 0L)
                : current;
        if (!matchesExpected(base, expectedAmount, expectedRevision)) {
            return current;
        }
        outcome.success = true;
        if (!requiresReplacement(base, replacementAmount, forceRevisionBump)) {
            return base;
        }
        outcome.changed = true;
        return new CachedBalance(replacementAmount, Math.addExact(base.version(), 1L));
    }

    private static boolean matchesExpected(
            CachedBalance balance,
            long expectedAmount,
            long expectedRevision
    ) {
        return balance.amount() == expectedAmount && balance.version() == expectedRevision;
    }

    private static boolean requiresReplacement(
            CachedBalance balance,
            long replacementAmount,
            boolean forceRevisionBump
    ) {
        return forceRevisionBump || balance.amount() != replacementAmount;
    }

    public long deposit(UUID uuid, long amount) {
        if (amount <= 0) {
            return getBalance(uuid);
        }

        CachedBalance updated = balances.compute(uuid, (ignored, current) -> {
            long base = current == null ? startingBalance : current.amount();
            long nextAmount = saturatingAdd(base, amount);
            return new CachedBalance(nextAmount, nextVersion(current));
        });
        markDirty(uuid);
        return updated.amount();
    }

    public boolean withdraw(UUID uuid, long amount) {
        if (amount <= 0) {
            return false;
        }

        boolean[] success = {false};
        balances.compute(uuid, (ignored, current) -> {
            long base = current == null ? startingBalance : current.amount();
            if (base < amount) {
                return current == null ? new CachedBalance(base, 0L) : current;
            }
            success[0] = true;
            return new CachedBalance(base - amount, nextVersion(current));
        });

        if (success[0]) {
            markDirty(uuid);
        }
        return success[0];
    }

    public Map<UUID, Long> getAllBalancesSnapshot() {
        Map<UUID, Long> snapshot = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, CachedBalance> entry : balances.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().amount());
        }
        return snapshot;
    }

    public CompletableFuture<Void> flushAsync() {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (flushLock) {
            pendingFlushFutures.add(future);
            if (!flushQueued) {
                flushQueued = true;
                writerExecutor.execute(this::runFlushLoop);
            }
        }
        return future;
    }

    public void save() {
        flushBlocking();
    }

    public void close() {
        if (closed) {
            return;
        }

        if (flushTaskId != -1) {
            Bukkit.getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }

        flushBlocking();
        closed = true;

        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Balance writer did not stop cleanly within 10 seconds.");
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }

        if (repository != null) {
            try {
                repository.close();
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to close balance repository cleanly: " + ex.getMessage());
            }
        }
    }

    private void startFlushTask() {
        if (flushTaskId != -1) {
            return;
        }
        flushTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::flushAsync,
                flushIntervalTicks,
                flushIntervalTicks
        ).getTaskId();
    }

    private void restartFlushTask() {
        if (flushTaskId != -1) {
            Bukkit.getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }
        if (!closed) {
            startFlushTask();
        }
    }

    private void markDirty(UUID uuid) {
        dirtyKeys.add(uuid);
        plugin.getDebugMetrics().balanceDirtyMark();
        if (dirtyKeys.size() >= flushThreshold) {
            flushAsync();
        }
    }

    private void runFlushLoop() {
        Throwable failure = null;
        long startedAt = System.currentTimeMillis();
        try {
            flushDirtyBalances();
        } catch (Exception ex) {
            failure = ex;
            plugin.getLogger().severe("Failed to flush balances: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            plugin.getDebugMetrics().balanceFlushDuration(System.currentTimeMillis() - startedAt);
            completePendingFlushes(failure);
        }
    }

    private void flushDirtyBalances() throws Exception {
        while (true) {
            Map<UUID, CachedBalance> snapshot = snapshotDirtyBalances();
            if (snapshot.isEmpty()) {
                return;
            }
            repository.saveBalances(balanceValues(snapshot));
            clearCleanDirtyKeys(snapshot);
        }
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private Map<UUID, BalanceRepository.StoredBalance> balanceValues(
            Map<UUID, CachedBalance> snapshot
    ) {
        Map<UUID, BalanceRepository.StoredBalance> toSave = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, CachedBalance> entry : snapshot.entrySet()) {
            CachedBalance value = entry.getValue();
            toSave.put(
                    entry.getKey(),
                    new BalanceRepository.StoredBalance(value.amount(), value.version())
            );
        }
        return toSave;
    }

    private void clearCleanDirtyKeys(Map<UUID, CachedBalance> snapshot) {
        for (Map.Entry<UUID, CachedBalance> entry : snapshot.entrySet()) {
            CachedBalance current = balances.get(entry.getKey());
            if (current != null && current.version() == entry.getValue().version()) {
                dirtyKeys.remove(entry.getKey());
            }
        }
    }

    private void completePendingFlushes(Throwable failure) {
        List<CompletableFuture<Void>> toComplete;
        synchronized (flushLock) {
            flushQueued = false;
            toComplete = new ArrayList<>(pendingFlushFutures);
            pendingFlushFutures.clear();
            if (!dirtyKeys.isEmpty() && !closed && !flushQueued) {
                flushQueued = true;
                writerExecutor.execute(this::runFlushLoop);
            }
        }
        for (CompletableFuture<Void> future : toComplete) {
            completeFuture(future, failure);
        }
    }

    private void completeFuture(CompletableFuture<Void> future, Throwable failure) {
        if (failure == null) {
            future.complete(null);
        } else {
            future.completeExceptionally(failure);
        }
    }

    private Map<UUID, CachedBalance> snapshotDirtyBalances() {
        Map<UUID, CachedBalance> snapshot = new ConcurrentHashMap<>();
        for (UUID uuid : dirtyKeys) {
            CachedBalance cachedBalance = balances.get(uuid);
            if (cachedBalance != null) {
                snapshot.put(uuid, cachedBalance);
            }
        }
        return snapshot;
    }

    private void flushBlocking() {
        if (dirtyKeys.isEmpty() && !flushQueued) {
            return;
        }

        try {
            CompletableFuture<Void> future = new CompletableFuture<>();
            synchronized (flushLock) {
                pendingFlushFutures.add(future);
                if (!flushQueued) {
                    flushQueued = true;
                    writerExecutor.execute(this::runFlushLoop);
                }
            }
            future.get(15, TimeUnit.SECONDS);
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to flush balances during shutdown: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private long loadStartingBalance() {
        BigDecimal configured = BigDecimal.valueOf(plugin.getConfig().getDouble("economy.starting-balance", 0.0D));
        BigDecimal normalized = configured.setScale(0, RoundingMode.DOWN);
        if (configured.compareTo(normalized) != 0) {
            plugin.getLogger().warning("economy.starting-balance contains a decimal value. It will be rounded down to " + normalized.toPlainString() + ".");
        }
        return normalized.longValue();
    }

    private static long nextVersion(CachedBalance current) {
        return current == null ? 1L : Math.addExact(current.version(), 1L);
    }

    private long saturatingAdd(long current, long delta) {
        if (delta > 0 && Long.MAX_VALUE - current < delta) {
            return Long.MAX_VALUE;
        }
        if (delta < 0 && Long.MIN_VALUE - current > delta) {
            return Long.MIN_VALUE;
        }
        return current + delta;
    }

    private static final class CasOutcome {
        private boolean success;
        private boolean changed;
    }

    private static final class BalanceWriterThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "EnthusiaCurrency-BalanceWriter");
            thread.setDaemon(true);
            return thread;
        }
    }
}
