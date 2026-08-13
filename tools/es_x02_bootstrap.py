from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise SystemExit(f"expected one match in {path}, got {text.count(old)}")
    file.write_text(text.replace(old, new), encoding="utf-8")


Path("src/main/java/com/enthusia/enthusiacurrency/storage/BalanceRepository.java").write_text('''package com.enthusia.enthusiacurrency.storage;

import java.util.Map;
import java.util.UUID;

public interface BalanceRepository extends AutoCloseable {

    record StoredBalance(long amount, long revision) {
        public StoredBalance {
            if (revision < 0L) {
                throw new IllegalArgumentException("revision cannot be negative");
            }
        }
    }

    void initialize() throws Exception;

    Map<UUID, StoredBalance> loadAllBalances() throws Exception;

    void saveBalances(Map<UUID, StoredBalance> balances) throws Exception;

    @Override
    void close() throws Exception;
}
''', encoding="utf-8")

Path("src/main/java/com/enthusia/enthusiacurrency/storage/SqliteBalanceRepository.java").write_text('''package com.enthusia.enthusiacurrency.storage;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class SqliteBalanceRepository implements BalanceRepository {

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS balances (
                uuid TEXT PRIMARY KEY,
                balance INTEGER NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0
            )
            """;

    private final Path databasePath;
    private final String jdbcUrl;

    public SqliteBalanceRepository(Path databasePath) {
        this.databasePath = databasePath;
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
            addColumnIfMissing(
                    statement,
                    "ALTER TABLE balances ADD COLUMN revision INTEGER NOT NULL DEFAULT 0"
            );
            addColumnIfMissing(
                    statement,
                    "ALTER TABLE balances ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0"
            );
        }
    }

    @Override
    public Map<UUID, StoredBalance> loadAllBalances() throws Exception {
        Map<UUID, StoredBalance> balances = new HashMap<>();
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT uuid, balance, revision FROM balances"
             )) {
            while (resultSet.next()) {
                UUID uuid = UUID.fromString(resultSet.getString("uuid"));
                balances.put(
                        uuid,
                        new StoredBalance(
                                resultSet.getLong("balance"),
                                resultSet.getLong("revision")
                        )
                );
            }
        }
        return balances;
    }

    @Override
    public void saveBalances(Map<UUID, StoredBalance> balances) throws Exception {
        if (balances.isEmpty()) {
            return;
        }

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO balances(uuid, balance, revision, updated_at) VALUES(?, ?, ?, ?) "
                            + "ON CONFLICT(uuid) DO UPDATE SET "
                            + "balance = excluded.balance, revision = excluded.revision, "
                            + "updated_at = excluded.updated_at"
            )) {
                long updatedAt = System.currentTimeMillis();
                for (Map.Entry<UUID, StoredBalance> entry : balances.entrySet()) {
                    statement.setString(1, entry.getKey().toString());
                    statement.setLong(2, entry.getValue().amount());
                    statement.setLong(3, entry.getValue().revision());
                    statement.setLong(4, updatedAt);
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

    private static void addColumnIfMissing(Statement statement, String sql) throws Exception {
        try {
            statement.execute(sql);
        } catch (Exception exception) {
            String message = exception.getMessage();
            if (message == null || !message.toLowerCase(Locale.ROOT).contains("duplicate column")) {
                throw exception;
            }
        }
    }

    public Path getDatabasePath() {
        return databasePath;
    }
}
''', encoding="utf-8")

replace_once(
    "src/main/java/com/enthusia/enthusiacurrency/storage/BalanceStorage.java",
    'public class BalanceStorage {\n\n    private record CachedBalance(long amount, long version) {\n    }',
    'public class BalanceStorage {\n\n    public record BalanceSnapshot(long amount, long revision) {\n    }\n\n    private record CachedBalance(long amount, long version) {\n    }'
)
replace_once(
    "src/main/java/com/enthusia/enthusiacurrency/storage/BalanceStorage.java",
    '''            Map<UUID, Long> loadedBalances = repository.loadAllBalances();
            if (loadedBalances.isEmpty()) {
                File yamlFile = new File(plugin.getDataFolder(), "balances.yml");
                Map<UUID, Long> migratedBalances = LegacyYamlBalanceMigration.loadBalances(yamlFile, plugin.getLogger());
                if (!migratedBalances.isEmpty()) {
                    repository.saveBalances(migratedBalances);
                    LegacyYamlBalanceMigration.markMigrated(yamlFile);
                    loadedBalances = migratedBalances;
                    plugin.getLogger().info("Migrated " + migratedBalances.size() + " balance(s) from balances.yml to SQLite.");
                }
            }

            balances.clear();
            for (Map.Entry<UUID, Long> entry : loadedBalances.entrySet()) {
                balances.put(entry.getKey(), new CachedBalance(Math.max(0L, entry.getValue()), 0L));
            }''',
    '''            Map<UUID, BalanceRepository.StoredBalance> loadedBalances = repository.loadAllBalances();
            if (loadedBalances.isEmpty()) {
                File yamlFile = new File(plugin.getDataFolder(), "balances.yml");
                Map<UUID, Long> migratedBalances = LegacyYamlBalanceMigration.loadBalances(yamlFile, plugin.getLogger());
                if (!migratedBalances.isEmpty()) {
                    Map<UUID, BalanceRepository.StoredBalance> migrated = new ConcurrentHashMap<>();
                    for (Map.Entry<UUID, Long> entry : migratedBalances.entrySet()) {
                        migrated.put(
                                entry.getKey(),
                                new BalanceRepository.StoredBalance(Math.max(0L, entry.getValue()), 0L)
                        );
                    }
                    repository.saveBalances(migrated);
                    LegacyYamlBalanceMigration.markMigrated(yamlFile);
                    loadedBalances = migrated;
                    plugin.getLogger().info("Migrated " + migratedBalances.size() + " balance(s) from balances.yml to SQLite.");
                }
            }

            balances.clear();
            for (Map.Entry<UUID, BalanceRepository.StoredBalance> entry : loadedBalances.entrySet()) {
                BalanceRepository.StoredBalance stored = entry.getValue();
                balances.put(
                        entry.getKey(),
                        new CachedBalance(Math.max(0L, stored.amount()), stored.revision())
                );
            }'''
)
replace_once(
    "src/main/java/com/enthusia/enthusiacurrency/storage/BalanceStorage.java",
    '''    public long getBalance(UUID uuid) {
        return balances.computeIfAbsent(uuid, ignored -> new CachedBalance(startingBalance, 0L)).amount();
    }
''',
    '''    public long getBalance(UUID uuid) {
        return balances.computeIfAbsent(uuid, ignored -> new CachedBalance(startingBalance, 0L)).amount();
    }

    public BalanceSnapshot getBalanceSnapshot(UUID uuid) {
        CachedBalance current = balances.computeIfAbsent(
                uuid,
                ignored -> new CachedBalance(startingBalance, 0L)
        );
        return new BalanceSnapshot(current.amount(), current.version());
    }
'''
)
replace_once(
    "src/main/java/com/enthusia/enthusiacurrency/storage/BalanceStorage.java",
    '''    public long deposit(UUID uuid, long amount) {''',
    '''    public boolean replaceIfCurrent(
            UUID uuid,
            long expectedAmount,
            long expectedRevision,
            long replacementAmount,
            boolean forceRevisionBump
    ) {
        if (expectedAmount < 0L || expectedRevision < 0L || replacementAmount < 0L) {
            throw new IllegalArgumentException("balance CAS values cannot be negative");
        }
        boolean[] success = {false};
        boolean[] changed = {false};
        balances.compute(uuid, (ignored, current) -> {
            CachedBalance base = current == null
                    ? new CachedBalance(startingBalance, 0L)
                    : current;
            if (base.amount() != expectedAmount || base.version() != expectedRevision) {
                return base;
            }
            success[0] = true;
            if (base.amount() == replacementAmount && !forceRevisionBump) {
                return base;
            }
            changed[0] = true;
            return new CachedBalance(replacementAmount, Math.addExact(base.version(), 1L));
        });
        if (changed[0]) {
            markDirty(uuid);
        }
        return success[0];
    }

    public long deposit(UUID uuid, long amount) {'''
)
replace_once(
    "src/main/java/com/enthusia/enthusiacurrency/storage/BalanceStorage.java",
    '''    private Map<UUID, Long> balanceValues(Map<UUID, CachedBalance> snapshot) {
        Map<UUID, Long> toSave = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, CachedBalance> entry : snapshot.entrySet()) {
            toSave.put(entry.getKey(), entry.getValue().amount());
        }
        return toSave;
    }''',
    '''    private Map<UUID, BalanceRepository.StoredBalance> balanceValues(
            Map<UUID, CachedBalance> snapshot
    ) {
        Map<UUID, BalanceRepository.StoredBalance> toSave = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, CachedBalance> entry : snapshot.entrySet()) {
            CachedBalance value = entry.getValue();
            toSave.put(
                    entry.getKey(),
                    new BalanceRepository.StoredBalance(value.amount(), value.version())
            );
        }
        return toSave;
    }'''
)

plugin_path = "src/main/java/com/enthusia/enthusiacurrency/EnthusiaCurrencyPlugin.java"
replace_once(
    plugin_path,
    'import com.enthusia.enthusiacurrency.command.*;\n',
    '''import com.enthusia.enthusiacurrency.command.*;
import com.enthusia.enthusiacurrency.api.moderation.CurrencyModerationApi;
import com.enthusia.enthusiacurrency.moderation.CurrencyModerationService;
import com.enthusia.enthusiacurrency.moderation.CurrencyMovementLockListener;
import com.enthusia.enthusiacurrency.moderation.MovementLockRegistry;
'''
)
replace_once(
    plugin_path,
    '''    private static EnthusiaCurrencyPlugin instance;

    private BalanceStorage balanceStorage;''',
    '''    private static EnthusiaCurrencyPlugin instance;

    private final MovementLockRegistry moderationLocks = new MovementLockRegistry();
    private CurrencyModerationService moderationService;
    private BalanceStorage balanceStorage;'''
)
replace_once(
    plugin_path,
    '''        this.currencyService = new CurrencyService(this, balanceStorage, currencyManager);
        startRuntimeServices();''',
    '''        this.currencyService = new CurrencyService(this, balanceStorage, currencyManager);
        setupModerationService();
        startRuntimeServices();'''
)
replace_once(
    plugin_path,
    '''    public void onDisable() {
        teardownPlaceholderAPI();
        stopRuntimeServices();''',
    '''    public void onDisable() {
        teardownPlaceholderAPI();
        teardownModerationService();
        stopRuntimeServices();'''
)
replace_once(
    plugin_path,
    '''    private void setupVault() {''',
    '''    private void setupModerationService() {
        this.moderationService = new CurrencyModerationService(
                this,
                balanceStorage,
                currencyManager,
                moderationLocks
        );
        Bukkit.getServicesManager().register(
                CurrencyModerationApi.class,
                moderationService,
                this,
                ServicePriority.Normal
        );
        Bukkit.getPluginManager().registerEvents(
                new CurrencyMovementLockListener(moderationLocks),
                this
        );
        getLogger().info(
                "Registered EnthusiaCurrency moderation API v" + CurrencyModerationApi.API_VERSION + "."
        );
    }

    private void teardownModerationService() {
        if (moderationService == null) {
            return;
        }
        Bukkit.getServicesManager().unregister(CurrencyModerationApi.class, moderationService);
        moderationService.close();
        moderationService = null;
    }

    private void setupVault() {'''
)

pom = Path("pom.xml").read_text(encoding="utf-8")
needle = '''        <dependency>
            <groupId>com.github.plan-player-analytics</groupId>
            <artifactId>Plan</artifactId>
            <version>5.6.2965</version>
            <scope>provided</scope>
        </dependency>
'''
if pom.count(needle) != 1:
    raise SystemExit("Plan dependency anchor changed")
pom = pom.replace(needle, needle + '''
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.13.4</version>
            <scope>test</scope>
        </dependency>
''')
shade = '''            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
'''
if pom.count(shade) != 1:
    raise SystemExit("shade plugin anchor changed")
pom = pom.replace(shade, '''            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.3</version>
            </plugin>

''' + shade)
Path("pom.xml").write_text(pom, encoding="utf-8")

Path("src/test/java/com/enthusia/enthusiacurrency/storage/SqliteBalanceRepositoryTest.java").parent.mkdir(parents=True, exist_ok=True)
Path("src/test/java/com/enthusia/enthusiacurrency/storage/SqliteBalanceRepositoryTest.java").write_text('''package com.enthusia.enthusiacurrency.storage;

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
''', encoding="utf-8")

Path("README.md").write_text('''# EnthusiaCurrency

Vault-backed token economy plugin with physical deposits, withdrawals, payments, balance leaderboards, and the supported EnthusiaStaff destructive-currency provider.

## Build and verification

```powershell
mvn -B -ntp verify
```

## EnthusiaStaff moderation API

EnthusiaCurrency publishes `CurrencyModerationApi` version `1` through Bukkit's `ServicesManager`. The API has no player-facing command and does not grant moderation authority: EnthusiaStaff performs permission, case, and audit authorization before invoking it.

A destructive operation acquires an expiring operation-owned movement lease, snapshots bank/inventory/Ender Chest state, creates an exact source-ordered plan, and applies the plan only when the before checksum and persistent bank revision still match. Repeated apply calls are idempotent when the replacement state is already present. Concurrent or stale state is rejected instead of overwritten.

Restoration requires the same operation-owned lease and the checksum of the state being replaced. It restores the exact serialized item state and bank balance while advancing the persistent bank revision so old snapshots cannot become current again. Bank mutations complete successfully only after the SQLite writer flushes; ambiguous persistence failures return a quarantine-required result.

The provider fails closed when the player is offline, the lease is missing, the plan is stale/invalid, or exact denomination removal is impossible. EnthusiaStaff separately fails closed when this service is absent or its API version is incompatible.

Representative live destructive balances and production rows are deliberately outside this repository validation; the EnthusiaStaff package defers that acceptance to `ES-V03`.
''', encoding="utf-8")
