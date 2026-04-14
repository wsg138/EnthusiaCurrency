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
    private volatile List<Map.Entry<UUID, Long>> cachedEntries = List.of();
    private volatile boolean dirty = true;
    private int refreshTaskId = -1;

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
            if (dirty) {
                refreshNow();
            }
        }, intervalTicks, intervalTicks).getTaskId();
    }

    public void stop() {
        if (refreshTaskId != -1) {
            Bukkit.getScheduler().cancelTask(refreshTaskId);
            refreshTaskId = -1;
        }
    }

    public void refreshTop3() {
        dirty = true;
    }

    public List<Map.Entry<UUID, Long>> getEntriesForDisplay() {
        if (dirty && Bukkit.isPrimaryThread()) {
            refreshNow();
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
