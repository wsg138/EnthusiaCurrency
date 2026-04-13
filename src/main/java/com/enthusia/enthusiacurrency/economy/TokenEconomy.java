package com.enthusia.enthusiacurrency.economy;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import com.enthusia.enthusiacurrency.util.CurrencyUtils;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;

public class TokenEconomy implements Economy {

    private final EnthusiaCurrencyPlugin plugin;
    private final BalanceStorage storage;
    private final CurrencyManager currencyManager;

    public TokenEconomy(EnthusiaCurrencyPlugin plugin, BalanceStorage storage, CurrencyManager currencyManager) {
        this.plugin = plugin;
        this.storage = storage;
        this.currencyManager = currencyManager;
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return "EnthusiaCurrency";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        return plugin.getCurrencySymbol() + String.format("%.0f", amount);
    }

    @Override
    public String currencyNamePlural() {
        return plugin.getCurrencyPlural();
    }

    @Override
    public String currencyNameSingular() {
        return plugin.getCurrencySingular();
    }


    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean hasAccount(String playerName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        return player != null;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer offlinePlayer) {
        Player player = offlinePlayer.getPlayer();
        double bank = storage.getBalance(offlinePlayer.getUniqueId());

        if (player != null && player.isOnline()) {
            int items = CurrencyUtils.countCurrencyInPlayer(currencyManager, player);
            return bank + items;
        }

        return bank;
    }

    @Override
    public double getBalance(String playerName) {
        return getBalance(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public double getBalance(String playerName, String world) {
        return getBalance(playerName);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer offlinePlayer, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(offlinePlayer), EconomyResponse.ResponseType.FAILURE, "Negative amount.");
        }

        Player player = offlinePlayer.getPlayer();

        if (player != null && player.isOnline()) {
            double bank = storage.getBalance(offlinePlayer.getUniqueId());
            int items = CurrencyUtils.countCurrencyInPlayer(currencyManager, player);
            double total = bank + items;

            if (total < amount) {
                return new EconomyResponse(0, total, EconomyResponse.ResponseType.FAILURE, "Not enough funds.");
            }

            double remaining = amount;

            if (bank >= remaining) {
                storage.withdraw(offlinePlayer.getUniqueId(), remaining);
                remaining = 0;
            } else {
                remaining -= bank;
                storage.setBalance(offlinePlayer.getUniqueId(), 0);
            }

            if (remaining > 0) {
                int toRemove = (int) Math.ceil(remaining);
                int removed = CurrencyUtils.removeCurrencyFromPlayer(currencyManager, player, toRemove);
                if (removed < toRemove) {
                    return new EconomyResponse(amount - remaining, getBalance(offlinePlayer),
                            EconomyResponse.ResponseType.FAILURE, "Could not remove enough tokens from items.");
                }
                if (removed > toRemove) {
                    storage.deposit(offlinePlayer.getUniqueId(), removed - toRemove);
                }
                remaining = 0;
            }

            plugin.getBaltopTracker().refreshTop3();
            return new EconomyResponse(amount, getBalance(offlinePlayer),
                    EconomyResponse.ResponseType.SUCCESS, null);
        } else {
            boolean success = storage.withdraw(offlinePlayer.getUniqueId(), amount);
            if (!success) {
                return new EconomyResponse(0, getBalance(offlinePlayer),
                        EconomyResponse.ResponseType.FAILURE, "Not enough bank funds (offline).");
            }
            plugin.getBaltopTracker().refreshTop3();
            return new EconomyResponse(amount, getBalance(offlinePlayer),
                    EconomyResponse.ResponseType.SUCCESS, null);
        }
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer offlinePlayer, double amount) {
        if (amount < 0) {
            return new EconomyResponse(0, getBalance(offlinePlayer), EconomyResponse.ResponseType.FAILURE, "Negative amount.");
        }
        storage.deposit(offlinePlayer.getUniqueId(), amount);
        plugin.getBaltopTracker().refreshTop3();
        return new EconomyResponse(amount, getBalance(offlinePlayer),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(Bukkit.getOfflinePlayer(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support disabled.");
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return createBank(name, player.getName());
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support disabled.");
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support disabled.");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support disabled.");
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support disabled.");
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support disabled.");
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support disabled.");
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support disabled.");
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support disabled.");
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support disabled.");
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
        return createPlayerAccount(player);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        storage.getBalance(player.getUniqueId());
        return true;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }
}
