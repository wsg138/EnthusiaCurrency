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

    public BaltopTracker(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void initializeSnapshot() {
        this.lastTop3 = computeTopSet(3);
    }

    public void refreshTop3() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::refreshTop3);
            return;
        }

        List<Map.Entry<UUID, Double>> entries = BaltopCommand.buildEntries(plugin);
        Set<UUID> currentTop3 = new LinkedHashSet<>();

        for (int i = 0; i < entries.size() && i < 3; i++) {
            currentTop3.add(entries.get(i).getKey());
        }

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

    public boolean isInTop(UUID uuid, int top) {
        if (top <= 0) {
            return false;
        }
        List<Map.Entry<UUID, Double>> entries = BaltopCommand.buildEntries(plugin);
        int limit = Math.min(top, entries.size());
        for (int i = 0; i < limit; i++) {
            if (entries.get(i).getKey().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    public int getRank(UUID uuid) {
        List<Map.Entry<UUID, Double>> entries = BaltopCommand.buildEntries(plugin);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getKey().equals(uuid)) {
                return i + 1;
            }
        }
        return -1;
    }

    private Set<UUID> computeTopSet(int top) {
        List<Map.Entry<UUID, Double>> entries = BaltopCommand.buildEntries(plugin);
        Set<UUID> result = new LinkedHashSet<>();
        int limit = Math.min(top, entries.size());
        for (int i = 0; i < limit; i++) {
            result.add(entries.get(i).getKey());
        }
        return result;
    }
}
