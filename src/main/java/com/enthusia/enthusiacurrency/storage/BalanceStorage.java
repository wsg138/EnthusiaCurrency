package com.enthusia.enthusiacurrency.storage;

import com.enthusia.enthusiacurrency.EnthusiaCurrencyPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;

public class BalanceStorage {

    private final EnthusiaCurrencyPlugin plugin;
    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    private File file;
    private YamlConfiguration config;

    public BalanceStorage(EnthusiaCurrencyPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "balances.yml");
        if (!file.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().severe("Could not create balances.yml");
                e.printStackTrace();
            }
        }

        config = YamlConfiguration.loadConfiguration(file);

        balances.clear();
        if (config.isConfigurationSection("balances")) {
            for (String key : config.getConfigurationSection("balances").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    double amount = config.getDouble("balances." + key, 0.0);
                    balances.put(uuid, amount);
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    public void save() {
        if (config == null) {
            config = new YamlConfiguration();
        }

        for (Map.Entry<UUID, Double> entry : balances.entrySet()) {
            config.set("balances." + entry.getKey(), entry.getValue());
        }

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save balances.yml");
            e.printStackTrace();
        }
    }

    public double getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, plugin.getConfig().getDouble("economy.starting-balance", 0.0));
    }

    public void setBalance(UUID uuid, double amount) {
        if (amount < 0) amount = 0;
        balances.put(uuid, amount);
    }

    public void deposit(UUID uuid, double amount) {
        if (amount <= 0) return;
        setBalance(uuid, getBalance(uuid) + amount);
    }

    public boolean withdraw(UUID uuid, double amount) {
        if (amount <= 0) return false;
        double current = getBalance(uuid);
        if (current < amount) return false;
        setBalance(uuid, current - amount);
        return true;
    }

    public Map<UUID, Double> getAllBalancesSnapshot() {
        return new HashMap<>(balances);
    }
}
