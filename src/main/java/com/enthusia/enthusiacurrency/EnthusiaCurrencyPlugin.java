package com.enthusia.enthusiacurrency;

import com.enthusia.enthusiacurrency.command.*;
import com.enthusia.enthusiacurrency.baltop.BaltopTracker;
import com.enthusia.enthusiacurrency.economy.TokenEconomy;
import com.enthusia.enthusiacurrency.listener.BaltopGuiListener;
import com.enthusia.enthusiacurrency.placeholder.EnthusiaCurrencyExpansion;
import com.enthusia.enthusiacurrency.skin.SkinCache;
import com.enthusia.enthusiacurrency.skin.SkinListener;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.util.CurrencyManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class EnthusiaCurrencyPlugin extends JavaPlugin {

    private static EnthusiaCurrencyPlugin instance;

    private BalanceStorage balanceStorage;
    private CurrencyManager currencyManager;
    private TokenEconomy tokenEconomy;
    private BaltopTracker baltopTracker;

    private SkinCache skinCache;

    @Override
    public void onEnable() {
        instance = this;

        syncConfigWithDefaults();

        this.currencyManager = new CurrencyManager(this);
        this.currencyManager.reload();

        this.balanceStorage = new BalanceStorage(this);
        this.balanceStorage.load();

        this.baltopTracker = new BaltopTracker(this);
        this.baltopTracker.initializeSnapshot();

        this.skinCache = new SkinCache(this);
        this.skinCache.load();
        Bukkit.getPluginManager().registerEvents(new SkinListener(this.skinCache), this);

        setupVault();
        registerCommands();
        setupPlaceholderAPI();
        registerListeners();

        getLogger().info("EnthusiaCurrency enabled.");
    }

    @Override
    public void onDisable() {
        if (balanceStorage != null) {
            balanceStorage.save();
        }
        if (skinCache != null) {
            skinCache.save();
        }
        getLogger().info("EnthusiaCurrency disabled.");
    }

    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().severe("Vault not found! Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.tokenEconomy = new TokenEconomy(this, balanceStorage, currencyManager);
        Bukkit.getServicesManager().register(Economy.class, tokenEconomy, this, ServicePriority.Highest);

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null || !(rsp.getProvider() instanceof TokenEconomy)) {
            getLogger().warning("Another economy provider is registered. Make sure EnthusiaCurrency is the only one.");
        } else {
            getLogger().info("Registered EnthusiaCurrency as Vault economy provider.");
        }
    }

    private void setupPlaceholderAPI() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new EnthusiaCurrencyExpansion(this).register();
            getLogger().info("PlaceholderAPI found, registered EnthusiaCurrency placeholders.");
        }
    }

    private void registerCommands() {
        PluginCommand bal = getCommand("balance");
        PluginCommand dep = getCommand("deposit");
        PluginCommand wit = getCommand("withdraw");
        PluginCommand pay = getCommand("pay");
        PluginCommand bt  = getCommand("baltop");
        PluginCommand cur = getCommand("currency");

        BalanceCommand balanceCommand = new BalanceCommand(this);
        BaltopCommand baltopCommand = new BaltopCommand(this);
        DepositCommand depositCommand = new DepositCommand(this);
        WithdrawCommand withdrawCommand = new WithdrawCommand(this);
        PayCommand payCommand = new PayCommand(this);
        EnthusiaCurrencyCommand enthusiaCurrencyCommand = new EnthusiaCurrencyCommand(this);

        if (bal != null) bal.setExecutor(balanceCommand);
        if (dep != null) {
            dep.setExecutor(depositCommand);
            dep.setTabCompleter(depositCommand);
        }
        if (wit != null) {
            wit.setExecutor(withdrawCommand);
            wit.setTabCompleter(withdrawCommand);
        }
        if (pay != null) {
            pay.setExecutor(payCommand);
            pay.setTabCompleter(payCommand);
        }
        if (bt != null) {
            bt.setExecutor(baltopCommand);
            bt.setTabCompleter(baltopCommand);
        }
        if (cur != null) cur.setExecutor(enthusiaCurrencyCommand);
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new BaltopGuiListener(this), this);
    }

    public void reloadAndSyncConfig() {
        syncConfigWithDefaults();
    }

    private void syncConfigWithDefaults() {
        saveDefaultConfig();
        reloadConfig();

        try (InputStream defaultStream = getResource("config.yml")) {
            if (defaultStream == null) {
                getLogger().warning("Default config.yml not found in plugin jar; skipping config sync.");
                return;
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            FileConfiguration config = getConfig();

            boolean changed = mergeMissing(config, defaults);
            if (changed) {
                saveConfig();
                reloadConfig();
                getLogger().info("Added missing config options from defaults.");
            }
        } catch (Exception ex) {
            getLogger().warning("Failed to sync config defaults: " + ex.getMessage());
        }
    }

    private boolean mergeMissing(ConfigurationSection target, ConfigurationSection defaults) {
        boolean changed = false;

        for (String key : defaults.getKeys(false)) {
            if (defaults.isConfigurationSection(key)) {
                ConfigurationSection defaultChild = defaults.getConfigurationSection(key);
                ConfigurationSection targetChild = target.getConfigurationSection(key);

                if (targetChild == null) {
                    targetChild = target.createSection(key);
                    changed = true;
                }

                if (defaultChild != null) {
                    changed |= mergeMissing(targetChild, defaultChild);
                }
            } else if (!target.isSet(key)) {
                target.set(key, defaults.get(key));
                changed = true;
            }
        }

        return changed;
    }

    public static EnthusiaCurrencyPlugin getInstance() {
        return instance;
    }

    public BalanceStorage getBalanceStorage() {
        return balanceStorage;
    }

    public CurrencyManager getCurrencyManager() {
        return currencyManager;
    }

    public TokenEconomy getTokenEconomy() {
        return tokenEconomy;
    }

    public BaltopTracker getBaltopTracker() {
        return baltopTracker;
    }

    public boolean isInBaltopTop(UUID uuid, int top) {
        return baltopTracker != null && baltopTracker.isInTop(uuid, top);
    }

    public int getBaltopRank(UUID uuid) {
        return baltopTracker == null ? -1 : baltopTracker.getRank(uuid);
    }

    public SkinCache getSkinCache() {
        return skinCache;
    }

    public boolean isBedrock(Player player) {
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
            return false;
        }
        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Object result = apiClass
                    .getMethod("isFloodgatePlayer", java.util.UUID.class)
                    .invoke(api, player.getUniqueId());
            if (result instanceof Boolean) {
                return (Boolean) result;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public String getPrefix() {
        String raw = getConfig().getString("messages.prefix", "&8[&6Currency&8] &r");
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public String msgNoPrefix(String path) {
        String raw = getConfig().getString("messages." + path, "");
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    public void sendMsg(org.bukkit.command.CommandSender sender, String path) {
        sender.sendMessage(getPrefix() + msgNoPrefix(path));
    }

    public String getCurrencySingular() {
        return getConfig().getString("economy.currency-name-singular", "Dollar");
    }

    public String getCurrencyPlural() {
        return getConfig().getString("economy.currency-name-plural", "Dollars");
    }

    public String getCurrencyName(double amount) {
        if (Math.abs(amount - 1.0) < 0.0001) {
            return getCurrencySingular();
        }
        return getCurrencyPlural();
    }

    public String getCurrencySymbol() {
        return getConfig().getString("economy.currency-symbol", "$");
    }
}
