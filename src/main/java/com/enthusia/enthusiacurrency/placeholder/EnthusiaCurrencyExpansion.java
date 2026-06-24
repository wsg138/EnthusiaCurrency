package com.enthusia.enthusiacurrency.placeholder;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.service.CurrencyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

import java.util.Locale;

public class EnthusiaCurrencyExpansion extends PlaceholderExpansion {

    private final EnthusiaCurrencyPlugin plugin;

    public EnthusiaCurrencyExpansion(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "currency";
    }

    @Override
    public String getAuthor() {
        return "Enthusia";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (params == null || params.isBlank()) {
            return null;
        }

        String lower = params.toLowerCase(Locale.ROOT);
        if (lower.startsWith("top_")) {
            LeaderboardPlaceholderCache cache = plugin.getLeaderboardPlaceholderCache();
            if (cache == null) {
                return "";
            }

            String[] parts = lower.split("_");
            if (parts.length != 4) {
                return "";
            }

            int rank;
            try {
                rank = Integer.parseInt(parts[2]);
            } catch (NumberFormatException ex) {
                return "";
            }

            return cache.resolve(parts[1], rank, parts[3]);
        }

        if (player == null) {
            return "";
        }

        plugin.getDebugMetrics().placeholderCachedReturn();
        CurrencyService.BalanceView balanceView = plugin.getCurrencyService().getCachedBalanceView(player);
        return switch (lower) {
            case "balance" -> String.valueOf(balanceView.total());
            case "bank" -> String.valueOf(balanceView.bank());
            case "items" -> String.valueOf(balanceView.items());
            case "top3" -> plugin.isInBaltopTop(player.getUniqueId(), 3) ? "true" : "false";
            default -> null;
        };
    }
}
