package com.enthusia.enthusiacurrency.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SqliteBalanceRepository implements BalanceRepository {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS balances (
                uuid TEXT PRIMARY KEY,
                balance INTEGER NOT NULL,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """;

    private final Path databasePath;
    private final String jdbcUrl;

    public SqliteBalanceRepository(Path databasePath) {
        this.databasePath = databasePath;
        this.jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
    }

    @Override
    public void initialize() throws Exception {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute(CREATE_TABLE_SQL);
            addUpdatedAtColumnIfMissing(statement);
        }
    }

    @Override
    public Map<UUID, Long> loadAllBalances() throws Exception {
        Map<UUID, Long> balances = new HashMap<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT uuid, balance FROM balances")) {
            while (resultSet.next()) {
                UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                long balance = resultSet.getLong("balance");
                balances.put(uuid, balance);
            }
        }
        return balances;
    }

    @Override
    public void saveBalances(Map<UUID, Long> balances) throws Exception {
        if (balances.isEmpty()) {
            return;
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO balances(uuid, balance, updated_at) VALUES(?, ?, ?) " +
                            "ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance, updated_at = excluded.updated_at")) {
                long updatedAt = System.currentTimeMillis();
                for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
                    statement.setString(1, entry.getKey().toString());
                    statement.setLong(2, entry.getValue());
                    statement.setLong(3, updatedAt);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        } catch (Exception ex) {
            throw ex;
        }
    }

    @Override
    public void close() {
        // Connections are short-lived; nothing to close.
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void addUpdatedAtColumnIfMissing(Statement statement) throws Exception {
        try {
            statement.execute("ALTER TABLE balances ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0");
        } catch (Exception ex) {
            String message = ex.getMessage();
            if (message == null || !message.toLowerCase().contains("duplicate column")) {
                throw ex;
            }
        }
    }

    public Path getDatabasePath() {
        return databasePath;
    }
}
