package com.enthusia.enthusiacurrency.leaderboard;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.storage.PlayerProfile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class LeaderboardExportService {

    private static final String BOARD_BALANCE = "balance";
    private static final String BOARD_LABEL_BALANCE = "Balance";
    private static final int MAX_EXPORTED_PLAYERS = 100;

    private final EnthusiaCurrencyPlugin plugin;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final DecimalFormat integerFormat = new DecimalFormat("#,###", DecimalFormatSymbols.getInstance(Locale.US));
    private final ExecutorService exportExecutor;

    private int exportTaskId = -1;
    private int debounceTaskId = -1;
    private volatile boolean closed;
    private volatile boolean dirty;

    public LeaderboardExportService(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
        this.exportExecutor = Executors.newSingleThreadExecutor(new ExportThreadFactory());
    }

    public void start() {
        if (!isEnabled()) {
            return;
        }

        exportNow();
        long intervalSeconds = Math.max(10L, plugin.getConfig().getLong("leaderboards.export.interval-seconds", 60L));
        long intervalTicks = intervalSeconds * 20L;
        exportTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::exportNow,
                intervalTicks,
                intervalTicks
        ).getTaskId();
    }

    public void reload() {
        stopTask();
        if (!closed) {
            start();
        }
    }

    public void exportNow() {
        if (!isEnabled() || closed) {
            return;
        }

        dirty = false;
        Map<UUID, Long> balances = plugin.getCurrencyService().getBankSnapshot();
        Map<UUID, PlayerProfile> profiles = plugin.getPlayerProfileStorage().getAllProfilesSnapshot();
        boolean includeZeroBalances = plugin.getConfig().getBoolean("leaderboards.export.include-zero-balances", false);
        String currencySingular = plugin.getCurrencySingular();
        String currencyPlural = plugin.getCurrencyPlural();

        exportExecutor.execute(() -> {
            try {
                plugin.getBalanceStorage().flushAsync().get(15, TimeUnit.SECONDS);
                writeBalanceExport(balances, profiles, includeZeroBalances, currencySingular, currencyPlural);
            } catch (Exception ex) {
                plugin.getLogger().warning("Skipped public balance leaderboard export because balances did not flush cleanly: " + ex.getMessage());
            }
        });
    }

    public void markDirty() {
        if (!isEnabled() || closed) {
            return;
        }

        dirty = true;
        if (debounceTaskId != -1) {
            return;
        }

        long delaySeconds = Math.max(5L, plugin.getConfig().getLong("leaderboards.export.debounce-seconds", 10L));
        debounceTaskId = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            debounceTaskId = -1;
            if (dirty) {
                exportNow();
            }
        }, delaySeconds * 20L).getTaskId();
    }

    public void close() {
        if (closed) {
            return;
        }

        stopTask();
        if (dirty) {
            exportNow();
        }
        closed = true;
        exportExecutor.shutdown();
        try {
            if (!exportExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Leaderboard export writer did not stop cleanly within 10 seconds.");
                exportExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            exportExecutor.shutdownNow();
        }
    }

    private void writeBalanceExport(
            Map<UUID, Long> balances,
            Map<UUID, PlayerProfile> profiles,
            boolean includeZeroBalances,
            String currencySingular,
            String currencyPlural
    ) {
        try {
            List<Map.Entry<UUID, Long>> entries = new ArrayList<>(balances.entrySet());
            if (!includeZeroBalances) {
                entries.removeIf(entry -> entry.getValue() <= 0L);
            }
            entries.sort(balanceComparator(profiles));

            int limit = Math.min(MAX_EXPORTED_PLAYERS, entries.size());
            List<LeaderboardPlayerEntry> players = new ArrayList<>(limit);
            for (int index = 0; index < limit; index++) {
                Map.Entry<UUID, Long> entry = entries.get(index);
                PlayerProfile profile = profiles.get(entry.getKey());
                String username = profile == null ? "Unknown" : profile.username();
                String displayName = profile == null ? null : profile.displayName();
                long value = entry.getValue();
                players.add(new LeaderboardPlayerEntry(
                        index + 1,
                        entry.getKey().toString(),
                        username,
                        displayName,
                        value,
                        integerFormat.format(value),
                        formatCurrencyText(value, currencySingular, currencyPlural)
                ));
            }

            LeaderboardExport export = new LeaderboardExport(
                    BOARD_BALANCE,
                    BOARD_LABEL_BALANCE,
                    currencyPlural,
                    Instant.now().toString(),
                    "EnthusiaCurrency",
                    "all_time",
                    "desc",
                    players
            );

            Path outputPath = getOutputPath();
            Files.createDirectories(outputPath.getParent());
            Path tempPath = outputPath.resolveSibling(outputPath.getFileName() + ".tmp");
            Files.writeString(tempPath, gson.toJson(export), StandardCharsets.UTF_8);
            try {
                Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to export public balance leaderboard: " + ex.getMessage());
        }
    }

    private String formatCurrencyText(long value, String currencySingular, String currencyPlural) {
        String currencyName = value == 1L ? currencySingular : currencyPlural;
        return integerFormat.format(value) + " " + currencyName;
    }

    private Comparator<Map.Entry<UUID, Long>> balanceComparator(Map<UUID, PlayerProfile> profiles) {
        return (left, right) -> {
            int amountCompare = Long.compare(right.getValue(), left.getValue());
            if (amountCompare != 0) {
                return amountCompare;
            }

            String leftName = nameForSort(left.getKey(), profiles);
            String rightName = nameForSort(right.getKey(), profiles);
            return leftName.compareToIgnoreCase(rightName);
        };
    }

    private String nameForSort(UUID uuid, Map<UUID, PlayerProfile> profiles) {
        PlayerProfile profile = profiles.get(uuid);
        if (profile == null || profile.username() == null) {
            return "";
        }
        return profile.username();
    }

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean("leaderboards.export.enabled", true);
    }

    private Path getOutputPath() {
        String directory = plugin.getConfig().getString("leaderboards.export.directory", "leaderboards");
        String fileName = plugin.getConfig().getString("leaderboards.export.balance-file", "balance.json");
        return plugin.getDataFolder().toPath().resolve(directory).resolve(fileName);
    }

    private void stopTask() {
        if (exportTaskId != -1) {
            Bukkit.getScheduler().cancelTask(exportTaskId);
            exportTaskId = -1;
        }
        if (debounceTaskId != -1) {
            Bukkit.getScheduler().cancelTask(debounceTaskId);
            debounceTaskId = -1;
        }
    }

    private record LeaderboardExport(
            String board,
            String label,
            String statLabel,
            String generatedAt,
            String source,
            String season,
            String order,
            List<LeaderboardPlayerEntry> players
    ) {
    }

    private record LeaderboardPlayerEntry(
            int rank,
            String uuid,
            String username,
            String displayName,
            long value,
            String formattedValue,
            String subtext
    ) {
    }

    private static final class ExportThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "EnthusiaCurrency-LeaderboardExport");
            thread.setDaemon(true);
            return thread;
        }
    }
}
