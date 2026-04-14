package com.enthusia.enthusiacurrency.baltop;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.command.BaltopCommand;
import com.enthusia.enthusiacurrency.event.BaltopTopEnterEvent;
import org.bukkit.Bukkit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BaltopTracker {

    private final EnthusiaCurrencyPlugin plugin;
    private Set<UUID> lastTop3 = new LinkedHashSet<>();
    private volatile List<Map.Entry<UUID, Double>> cachedEntries = List.of();
    private int refreshTaskId = -1;

    public BaltopTracker(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void initializeSnapshot() {
        List<Map.Entry<UUID, Double>> entries = BaltopCommand.buildEntries(plugin);
        cachedEntries = entries;
        this.lastTop3 = extractTopSet(entries, 3);
    }

    /**
     * Start periodic leaderboard refresh (every 60s).
     * Replaces per-transaction rebuilds for performance.
     */
    public void startPeriodicRefresh() {
        long intervalTicks = 60 * 20L; // 60 seconds
        refreshTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            List<Map.Entry<UUID, Double>> entries = BaltopCommand.buildEntries(plugin);
            cachedEntries = entries;
            checkTop3Changes(entries);
        }, intervalTicks, intervalTicks).getTaskId();
    }

    public void stopPeriodicRefresh() {
        if (refreshTaskId != -1) {
            Bukkit.getScheduler().cancelTask(refreshTaskId);
            refreshTaskId = -1;
        }
    }

    public List<Map.Entry<UUID, Double>> getCachedEntries() {
        return cachedEntries;
    }

    /**
     * No-op — kept for API compatibility. Refresh now happens on a timer.
     */
    public void refreshTop3() {
        // Intentional no-op. Leaderboard refreshes every 60s via startPeriodicRefresh().
    }

    public boolean isInTop(UUID uuid, int top) {
        if (top <= 0) return false;
        List<Map.Entry<UUID, Double>> entries = cachedEntries;
        int limit = Math.min(top, entries.size());
        for (int i = 0; i < limit; i++) {
            if (entries.get(i).getKey().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    public int getRank(UUID uuid) {
        List<Map.Entry<UUID, Double>> entries = cachedEntries;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getKey().equals(uuid)) {
                return i + 1;
            }
        }
        return -1;
    }

    private void checkTop3Changes(List<Map.Entry<UUID, Double>> entries) {
        Set<UUID> currentTop3 = extractTopSet(entries, 3);

        if (lastTop3.isEmpty()) {
            lastTop3 = currentTop3;
            return;
        }

        for (int i = 0; i < entries.size() && i < 3; i++) {
            UUID uuid = entries.get(i).getKey();
            if (!lastTop3.contains(uuid)) {
                double balance = entries.get(i).getValue();
                Bukkit.getPluginManager().callEvent(new BaltopTopEnterEvent(uuid, i + 1, balance));
            }
        }

        lastTop3 = currentTop3;
    }

    private static Set<UUID> extractTopSet(List<Map.Entry<UUID, Double>> entries, int top) {
        Set<UUID> result = new LinkedHashSet<>();
        int limit = Math.min(top, entries.size());
        for (int i = 0; i < limit; i++) {
            result.add(entries.get(i).getKey());
        }
        return result;
    }
}
