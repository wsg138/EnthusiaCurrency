package com.enthusia.enthusiacurrency.economy;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import com.enthusia.enthusiacurrency.service.CurrencyAmountParser;
import com.enthusia.enthusiacurrency.service.CurrencyService;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Callable;

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
        return 0;
    }

    @Override
    public String format(double amount) {
        long normalized = normalizeAmount(amount).orElse(0L);
        return plugin.getCurrencySymbol() + normalized;
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
        return runSyncIfNeeded(() -> {
            CurrencyService.BalanceView balanceView = plugin.getCurrencyService().getBalanceView(offlinePlayer);
            return (double) balanceView.total();
        });
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
        OptionalLong normalized = normalizeAmount(amount);
        if (normalized.isEmpty()) {
            return new EconomyResponse(0, getBalance(offlinePlayer), EconomyResponse.ResponseType.FAILURE, "Invalid amount.");
        }

        return runSyncIfNeeded(() -> {
            CurrencyService.VaultWithdrawResult result = plugin.getCurrencyService().withdrawTotal(offlinePlayer, normalized.getAsLong());
            if (!result.success()) {
                String message = "insufficient".equals(result.failureReason())
                        ? "Not enough funds."
                        : "Invalid amount.";
                return new EconomyResponse(0, result.newBalance(), EconomyResponse.ResponseType.FAILURE, message);
            }

            plugin.getCurrencyService().markLeaderboardDirty();
            return new EconomyResponse(amount, result.newBalance(), EconomyResponse.ResponseType.SUCCESS, null);
        });
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
        OptionalLong normalized = normalizeAmount(amount);
        if (normalized.isEmpty()) {
            return new EconomyResponse(0, getBalance(offlinePlayer), EconomyResponse.ResponseType.FAILURE, "Invalid amount.");
        }

        try {
            long newBalance = plugin.getCurrencyService().depositBank(offlinePlayer.getUniqueId(), normalized.getAsLong());
            return new EconomyResponse(amount, newBalance, EconomyResponse.ResponseType.SUCCESS, null);
        } catch (IllegalArgumentException ex) {
            return new EconomyResponse(0, getBalance(offlinePlayer), EconomyResponse.ResponseType.FAILURE, "Invalid amount.");
        }
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
        return notImplemented();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return notImplemented();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return notImplemented();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return createPlayerAccount(Bukkit.getOfflinePlayer(playerName));
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        storage.ensureAccount(player.getUniqueId());
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

    private OptionalLong normalizeAmount(double amount) {
        return CurrencyAmountParser.parseUserAmount(Double.toString(amount), true);
    }

    private EconomyResponse notImplemented() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support disabled.");
    }

    private <T> T runSyncIfNeeded(Callable<T> callable) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return callable.call();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }

        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, callable).get();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
