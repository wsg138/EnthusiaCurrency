package com.enthusia.enthusiacurrency.placeholder;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import com.enthusia.enthusiacurrency.util.CurrencyUtils;
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
        if (player == null) return "";

        BalanceStorage storage = plugin.getBalanceStorage();
        CurrencyManager currency = plugin.getCurrencyManager();

        double bank = storage.getBalance(player.getUniqueId());
        int items = CurrencyUtils.countCurrencyInPlayer(currency, player);
        double total = bank + items;

        switch (params.toLowerCase()) {
            case "balance":
                return String.format("%.0f", total);
            case "bank":
                return String.format("%.0f", bank);
            case "items":
                return String.valueOf(items);
            case "top3":
                return plugin.isInBaltopTop(player.getUniqueId(), 3) ? "true" : "false";
            default:
                return null;
        }
    }
}
