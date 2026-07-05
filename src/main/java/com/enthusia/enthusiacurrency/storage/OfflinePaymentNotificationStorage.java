package com.enthusia.enthusiacurrency.storage;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("PMD.DoNotUseThreads")
public final class OfflinePaymentNotificationStorage {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS offline_payment_notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                target_uuid TEXT NOT NULL,
                sender_uuid TEXT NOT NULL,
                sender_name TEXT NOT NULL,
                amount INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """;

    private static final String CREATE_TARGET_INDEX_SQL = """
            CREATE INDEX IF NOT EXISTS idx_offline_payment_notifications_target
            ON offline_payment_notifications(target_uuid, created_at)
            """;

    private record Notification(long id, UUID senderUuid, String senderName, long amount) {
    }

    private final EnthusiaCurrencyPlugin plugin;
    private final ExecutorService executor;

    private String jdbcUrl;
    private volatile boolean closed;

    public OfflinePaymentNotificationStorage(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
        this.executor = Executors.newSingleThreadExecutor(new NotificationThreadFactory());
    }

    public void load() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            jdbcUrl = "jdbc:sqlite:" + plugin.getDataFolder().toPath().resolve("balances.db").toAbsolutePath();
            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute(CREATE_TABLE_SQL);
                statement.execute(CREATE_TARGET_INDEX_SQL);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize offline payment notification storage", ex);
        }
    }

    public void record(UUID targetUuid, UUID senderUuid, String senderName, long amount) {
        if (closed || targetUuid == null || senderUuid == null || amount <= 0L) {
            return;
        }
        String safeSenderName = senderName == null || senderName.isBlank() ? "Someone" : senderName;
        executor.execute(() -> {
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO offline_payment_notifications(target_uuid, sender_uuid, sender_name, amount, created_at)
                         VALUES(?, ?, ?, ?, ?)
                         """)) {
                statement.setString(1, targetUuid.toString());
                statement.setString(2, senderUuid.toString());
                statement.setString(3, safeSenderName);
                statement.setLong(4, amount);
                statement.setLong(5, System.currentTimeMillis());
                statement.executeUpdate();
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to store offline payment notification: " + ex.getMessage());
            }
        });
    }

    public void deliverPending(Player player) {
        if (closed || player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        executor.execute(() -> {
            List<Notification> notifications = loadNotifications(playerId);
            if (notifications.isEmpty()) {
                return;
            }

            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player online = plugin.getServer().getPlayer(playerId);
                if (online == null || !online.isOnline()) {
                    return;
                }

                List<Long> deliveredIds = new ArrayList<>();
                for (Notification notification : notifications) {
                    sendNotification(online, notification);
                    deliveredIds.add(notification.id());
                }
                deleteNotifications(deliveredIds);
            });
        });
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Offline payment notification writer did not stop cleanly within 10 seconds.");
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private List<Notification> loadNotifications(UUID targetUuid) {
        List<Notification> notifications = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, sender_uuid, sender_name, amount
                     FROM offline_payment_notifications
                     WHERE target_uuid = ?
                     ORDER BY created_at ASC, id ASC
                     """)) {
            statement.setString(1, targetUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    notifications.add(new Notification(
                            resultSet.getLong("id"),
                            UUID.fromString(resultSet.getString("sender_uuid")),
                            resultSet.getString("sender_name"),
                            resultSet.getLong("amount")
                    ));
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load offline payment notifications: " + ex.getMessage());
        }
        return notifications;
    }

    private void sendNotification(Player player, Notification notification) {
        String message = plugin.msgNoPrefix("pay-received-offline")
                .replace("%sender%", notification.senderName())
                .replace("%amount%", String.valueOf(notification.amount()))
                .replace("%symbol%", plugin.getCurrencySymbol())
                .replace("%currency%", plugin.getCurrencyName(notification.amount()));
        player.sendMessage(plugin.getPrefix() + message);
    }

    private void deleteNotifications(List<Long> ids) {
        if (ids.isEmpty() || closed) {
            return;
        }
        executor.execute(() -> {
            try (Connection connection = openConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM offline_payment_notifications WHERE id = ?")) {
                for (Long id : ids) {
                    statement.setLong(1, id);
                    statement.addBatch();
                }
                statement.executeBatch();
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to clear offline payment notifications: " + ex.getMessage());
            }
        });
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    private static final class NotificationThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "EnthusiaCurrency-OfflinePaymentNotifications");
            thread.setDaemon(true);
            return thread;
        }
    }
}
