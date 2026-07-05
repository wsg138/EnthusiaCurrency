package com.enthusia.enthusiacurrency.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SqlitePlayerProfileRepository implements PlayerProfileRepository {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS player_profiles (
                uuid TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                display_name TEXT,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """;

    private final String jdbcUrl;

    public SqlitePlayerProfileRepository(Path databasePath) {
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
        }
    }

    @Override
    public Map<UUID, PlayerProfile> loadAllProfiles() throws Exception {
        Map<UUID, PlayerProfile> profiles = new ConcurrentHashMap<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT uuid, username, display_name, first_seen_at, last_seen_at, updated_at
                     FROM player_profiles
                     """)) {
            while (resultSet.next()) {
                UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                profiles.put(uuid, new PlayerProfile(
                        uuid,
                        resultSet.getString("username"),
                        resultSet.getString("display_name"),
                        resultSet.getLong("first_seen_at"),
                        resultSet.getLong("last_seen_at"),
                        resultSet.getLong("updated_at")
                ));
            }
        }
        return profiles;
    }

    @Override
    public void saveProfiles(Map<UUID, PlayerProfile> profiles) throws Exception {
        if (profiles.isEmpty()) {
            return;
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO player_profiles(uuid, username, display_name, first_seen_at, last_seen_at, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        username = excluded.username,
                        display_name = excluded.display_name,
                        first_seen_at = MIN(player_profiles.first_seen_at, excluded.first_seen_at),
                        last_seen_at = MAX(player_profiles.last_seen_at, excluded.last_seen_at),
                        updated_at = excluded.updated_at
                    """)) {
                for (PlayerProfile profile : profiles.values()) {
                    statement.setString(1, profile.uuid().toString());
                    statement.setString(2, profile.username());
                    statement.setString(3, profile.displayName());
                    statement.setLong(4, profile.firstSeenAt());
                    statement.setLong(5, profile.lastSeenAt());
                    statement.setLong(6, profile.updatedAt());
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
}
