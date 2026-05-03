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
    private Map<UUID, PlayerProfile> pendingProfiles = Map.of();
    private int pendingPlayerIndex;
    private int refreshGeneration;

    public BaltopTracker(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void initializeSnapshot() {
        if (!refreshInProgress) {
            startRefresh();
        }
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
        refreshGeneration++;
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

    private void startRefresh() {
        refreshInProgress = true;
        int generation = ++refreshGeneration;
        pendingPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
        pendingTotals = Map.of();
        pendingProfiles = Map.of();
        pendingPlayerIndex = 0;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<UUID, Long> bankSnapshot = new HashMap<>(plugin.getCurrencyService().getBankSnapshot());
            Map<UUID, PlayerProfile> profileSnapshot = plugin.getPlayerProfileStorage().getAllProfilesSnapshot();
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> continueRefresh(generation, bankSnapshot, profileSnapshot));
        });
    }

    private void continueRefresh(int generation, Map<UUID, Long> bankSnapshot, Map<UUID, PlayerProfile> profileSnapshot) {
        if (!refreshInProgress || generation != refreshGeneration) {
            return;
        }

        pendingTotals = bankSnapshot;
        pendingProfiles = profileSnapshot;

        if (pendingPlayers.isEmpty()) {
            completeRefreshAsync(generation);
            return;
        }

        batchRefreshTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::processRefreshBatch, 1L, 1L).getTaskId();
    }

    private void processRefreshBatch() {
        int processed = 0;
        while (pendingPlayerIndex < pendingPlayers.size() && processed < REFRESH_BATCH_SIZE) {
            Player player = pendingPlayers.get(pendingPlayerIndex++);
            pendingTotals.put(player.getUniqueId(), plugin.getCurrencyService().getBalanceView(player).total());
            processed++;
        }

        if (pendingPlayerIndex >= pendingPlayers.size()) {
            if (batchRefreshTaskId != -1) {
                Bukkit.getScheduler().cancelTask(batchRefreshTaskId);
                batchRefreshTaskId = -1;
            }
            completeRefreshAsync(refreshGeneration);
        }
    }

    private void completeRefreshAsync(int generation) {
        Map<UUID, Long> totals = new HashMap<>(pendingTotals);
        Map<UUID, PlayerProfile> profiles = new HashMap<>(pendingProfiles);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Map.Entry<UUID, Long>> entries = BaltopCommand.sortEntries(totals, profiles);
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> publishRefresh(generation, entries));
        });
    }

    private void publishRefresh(int generation, List<Map.Entry<UUID, Long>> entries) {
        if (!refreshInProgress || generation != refreshGeneration) {
            return;
        }

        cachedEntries = entries;
        dirty = false;
        refreshInProgress = false;
        pendingPlayers = List.of();
        pendingTotals = Map.of();
        pendingProfiles = Map.of();
        pendingPlayerIndex = 0;
        checkTop3Changes(entries);
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
