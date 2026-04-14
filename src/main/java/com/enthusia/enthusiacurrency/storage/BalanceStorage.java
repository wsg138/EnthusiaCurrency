package com.enthusia.enthusiacurrency.storage;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BalanceStorage {

    private final EnthusiaCurrencyPlugin plugin;
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyKeys = ConcurrentHashMap.newKeySet();
    private final Object saveLock = new Object();
    private HikariDataSource dataSource;
    private int autoSaveTaskId = -1;
    private int saveCount = 0;
    private static final int BACKUP_EVERY_N_SAVES = 5;
    private static final int MAX_BACKUPS = 5;

    public BalanceStorage(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        initializeDatabase();
        loadFromDatabase();
        migrateFromYamlIfNeeded();
        startAutoSave();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (dataSource != null && !dataSource.isClosed()) {
                try {
                    save();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "EnthusiaCurrency-ShutdownHook"));
    }

    public void save() {
        synchronized (saveLock) {
            if (dataSource == null || dataSource.isClosed()) return;

            Set<UUID> toFlush = new HashSet<>(dirtyKeys);
            dirtyKeys.removeAll(toFlush);
            if (toFlush.isEmpty()) return;

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement stmt = conn.prepareStatement(
                        "INSERT OR REPLACE INTO balances (uuid, balance) VALUES (?, ?)")) {
                    for (UUID uuid : toFlush) {
                        Long bal = balances.get(uuid);
                        if (bal == null) continue;
                        stmt.setString(1, uuid.toString());
                        stmt.setLong(2, bal);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
                conn.commit();

                saveCount++;
                if (saveCount % BACKUP_EVERY_N_SAVES == 0) {
                    backupDatabase();
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to save balances to database: " + e.getMessage());
                e.printStackTrace();
                dirtyKeys.addAll(toFlush);
            }
        }
    }

    /**
     * Write-through for a single balance. Called after critical operations
     * to minimize crash data-loss window.
     */
    public void saveSingle(UUID uuid) {
        Long bal = balances.get(uuid);
        if (bal == null || dataSource == null || dataSource.isClosed()) return;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT OR REPLACE INTO balances (uuid, balance) VALUES (?, ?)")) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, bal);
            stmt.executeUpdate();
            dirtyKeys.remove(uuid);
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to write-through balance for " + uuid + ": " + e.getMessage());
        }
    }

    public void close() {
        if (autoSaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autoSaveTaskId);
            autoSaveTaskId = -1;
        }
        try {
            save();
        } catch (Exception e) {
            plugin.getLogger().severe("Error during final save: " + e.getMessage());
            emergencyDump();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Balance database connection pool closed.");
        }
    }

    public long getBalance(UUID uuid) {
        return balances.computeIfAbsent(uuid,
                k -> plugin.getConfig().getLong("economy.starting-balance", 0L));
    }

    public void setBalance(UUID uuid, long amount) {
        if (amount < 0) amount = 0;
        balances.put(uuid, amount);
        dirtyKeys.add(uuid);
    }

    public void deposit(UUID uuid, long amount) {
        if (amount <= 0) return;
        long startingBalance = plugin.getConfig().getLong("economy.starting-balance", 0L);
        balances.compute(uuid, (k, current) -> {
            long base = (current != null) ? current : startingBalance;
            long result = base + amount;
            if (result < base) return base; // overflow protection
            return result;
        });
        dirtyKeys.add(uuid);
    }

    public boolean withdraw(UUID uuid, long amount) {
        if (amount <= 0) return false;
        long startingBalance = plugin.getConfig().getLong("economy.starting-balance", 0L);
        boolean[] success = {false};
        balances.compute(uuid, (k, current) -> {
            long base = (current != null) ? current : startingBalance;
            if (base < amount) {
                success[0] = false;
                return current;
            }
            success[0] = true;
            return base - amount;
        });
        if (success[0]) dirtyKeys.add(uuid);
        return success[0];
    }

    public Map<UUID, Long> getAllBalancesSnapshot() {
        return new HashMap<>(balances);
    }

    // ── Private ──────────────────────────────────────────────────────

    private void initializeDatabase() {
        try {
            File dataFolder = plugin.getDataFolder();
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            File dbFile = new File(dataFolder, "balances.db");
            String dbPath = dbFile.getAbsolutePath();

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + dbPath);
            config.setMaximumPoolSize(1);
            config.setConnectionTestQuery("SELECT 1");
            config.setPoolName("EnthusiaCurrency-DB");
            config.setConnectionInitSql(
                    "PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA synchronous=NORMAL;");

            dataSource = new HikariDataSource(config);

            try (Connection conn = dataSource.getConnection()) {
                // Integrity check
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
                    if (rs.next() && !"ok".equalsIgnoreCase(rs.getString(1))) {
                        String result = rs.getString(1);
                        plugin.getLogger().severe("Database integrity check failed: " + result);
                        dataSource.close();

                        File backup = new File(dataFolder,
                                "balances.db.corrupt." + System.currentTimeMillis());
                        if (dbFile.renameTo(backup)) {
                            plugin.getLogger().warning("Corrupt DB backed up to " + backup.getName());
                        }

                        // Recreate
                        config = new HikariConfig();
                        config.setJdbcUrl("jdbc:sqlite:" + dbPath);
                        config.setMaximumPoolSize(1);
                        config.setConnectionTestQuery("SELECT 1");
                        config.setPoolName("EnthusiaCurrency-DB");
                        config.setConnectionInitSql(
                                "PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA synchronous=NORMAL;");
                        dataSource = new HikariDataSource(config);
                    }
                }

                // Create table
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS balances (
                            uuid VARCHAR(36) PRIMARY KEY,
                            balance INTEGER NOT NULL DEFAULT 0
                        )
                    """);
                }
            }

            plugin.getLogger().info("Balance database initialized (WAL mode): " + dbPath);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize balance database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadFromDatabase() {
        balances.clear();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT uuid, balance FROM balances")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                // Support loading from legacy REAL columns via Math.round
                long balance = Math.round(rs.getDouble("balance"));
                balances.put(uuid, balance);
            }
            plugin.getLogger().info("Loaded " + balances.size() + " player balance(s) from database.");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to load balances from database: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * One-time migration: if balances.yml exists and the database is empty,
     * import all YAML balances into SQLite in a single transaction, then rename the file.
     */
    private void migrateFromYamlIfNeeded() {
        File yamlFile = new File(plugin.getDataFolder(), "balances.yml");
        if (!yamlFile.exists()) return;
        if (!balances.isEmpty()) {
            plugin.getLogger().info("balances.yml found but database already has data. Skipping migration. " +
                    "Delete balances.yml manually if migration is complete.");
            return;
        }

        plugin.getLogger().info("Migrating balances from balances.yml to SQLite...");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(yamlFile);

        if (!config.isConfigurationSection("balances")) {
            plugin.getLogger().info("No balances section in YAML — nothing to migrate.");
            return;
        }

        Map<UUID, Long> toMigrate = new HashMap<>();
        for (String key : config.getConfigurationSection("balances").getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                long amount = Math.round(config.getDouble("balances." + key, 0.0));
                toMigrate.put(uuid, amount);
            } catch (IllegalArgumentException ignored) {}
        }

        if (toMigrate.isEmpty()) {
            plugin.getLogger().info("No valid entries to migrate.");
            return;
        }

        // Write directly to DB in single transaction
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT OR REPLACE INTO balances (uuid, balance) VALUES (?, ?)")) {
                for (Map.Entry<UUID, Long> entry : toMigrate.entrySet()) {
                    stmt.setString(1, entry.getKey().toString());
                    stmt.setLong(2, entry.getValue());
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            plugin.getLogger().severe("Migration FAILED — YAML file is untouched. Error: " + e.getMessage());
            e.printStackTrace();
            return;
        }

        // DB commit succeeded — load into memory and rename
        balances.putAll(toMigrate);

        File backup = new File(plugin.getDataFolder(), "balances.yml.migrated");
        if (yamlFile.renameTo(backup)) {
            plugin.getLogger().info("Migrated " + toMigrate.size() + " balance(s) to SQLite. " +
                    "Old file renamed to balances.yml.migrated");
        } else {
            plugin.getLogger().warning("Migrated " + toMigrate.size() + " balance(s) but could not rename balances.yml. " +
                    "Delete it manually to prevent re-migration attempts.");
        }
    }

    private void startAutoSave() {
        long intervalSeconds = plugin.getConfig().getLong("economy.auto-save-interval", 60L);
        long intervalTicks = intervalSeconds * 20L;
        autoSaveTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::save,
                intervalTicks, intervalTicks).getTaskId();
        plugin.getLogger().info("Auto-save scheduled every " + intervalSeconds + " seconds.");
    }

    private void backupDatabase() {
        File dbFile = new File(plugin.getDataFolder(), "balances.db");
        if (!dbFile.exists()) return;

        File backupDir = new File(plugin.getDataFolder(), "backups");
        if (!backupDir.exists()) backupDir.mkdirs();

        File backupFile = new File(backupDir, "balances-" + System.currentTimeMillis() + ".db");
        try {
            Files.copy(dbFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Database backup created: " + backupFile.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to create database backup: " + e.getMessage());
            return;
        }

        // Prune old backups, keep last MAX_BACKUPS
        File[] backups = backupDir.listFiles((dir, name) -> name.startsWith("balances-") && name.endsWith(".db"));
        if (backups != null && backups.length > MAX_BACKUPS) {
            java.util.Arrays.sort(backups, java.util.Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < backups.length - MAX_BACKUPS; i++) {
                backups[i].delete();
            }
        }
    }

    private void emergencyDump() {
        File dumpFile = new File(plugin.getDataFolder(),
                "EMERGENCY-balances-" + System.currentTimeMillis() + ".json");
        try (PrintWriter pw = new PrintWriter(dumpFile)) {
            pw.println("{");
            boolean first = true;
            for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
                if (!first) pw.println(",");
                pw.printf("  \"%s\": %d", entry.getKey(), entry.getValue());
                first = false;
            }
            pw.println("\n}");
            plugin.getLogger().severe("Emergency balance dump written to: " + dumpFile.getName());
        } catch (Exception e) {
            plugin.getLogger().severe("EMERGENCY DUMP ALSO FAILED: " + e.getMessage());
        }
    }
}
