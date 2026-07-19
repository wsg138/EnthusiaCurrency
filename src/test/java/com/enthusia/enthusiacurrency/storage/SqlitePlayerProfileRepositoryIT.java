package com.enthusia.enthusiacurrency.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlitePlayerProfileRepositoryIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesMultipleProfilesAcrossRestartIncludingNullableDisplayNames() throws Exception {
        Path database = temporaryDirectory.resolve("profiles.db");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        PlayerProfile firstProfile = new PlayerProfile(first, "FirstPlayer", "First Display", 10L, 20L, 20L);
        PlayerProfile secondProfile = new PlayerProfile(second, "SecondPlayer", null, 30L, 40L, 40L);

        SqlitePlayerProfileRepository repository = new SqlitePlayerProfileRepository(database);
        repository.initialize();
        repository.saveProfiles(Map.of(first, firstProfile, second, secondProfile));

        SqlitePlayerProfileRepository restarted = new SqlitePlayerProfileRepository(database);
        restarted.initialize();
        assertThat(restarted.loadAllProfiles())
                .containsExactlyInAnyOrderEntriesOf(Map.of(first, firstProfile, second, secondProfile));
    }

    @Test
    void updatesNamesWhilePreservingEarliestAndLatestSeenTimes() throws Exception {
        Path database = temporaryDirectory.resolve("profiles.db");
        UUID player = UUID.randomUUID();
        SqlitePlayerProfileRepository repository = new SqlitePlayerProfileRepository(database);
        repository.initialize();
        repository.saveProfiles(Map.of(player,
                new PlayerProfile(player, "OldName", "Old Display", 100L, 200L, 200L)));

        repository.saveProfiles(Map.of(player,
                new PlayerProfile(player, "NewName", "New Display", 150L, 300L, 310L)));

        assertThat(repository.loadAllProfiles()).containsEntry(player,
                new PlayerProfile(player, "NewName", "New Display", 100L, 300L, 310L));
    }

    @Test
    void emptySaveIsANoOpAndDatabaseConstraintsRejectMissingUsername() throws Exception {
        Path database = temporaryDirectory.resolve("profiles.db");
        SqlitePlayerProfileRepository repository = new SqlitePlayerProfileRepository(database);
        repository.initialize();
        repository.saveProfiles(Map.of());
        assertThat(repository.loadAllProfiles()).isEmpty();

        String jdbcUrl = "jdbc:sqlite:" + database.toAbsolutePath();
        assertThatThrownBy(() -> {
            try (var connection = DriverManager.getConnection(jdbcUrl);
                 var statement = connection.prepareStatement("""
                         INSERT INTO player_profiles(uuid, username, first_seen_at, last_seen_at, updated_at)
                         VALUES (?, NULL, 1, 1, 1)
                         """)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.executeUpdate();
            }
        }).isInstanceOf(SQLException.class);
        assertThat(repository.loadAllProfiles()).isEmpty();
    }
}
