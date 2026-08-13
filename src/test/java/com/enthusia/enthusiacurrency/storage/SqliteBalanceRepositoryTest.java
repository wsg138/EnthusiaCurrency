package com.enthusia.enthusiacurrency.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteBalanceRepositoryTest {

    @TempDir
    Path tempDir;

    @Test
    void upgradesLegacyTableAndPersistsRevisionAcrossRestart() throws Exception {
        Path database = tempDir.resolve("balances.db");
        UUID playerId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE balances (uuid TEXT PRIMARY KEY, balance INTEGER NOT NULL)");
            statement.execute("INSERT INTO balances(uuid, balance) VALUES('" + playerId + "', 42)");
        }

        SqliteBalanceRepository repository = new SqliteBalanceRepository(database);
        repository.initialize();
        assertEquals(
                new BalanceRepository.StoredBalance(42L, 0L),
                repository.loadAllBalances().get(playerId)
        );
        repository.saveBalances(Map.of(
                playerId,
                new BalanceRepository.StoredBalance(17L, 9L)
        ));
        repository.close();

        SqliteBalanceRepository reopened = new SqliteBalanceRepository(database);
        reopened.initialize();
        assertEquals(
                new BalanceRepository.StoredBalance(17L, 9L),
                reopened.loadAllBalances().get(playerId)
        );
        reopened.close();
    }
}
