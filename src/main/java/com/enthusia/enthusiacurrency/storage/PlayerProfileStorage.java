package com.enthusia.enthusiacurrency.storage;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class PlayerProfileStorage {

    private record CachedProfile(PlayerProfile profile, long version) {
    }

    private final EnthusiaCurrencyPlugin plugin;
    private final Map<UUID, CachedProfile> profiles = new ConcurrentHashMap<>();
    private final Set<UUID> dirtyKeys = ConcurrentHashMap.newKeySet();
    private final Object flushLock = new Object();
    private final ArrayList<CompletableFuture<Void>> pendingFlushFutures = new ArrayList<>();
    private final ExecutorService writerExecutor;

    private PlayerProfileRepository repository;
    private volatile boolean flushQueued;
    private volatile boolean closed;

    public PlayerProfileStorage(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
        this.writerExecutor = Executors.newSingleThreadExecutor(new ProfileWriterThreadFactory());
    }

    public void load() {
        try {
            Files.createDirectories(plugin.getDataFolder().toPath());
            this.repository = new SqlitePlayerProfileRepository(plugin.getDataFolder().toPath().resolve("balances.db"));
            this.repository.initialize();

            profiles.clear();
            for (Map.Entry<UUID, PlayerProfile> entry : repository.loadAllProfiles().entrySet()) {
                profiles.put(entry.getKey(), new CachedProfile(entry.getValue(), 0L));
            }

            plugin.getLogger().info("Loaded " + profiles.size() + " player profile(s) for public leaderboards.");
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize player profile storage", ex);
        }
    }

    public void recordOnlinePlayer(Player player) {
        String displayName = stripToNull(player.getDisplayName());
        if (displayName != null && displayName.equals(player.getName())) {
            displayName = null;
        }
        record(player.getUniqueId(), player.getName(), displayName, Instant.now().toEpochMilli());
    }

    public void recordKnownPlayer(OfflinePlayer player) {
        recordKnownPlayer(player, null);
    }

    public void recordKnownPlayer(OfflinePlayer player, String fallbackUsername) {
        Player onlinePlayer = player.getPlayer();
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            recordOnlinePlayer(onlinePlayer);
            return;
        }

        String username = fallbackUsername == null || fallbackUsername.isBlank()
                ? player.getName()
                : fallbackUsername;
        if (username == null || username.isBlank()) {
            return;
        }
        record(player.getUniqueId(), username, null, Instant.now().toEpochMilli());
    }

    public void recordKnownPlayerName(UUID uuid, String username) {
        if (uuid == null || username == null || username.isBlank()) {
            return;
        }
        record(uuid, username, null, Instant.now().toEpochMilli());
    }

    public PlayerProfile getProfile(UUID uuid) {
        CachedProfile cachedProfile = profiles.get(uuid);
        return cachedProfile == null ? null : cachedProfile.profile();
    }

    public String getLastKnownName(UUID uuid) {
        PlayerProfile profile = getProfile(uuid);
        if (profile == null || profile.lastKnownName() == null || profile.lastKnownName().isBlank()) {
            return null;
        }
        return profile.lastKnownName();
    }

    public Map<UUID, PlayerProfile> getAllProfilesSnapshot() {
        Map<UUID, PlayerProfile> snapshot = new HashMap<>();
        for (Map.Entry<UUID, CachedProfile> entry : profiles.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().profile());
        }
        return snapshot;
    }

    public void flushAsync() {
        if (closed) {
            return;
        }

        synchronized (flushLock) {
            if (!flushQueued) {
                flushQueued = true;
                writerExecutor.execute(this::runFlushLoop);
            }
        }
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
                plugin.getLogger().warning("Player profile writer did not stop cleanly within 10 seconds.");
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
                plugin.getLogger().warning("Failed to close player profile repository cleanly: " + ex.getMessage());
            }
        }
    }

    private void record(UUID uuid, String username, String displayName, long seenAt) {
        if (username == null || username.isBlank()) {
            return;
        }

        CachedProfile updated = profiles.compute(uuid, (ignored, current) -> {
            long firstSeen = current == null ? seenAt : Math.min(current.profile().firstSeenAt(), seenAt);
            long version = current == null ? 1L : current.version() + 1L;
            String previousDisplayName = current == null ? null : current.profile().displayName();
            String effectiveDisplayName = displayName == null ? previousDisplayName : displayName;
            return new CachedProfile(new PlayerProfile(
                    uuid,
                    username,
                    effectiveDisplayName,
                    firstSeen,
                    Math.max(current == null ? seenAt : current.profile().lastSeenAt(), seenAt),
                    seenAt
            ), version);
        });

        if (updated != null) {
            dirtyKeys.add(uuid);
            flushAsync();
        }
    }

    private void runFlushLoop() {
        Throwable failure = null;
        try {
            while (true) {
                Map<UUID, CachedProfile> snapshot = snapshotDirtyProfiles();
                if (snapshot.isEmpty()) {
                    break;
                }

                Map<UUID, PlayerProfile> toSave = new HashMap<>();
                for (Map.Entry<UUID, CachedProfile> entry : snapshot.entrySet()) {
                    toSave.put(entry.getKey(), entry.getValue().profile());
                }
                repository.saveProfiles(toSave);

                for (Map.Entry<UUID, CachedProfile> entry : snapshot.entrySet()) {
                    CachedProfile current = profiles.get(entry.getKey());
                    if (current != null && current.version() == entry.getValue().version()) {
                        dirtyKeys.remove(entry.getKey());
                    }
                }
            }
        } catch (Exception ex) {
            failure = ex;
            plugin.getLogger().severe("Failed to flush player profiles: " + ex.getMessage());
            ex.printStackTrace();
        } finally {
            ArrayList<CompletableFuture<Void>> toComplete;
            synchronized (flushLock) {
                flushQueued = false;
                toComplete = new ArrayList<>(pendingFlushFutures);
                pendingFlushFutures.clear();
                if (!dirtyKeys.isEmpty() && !closed && !flushQueued) {
                    flushQueued = true;
                    writerExecutor.execute(this::runFlushLoop);
                }
            }

            for (CompletableFuture<Void> future : toComplete) {
                if (failure == null) {
                    future.complete(null);
                } else {
                    future.completeExceptionally(failure);
                }
            }
        }
    }

    private Map<UUID, CachedProfile> snapshotDirtyProfiles() {
        Map<UUID, CachedProfile> snapshot = new HashMap<>();
        for (UUID uuid : dirtyKeys) {
            CachedProfile cachedProfile = profiles.get(uuid);
            if (cachedProfile != null) {
                snapshot.put(uuid, cachedProfile);
            }
        }
        return snapshot;
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
            plugin.getLogger().severe("Failed to flush player profiles during shutdown: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private String stripToNull(String value) {
        if (value == null) {
            return null;
        }
        String stripped = ChatColor.stripColor(value);
        if (stripped == null || stripped.isBlank()) {
            return null;
        }
        return stripped;
    }

    private static final class ProfileWriterThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "EnthusiaCurrency-ProfileWriter");
            thread.setDaemon(true);
            return thread;
        }
    }
}
