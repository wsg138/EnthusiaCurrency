package com.enthusia.enthusiacurrency.analytics;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("PMD.DoNotUseThreads")
public final class CurrencyAnalyticsStorage {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS currency_analytics_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                occurred_at INTEGER NOT NULL,
                actor_uuid TEXT NOT NULL,
                actor_name TEXT,
                target_uuid TEXT,
                target_name TEXT,
                action TEXT NOT NULL,
                success INTEGER NOT NULL,
                amount INTEGER NOT NULL,
                balance_after INTEGER NOT NULL,
                reason TEXT
            )
            """;

    private static final String CREATE_OCCURRED_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_currency_analytics_occurred_at
            ON currency_analytics_events(occurred_at)
            """;

    private static final String CREATE_PLAYER_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_currency_analytics_actor_uuid
            ON currency_analytics_events(actor_uuid, occurred_at)
            """;

    private static final String CREATE_PLAYER_TOTALS_SQL = """
            CREATE TABLE IF NOT EXISTS currency_analytics_player_totals (
                uuid TEXT PRIMARY KEY,
                deposited INTEGER NOT NULL,
                withdrawn INTEGER NOT NULL,
                last_activity_at INTEGER NOT NULL
            )
            """;

    private static final String CREATE_SERVER_TOTALS_SQL = """
            CREATE TABLE IF NOT EXISTS currency_analytics_server_totals (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                deposited INTEGER NOT NULL,
                withdrawn INTEGER NOT NULL,
                last_activity_at INTEGER NOT NULL
            )
            """;

    private final EnthusiaCurrencyPlugin plugin;
    private final CopyOnWriteArrayList<CurrencyAnalyticsEvent> events = new CopyOnWriteArrayList<>();
    private final Map<UUID, CurrencyAnalyticsTotals> playerTotals = new ConcurrentHashMap<>();
    private final List<CurrencyAnalyticsEvent> pendingEvents = new ArrayList<>();
    private final Set<UUID> pendingTotalPlayers = new HashSet<>();
    private final Object pendingLock = new Object();
    private final ExecutorService writerExecutor;

    private String jdbcUrl;
    private volatile long retentionMillis;
    private volatile int flushThreshold = 100;
    private volatile int flushTaskId = -1;
    private volatile CurrencyAnalyticsTotals serverTotals = new CurrencyAnalyticsTotals(0L, 0L, 0L);
    private volatile boolean closed;
    private volatile boolean flushQueued;

    public CurrencyAnalyticsStorage(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
        this.writerExecutor = Executors.newSingleThreadExecutor(new AnalyticsWriterThreadFactory());
    }

    public void load() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            jdbcUrl = "jdbc:sqlite:" + plugin.getDataFolder().toPath().resolve("balances.db").toAbsolutePath();
            reloadSettings();

            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute(CREATE_TABLE_SQL);
                statement.execute(CREATE_OCCURRED_INDEX_SQL);
                statement.execute(CREATE_PLAYER_INDEX_SQL);
                statement.execute(CREATE_PLAYER_TOTALS_SQL);
                statement.execute(CREATE_SERVER_TOTALS_SQL);
            }

            pruneOldEvents();
            events.clear();
            events.addAll(loadRecentEvents(cutoffMillis()));
            playerTotals.clear();
            playerTotals.putAll(loadPlayerTotals());
            serverTotals = loadServerTotals();
            startFlushTask();
            plugin.getLogger().info("Loaded " + events.size() + " currency analytics event(s).");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize currency analytics storage", ex);
        }
    }

    public void reloadSettings() {
        long retentionDays = Math.max(1L, plugin.getConfig().getLong("analytics.retention-days", 90L));
        retentionMillis = Duration.ofDays(retentionDays).toMillis();
        flushThreshold = Math.max(1, plugin.getConfig().getInt("analytics.flush-threshold", 100));
        restartFlushTask();
    }

    public void record(
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
        if (closed || actorUuid == null || action == null || !plugin.getConfig().getBoolean("analytics.enabled", true)) {
            return;
        }

        CurrencyAnalyticsEvent event = new CurrencyAnalyticsEvent(
                Instant.now().toEpochMilli(),
                actorUuid,
                actorName,
                targetUuid,
                targetName,
                action,
                success,
                Math.max(0L, amount),
                Math.max(0L, balanceAfter),
                cleanReason(reason)
        );
        events.add(event);
        updateTotalsInMemory(event);
        pruneMemory();
        synchronized (pendingLock) {
            pendingEvents.add(event);
            pendingTotalPlayers.add(actorUuid);
            plugin.getDebugMetrics().analyticsQueued();
            if (pendingEvents.size() >= flushThreshold) {
                queueFlush();
            }
        }
    }

    public CurrencyAnalyticsSummary summarizeServer(Duration window) {
        long since = Instant.now().minus(window).toEpochMilli();
        return summarize(events.stream()
                .filter(event -> event.occurredAt() >= since)
                .toList());
    }

    public CurrencyAnalyticsSummary summarizePlayer(UUID playerUuid, Duration window) {
        long since = Instant.now().minus(window).toEpochMilli();
        return summarize(events.stream()
                .filter(event -> event.occurredAt() >= since)
                .filter(event -> playerUuid.equals(event.actorUuid()))
                .toList());
    }

    public long lastActivityAt(UUID playerUuid) {
        CurrencyAnalyticsTotals totals = playerTotals.get(playerUuid);
        return totals == null ? 0L : totals.lastActivityAt();
    }

    public CurrencyAnalyticsTotals getPlayerTotals(UUID playerUuid) {
        return playerTotals.getOrDefault(playerUuid, new CurrencyAnalyticsTotals(0L, 0L, 0L));
    }

    public CurrencyAnalyticsTotals getServerTotals() {
        return serverTotals;
    }

    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        if (flushTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }
        queueFlush();
        writerExecutor.shutdown();
        try {
            long timeout = Math.max(1L, plugin.getConfig().getLong("analytics.shutdown-flush-timeout-seconds", 10L));
            if (!writerExecutor.awaitTermination(timeout, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Currency analytics writer did not stop cleanly within 10 seconds.");
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }
    }

    private CurrencyAnalyticsSummary summarize(List<CurrencyAnalyticsEvent> snapshot) {
        long deposited = 0L;
        long withdrawn = 0L;

        for (CurrencyAnalyticsEvent event : snapshot) {
            if (event.success()) {
                switch (event.action()) {
                    case DEPOSIT -> deposited += event.amount();
                    case WITHDRAW -> withdrawn += event.amount();
                    default -> {
                    }
                }
            }
        }

        return new CurrencyAnalyticsSummary(deposited, withdrawn);
    }

    private void flushPending() {
        List<CurrencyAnalyticsEvent> eventsToSave;
        Set<UUID> totalPlayersToSave;
        synchronized (pendingLock) {
            if (pendingEvents.isEmpty() && pendingTotalPlayers.isEmpty()) {
                flushQueued = false;
                return;
            }
            eventsToSave = new ArrayList<>(pendingEvents);
            totalPlayersToSave = new HashSet<>(pendingTotalPlayers);
            pendingEvents.clear();
            pendingTotalPlayers.clear();
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement eventStatement = connection.prepareStatement("""
                     INSERT INTO currency_analytics_events(
                         occurred_at, actor_uuid, actor_name, target_uuid, target_name,
                         action, success, amount, balance_after, reason
                     ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """);
                 PreparedStatement playerStatement = connection.prepareStatement("""
                     INSERT INTO currency_analytics_player_totals(uuid, deposited, withdrawn, last_activity_at)
                     VALUES(?, ?, ?, ?)
                     ON CONFLICT(uuid) DO UPDATE SET
                         deposited = excluded.deposited,
                         withdrawn = excluded.withdrawn,
                         last_activity_at = excluded.last_activity_at
                     """);
                 PreparedStatement serverStatement = connection.prepareStatement("""
                     INSERT INTO currency_analytics_server_totals(id, deposited, withdrawn, last_activity_at)
                     VALUES(1, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET
                         deposited = excluded.deposited,
                         withdrawn = excluded.withdrawn,
                         last_activity_at = excluded.last_activity_at
                     """)) {
                for (CurrencyAnalyticsEvent event : eventsToSave) {
                    eventStatement.setLong(1, event.occurredAt());
                    eventStatement.setString(2, event.actorUuid().toString());
                    eventStatement.setString(3, event.actorName());
                    eventStatement.setString(4, event.targetUuid() == null ? null : event.targetUuid().toString());
                    eventStatement.setString(5, event.targetName());
                    eventStatement.setString(6, event.action().name());
                    eventStatement.setInt(7, event.success() ? 1 : 0);
                    eventStatement.setLong(8, event.amount());
                    eventStatement.setLong(9, event.balanceAfter());
                    eventStatement.setString(10, event.reason());
                    eventStatement.addBatch();
                }
                eventStatement.executeBatch();

                for (UUID uuid : totalPlayersToSave) {
                    CurrencyAnalyticsTotals totals = playerTotals.get(uuid);
                    if (totals == null) {
                        continue;
                    }
                    playerStatement.setString(1, uuid.toString());
                    playerStatement.setLong(2, totals.deposited());
                    playerStatement.setLong(3, totals.withdrawn());
                    playerStatement.setLong(4, totals.lastActivityAt());
                    playerStatement.addBatch();
                }
                playerStatement.executeBatch();

                CurrencyAnalyticsTotals totals = serverTotals;
                serverStatement.setLong(1, totals.deposited());
                serverStatement.setLong(2, totals.withdrawn());
                serverStatement.setLong(3, totals.lastActivityAt());
                serverStatement.executeUpdate();
            }
            connection.commit();
            plugin.getDebugMetrics().analyticsFlushed(eventsToSave.size());
        } catch (Exception ex) {
            synchronized (pendingLock) {
                pendingEvents.addAll(0, eventsToSave);
                pendingTotalPlayers.addAll(totalPlayersToSave);
            }
            plugin.getDebugMetrics().analyticsFailed();
            plugin.getLogger().warning("Failed to store currency analytics batch: " + ex.getMessage());
        } finally {
            flushQueued = false;
        }
    }

    private void updateTotalsInMemory(CurrencyAnalyticsEvent event) {
        long depositedDelta = event.success() && event.action() == CurrencyAnalyticsAction.DEPOSIT ? event.amount() : 0L;
        long withdrawnDelta = event.success() && event.action() == CurrencyAnalyticsAction.WITHDRAW ? event.amount() : 0L;

        playerTotals.compute(event.actorUuid(), (uuid, current) -> {
            CurrencyAnalyticsTotals base = current == null ? new CurrencyAnalyticsTotals(0L, 0L, 0L) : current;
            return new CurrencyAnalyticsTotals(
                    base.deposited() + depositedDelta,
                    base.withdrawn() + withdrawnDelta,
                    Math.max(base.lastActivityAt(), event.occurredAt())
            );
        });

        CurrencyAnalyticsTotals currentServer = serverTotals;
        serverTotals = new CurrencyAnalyticsTotals(
                currentServer.deposited() + depositedDelta,
                currentServer.withdrawn() + withdrawnDelta,
                Math.max(currentServer.lastActivityAt(), event.occurredAt())
        );

        synchronized (pendingLock) {
            pendingTotalPlayers.add(event.actorUuid());
        }
    }

    private void startFlushTask() {
        if (flushTaskId != -1 || closed) {
            return;
        }
        long intervalSeconds = Math.max(1L, plugin.getConfig().getLong("analytics.flush-interval-seconds", 10L));
        flushTaskId = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::queueFlush,
                intervalSeconds * 20L,
                intervalSeconds * 20L
        ).getTaskId();
    }

    private void restartFlushTask() {
        if (flushTaskId != -1) {
            plugin.getServer().getScheduler().cancelTask(flushTaskId);
            flushTaskId = -1;
        }
        if (!closed && plugin.isEnabled()) {
            startFlushTask();
        }
    }

    private void queueFlush() {
        if (flushQueued) {
            return;
        }
        flushQueued = true;
        writerExecutor.execute(this::flushPending);
    }

    private List<CurrencyAnalyticsEvent> loadRecentEvents(long since) throws Exception {
        List<CurrencyAnalyticsEvent> loaded = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT occurred_at, actor_uuid, actor_name, target_uuid, target_name,
                            action, success, amount, balance_after, reason
                     FROM currency_analytics_events
                     WHERE occurred_at >= ?
                     ORDER BY occurred_at DESC
                     """)) {
            statement.setLong(1, since);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String targetUuid = resultSet.getString("target_uuid");
                    loaded.add(new CurrencyAnalyticsEvent(
                            resultSet.getLong("occurred_at"),
                            UUID.fromString(resultSet.getString("actor_uuid")),
                            resultSet.getString("actor_name"),
                            targetUuid == null ? null : UUID.fromString(targetUuid),
                            resultSet.getString("target_name"),
                            CurrencyAnalyticsAction.valueOf(resultSet.getString("action")),
                            resultSet.getInt("success") == 1,
                            resultSet.getLong("amount"),
                            resultSet.getLong("balance_after"),
                            resultSet.getString("reason")
                    ));
                }
            }
        }
        return loaded;
    }

    private Map<UUID, CurrencyAnalyticsTotals> loadPlayerTotals() throws Exception {
        Map<UUID, CurrencyAnalyticsTotals> totals = new ConcurrentHashMap<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT uuid, deposited, withdrawn, last_activity_at
                     FROM currency_analytics_player_totals
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                totals.put(uuid, new CurrencyAnalyticsTotals(
                        resultSet.getLong("deposited"),
                        resultSet.getLong("withdrawn"),
                        resultSet.getLong("last_activity_at")
                ));
            }
        }
        return totals;
    }

    private CurrencyAnalyticsTotals loadServerTotals() throws Exception {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT deposited, withdrawn, last_activity_at
                     FROM currency_analytics_server_totals
                     WHERE id = 1
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return new CurrencyAnalyticsTotals(
                        resultSet.getLong("deposited"),
                        resultSet.getLong("withdrawn"),
                        resultSet.getLong("last_activity_at")
                );
            }
        }
        return new CurrencyAnalyticsTotals(0L, 0L, 0L);
    }

    private void upsertPlayerTotals(UUID playerUuid) {
        CurrencyAnalyticsTotals totals = playerTotals.get(playerUuid);
        if (totals == null) {
            return;
        }

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO currency_analytics_player_totals(uuid, deposited, withdrawn, last_activity_at)
                     VALUES(?, ?, ?, ?)
                     ON CONFLICT(uuid) DO UPDATE SET
                         deposited = excluded.deposited,
                         withdrawn = excluded.withdrawn,
                         last_activity_at = excluded.last_activity_at
                     """)) {
            statement.setString(1, playerUuid.toString());
            statement.setLong(2, totals.deposited());
            statement.setLong(3, totals.withdrawn());
            statement.setLong(4, totals.lastActivityAt());
            statement.executeUpdate();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to store player analytics totals: " + ex.getMessage());
        }
    }

    private void upsertServerTotals() {
        CurrencyAnalyticsTotals totals = serverTotals;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO currency_analytics_server_totals(id, deposited, withdrawn, last_activity_at)
                     VALUES(1, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET
                         deposited = excluded.deposited,
                         withdrawn = excluded.withdrawn,
                         last_activity_at = excluded.last_activity_at
                     """)) {
            statement.setLong(1, totals.deposited());
            statement.setLong(2, totals.withdrawn());
            statement.setLong(3, totals.lastActivityAt());
            statement.executeUpdate();
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to store server analytics totals: " + ex.getMessage());
        }
    }

    private void pruneOldEvents() {
        writerExecutor.execute(() -> {
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM currency_analytics_events WHERE occurred_at < ?")) {
                statement.setLong(1, cutoffMillis());
                statement.executeUpdate();
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to prune old currency analytics events: " + ex.getMessage());
            }
        });
    }

    private void pruneMemory() {
        long cutoff = cutoffMillis();
        events.removeIf(event -> event.occurredAt() < cutoff);
    }

    private long cutoffMillis() {
        return Instant.now().toEpochMilli() - retentionMillis;
    }

    private String cleanReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.length() > 64 ? reason.substring(0, 64) : reason;
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    private static final class AnalyticsWriterThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "EnthusiaCurrency-AnalyticsWriter");
            thread.setDaemon(true);
            return thread;
        }
    }
}
