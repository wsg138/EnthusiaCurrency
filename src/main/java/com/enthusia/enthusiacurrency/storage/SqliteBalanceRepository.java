package com.enthusia.enthusiacurrency.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SqliteBalanceRepository implements BalanceRepository {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS balances (
                uuid TEXT PRIMARY KEY,
                balance INTEGER NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0,
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
            addRevisionColumnIfMissing(statement);
            addUpdatedAtColumnIfMissing(statement);
        }
    }

    @Override
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    public Map<UUID, StoredBalance> loadAllBalances() throws Exception {
        Map<UUID, StoredBalance> balances = new ConcurrentHashMap<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT uuid, balance, revision FROM balances"
             )) {
            while (resultSet.next()) {
                UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                balances.put(
                        uuid,
                        new StoredBalance(
                                resultSet.getLong("balance"),
                                resultSet.getLong("revision")
                        )
                );
            }
        }
        return balances;
    }

    @Override
    public void saveBalances(Map<UUID, StoredBalance> balances) throws Exception {
        if (balances.isEmpty()) {
            return;
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO balances(uuid, balance, revision, updated_at) VALUES(?, ?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET "
                            + "balance = excluded.balance, revision = excluded.revision, "
                            + "updated_at = excluded.updated_at"
            )) {
                long updatedAt = System.currentTimeMillis();
                for (Map.Entry<UUID, StoredBalance> entry : balances.entrySet()) {
                    statement.setString(1, entry.getKey().toString());
                    statement.setLong(2, entry.getValue().amount());
                    statement.setLong(3, entry.getValue().revision());
                    statement.setLong(4, updatedAt);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.commit();
        }
    }

    @Override
    public void close() {
        // Connections are short-lived; nothing to close.
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(jdbcUrl);
    }

    private static void addRevisionColumnIfMissing(Statement statement) throws Exception {
        try {
            statement.execute(
                    "ALTER TABLE balances ADD COLUMN revision INTEGER NOT NULL DEFAULT 0"
            );
        } catch (Exception exception) {
            rethrowUnlessDuplicateColumn(exception);
        }
    }

    private static void addUpdatedAtColumnIfMissing(Statement statement) throws Exception {
        try {
            statement.execute(
                    "ALTER TABLE balances ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0"
            );
        } catch (Exception exception) {
            rethrowUnlessDuplicateColumn(exception);
        }
    }

    private static void rethrowUnlessDuplicateColumn(Exception exception) throws Exception {
        String message = exception.getMessage();
        if (message == null || !message.toLowerCase(Locale.ROOT).contains("duplicate column")) {
            throw exception;
        }
    }

    public Path getDatabasePath() {
        return databasePath;
    }
}
