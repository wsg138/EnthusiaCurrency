package com.enthusia.enthusiacurrency.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteBalanceRepositoryIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesBalancesAcrossRestartAndMigratesTheLegacySchema() throws Exception {
        Path database = temporaryDirectory.resolve("balances.db");
        UUID player = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE balances (uuid TEXT PRIMARY KEY, balance INTEGER NOT NULL)");
            statement.execute("INSERT INTO balances(uuid, balance) VALUES ('" + player + "', 500)");
        }

        SqliteBalanceRepository firstStart = new SqliteBalanceRepository(database);
        firstStart.initialize();
        assertThat(firstStart.loadAllBalances()).containsEntry(player, 500L);
        firstStart.saveBalances(Map.of(player, 725L));

        SqliteBalanceRepository restarted = new SqliteBalanceRepository(database);
        restarted.initialize();

        assertThat(restarted.loadAllBalances()).containsExactly(Map.entry(player, 725L));
    }

    @Test
    void importsLegacyBalancesOnceAndPreservesThemAcrossRestart() throws Exception {
        Path database = temporaryDirectory.resolve("balances.db");
        Path yaml = temporaryDirectory.resolve("balances.yml");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        java.nio.file.Files.writeString(yaml, """
                balances:
                  %s: 100
                  %s: 250
                """.formatted(first, second));

        SqliteBalanceRepository repository = new SqliteBalanceRepository(database);
        repository.initialize();
        Map<UUID, Long> migrated = LegacyYamlBalanceMigration.loadBalances(
                yaml.toFile(), java.util.logging.Logger.getAnonymousLogger());
        repository.saveBalances(migrated);
        LegacyYamlBalanceMigration.markMigrated(yaml.toFile());

        SqliteBalanceRepository restarted = new SqliteBalanceRepository(database);
        restarted.initialize();
        assertThat(restarted.loadAllBalances()).containsExactlyInAnyOrderEntriesOf(migrated);
        assertThat(yaml).doesNotExist();
        assertThat(yaml.resolveSibling("balances.yml.migrated")).exists();

        LegacyYamlBalanceMigration.markMigrated(yaml.toFile());
        assertThat(restarted.loadAllBalances()).containsExactlyInAnyOrderEntriesOf(migrated);
    }
}
