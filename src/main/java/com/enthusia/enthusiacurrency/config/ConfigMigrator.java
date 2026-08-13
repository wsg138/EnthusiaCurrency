package com.enthusia.enthusiacurrency.config;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class ConfigMigrator {

    public static final int CURRENT_CONFIG_VERSION = 4;
    private static final String CONFIG_VERSION_KEY = "config-version";

    private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final EnthusiaCurrencyPlugin plugin;

    public ConfigMigrator(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void migrateConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        try (InputStream defaultStream = plugin.getResource("config.yml")) {
            if (defaultStream == null) {
                plugin.getLogger().warning("Default config.yml not found in plugin jar; skipping config migration.");
                return;
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            FileConfiguration config = plugin.getConfig();
            int userVersion = config.getInt(CONFIG_VERSION_KEY, 1);
            boolean oldConfig = userVersion < CURRENT_CONFIG_VERSION;
            List<String> addedKeys = new ArrayList<>();
            List<String> removedKeys = new ArrayList<>();

            if (oldConfig) {
                backup("config.yml");
            }

            boolean changed = addMissingKeys(config, defaults, "", addedKeys);
            changed |= removeDeprecatedKeys(config, removedKeys);
            if (!config.isSet(CONFIG_VERSION_KEY) || userVersion != CURRENT_CONFIG_VERSION) {
                config.set(CONFIG_VERSION_KEY, CURRENT_CONFIG_VERSION);
                changed = true;
                addedKeys.add(CONFIG_VERSION_KEY);
            }

            if (changed) {
                plugin.saveConfig();
                plugin.reloadConfig();
            }

            if (oldConfig) {
                plugin.getLogger().info("Migrated config.yml from version " + userVersion + " to " + CURRENT_CONFIG_VERSION + ".");
            }
            for (String key : addedKeys) {
                plugin.getLogger().info("Config key added or migrated: " + key);
            }
            for (String key : removedKeys) {
                plugin.getLogger().info("Removed deprecated config key: " + key);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to migrate config.yml safely: " + ex.getMessage());
            plugin.getLogger().warning("A backup is kept if one was created before migration.");
        }
    }

    public void backupIfExists(String fileName) {
        try {
            Path path = plugin.getDataFolder().toPath().resolve(fileName);
            if (Files.exists(path)) {
                backup(fileName);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to back up " + fileName + ": " + ex.getMessage());
        }
    }

    private void backup(String fileName) throws Exception {
        Path source = plugin.getDataFolder().toPath().resolve(fileName);
        if (!Files.exists(source)) {
            return;
        }
        Path backupDir = plugin.getDataFolder().toPath().resolve("backups");
        Files.createDirectories(backupDir);
        String timestamp = BACKUP_FORMAT.format(LocalDateTime.now());
        Path target = backupDir.resolve(fileName + "." + timestamp + ".bak");
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        plugin.getLogger().info("Backed up " + fileName + " to " + target.getFileName() + ".");
    }

    private boolean addMissingKeys(ConfigurationSection target, ConfigurationSection defaults, String prefix, List<String> addedKeys) {
        boolean changed = false;

        for (String key : defaults.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection defaultChild = defaults.getConfigurationSection(key);
                ConfigurationSection targetChild = target.getConfigurationSection(key);

                if (targetChild == null) {
                    targetChild = target.createSection(key);
                    changed = true;
                    addedKeys.add(path);
                }

                if (defaultChild != null) {
                    changed |= addMissingKeys(targetChild, defaultChild, path, addedKeys);
                }
            } else if (!target.isSet(key)) {
                target.set(key, defaults.get(key));
                changed = true;
                addedKeys.add(path);
            }
        }

        return changed;
    }

    private boolean removeDeprecatedKeys(FileConfiguration config, List<String> removedKeys) {
        if (!config.isSet("skin-cache")) {
            return false;
        }
        config.set("skin-cache", null);
        removedKeys.add("skin-cache");
        return true;
    }
}
