package com.enthusia.enthusiacurrency.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyYamlBalanceMigrationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingAndEmptyFilesProduceNoBalances() throws Exception {
        Path missing = temporaryDirectory.resolve("missing.yml");
        Path empty = temporaryDirectory.resolve("empty.yml");
        Files.writeString(empty, "config-version: 1\n");

        assertThat(LegacyYamlBalanceMigration.loadBalances(missing.toFile(), logger())).isEmpty();
        assertThat(LegacyYamlBalanceMigration.loadBalances(empty.toFile(), logger())).isEmpty();
    }

    @Test
    void loadsValidRowsClampsNegativeValuesAndPreservesMaximumInteger() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID maximum = UUID.randomUUID();
        Path yaml = writeYaml("""
                balances:
                  %s: 125
                  %s: -50
                  %s: '%s'
                """.formatted(first, second, maximum, Long.MAX_VALUE));

        assertThat(LegacyYamlBalanceMigration.loadBalances(yaml.toFile(), logger()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(first, 125L, second, 0L, maximum, Long.MAX_VALUE));
    }

    @Test
    void malformedRowsDoNotDiscardValidPlayers() throws Exception {
        UUID valid = UUID.randomUUID();
        UUID invalidAmount = UUID.randomUUID();
        RecordingHandler warnings = new RecordingHandler();
        Logger logger = logger();
        logger.addHandler(warnings);
        Path yaml = writeYaml("""
                balances:
                  %s: 40
                  not-a-uuid: 90
                  %s: not-a-number
                """.formatted(valid, invalidAmount));

        assertThat(LegacyYamlBalanceMigration.loadBalances(yaml.toFile(), logger))
                .containsExactlyInAnyOrderEntriesOf(Map.of(valid, 40L, invalidAmount, 0L));
        assertThat(warnings.message).contains("not-a-uuid");
    }

    @Test
    void truncatesLegacyDecimalsTowardZero() throws Exception {
        UUID numeric = UUID.randomUUID();
        UUID text = UUID.randomUUID();
        Path yaml = writeYaml("""
                balances:
                  %s: 12.9
                  %s: '7.8'
                """.formatted(numeric, text));

        assertThat(LegacyYamlBalanceMigration.loadBalances(yaml.toFile(), logger()))
                .containsExactlyInAnyOrderEntriesOf(Map.of(numeric, 12L, text, 7L));
    }

    @Test
    void archivesTheLegacyFileAndIsIdempotentAfterward() throws Exception {
        Path yaml = writeYaml("balances: {}\n");
        Path archive = yaml.resolveSibling("balances.yml.migrated");
        Files.writeString(archive, "old archive");

        LegacyYamlBalanceMigration.markMigrated(yaml.toFile());

        assertThat(yaml).doesNotExist();
        assertThat(archive).hasContent("balances: {}\n");
        LegacyYamlBalanceMigration.markMigrated(yaml.toFile());
        assertThat(archive).hasContent("balances: {}\n");
    }

    private Path writeYaml(String content) throws Exception {
        Path yaml = temporaryDirectory.resolve("balances.yml");
        Files.writeString(yaml, content);
        return yaml;
    }

    private Logger logger() {
        Logger logger = Logger.getLogger(getClass().getName() + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        return logger;
    }

    private static final class RecordingHandler extends Handler {
        private String message;

        @Override public void publish(LogRecord record) { message = record.getMessage(); }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
