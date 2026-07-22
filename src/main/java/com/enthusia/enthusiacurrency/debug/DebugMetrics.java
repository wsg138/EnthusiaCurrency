package com.enthusia.enthusiacurrency.debug;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import org.bukkit.Bukkit;

import java.util.concurrent.atomic.AtomicLong;

public final class DebugMetrics {

    private final EnthusiaCurrencyPlugin plugin;

    private final AtomicLong itemScansRun = new AtomicLong();
    private final AtomicLong playersScanned = new AtomicLong();
    private final AtomicLong shulkersScanned = new AtomicLong();
    private final AtomicLong dirtyPlayersQueued = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong placeholderCachedReturns = new AtomicLong();
    private final AtomicLong baltopRefreshDurationMillis = new AtomicLong();
    private final AtomicLong baltopPlayersProcessed = new AtomicLong();
    private final AtomicLong analyticsQueuedCount = new AtomicLong();
    private final AtomicLong analyticsFlushedCount = new AtomicLong();
    private final AtomicLong analyticsFailedCount = new AtomicLong();
    private final AtomicLong balanceDirtyMarks = new AtomicLong();
    private final AtomicLong balanceFlushDurationMillis = new AtomicLong();
    private final AtomicLong exportAttempts = new AtomicLong();
    private final AtomicLong exportSkippedCount = new AtomicLong();
    private final AtomicLong exportUploadedCount = new AtomicLong();
    private final AtomicLong exportFailedCount = new AtomicLong();
    private final AtomicLong r2Failures = new AtomicLong();
    private final AtomicLong skinCacheHits = new AtomicLong();
    private final AtomicLong skinCacheMisses = new AtomicLong();

    private int logTaskId = -1;

    public DebugMetrics(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        stop();
        if (!plugin.getConfig().getBoolean("debug.performance.enabled", false)) {
            return;
        }
        long intervalSeconds = Math.max(30L, plugin.getConfig().getLong("debug.performance.log-interval-seconds", 300L));
        logTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::logAndReset,
                intervalSeconds * 20L,
                intervalSeconds * 20L
        ).getTaskId();
    }

    public void stop() {
        if (logTaskId != -1) {
            Bukkit.getScheduler().cancelTask(logTaskId);
            logTaskId = -1;
        }
    }

    public void itemScan(long players, long shulkers) {
        itemScansRun.incrementAndGet();
        playersScanned.addAndGet(players);
        shulkersScanned.addAndGet(shulkers);
    }

    public void dirtyPlayerQueued() {
        dirtyPlayersQueued.incrementAndGet();
    }

    public void cacheHit() {
        cacheHits.incrementAndGet();
    }

    public void cacheMiss() {
        cacheMisses.incrementAndGet();
    }

    public void placeholderCachedReturn() {
        placeholderCachedReturns.incrementAndGet();
    }

    public void baltopRefresh(long durationMillis, int playersProcessed) {
        baltopRefreshDurationMillis.addAndGet(durationMillis);
        baltopPlayersProcessed.addAndGet(playersProcessed);
    }

    public void analyticsQueued() {
        analyticsQueuedCount.incrementAndGet();
    }

    public void analyticsFlushed(long count) {
        analyticsFlushedCount.addAndGet(count);
    }

    public void analyticsFailed() {
        analyticsFailedCount.incrementAndGet();
    }

    public void balanceDirtyMark() {
        balanceDirtyMarks.incrementAndGet();
    }

    public void balanceFlushDuration(long durationMillis) {
        balanceFlushDurationMillis.addAndGet(durationMillis);
    }

    public void exportAttempt() {
        exportAttempts.incrementAndGet();
    }

    public void exportSkipped() {
        exportSkippedCount.incrementAndGet();
    }

    public void exportUploaded() {
        exportUploadedCount.incrementAndGet();
    }

    public void exportFailed() {
        exportFailedCount.incrementAndGet();
    }

    public void r2Failure() {
        r2Failures.incrementAndGet();
    }

    public void skinCacheHit() {
        skinCacheHits.incrementAndGet();
    }

    public void skinCacheMiss() {
        skinCacheMisses.incrementAndGet();
    }

    private void logAndReset() {
        plugin.getLogger().info("[Performance] itemScans=" + itemScansRun.getAndSet(0)
                + ", playersScanned=" + playersScanned.getAndSet(0)
                + ", shulkersScanned=" + shulkersScanned.getAndSet(0)
                + ", dirtyPlayersQueued=" + dirtyPlayersQueued.getAndSet(0)
                + ", cacheHits=" + cacheHits.getAndSet(0)
                + ", cacheMisses=" + cacheMisses.getAndSet(0)
                + ", placeholderCachedReturns=" + placeholderCachedReturns.getAndSet(0)
                + ", baltopRefreshMillis=" + baltopRefreshDurationMillis.getAndSet(0)
                + ", baltopPlayersProcessed=" + baltopPlayersProcessed.getAndSet(0)
                + ", analyticsQueued=" + analyticsQueuedCount.getAndSet(0)
                + ", analyticsFlushed=" + analyticsFlushedCount.getAndSet(0)
                + ", analyticsFailed=" + analyticsFailedCount.getAndSet(0)
                + ", balanceDirtyMarks=" + balanceDirtyMarks.getAndSet(0)
                + ", balanceFlushMillis=" + balanceFlushDurationMillis.getAndSet(0)
                + ", exportAttempts=" + exportAttempts.getAndSet(0)
                + ", exportSkipped=" + exportSkippedCount.getAndSet(0)
                + ", exportUploaded=" + exportUploadedCount.getAndSet(0)
                + ", exportFailed=" + exportFailedCount.getAndSet(0)
                + ", r2Failures=" + r2Failures.getAndSet(0)
                + ", skinCacheHits=" + skinCacheHits.getAndSet(0)
                + ", skinCacheMisses=" + skinCacheMisses.getAndSet(0));
    }
}
