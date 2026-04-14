package com.enthusia.enthusiacurrency.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class LegacyYamlBalanceMigration {

    private LegacyYamlBalanceMigration() {
    }

    public static Map<UUID, Long> loadBalances(File yamlFile, Logger logger) {
        Map<UUID, Long> balances = new HashMap<>();
        if (!yamlFile.exists()) {
            return balances;
        }

        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(yamlFile);
        ConfigurationSection section = configuration.getConfigurationSection("balances");
        if (section == null) {
            return balances;
        }

        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long amount = toLong(configuration.get("balances." + key));
                balances.put(uuid, Math.max(0L, amount));
            } catch (IllegalArgumentException ignored) {
                logger.warning("Skipping invalid balance entry for key: " + key);
            }
        }

        return balances;
    }

    public static void markMigrated(File yamlFile) throws Exception {
        if (!yamlFile.exists()) {
            return;
        }
        File migratedFile = new File(yamlFile.getParentFile(), yamlFile.getName() + ".migrated");
        Files.move(yamlFile.toPath(), migratedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(0, RoundingMode.DOWN).longValue();
        }
        try {
            return new BigDecimal(String.valueOf(value)).setScale(0, RoundingMode.DOWN).longValue();
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
