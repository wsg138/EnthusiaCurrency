package com.enthusia.enthusiacurrency.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteBalanceRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndReplacesBalancesWithoutLosingOtherAccounts() throws Exception {
        Path database = temporaryDirectory.resolve("balances.db");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        SqliteBalanceRepository repository = new SqliteBalanceRepository(database);
        repository.initialize();
        repository.saveBalances(Map.of(first, 120L, second, 25L));
        repository.saveBalances(Map.of(first, 75L));

        assertThat(repository.loadAllBalances()).containsExactlyInAnyOrderEntriesOf(Map.of(first, 75L, second, 25L));
    }

    @Test
    void ignoresEmptySavesWithoutCreatingRows() throws Exception {
        SqliteBalanceRepository repository = new SqliteBalanceRepository(temporaryDirectory.resolve("balances.db"));
        repository.initialize();

        repository.saveBalances(Map.of());

        assertThat(repository.loadAllBalances()).isEmpty();
    }
}
