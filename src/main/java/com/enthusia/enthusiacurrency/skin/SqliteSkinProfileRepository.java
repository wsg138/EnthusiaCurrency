package com.enthusia.enthusiacurrency.skin;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class SqliteSkinProfileRepository implements SkinProfileRepository {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS player_skin_profiles (
                uuid TEXT PRIMARY KEY,
                texture_value TEXT NOT NULL,
                texture_signature TEXT,
                updated_at INTEGER NOT NULL
            )
            """;

    private final String jdbcUrl;

    SqliteSkinProfileRepository(Path databasePath) {
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
    public Map<UUID, SkinProfile> loadAll() throws Exception {
        Map<UUID, SkinProfile> profiles = new ConcurrentHashMap<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT uuid, texture_value, texture_signature, updated_at
                     FROM player_skin_profiles
                     """)) {
            while (resultSet.next()) {
                UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                profiles.put(uuid, new SkinProfile(
                        uuid,
                        resultSet.getString("texture_value"),
                        resultSet.getString("texture_signature"),
                        resultSet.getLong("updated_at")
                ));
            }
        }
        return profiles;
    }

    @Override
    public void saveAll(Map<UUID, SkinProfile> profiles) throws Exception {
        if (profiles.isEmpty()) {
            return;
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO player_skin_profiles(uuid, texture_value, texture_signature, updated_at)
                    VALUES(?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        texture_value = excluded.texture_value,
                        texture_signature = excluded.texture_signature,
                        updated_at = excluded.updated_at
                    """)) {
                for (SkinProfile profile : profiles.values()) {
                    statement.setString(1, profile.uuid().toString());
                    statement.setString(2, profile.textureValue());
                    statement.setString(3, profile.textureSignature());
                    statement.setLong(4, profile.updatedAt());
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
