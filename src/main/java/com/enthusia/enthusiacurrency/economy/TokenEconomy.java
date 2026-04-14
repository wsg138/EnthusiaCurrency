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

    /**
     * Convert Vault double to internal long. Rejects NaN, Infinity, and negative values.
     */
    private static long toLong(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount < 0) return -1;
        return (long) Math.floor(amount);
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
        return 0;
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
        return true;
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
        long bank = storage.getBalance(offlinePlayer.getUniqueId());

        if (player != null && player.isOnline()) {
            int items = CurrencyUtils.countCurrencyInPlayer(currencyManager, player);
            return (double) (bank + items);
        }

        return (double) bank;
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
        long amt = toLong(amount);
        if (amt < 0) {
            return new EconomyResponse(0, getBalance(offlinePlayer),
                    EconomyResponse.ResponseType.FAILURE, "Invalid amount.");
        }
        if (amt == 0) {
            return new EconomyResponse(0, getBalance(offlinePlayer),
                    EconomyResponse.ResponseType.SUCCESS, null);
        }

        Player player = offlinePlayer.getPlayer();

        if (player != null && player.isOnline()) {
            long bank = storage.getBalance(offlinePlayer.getUniqueId());
            int items = CurrencyUtils.countCurrencyInPlayer(currencyManager, player);
            long total = bank + items;

            if (total < amt) {
                return new EconomyResponse(0, (double) total, EconomyResponse.ResponseType.FAILURE, "Not enough funds.");
            }

            if (bank >= amt) {
                storage.withdraw(offlinePlayer.getUniqueId(), amt);
            } else {
                // Need items too — remove items FIRST, then zero bank (C2 fix)
                long fromItems = amt - bank;
                int toRemove = (int) fromItems;
                int removed = CurrencyUtils.removeCurrencyFromPlayer(currencyManager, player, toRemove);
                if (removed < toRemove) {
                    // Item removal failed — bank untouched, no rollback needed
                    return new EconomyResponse(0, getBalance(offlinePlayer),
                            EconomyResponse.ResponseType.FAILURE, "Could not remove enough tokens from items.");
                }
                // Items removed successfully — now zero bank
                storage.setBalance(offlinePlayer.getUniqueId(), 0L);
                // Refund overshoot
                if (removed > toRemove) {
                    storage.deposit(offlinePlayer.getUniqueId(), (long) (removed - toRemove));
                }
            }

            plugin.getBaltopTracker().refreshTop3();
            return new EconomyResponse(amount, getBalance(offlinePlayer),
                    EconomyResponse.ResponseType.SUCCESS, null);
        } else {
            boolean success = storage.withdraw(offlinePlayer.getUniqueId(), amt);
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
        long amt = toLong(amount);
        if (amt <= 0) {
            return new EconomyResponse(0, getBalance(offlinePlayer),
                    EconomyResponse.ResponseType.FAILURE, "Invalid amount.");
        }
        storage.deposit(offlinePlayer.getUniqueId(), amt);
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
