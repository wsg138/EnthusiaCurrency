package com.enthusia.enthusiacurrency.baltop;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.command.BaltopCommand;
import com.enthusia.enthusiacurrency.event.BaltopTopEnterEvent;
import com.enthusia.enthusiacurrency.storage.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BaltopTracker {

    private static final int REFRESH_BATCH_SIZE = 10;

    private final EnthusiaCurrencyPlugin plugin;

    private Set<UUID> lastTop3 = new LinkedHashSet<>();
    private volatile List<Map.Entry<UUID, Long>> cachedEntries = List.of();
    private volatile boolean dirty = true;
    private int refreshTaskId = -1;
    private int batchRefreshTaskId = -1;
    private boolean refreshInProgress;
    private List<Player> pendingPlayers = List.of();
    private Map<UUID, Long> pendingTotals = Map.of();
    private long refreshStartedAtMillis;
    private int playersProcessed;
    private int pendingPlayerIndex;

    public BaltopTracker(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void initializeSnapshot() {
        refreshNow();
    }

    public void start() {
        long intervalSeconds = Math.max(5L, plugin.getConfig().getLong("baltop.refresh-interval-seconds", 15L));
        long intervalTicks = intervalSeconds * 20L;
        refreshTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty && !refreshInProgress) {
                startRefresh();
            }
        }, intervalTicks, intervalTicks).getTaskId();
    }

    public void stop() {
        if (refreshTaskId != -1) {
            Bukkit.getScheduler().cancelTask(refreshTaskId);
            refreshTaskId = -1;
        }
        if (batchRefreshTaskId != -1) {
            Bukkit.getScheduler().cancelTask(batchRefreshTaskId);
            batchRefreshTaskId = -1;
        }
        refreshInProgress = false;
    }

    public void refreshTop3() {
        dirty = true;
    }

    public List<Map.Entry<UUID, Long>> getEntriesForDisplay() {
        if (dirty && Bukkit.isPrimaryThread() && !refreshInProgress) {
            startRefresh();
        }
        return cachedEntries;
    }

    public boolean isInTop(UUID uuid, int top) {
        if (top <= 0) {
            return false;
        }

        List<Map.Entry<UUID, Long>> entries = getEntriesForDisplay();
        int limit = Math.min(top, entries.size());
        for (int index = 0; index < limit; index++) {
            if (entries.get(index).getKey().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    public int getRank(UUID uuid) {
        List<Map.Entry<UUID, Long>> entries = getEntriesForDisplay();
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).getKey().equals(uuid)) {
                return index + 1;
            }
        }
        return -1;
    }

    private void refreshNow() {
        List<Map.Entry<UUID, Long>> entries = BaltopCommand.buildEntries(plugin);
        cachedEntries = entries;
        dirty = false;
        checkTop3Changes(entries);
    }

    private void startRefresh() {
        refreshInProgress = true;
        refreshStartedAtMillis = System.currentTimeMillis();
        playersProcessed = 0;
        pendingPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        pendingTotals = new HashMap<>(plugin.getCurrencyService().getBankSnapshot());
        pendingPlayerIndex = 0;

        if (pendingPlayers.isEmpty()) {
            completeRefresh();
            return;
        }

        batchRefreshTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::processRefreshBatch, 1L, 1L).getTaskId();
    }

    private void processRefreshBatch() {
        int processed = 0;
        while (pendingPlayerIndex < pendingPlayers.size() && processed < REFRESH_BATCH_SIZE) {
            Player player = pendingPlayers.get(pendingPlayerIndex++);
            pendingTotals.put(player.getUniqueId(), plugin.getCurrencyService().getCachedBalanceView(player).total());
            processed++;
            playersProcessed++;
        }

        if (pendingPlayerIndex >= pendingPlayers.size()) {
            if (batchRefreshTaskId != -1) {
                Bukkit.getScheduler().cancelTask(batchRefreshTaskId);
                batchRefreshTaskId = -1;
            }
            completeRefresh();
        }
    }

    private void completeRefresh() {
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>(pendingTotals.entrySet());
        Map<UUID, PlayerProfile> profiles = plugin.getPlayerProfileStorage().getAllProfilesSnapshot();
        entries.sort((left, right) -> {
            int amountCompare = Long.compare(right.getValue(), left.getValue());
            if (amountCompare != 0) {
                return amountCompare;
            }

            String leftName = profileName(profiles, left.getKey());
            String rightName = profileName(profiles, right.getKey());
            return (leftName == null ? "" : leftName).compareToIgnoreCase(rightName == null ? "" : rightName);
        });

        cachedEntries = entries;
        dirty = false;
        refreshInProgress = false;
        pendingPlayers = List.of();
        pendingTotals = Map.of();
        pendingPlayerIndex = 0;
        checkTop3Changes(entries);
        plugin.getDebugMetrics().baltopRefresh(System.currentTimeMillis() - refreshStartedAtMillis, playersProcessed);
    }

    private String profileName(Map<UUID, PlayerProfile> profiles, UUID uuid) {
        PlayerProfile profile = profiles.get(uuid);
        if (profile != null && profile.username() != null) {
            return profile.username();
        }
        return "";
    }

    private void checkTop3Changes(List<Map.Entry<UUID, Long>> entries) {
        Set<UUID> currentTop3 = extractTopSet(entries, 3);
        if (lastTop3.isEmpty()) {
            lastTop3 = currentTop3;
            return;
        }

        for (int index = 0; index < entries.size() && index < 3; index++) {
            UUID uuid = entries.get(index).getKey();
            if (!lastTop3.contains(uuid)) {
                Bukkit.getPluginManager().callEvent(new BaltopTopEnterEvent(uuid, index + 1, entries.get(index).getValue()));
            }
        }

        lastTop3 = currentTop3;
    }

    private Set<UUID> extractTopSet(List<Map.Entry<UUID, Long>> entries, int top) {
        Set<UUID> topSet = new LinkedHashSet<>();
        int limit = Math.min(top, entries.size());
        for (int index = 0; index < limit; index++) {
            topSet.add(entries.get(index).getKey());
        }
        return topSet;
    }
}
