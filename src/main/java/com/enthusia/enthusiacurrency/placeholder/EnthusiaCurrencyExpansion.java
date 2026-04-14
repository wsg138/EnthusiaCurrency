package com.enthusia.enthusiacurrency.placeholder;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.service.CurrencyService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

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
        if (player == null) {
            return "";
        }

        CurrencyService.BalanceView balanceView = plugin.getCurrencyService().getBalanceView(player);
        return switch (params.toLowerCase()) {
            case "balance" -> String.valueOf(balanceView.total());
            case "bank" -> String.valueOf(balanceView.bank());
            case "items" -> String.valueOf(balanceView.items());
            case "top3" -> plugin.isInBaltopTop(player.getUniqueId(), 3) ? "true" : "false";
            default -> null;
        };
    }
}
