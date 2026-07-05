package com.enthusia.enthusiacurrency.placeholder;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.baltop.BaltopTracker;
import com.enthusia.enthusiacurrency.storage.PlayerProfile;
import com.enthusia.enthusiacurrency.item.ItemBalanceSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LeaderboardPlaceholderCache {

    public enum LeaderboardType {
        BALANCE,
        BANK,
        ITEMS
    }

    private record Entry(UUID uuid, String name, long value) {
    }

    private static final int MIN_RANK = 1;
    private static final int MAX_CONFIGURED_RANK = 100;
    private static final long MIN_LEADERBOARD_VALUE = 1L;
    private static final String FALLBACK_NOT_AVAILABLE = "N/A";

    private final EnthusiaCurrencyPlugin plugin;
    private final DecimalFormat integerFormat = new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.US));
    private final Map<LeaderboardType, List<Entry>> cachedEntries = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();

    private volatile int refreshTaskId = -1;
    private volatile int maxRank = MAX_CONFIGURED_RANK;
    private volatile long refreshIntervalSeconds = 30L;
    private volatile String missingFallback = FALLBACK_NOT_AVAILABLE;

    public LeaderboardPlaceholderCache(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
        for (LeaderboardType type : LeaderboardType.values()) {
            cachedEntries.put(type, List.of());
        }
    }

    public void start() {
        synchronized (lifecycleLock) {
            stopTask();
            refreshNow();

            long intervalTicks = Math.max(10L, refreshIntervalSeconds) * 20L;
            refreshTaskId = Bukkit.getScheduler()
                    .runTaskTimer(plugin, this::refreshNow, intervalTicks, intervalTicks)
                    .getTaskId();
        }
    }

    public void reload() {
        start();
    }

    public void stop() {
        synchronized (lifecycleLock) {
            stopTask();
        }
    }

    private void stopTask() {
        if (refreshTaskId != -1) {
            Bukkit.getScheduler().cancelTask(refreshTaskId);
            refreshTaskId = -1;
        }
    }

    public String resolve(String typeName, int rank, String fieldName) {
        if (rank < MIN_RANK || rank > maxRank) {
            return missingFallback;
        }

        LeaderboardType type = parseType(typeName);
        if (type == null) {
            return missingFallback;
        }

        List<Entry> entries = cachedEntries.getOrDefault(type, List.of());
        Entry entry = entryAtRank(entries, rank);
        if (entry == null) {
            return missingFallback;
        }

        return resolveField(entry, fieldName);
    }

    private LeaderboardType parseType(String typeName) {
        try {
            return LeaderboardType.valueOf(typeName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Entry entryAtRank(List<Entry> entries, int rank) {
        return rank <= entries.size() ? entries.get(rank - 1) : null;
    }

    private String resolveField(Entry entry, String fieldName) {
        return switch (fieldName.toLowerCase(Locale.ROOT)) {
            case "name" -> entry.name().isBlank() ? missingFallback : entry.name();
            case "uuid" -> entry.uuid().toString();
            case "value" -> Long.toString(entry.value());
            case "formatted" -> formatValue(entry.value());
            default -> missingFallback;
        };
    }

    public void reloadSettings() {
        refreshIntervalSeconds = Math.max(10L, plugin.getConfig().getLong(
                "placeholders.cache-seconds",
                plugin.getConfig().getLong(
                        "item-leaderboard.cache-seconds",
                        plugin.getConfig().getLong("placeholderapi.leaderboard-refresh-seconds", 30L)
                )
        ));
        maxRank = clampMaxRank(plugin.getConfig().getInt("placeholderapi.leaderboard-max-rank", MAX_CONFIGURED_RANK));

        String fallback = plugin.getConfig().getString("placeholderapi.leaderboard-missing-fallback", FALLBACK_NOT_AVAILABLE);
        missingFallback = fallback == null ? FALLBACK_NOT_AVAILABLE : fallback;
    }

    private void refreshNow() {
        reloadSettings();

        cachedEntries.put(LeaderboardType.BALANCE, buildBalanceEntries());
        cachedEntries.put(LeaderboardType.BANK, buildBankEntries());
        cachedEntries.put(LeaderboardType.ITEMS, buildItemEntries());
    }

    private List<Entry> buildBalanceEntries() {
        BaltopTracker tracker = plugin.getBaltopTracker();
        if (tracker == null) {
            return List.of();
        }

        Map<UUID, String> names = snapshotNames();
        List<Entry> entries = new ArrayList<>();
        List<Map.Entry<UUID, Long>> source = tracker.getEntriesForDisplay();

        for (int index = 0; index < source.size() && index < maxRank; index++) {
            Map.Entry<UUID, Long> entry = source.get(index);
            entries.add(new Entry(entry.getKey(), displayName(entry.getKey(), names), entry.getValue()));
        }

        return List.copyOf(entries);
    }

    private List<Entry> buildBankEntries() {
        Map<UUID, Long> snapshot = new ConcurrentHashMap<>(plugin.getCurrencyService().getBankSnapshot());
        Map<UUID, String> names = snapshotNames();

        List<Entry> entries = snapshot.entrySet().stream()
                .filter(entry -> entry.getValue() > 0L)
                .sorted(entryComparator(names))
                .limit(maxRank)
                .map(entry -> new Entry(entry.getKey(), displayName(entry.getKey(), names), entry.getValue()))
                .toList();

        return List.copyOf(entries);
    }

    private List<Entry> buildItemEntries() {
        if (!plugin.getConfig().getBoolean("item-leaderboard.enabled", true)) {
            return List.of();
        }
        if (plugin.getItemBalanceTracker() == null) {
            return List.of();
        }
        Map<UUID, String> names = snapshotNames();
        List<Entry> entries = new ArrayList<>();

        for (ItemBalanceSnapshot snapshot : plugin.getItemBalanceTracker().getSnapshots().values()) {
            long value = snapshot.totalItemCurrency();
            if (value < MIN_LEADERBOARD_VALUE) {
                continue;
            }

            UUID uuid = snapshot.uuid();
            if (snapshot.lastKnownName() != null && !snapshot.lastKnownName().isBlank()) {
                names.put(uuid, snapshot.lastKnownName());
            }
            entries.add(new Entry(uuid, displayName(uuid, names), value));
        }

        entries.sort(leaderboardComparator(names));
        if (entries.size() > maxRank) {
            return List.copyOf(entries.subList(0, maxRank));
        }
        return List.copyOf(entries);
    }

    private Map<UUID, String> snapshotNames() {
        Map<UUID, String> names = new ConcurrentHashMap<>();
        for (PlayerProfile profile : plugin.getPlayerProfileStorage().getAllProfilesSnapshot().values()) {
            if (profile == null || profile.uuid() == null) {
                continue;
            }
            if (profile.username() != null && !profile.username().isBlank()) {
                names.put(profile.uuid(), profile.username());
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.put(player.getUniqueId(), player.getName());
        }
        return names;
    }

    private String displayName(UUID uuid, Map<UUID, String> names) {
        String name = names.get(uuid);
        if (name == null || name.isBlank()) {
            return missingFallback;
        }
        return name;
    }

    private String sortName(UUID uuid, Map<UUID, String> names) {
        String name = names.get(uuid);
        if (name != null && !name.isBlank()) {
            return name;
        }
        return uuid.toString();
    }

    private String formatValue(long value) {
        return plugin.getCurrencySymbol() + integerFormat.format(value);
    }

    private Comparator<Map.Entry<UUID, Long>> entryComparator(Map<UUID, String> names) {
        return (left, right) -> {
            int compare = Long.compare(right.getValue(), left.getValue());
            if (compare != 0) {
                return compare;
            }

            return sortName(left.getKey(), names).compareToIgnoreCase(sortName(right.getKey(), names));
        };
    }

    private Comparator<Entry> leaderboardComparator(Map<UUID, String> names) {
        return (left, right) -> {
            int compare = Long.compare(right.value(), left.value());
            if (compare != 0) {
                return compare;
            }

            return sortName(left.uuid(), names).compareToIgnoreCase(sortName(right.uuid(), names));
        };
    }

    private int clampMaxRank(int configured) {
        if (configured < MIN_RANK) {
            return MIN_RANK;
        }
        return Math.min(MAX_CONFIGURED_RANK, configured);
    }
}
