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
}
