package com.enthusia.enthusiacurrency.skin;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("PMD.DoNotUseThreads")
public final class SkinCache {

    private record CachedSkin(SkinProfile profile, long version) {
    }

    private static final String TEXTURES_PROPERTY = "textures";

    private final EnthusiaCurrencyPlugin plugin;
    private final Map<UUID, CachedSkin> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyKeys = ConcurrentHashMap.newKeySet();
    private final Object flushLock = new Object();
    private final ArrayList<CompletableFuture<Void>> pendingFlushFutures = new ArrayList<>();
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(new SkinWriterThreadFactory());

    private SkinProfileRepository repository;
    private volatile boolean flushQueued;
    private volatile boolean closed;

    public SkinCache(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            repository = new SqliteSkinProfileRepository(plugin.getDataFolder().toPath().resolve("balances.db"));
            repository.initialize();

            cache.clear();
            for (Map.Entry<UUID, SkinProfile> entry : repository.loadAll().entrySet()) {
                cache.put(entry.getKey(), new CachedSkin(entry.getValue(), 0L));
            }

            if (cache.isEmpty()) {
                migrateLegacyYaml();
            }
            plugin.getLogger().info("Loaded " + cache.size() + " cached player skin profile(s).");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize player skin storage", ex);
        }
    }

    public void cacheFromOnline(Player player) {
        cacheProfile(player.getUniqueId(), player.getPlayerProfile());
    }

    public ItemStack createHead(UUID uuid, String displayName) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        CachedSkin cachedSkin = cache.get(uuid);
        if (cachedSkin == null) {
            plugin.getDebugMetrics().skinCacheMiss();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
        } else {
            plugin.getDebugMetrics().skinCacheHit();
            PlayerProfile profile = Bukkit.getServer().createProfile(uuid);
            String signature = cachedSkin.profile().textureSignature();
            ProfileProperty textures = signature == null
                    ? new ProfileProperty(TEXTURES_PROPERTY, cachedSkin.profile().textureValue())
                    : new ProfileProperty(TEXTURES_PROPERTY, cachedSkin.profile().textureValue(), signature);
            profile.setProperty(textures);
            meta.setPlayerProfile(profile);
        }

        if (displayName != null) {
            meta.setDisplayName(displayName);
        }
        head.setItemMeta(meta);
        return head;
    }

    public void close() {
        if (closed) {
            return;
        }

        flushBlocking();
        closed = true;
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Player skin writer did not stop cleanly within 10 seconds.");
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            writerExecutor.shutdownNow();
        }

        if (repository != null) {
            try {
                repository.close();
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to close player skin repository cleanly: " + ex.getMessage());
            }
        }
    }

    private void migrateLegacyYaml() throws Exception {
        File yamlFile = new File(plugin.getDataFolder(), "skins.yml");
        if (!yamlFile.exists()) {
            return;
        }

        Map<UUID, SkinProfile> migrated = new ConcurrentHashMap<>();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(yamlFile);
        for (String key : config.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ItemStack head = config.getItemStack(key);
                if (head == null || head.getType() != Material.PLAYER_HEAD || !(head.getItemMeta() instanceof SkullMeta meta)) {
                    continue;
                }
                SkinProfile profile = skinProfile(uuid, meta.getPlayerProfile());
                if (profile != null) {
                    migrated.put(uuid, profile);
                }
            } catch (IllegalArgumentException ignored) {
                // Ignore malformed legacy keys and continue migrating usable skins.
            }
        }

        if (migrated.isEmpty()) {
            plugin.getLogger().warning("No usable player skin profiles were found in skins.yml; leaving it unchanged for recovery.");
            return;
        }

        repository.saveAll(migrated);
        for (Map.Entry<UUID, SkinProfile> entry : migrated.entrySet()) {
            cache.put(entry.getKey(), new CachedSkin(entry.getValue(), 0L));
        }

        File migratedFile = new File(yamlFile.getParentFile(), yamlFile.getName() + ".migrated");
        Files.move(yamlFile.toPath(), migratedFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        plugin.getLogger().info("Migrated " + migrated.size() + " player skin profile(s) from skins.yml to SQLite.");
    }

    private void cacheProfile(UUID uuid, PlayerProfile playerProfile) {
        SkinProfile profile = skinProfile(uuid, playerProfile);
        if (profile == null || closed) {
            return;
        }

        CachedSkin previous = cache.get(uuid);
        if (previous != null
                && previous.profile().textureValue().equals(profile.textureValue())
                && sameSignature(previous.profile().textureSignature(), profile.textureSignature())) {
            return;
        }

        long version = previous == null ? 1L : previous.version() + 1L;
        cache.put(uuid, new CachedSkin(profile, version));
        dirtyKeys.add(uuid);
        flushAsync();
    }

    private SkinProfile skinProfile(UUID uuid, PlayerProfile playerProfile) {
        if (playerProfile == null) {
            return null;
        }
        for (ProfileProperty property : playerProfile.getProperties()) {
            if (TEXTURES_PROPERTY.equals(property.getName()) && !property.getValue().isBlank()) {
                return new SkinProfile(uuid, property.getValue(), property.getSignature(), Instant.now().toEpochMilli());
            }
        }
        return null;
    }

    private void flushAsync() {
        synchronized (flushLock) {
            if (!flushQueued && !closed) {
                flushQueued = true;
                writerExecutor.execute(this::runFlushLoop);
            }
        }
    }

    private void runFlushLoop() {
        Throwable failure = null;
        try {
            while (true) {
                Map<UUID, CachedSkin> snapshot = snapshotDirtyProfiles();
                if (snapshot.isEmpty()) {
                    break;
                }
                repository.saveAll(profileValues(snapshot));
                clearCleanDirtyKeys(snapshot);
            }
        } catch (Exception ex) {
            failure = ex;
            plugin.getLogger().severe("Failed to flush player skin profiles: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            completePendingFlushes(failure);
        }
    }

    private Map<UUID, CachedSkin> snapshotDirtyProfiles() {
        Map<UUID, CachedSkin> snapshot = new ConcurrentHashMap<>();
        for (UUID uuid : dirtyKeys) {
            CachedSkin cachedSkin = cache.get(uuid);
            if (cachedSkin != null) {
                snapshot.put(uuid, cachedSkin);
            }
        }
        return snapshot;
    }

    private Map<UUID, SkinProfile> profileValues(Map<UUID, CachedSkin> snapshot) {
        Map<UUID, SkinProfile> profiles = new ConcurrentHashMap<>();
        for (Map.Entry<UUID, CachedSkin> entry : snapshot.entrySet()) {
            profiles.put(entry.getKey(), entry.getValue().profile());
        }
        return profiles;
    }

    private void clearCleanDirtyKeys(Map<UUID, CachedSkin> snapshot) {
        for (Map.Entry<UUID, CachedSkin> entry : snapshot.entrySet()) {
            CachedSkin current = cache.get(entry.getKey());
            if (current != null && current.version() == entry.getValue().version()) {
                dirtyKeys.remove(entry.getKey());
            }
        }
    }

    private void completePendingFlushes(Throwable failure) {
        ArrayList<CompletableFuture<Void>> futures;
        synchronized (flushLock) {
            flushQueued = false;
            futures = new ArrayList<>(pendingFlushFutures);
            pendingFlushFutures.clear();
            if (!dirtyKeys.isEmpty() && !closed) {
                flushQueued = true;
                writerExecutor.execute(this::runFlushLoop);
            }
        }
        for (CompletableFuture<Void> future : futures) {
            if (failure == null) {
                future.complete(null);
            } else {
                future.completeExceptionally(failure);
            }
        }
    }

    private void flushBlocking() {
        if (dirtyKeys.isEmpty() && !flushQueued) {
            return;
        }
        try {
            CompletableFuture<Void> future = new CompletableFuture<>();
            synchronized (flushLock) {
                pendingFlushFutures.add(future);
                if (!flushQueued) {
                    flushQueued = true;
                    writerExecutor.execute(this::runFlushLoop);
                }
            }
            future.get(15, TimeUnit.SECONDS);
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to flush player skin profiles during shutdown: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private boolean sameSignature(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static final class SkinWriterThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "EnthusiaCurrency-SkinWriter");
            thread.setDaemon(true);
            return thread;
        }
    }
}
