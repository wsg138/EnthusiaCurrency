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

        long bank = storage.getBalance(player.getUniqueId());
        int items = CurrencyUtils.countCurrencyInPlayer(currency, player);
        long total = bank + items;

        return switch (params.toLowerCase()) {
            case "balance" -> String.valueOf(total);
            case "bank" -> String.valueOf(bank);
            case "items" -> String.valueOf(items);
            case "top3" -> plugin.isInBaltopTop(player.getUniqueId(), 3) ? "true" : "false";
            default -> null;
        };
    }
}
