package com.enthusia.enthusiacurrency;

import com.enthusia.enthusiacurrency.command.*;
import com.enthusia.enthusiacurrency.analytics.CurrencyAnalyticsStorage;
import com.enthusia.enthusiacurrency.baltop.BaltopTracker;
import com.enthusia.enthusiacurrency.economy.TokenEconomy;
import com.enthusia.enthusiacurrency.leaderboard.LeaderboardExportService;
import com.enthusia.enthusiacurrency.listener.BaltopGuiListener;
import com.enthusia.enthusiacurrency.listener.PlayerProfileListener;
import com.enthusia.enthusiacurrency.plan.PlanIntegrationHook;
import com.enthusia.enthusiacurrency.placeholder.EnthusiaCurrencyExpansion;
import com.enthusia.enthusiacurrency.placeholder.LeaderboardPlaceholderCache;
import com.enthusia.enthusiacurrency.service.CurrencyService;
import com.enthusia.enthusiacurrency.skin.SkinCache;
import com.enthusia.enthusiacurrency.skin.SkinListener;
import com.enthusia.enthusiacurrency.storage.BalanceStorage;
import com.enthusia.enthusiacurrency.storage.PlayerProfileStorage;
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
    private CurrencyService currencyService;
    private TokenEconomy tokenEconomy;
    private BaltopTracker baltopTracker;
    private PlayerProfileStorage playerProfileStorage;
    private LeaderboardExportService leaderboardExportService;
    private CurrencyAnalyticsStorage currencyAnalyticsStorage;
    private LeaderboardPlaceholderCache leaderboardPlaceholderCache;
    private EnthusiaCurrencyExpansion placeholderExpansion;

    private SkinCache skinCache;

    @Override
    public void onEnable() {
        instance = this;

        syncConfigWithDefaults();

        this.currencyManager = new CurrencyManager(this);
        this.currencyManager.reload();

        this.balanceStorage = new BalanceStorage(this);
        try {
            this.balanceStorage.load();
        } catch (IllegalStateException ex) {
            getLogger().severe("Failed to start balance storage: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        this.currencyService = new CurrencyService(this, balanceStorage, currencyManager);

        this.playerProfileStorage = new PlayerProfileStorage(this);
        try {
            this.playerProfileStorage.load();
        } catch (IllegalStateException ex) {
            getLogger().severe("Failed to start player profile storage: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.currencyAnalyticsStorage = new CurrencyAnalyticsStorage(this);
        try {
            this.currencyAnalyticsStorage.load();
        } catch (IllegalStateException ex) {
            getLogger().severe("Failed to start currency analytics storage: " + ex.getMessage());
            ex.printStackTrace();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.baltopTracker = new BaltopTracker(this);
        this.baltopTracker.initializeSnapshot();
        this.baltopTracker.start();

        this.leaderboardExportService = new LeaderboardExportService(this);

        this.skinCache = new SkinCache(this);
        this.skinCache.load();
        Bukkit.getPluginManager().registerEvents(new SkinListener(this.skinCache), this);

        setupVault();
        registerCommands();
        setupPlaceholderAPI();
        registerListeners();
        this.leaderboardExportService.start();
        setupPlanIntegration();

        getLogger().info("EnthusiaCurrency enabled.");
    }

    @Override
    public void onDisable() {
        teardownPlaceholderAPI();
        if (baltopTracker != null) {
            baltopTracker.stop();
        }
        if (leaderboardExportService != null) {
            leaderboardExportService.close();
        }
        if (currencyAnalyticsStorage != null) {
            currencyAnalyticsStorage.close();
        }
        if (balanceStorage != null) {
            balanceStorage.close();
        }
        if (playerProfileStorage != null) {
            playerProfileStorage.close();
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
        teardownPlaceholderAPI();

        if (!getConfig().getBoolean("placeholderapi.enabled", true)) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }

        try {
            leaderboardPlaceholderCache = new LeaderboardPlaceholderCache(this);
            leaderboardPlaceholderCache.start();

            placeholderExpansion = new EnthusiaCurrencyExpansion(this);
            if (placeholderExpansion.register()) {
                getLogger().info("PlaceholderAPI found, registered EnthusiaCurrency placeholders.");
            } else {
                getLogger().warning("Failed to register PlaceholderAPI expansion.");
                teardownPlaceholderAPI();
            }
        } catch (Throwable ex) {
            getLogger().warning("Failed to initialize PlaceholderAPI support: " + ex.getMessage());
            teardownPlaceholderAPI();
        }
    }

    private void teardownPlaceholderAPI() {
        if (placeholderExpansion != null) {
            try {
                placeholderExpansion.unregister();
            } catch (Throwable ignored) {
            }
            placeholderExpansion = null;
        }
        if (leaderboardPlaceholderCache != null) {
            leaderboardPlaceholderCache.stop();
            leaderboardPlaceholderCache = null;
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
        if (playerProfileStorage != null) {
            Bukkit.getPluginManager().registerEvents(new PlayerProfileListener(playerProfileStorage), this);
            for (Player player : Bukkit.getOnlinePlayers()) {
                playerProfileStorage.recordOnlinePlayer(player);
            }
        }
    }

    private void setupPlanIntegration() {
        if (!getConfig().getBoolean("integrations.plan.enabled", true)) {
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("Plan") == null) {
            return;
        }

        try {
            new PlanIntegrationHook(this).hookIntoPlan();
        } catch (NoClassDefFoundError ex) {
            getLogger().fine("Plan API was not available; skipping Plan integration.");
        } catch (Throwable ex) {
            getLogger().warning("Failed to register Plan integration: " + ex.getMessage());
        }
    }

    public void reloadAndSyncConfig() {
        syncConfigWithDefaults();
        if (balanceStorage != null) {
            balanceStorage.reloadSettings();
        }
        if (currencyAnalyticsStorage != null) {
            currencyAnalyticsStorage.reloadSettings();
        }
        if (baltopTracker != null) {
            baltopTracker.refreshTop3();
            baltopTracker.stop();
            baltopTracker.start();
        }
        if (leaderboardExportService != null) {
            leaderboardExportService.reload();
        }
        setupPlaceholderAPI();
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

    public CurrencyService getCurrencyService() {
        return currencyService;
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

    public PlayerProfileStorage getPlayerProfileStorage() {
        return playerProfileStorage;
    }

    public LeaderboardExportService getLeaderboardExportService() {
        return leaderboardExportService;
    }

    public LeaderboardPlaceholderCache getLeaderboardPlaceholderCache() {
        return leaderboardPlaceholderCache;
    }

    public CurrencyAnalyticsStorage getCurrencyAnalyticsStorage() {
        return currencyAnalyticsStorage;
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

    public String getCurrencyName(long amount) {
        return amount == 1L ? getCurrencySingular() : getCurrencyPlural();
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
